package com.newhost.qwertylauncher.feature.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Extra
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.meter
import com.newhost.qwertylauncher.core.tile
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Memory, storage and uptime, read on demand. */
internal class SystemStats(private val context: Context) {
    class Usage(val used: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) used.toFloat() / total else 0f
    }

    fun memory(): Usage {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return Usage(info.totalMem - info.availMem, info.totalMem)
    }

    fun storage(): Usage {
        val stat = StatFs(Environment.getDataDirectory().path)
        return Usage(stat.totalBytes - stat.availableBytes, stat.totalBytes)
    }

    /** `3d 04:12`, or `04:12` under a day. */
    fun uptime(): String {
        val total = Duration.ofMillis(SystemClock.elapsedRealtime())
        val days = total.toDays()
        val clock = "%02d:%02d".format(total.toHours() % 24, total.toMinutes() % 60)
        return if (days > 0) "${days}d $clock" else clock
    }

    companion object {
        /** `5.1G`, `812M`. */
        fun size(bytes: Long): String {
            val gib = bytes / (1024.0 * 1024 * 1024)
            return if (gib >= 1) "%.1fG".format(gib) else "%.0fM".format(bytes / (1024.0 * 1024))
        }
    }
}

/**
 * ```
 * up    3d 04:12
 * mem   ▕████░░▏ 62%
 * disk  ▕██░░░░▏ 31%
 * ```
 */
/**
 * The system: a chip drawn as art beside the memory in use, and facts:
 * memory, storage (each with a meter) and uptime. On a big tile, a chart of
 * memory in use since home was opened.
 */
internal class SysWidget(
    private val stats: SystemStats,
    private val settings: SettingsStore,
) : HomeWidget {
    override val id = "sys"
    override val title = "system"
    override val order = 30

    /** Memory in use at each refresh, newest last, for the chart. */
    private val memoryHistory = ArrayDeque<Double>()

    override fun render(): Flow<WidgetContent> = flow {
        while (true) {
            emit(content())
            delay(settings[SystemSettings.refreshSeconds] * 1000L)
        }
    }.flowOn(Dispatchers.IO)

    override fun onClick(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    }

    private fun content(): WidgetContent {
        val memory = stats.memory()
        val storage = stats.storage()
        synchronized(memoryHistory) {
            memoryHistory.addLast(memory.fraction * 100.0)
            while (memoryHistory.size > HISTORY) memoryHistory.removeFirst()
        }
        val headline = "memory ${percent(memory.fraction)}"
        // Short, so they pack in beside the chip; sizes go last, dropped first when space runs out.
        val facts = listOf(
            // Bars drawn as art, filling the rest of their line.
            meter("sys:mem", "mem ${percent(memory.fraction)}", memory.fraction, RAM),
            meter("sys:disk", "disk ${percent(storage.fraction)}", storage.fraction, DISK),
            fact("sys:up", "up ${stats.uptime()}", CLOCK),
            fact("sys:memsize", "${SystemStats.size(memory.used)} of ${SystemStats.size(memory.total)} ram", RAM),
            fact("sys:disksize", "${SystemStats.size(storage.used)} of ${SystemStats.size(storage.total)} disk", DISK),
        )
        val history = synchronized(memoryHistory) { memoryHistory.toList() }
        val extras = if (history.size < MIN_CHART_POINTS) {
            emptyList()
        } else {
            listOf(
                Extra(
                    Cell.Chart(
                        history,
                        lines = CHART_LINES,
                        low = 0.0,
                        high = 100.0,
                        colors = listOf(IconColors.GREEN, IconColors.YELLOW, IconColors.RED),
                        labels = "mem" to percent(memory.fraction),
                    ),
                    CHART_LINES,
                ),
            )
        }
        val chip = Cell.Art("sys:chip", Size(PICTURE_COLUMNS, PICTURE_LINES), drawable = CHIP)
        return WidgetContent(headline, layout = tile(chip, headline, facts, extras = extras))
    }

    private fun percent(fraction: Float) = "%d%%".format((fraction * 100).toInt())

    private companion object {
        const val HISTORY = 120
        const val MIN_CHART_POINTS = 4
        const val CHART_LINES = 3
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3

        /**
         * A chip in bold shapes, so it reads at any size: three thick pins a
         * side, a dark body, and a bright core.
         */
        val CHIP = icon {
            fill(IconColors.GREY)
            for (i in 0 until 3) {
                val at = 28f + i * 18f
                rect(at, 4f, at + 10f, 22f); rect(at, 78f, at + 10f, 96f)
                rect(4f, at, 22f, at + 10f); rect(78f, at, 96f, at + 10f)
            }
            fill(IconColors.DARK); round(18f, 18f, 82f, 82f, 10f)
            fill(IconColors.CYAN); round(32f, 32f, 68f, 68f, 6f)
        }
        val RAM = icon {
            fill(IconColors.GREEN); rect(6f, 30f, 94f, 66f)
            fill(IconColors.DARK); for (i in 0 until 4) rect(14f + i * 21f, 38f, 28f + i * 21f, 58f)
            fill(IconColors.YELLOW); for (i in 0 until 8) rect(10f + i * 11f, 66f, 16f + i * 11f, 76f)
        }
        val DISK = icon {
            fill(IconColors.GREY); round(10f, 24f, 90f, 76f, 10f)
            fill(IconColors.DARK); rect(18f, 34f, 82f, 50f)
            fill(IconColors.GREEN); circle(76f, 64f, 5f)
        }
        val CLOCK = icon {
            fill(IconColors.WHITE); circle(50f, 50f, 42f)
            stroke(IconColors.DARK, 8f); line(50f, 50f, 50f, 22f); line(50f, 50f, 70f, 60f)
        }
    }
}
