package com.newhost.qwertylauncher.feature.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Extra
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The battery: a battery drawn as art, filled green, yellow or red by level
 * (and running up while charging, with moving art on), the level, and facts:
 * state, temperature, voltage, power source, health. On a big tile, a chart
 * of the charge over the last day.
 */
internal class BatteryWidget(
    private val context: Context,
    private val settings: SettingsStore,
    private val services: Services,
) : HomeWidget {
    override val id = "battery"
    override val order = 10

    private val history = BatteryHistory(context)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun render(): Flow<WidgetContent> = batteryChanges().flatMapLatest { intent ->
        history.record(level(intent))
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val animator = services.find<ArtAnimator>()
        if (charging && animator != null) {
            animator.frames(CHARGE_FRAME_MS).map { frame -> content(intent, frame) }
        } else {
            flowOf(content(intent, 0))
        }
    }

    private fun batteryChanges(): Flow<Intent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(intent)
            }
        }
        // Battery broadcasts are sticky: registering returns the current state immediately.
        val current = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        current?.let { trySend(it) }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override fun onClick(host: LauncherHost) {
        host.startActivitySafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
    }

    private fun level(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    /** [frame] (0 for still) runs the charging fill: from the level up to full, over and over. */
    private fun content(intent: Intent, frame: Int): WidgetContent {
        val percent = level(intent)
        if (percent < 0) return WidgetContent("unknown")
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val shown = if (frame == 0) percent else percent + (100 - percent) * (frame % CHARGE_STEPS) / (CHARGE_STEPS - 1)
        val color = when {
            percent > HIGH -> IconColors.GREEN
            percent > LOW -> IconColors.YELLOW
            else -> IconColors.RED
        }
        val picture = Cell.Art("battery:${percent / KEY_STEP}:$charging", Size(PICTURE_COLUMNS, PICTURE_LINES), frame = frame) {
            BatteryDrawable(shown, color, charging)
        }
        val headline = SpannableStringBuilder().apply {
            append("$percent%", ForegroundColorSpan(context.getColor(levelColor(percent))), SPAN)
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> append(" charging")
                BatteryManager.BATTERY_STATUS_FULL -> append(" full")
            }
        }
        val levels = history.levels()
        val chart = if (levels.size < MIN_CHART_POINTS) {
            emptyList()
        } else {
            listOf(
                Extra(
                    Cell.Chart(
                        levels.map { it.toDouble() },
                        lines = CHART_LINES,
                        low = 0.0,
                        high = 100.0,
                        colors = listOf(IconColors.RED, IconColors.YELLOW, IconColors.GREEN),
                        labels = "100%" to "day",
                    ),
                    CHART_LINES,
                ),
            )
        }
        return WidgetContent(headline, layout = tile(picture, headline, facts(intent), extras = chart))
    }

    private fun facts(intent: Intent): List<Cell> {
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            else -> null
        }
        return listOfNotNull(
            if (tenthsC != Int.MIN_VALUE && settings[BatterySettings.batteryTemperature]) {
                fact("battery:temp", "%.1f°C".format(tenthsC / 10f), THERMOMETER)
            } else {
                null
            },
            plugged?.let { fact("battery:plug", it, PLUG) },
            if (millivolts > 0) fact("battery:volts", "%.2f V".format(millivolts / 1000f), BOLT) else null,
            health?.let { fact("battery:health", "health $it", HEART) },
        )
    }

    private fun levelColor(percent: Int) = when {
        percent > HIGH -> CoreR.color.tty_fg
        percent > LOW -> CoreR.color.tty_yellow
        else -> CoreR.color.tty_red
    }

    private companion object {
        const val HIGH = 50
        const val LOW = 20
        const val CHARGE_STEPS = 6
        const val CHARGE_FRAME_MS = 350L

        /** The picture changes with every few percent, not every one. */
        const val KEY_STEP = 5
        const val PICTURE_COLUMNS = 8
        const val PICTURE_LINES = 3
        const val CHART_LINES = 3
        const val MIN_CHART_POINTS = 4
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        val THERMOMETER = icon {
            fill(IconColors.WHITE); round(40f, 6f, 60f, 70f, 10f); circle(50f, 76f, 18f)
            fill(IconColors.RED); rect(45f, 30f, 55f, 72f); circle(50f, 76f, 13f)
        }
        val PLUG = icon {
            fill(IconColors.GREY); round(26f, 30f, 74f, 70f, 10f); rect(42f, 70f, 58f, 94f)
            fill(IconColors.WHITE); rect(34f, 8f, 42f, 30f); rect(58f, 8f, 66f, 30f)
        }
        val BOLT = icon {
            fill(IconColors.YELLOW); polygon(58f, 4f, 22f, 56f, 46f, 56f, 38f, 96f, 78f, 40f, 54f, 40f, 64f, 4f)
        }
        val HEART = icon {
            fill(IconColors.PINK); circle(34f, 38f, 20f); circle(66f, 38f, 20f); polygon(15f, 44f, 85f, 44f, 50f, 88f)
        }
    }
}

/** A battery: body, nub, fill to [percent] in [color], and a bolt while [charging]. Wider than tall. */
private class BatteryDrawable(percent: Int, color: Int, charging: Boolean) : android.graphics.drawable.Drawable() {
    private val icon = com.newhost.qwertylauncher.core.ShapeIcon {
        stroke(IconColors.WHITE, 7f); round(6f, 24f, 86f, 76f, 8f)
        fill(IconColors.WHITE); rect(86f, 38f, 96f, 62f)
        fill(color); rect(13f, 31f, 13f + 66f * percent.coerceIn(0, 100) / 100f, 69f)
        if (charging) {
            fill(IconColors.DARK); polygon(52f, 26f, 32f, 54f, 46f, 54f, 40f, 74f, 64f, 44f, 50f, 44f, 58f, 26f)
        }
    }

    override fun getIntrinsicWidth() = WIDTH
    override fun getIntrinsicHeight() = HEIGHT

    override fun draw(canvas: android.graphics.Canvas) {
        // The shape icon is square; stretch its 100 × 100 to the battery's wider box.
        icon.bounds = bounds
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = icon.setAlpha(alpha)

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = icon.setColorFilter(colorFilter)

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 100
    }
}

/** Charge over the last day, a reading every few minutes, for the chart. Kept on disk. */
private class BatteryHistory(context: Context) {
    private val prefs = context.getSharedPreferences("battery_history", Context.MODE_PRIVATE)

    fun record(level: Int) {
        if (level < 0) return
        val now = System.currentTimeMillis()
        val readings = read().filter { now - it.first < DAY_MS }.toMutableList()
        if (readings.isNotEmpty() && now - readings.last().first < STEP_MS) return
        readings += now to level
        val json = JSONArray()
        readings.forEach { json.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** The day's levels, oldest first. */
    fun levels(): List<Int> {
        val now = System.currentTimeMillis()
        return read().filter { now - it.first < DAY_MS }.map { it.second }
    }

    private fun read(): List<Pair<Long, Int>> = try {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        (0 until json.length()).map { json.getJSONArray(it).let { r -> r.getLong(0) to r.getInt(1) } }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    private companion object {
        const val KEY = "readings"
        const val DAY_MS = 24 * 60 * 60_000L
        const val STEP_MS = 5 * 60_000L
    }
}
