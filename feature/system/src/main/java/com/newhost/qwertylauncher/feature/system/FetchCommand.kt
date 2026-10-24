package com.newhost.qwertylauncher.feature.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * `fetch`: a neofetch-style summary in the console, an Android robot drawn
 * with characters beside the system's details.
 */
internal class FetchCommand(
    private val context: Context,
    private val stats: SystemStats,
    private val services: Services,
) : Command {
    override val name = "fetch"
    override val aliases = listOf("neofetch")
    override val usage = "fetch"
    override val summary = "system summary, neofetch style"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "fetch",
            tag = "cmd",
            title = "fetch",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.openPrompt() // keeps the output in view
                val console = host.console
                host.scope.launch {
                    val info = withContext(Dispatchers.IO) { fetch() }
                    // The logo and facts type themselves in, if the ascii art module animates.
                    val animator = services.find<ArtAnimator>()
                    if (animator == null) {
                        console.println(info)
                    } else {
                        val line = console.newLine()
                        animator.drawIn(info).collect { line.set(it) }
                    }
                }
            },
        ),
    )

    private fun fetch(): CharSequence {
        val green = context.getColor(CoreR.color.tty_fg)
        val accent = context.getColor(CoreR.color.tty_accent)
        val host = Build.MODEL.lowercase().replace(' ', '-')
        val memory = stats.memory()
        val storage = stats.storage()
        val version = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        val apps = services.find<AppCatalog>()?.apps?.value?.size
        val battery = batteryPercent()

        val info = listOfNotNull(
            null to "qwerty@$host",
            null to "-".repeat(7 + host.length),
            "os" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "host" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "kernel" to (System.getProperty("os.version") ?: "unknown"),
            "uptime" to stats.uptime(),
            apps?.let { "apps" to it.toString() },
            "shell" to "QwertyLauncher ${version.orEmpty()}",
            "cpu" to "${Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } ?: Build.HARDWARE}, ${Runtime.getRuntime().availableProcessors()} cores",
            "memory" to "${SystemStats.size(memory.used)} / ${SystemStats.size(memory.total)}",
            "storage" to "${SystemStats.size(storage.used)} / ${SystemStats.size(storage.total)}",
            battery?.let { "battery" to "$it%" },
        )

        val width = ROBOT.maxOf { it.length } + GAP
        return SpannableStringBuilder().apply {
            for (i in 0 until maxOf(ROBOT.size, info.size)) {
                if (i > 0) append('\n')
                append(ROBOT.getOrElse(i) { "" }.padEnd(width), ForegroundColorSpan(green), SPAN)
                val (key, value) = info.getOrNull(i) ?: continue
                if (key != null) append("$key: ", ForegroundColorSpan(accent), SPAN)
                append(value)
            }
        }
    }

    private fun batteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private companion object {
        const val GAP = 3
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val ROBOT = listOf(
            "  \\  _____  /",
            "   /       \\",
            "  |  o   o  |",
            "  |_________|",
            " [|         |]",
            " [|         |]",
            "  |_________|",
            "    |_| |_|",
        )
    }
}
