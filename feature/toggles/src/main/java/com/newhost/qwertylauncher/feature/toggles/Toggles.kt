package com.newhost.qwertylauncher.feature.toggles

import android.Manifest
import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** How a toggle looks now: its picture, and what it says under the cursor ("bluetooth on"). */
internal class Look(val label: String, val icon: () -> Drawable)

/**
 * One quick toggle: what it shows ([looks], following the system), what
 * enter does ([press]), and the system screen for it ([settingsIntent]).
 * [short] stands in for its picture without the ascii art module.
 */
internal abstract class Toggle(val id: String, val name: String, val short: String) {
    /** Whether the phone has it at all; the ones it hasn't aren't offered. */
    open val available: Boolean get() = true

    /** The look now, then on every change. Emits right away. */
    abstract fun looks(): Flow<Look>

    abstract fun press(host: LauncherHost)

    open fun settingsIntent(): Intent? = null

    /** More for its hold menu, before its settings. */
    open fun menu(): List<MenuItem> = emptyList()
}

/** Draws a toggle's picture in a colour, on or off. */
internal typealias Draw = (color: Int, on: Boolean) -> () -> Drawable

/** A toggle that's on or off, drawn in its colour when on and grey when off. */
internal abstract class Switch(
    id: String,
    name: String,
    short: String,
    private val color: Int,
    private val draw: Draw,
) : Toggle(id, name, short) {
    /** Emits whenever it may have changed; read with [isOn]. */
    abstract fun changes(): Flow<Unit>

    abstract fun isOn(): Boolean

    override fun looks(): Flow<Look> = changes().map { isOn() }.distinctUntilChanged().map { on ->
        Look("$name ${if (on) "on" else "off"}", draw(if (on) color else OFF, on))
    }
}

/** Not a switch but a way in: always in colour, enter opens the first of [intents] the phone has. */
internal open class Shortcut(
    private val context: Context,
    id: String,
    name: String,
    short: String,
    private val color: Int,
    private val draw: Draw,
    private vararg val intents: Intent,
) : Toggle(id, name, short) {
    override fun looks(): Flow<Look> = flowOf(Look(name, draw(color, true)))

    override fun press(host: LauncherHost) = openFirst(context, host, *intents)
}

// --- Airplane mode, bluetooth, sound and the flashlight: shown by default ---

/** Apps can't switch airplane mode: enter opens the system's switch for it. */
internal class Airplane(private val context: Context) : Switch("airplane", "airplane mode", "air", IconColors.ORANGE, Icons.airplane) {
    override fun changes() = context.settingChanges(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON))

    override fun isOn() = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    }
}

/** Asks the system to turn bluetooth on or off (a one-tap confirmation); else its settings. */
internal class Bluetooth(private val context: Context) : Switch("bluetooth", "bluetooth", "bt", IconColors.BLUE, Icons.bluetooth) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    override val available get() = adapter != null

    override fun changes() = context.broadcasts(BluetoothAdapter.ACTION_STATE_CHANGED)

    override fun isOn() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)


    override fun press(host: LauncherHost) {
        // Asking needs the "nearby devices" permission; without it, the settings still work.
        host.requestPermission(Manifest.permission.BLUETOOTH_CONNECT) { granted ->
            val ask = Intent(if (isOn()) REQUEST_DISABLE else BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (granted) openFirst(context, host, ask, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            else host.startActivitySafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    override fun settingsIntent() = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    private companion object {
        /** Not public, but the settings app answers it on most phones. */
        const val REQUEST_DISABLE = "android.bluetooth.adapter.action.REQUEST_DISABLE"
    }
}

/**
 * Ring, vibrate, silent, round and round. Silent (and leaving it) needs do
 * not disturb access, which a notification listener has; without it, the
 * round skips silent and the hold menu offers to allow it.
 */
internal class Ringer(private val context: Context) : Toggle("ringer", "sound", "snd") {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun looks(): Flow<Look> =
        context.broadcasts(AudioManager.RINGER_MODE_CHANGED_ACTION).map { audio.ringerMode }.distinctUntilChanged().map { mode ->
            when (mode) {
                AudioManager.RINGER_MODE_NORMAL -> Look("sound ring", Icons.bell(IconColors.GREEN, true))
                AudioManager.RINGER_MODE_VIBRATE -> Look("sound vibrate", Icons.vibrate(IconColors.YELLOW, true))
                else -> Look("sound silent", Icons.silent(IconColors.RED, true))
            }
        }

    private val allowed get() = notifications.isNotificationPolicyAccessGranted

    override fun press(host: LauncherHost) {
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> if (allowed) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE && !allowed) {
            host.console.println("Silent needs do not disturb access: hold on the sound toggle to allow it.")
        }
        try {
            audio.ringerMode = next
        } catch (e: SecurityException) {
            host.console.println("Changing the sound needs do not disturb access: hold on the sound toggle to allow it.")
        }
    }

    override fun menu() = if (allowed) {
        emptyList()
    } else {
        listOf(MenuItem("allow silent") { it.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
    }

    override fun settingsIntent() = Intent(Settings.ACTION_SOUND_SETTINGS)
}

/** The flashlight: the back camera's torch, switched directly. */
internal class Flashlight(context: Context) : Toggle("flashlight", "flashlight", "lit") {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = runCatching {
        val flash = camera.cameraIdList.filter { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        flash.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: flash.firstOrNull()
    }.getOrNull()

    @Volatile private var lit = false

    override val available get() = cameraId != null

    override fun looks(): Flow<Look> = callbackFlow {
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) trySend(enabled)
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id == cameraId) trySend(false)
            }
        }
        camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { camera.unregisterTorchCallback(callback) }
    }.onStart { emit(lit) }.distinctUntilChanged().map { on ->
        lit = on
        Look("flashlight ${if (on) "on" else "off"}", Icons.torch(if (on) IconColors.YELLOW else OFF, on))
    }

    override fun press(host: LauncherHost) {
        val id = cameraId ?: return
        try {
            camera.setTorchMode(id, !lit)
        } catch (e: Exception) {
            // The camera is in use by an app, or the torch is unavailable for now.
            host.console.println("The flashlight can't be used right now: ${e.message ?: "the camera is busy"}")
        }
    }
}

// --- Connections ---

/** Wi-Fi: apps can't switch it; enter opens the system's quick Wi-Fi panel. */
internal class Wifi(private val context: Context) : Switch("wifi", "wi-fi", "wifi", IconColors.CYAN, Icons.wifi) {
    private val wifi = context.getSystemService(WifiManager::class.java)

    override val available get() = wifi != null

    override fun changes() = context.broadcasts(WifiManager.WIFI_STATE_CHANGED_ACTION)

    override fun isOn() = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.Panel.ACTION_WIFI))
    }

    override fun settingsIntent() = Intent(Settings.ACTION_WIFI_SETTINGS)
}

/** Mobile data: apps can't switch it; enter opens the system's internet panel. */
internal class MobileData(private val context: Context) : Switch("data", "mobile data", "data", IconColors.GREEN, Icons.data) {
    override val available get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun changes() = context.settingChanges(Settings.Global.getUriFor(MOBILE_DATA))

    override fun isOn() = Settings.Global.getInt(context.contentResolver, MOBILE_DATA, 0) == 1


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
    }

    override fun settingsIntent() = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)

    private companion object {
        /** Readable by any app, though not in the public list. */
        const val MOBILE_DATA = "mobile_data"
    }
}

/** The hotspot's settings: its state isn't open to apps. */
internal class Hotspot(context: Context) : Shortcut(
    context, "hotspot", "hotspot", "ap", IconColors.ORANGE, Icons.hotspot,
    Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS),
)

/** NFC: apps can't switch it; enter opens the system's quick NFC panel. */
internal class Nfc(private val context: Context) : Switch("nfc", "nfc", "nfc", IconColors.LIGHT_BLUE, Icons.nfc) {
    private val nfc: NfcAdapter? = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()

    override val available get() = nfc != null

    override fun changes() = context.broadcasts(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)

    override fun isOn() = runCatching { nfc?.isEnabled == true }.getOrDefault(false)


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.Panel.ACTION_NFC))
    }

    override fun settingsIntent() = Intent(Settings.ACTION_NFC_SETTINGS)
}

/** Location: apps can't switch it; enter opens its settings. */
internal class Location(private val context: Context) : Switch("location", "location", "gps", IconColors.PINK, Icons.pin) {
    private val location = context.getSystemService(LocationManager::class.java)

    override fun changes() = context.broadcasts(LocationManager.MODE_CHANGED_ACTION)

    override fun isOn() = runCatching { location.isLocationEnabled }.getOrDefault(false)


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}

/** Whether a VPN is connected; enter opens the VPN settings. */
internal class Vpn(private val context: Context) : Toggle("vpn", "vpn", "vpn") {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun looks(): Flow<Look> = callbackFlow {
        val up = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                up += network
                trySend(true)
            }

            override fun onLost(network: Network) {
                up -= network
                trySend(up.isNotEmpty())
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.onStart { emit(false) }.distinctUntilChanged().map { on ->
        Look("vpn ${if (on) "connected" else "off"}", Icons.lock(if (on) IconColors.GREEN else OFF, on))
    }

    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_VPN_SETTINGS))
    }
}

/** Casting the screen: its settings. */
internal class Cast(context: Context) : Shortcut(
    context, "cast", "cast", "cast", IconColors.LIGHT_BLUE, Icons.cast, Intent(Settings.ACTION_CAST_SETTINGS),
)

// --- Sound and quiet ---

/** Do not disturb, switched directly with do not disturb access; else asks for it. */
internal class DoNotDisturb(private val context: Context) : Switch("dnd", "do not disturb", "dnd", IconColors.VIOLET, Icons.moon) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun changes() = context.broadcasts(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)

    override fun isOn() = notifications.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }


    override fun press(host: LauncherHost) {
        if (!notifications.isNotificationPolicyAccessGranted) {
            host.console.println("Allow do not disturb access for QwertyLauncher, then press again.")
            host.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        notifications.setInterruptionFilter(
            if (isOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
    }

    override fun settingsIntent() = Intent("android.settings.ZEN_MODE_SETTINGS")
}

/** Media sound muted or not, switched directly. */
internal class MuteMedia(private val context: Context) : Switch("mute", "media muted", "mute", IconColors.RED, Icons.speaker) {
    private val audio = context.getSystemService(AudioManager::class.java)

    // Not public names, but sent on every volume change.
    override fun changes() = context.broadcasts("android.media.VOLUME_CHANGED_ACTION", "android.media.STREAM_MUTE_CHANGED_ACTION")

    override fun isOn() = audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0


    override fun press(host: LauncherHost) {
        if (isOn()) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            // Muted by turning it all the way down: back to a quarter.
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 4).coerceAtLeast(1), 0)
            }
        } else {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
    }

    override fun settingsIntent() = Intent(Settings.ACTION_SOUND_SETTINGS)
}

/** Vibration on touch, switched directly once allowed to change system settings. */
internal class TouchVibration(private val context: Context) :
    Switch("haptics", "touch vibration", "hap", IconColors.CYAN, Icons.vibrate) {
    override fun changes() = context.settingChanges(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED))

    override fun isOn() = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1


    override fun press(host: LauncherHost) {
        if (canWrite(context, host)) Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (isOn()) 0 else 1)
    }

    override fun settingsIntent() = Intent(Settings.ACTION_SOUND_SETTINGS)
}

// --- The screen ---

/** Auto-rotate, switched directly once allowed to change system settings. */
internal class AutoRotate(private val context: Context) : Switch("rotate", "auto-rotate", "rot", IconColors.GREEN, Icons.rotate) {
    override fun changes() = context.settingChanges(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION))

    override fun isOn() = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1


    override fun press(host: LauncherHost) {
        if (canWrite(context, host)) Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (isOn()) 0 else 1)
    }

    override fun settingsIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)
}

/** Automatic brightness, switched directly once allowed to change system settings. */
internal class AutoBrightness(private val context: Context) :
    Switch("autobright", "auto brightness", "abr", IconColors.YELLOW, Icons.autoSun) {
    override fun changes() = context.settingChanges(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE))

    override fun isOn() = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC


    override fun press(host: LauncherHost) {
        if (!canWrite(context, host)) return
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (isOn()) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
    }

    override fun settingsIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)
}

/** Brightness in steps (a quarter, half, three quarters, full), round and round; the sun grows with it. */
internal class Brightness(private val context: Context) : Toggle("brightness", "brightness", "bri") {
    private fun percent() = (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, MAX / 2) * 100f / MAX).roundToInt()

    private fun next(now: Int) = LEVELS.firstOrNull { it > now + 2 } ?: LEVELS.first()

    override fun looks(): Flow<Look> =
        context.settingChanges(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)).map { percent() }.distinctUntilChanged().map { now ->
            Look("brightness $now%", Icons.sun(IconColors.YELLOW, now / 100f))
        }

    override fun press(host: LauncherHost) {
        if (!canWrite(context, host)) return
        val resolver = context.contentResolver
        // A set level means no automatic brightness.
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (next(percent()) * MAX / 100f).roundToInt().coerceIn(1, MAX))
    }

    override fun settingsIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)

    private companion object {
        const val MAX = 255
        val LEVELS = listOf(25, 50, 75, 100)
    }
}

/** How long the screen stays on, in steps from 15 seconds to 10 minutes, round and round. */
internal class ScreenTimeout(private val context: Context) : Toggle("timeout", "screen timeout", "tmo") {
    private fun now() = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, STEPS[1])

    private fun next(now: Int) = STEPS.firstOrNull { it > now } ?: STEPS.first()

    override fun looks(): Flow<Look> =
        context.settingChanges(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT)).map { now() }.distinctUntilChanged().map { now ->
            Look("screen timeout ${words(now)}", Icons.hourglass(IconColors.LIGHT_BLUE, true))
        }

    override fun press(host: LauncherHost) {
        if (canWrite(context, host)) Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, next(now()))
    }

    override fun settingsIntent() = Intent(Settings.ACTION_DISPLAY_SETTINGS)

    private fun words(ms: Int) = if (ms < MINUTE) "${ms / SECOND} seconds" else if (ms == MINUTE) "1 minute" else "${ms / MINUTE} minutes"

    private companion object {
        const val SECOND = 1000
        const val MINUTE = 60 * SECOND
        val STEPS = listOf(15 * SECOND, 30 * SECOND, MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE)
    }
}

/** Dark theme: apps can't switch the phone's; enter opens its setting. */
internal class DarkTheme(private val context: Context) : Switch("dark", "dark theme", "drk", IconColors.WHITE, Icons.halfMoon) {
    private val uiMode = context.getSystemService(UiModeManager::class.java)

    override fun changes() = context.broadcasts(Intent.ACTION_CONFIGURATION_CHANGED)

    override fun isOn() = uiMode.nightMode == UiModeManager.MODE_NIGHT_YES ||
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES


    override fun press(host: LauncherHost) =
        openFirst(context, host, Intent("android.settings.DARK_THEME_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS))
}

// --- Power and the camera ---

/** Battery saver: apps can't switch it; enter opens its settings. */
internal class BatterySaver(private val context: Context) :
    Switch("saver", "battery saver", "bat", IconColors.ORANGE, Icons.battery) {
    private val power = context.getSystemService(PowerManager::class.java)

    override fun changes() = context.broadcasts(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)

    override fun isOn() = power.isPowerSaveMode


    override fun press(host: LauncherHost) {
        host.startActivitySafely(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }
}

/** The camera, to take a photo. */
internal class Camera(context: Context) : Shortcut(
    context, "camera", "camera", "cam", IconColors.WHITE, Icons.camera, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
)

/** Every toggle there is, in the order they're offered (and shown). */
internal fun allToggles(context: Context): List<Toggle> = listOf(
    Airplane(context),
    Bluetooth(context),
    Ringer(context),
    Flashlight(context),
    Wifi(context),
    MobileData(context),
    Hotspot(context),
    Nfc(context),
    Location(context),
    Vpn(context),
    Cast(context),
    DoNotDisturb(context),
    MuteMedia(context),
    TouchVibration(context),
    AutoRotate(context),
    AutoBrightness(context),
    Brightness(context),
    ScreenTimeout(context),
    DarkTheme(context),
    BatterySaver(context),
    Camera(context),
)

/** Grey: a toggle that's off. */
private val OFF = IconColors.GREY

// --- Asking the system ---

/** Opens the first of [intents] the phone has; the last anyway (it says if nothing can). */
internal fun openFirst(context: Context, host: LauncherHost, vararg intents: Intent) {
    val intent = intents.firstOrNull { it.resolveActivity(context.packageManager) != null } ?: intents.last()
    host.startActivitySafely(intent)
}

/** Whether system settings can be changed; if not, opens the screen to allow it and says so. */
internal fun canWrite(context: Context, host: LauncherHost): Boolean {
    if (Settings.System.canWrite(context)) return true
    host.console.println("Allow QwertyLauncher to modify system settings, then press again.")
    host.startActivitySafely(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
    return false
}

// --- Watching the system ---

/** Emits now, then on each of [actions]' broadcasts. */
internal fun Context.broadcasts(vararg actions: String): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(Unit)
        }
    }
    // Exported: some come from system apps (bluetooth, nfc) rather than the system itself.
    registerReceiver(receiver, IntentFilter().apply { actions.forEach(::addAction) }, Context.RECEIVER_EXPORTED)
    trySend(Unit)
    awaitClose { unregisterReceiver(receiver) }
}

/** Emits now, then whenever the system setting at [uri] changes. */
internal fun Context.settingChanges(uri: Uri): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    contentResolver.registerContentObserver(uri, false, observer)
    trySend(Unit)
    awaitClose { contentResolver.unregisterContentObserver(observer) }
}
