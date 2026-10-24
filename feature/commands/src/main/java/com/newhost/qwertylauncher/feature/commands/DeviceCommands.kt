package com.newhost.qwertylauncher.feature.commands

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find

/** `settings [page]` */
internal class SettingsCommand : Command {
    override val name = "settings"
    override val aliases = listOf("set")
    override val usage = "settings [page]"
    override val summary = "open a settings page"

    private val pages = linkedMapOf(
        "all" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "network" to Settings.ACTION_WIRELESS_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "home" to Settings.ACTION_HOME_SETTINGS,
        "keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
    )

    override suspend fun suggest(args: String): List<SearchResult> {
        val prefix = args.trim().lowercase()
        return pages.filterKeys { it.startsWith(prefix) }.map { (page, action) ->
            SearchResult(
                key = "settings:$page",
                tag = "set",
                title = "settings $page",
                score = if (page == prefix) Score.EXACT else Score.PREFIX,
                completion = "settings $page",
                action = { host ->
                    host.clearQuery()
                    host.startActivitySafely(Intent(action))
                },
            )
        }
    }
}

internal class TorchController(context: Context) {
    private val cameras = context.getSystemService(CameraManager::class.java)

    private val cameraId: String? by lazy {
        try {
            cameras.cameraIdList.firstOrNull { id ->
                cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            null
        }
    }

    @Volatile var isOn = false
        private set

    init {
        cameras.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId) isOn = enabled
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Returns false if there is no flash or it is busy (e.g. the camera app is open). */
    fun set(on: Boolean): Boolean {
        val id = cameraId ?: return false
        return try {
            cameras.setTorchMode(id, on)
            true
        } catch (e: CameraAccessException) {
            false
        }
    }
}

/** `torch [on|off]` */
internal class TorchCommand(private val torch: TorchController) : Command {
    override val name = "torch"
    override val aliases = listOf("flash", "flashlight")
    override val usage = "torch [on|off]"
    override val summary = "toggle the flashlight"

    override suspend fun suggest(args: String): List<SearchResult> {
        val turnOn = when (args.trim().lowercase()) {
            "on" -> true
            "off" -> false
            else -> !torch.isOn
        }
        val state = if (turnOn) "on" else "off"
        return listOf(
            SearchResult(
                key = "torch",
                tag = "cmd",
                title = "torch $state",
                score = Score.EXACT,
                action = { host ->
                    host.clearQuery()
                    if (!torch.set(turnOn)) host.console.println("torch: flash unavailable")
                },
            ),
        )
    }
}

/** `alarm 7:30 [label]`, also `alarm 730` or `alarm 7`. */
internal class AlarmCommand : Command {
    override val name = "alarm"
    override val usage = "alarm <hh:mm> [label]"
    override val summary = "set an alarm"

    override suspend fun suggest(args: String): List<SearchResult> {
        val match = PATTERN.find(args.trim()) ?: return emptyList()
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val label = match.groupValues[3].trim()
        if (hour > 23 || minute > 59) return emptyList()
        val time = "%02d:%02d".format(hour, minute)
        return listOf(
            SearchResult(
                key = "alarm",
                tag = "cmd",
                title = "alarm $time",
                detail = label.ifEmpty { null },
                score = Score.EXACT,
                action = { host ->
                    host.clearQuery()
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (host.startActivitySafely(intent)) host.console.println("alarm set for $time")
                },
            ),
        )
    }

    private companion object {
        val PATTERN = Regex("""^(\d{1,2})(?::?(\d{2}))?\s*(.*)$""")
    }
}

/** `timer 5m`, `timer 1h30m`, `timer 90s`, `timer 10` (minutes). */
internal class TimerCommand(private val services: Services) : Command {
    override val name = "timer"
    override val usage = "timer <5m|1h30m|90s>"
    override val summary = "start a countdown"

    override suspend fun suggest(args: String): List<SearchResult> {
        val seconds = parseSeconds(args.trim())
        if (seconds <= 0) return emptyList()
        val pretty = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        return listOf(
            SearchResult(
                key = "timer",
                tag = "cmd",
                title = "timer $pretty",
                score = Score.EXACT,
                action = { host ->
                    host.clearQuery()
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (host.startActivitySafely(intent)) {
                        host.console.println("timer started: $pretty")
                        // When it ends, for whoever cares (Sir Alex blows his trumpet).
                        services.find<Happenings>()?.post("timer.ends", (System.currentTimeMillis() + seconds * 1000L).toString())
                    }
                },
            ),
        )
    }

    private fun parseSeconds(text: String): Int {
        if (text.isEmpty()) return 0
        text.toIntOrNull()?.let { return it * 60 }
        val parts = PART.findAll(text).toList()
        // Everything must be consumed, so "5 apples" isn't read as 5 minutes.
        if (parts.sumOf { it.value.length } != text.replace(" ", "").length) return 0
        return parts.sumOf { part ->
            val amount = part.groupValues[1].toInt()
            when (part.groupValues[2]) {
                "h" -> amount * 3600
                "m" -> amount * 60
                else -> amount
            }
        }.coerceAtMost(MAX_SECONDS)
    }

    private companion object {
        val PART = Regex("""(\d+)([hms])""")
        const val MAX_SECONDS = 24 * 3600
    }
}
