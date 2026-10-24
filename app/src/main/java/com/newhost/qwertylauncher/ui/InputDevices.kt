package com.newhost.qwertylauncher.ui

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's input devices, and what each is for, learnt from what they
 * declare, not their names: when the launcher starts, and again whenever
 * one comes, changes or goes. Touches from the [Kind.SCREEN] are a finger
 * on the glass; those from a [Kind.SURFACE] (a keyboard's touch surface,
 * which calls itself a touch screen too) are the keyboard scrolling.
 */
internal class InputDevices(context: Context) {
    enum class Kind(val words: String) {
        /** A touch screen that reports only where it's touched. */
        SCREEN("touch screen"),

        /** A touch surface that also reports relative movement, like a keyboard's or a touchpad. */
        SURFACE("touch surface (keyboard, touchpad)"),
        MOUSE("mouse"),
        KEYS("keys"),
        OTHER("other"),
    }

    private val manager = context.getSystemService(InputManager::class.java)
    private val kinds = ConcurrentHashMap<Int, Kind>()

    /** Hears what's learnt, for the debug log. */
    var onLearnt: (String) -> Unit = {}

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            learn(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            learn(deviceId)
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            kinds.remove(deviceId)
        }
    }

    /** Learns every device there is now, and keeps up with changes. */
    fun start() {
        InputDevice.getDeviceIds().forEach(::learn)
        manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
    }

    fun stop() = manager.unregisterInputDeviceListener(listener)

    /** What [deviceId] is; one not seen before is learnt now. */
    fun kind(deviceId: Int): Kind = kinds[deviceId] ?: learn(deviceId)

    /** A touch from a keyboard's touch surface (or a touchpad that reports as one): never meant for the screen. */
    fun isSurface(event: MotionEvent): Boolean = kind(event.deviceId) == Kind.SURFACE

    /** A finger on the glass: a touch from the screen, by a finger. */
    fun isFinger(event: MotionEvent): Boolean =
        kind(event.deviceId) == Kind.SCREEN && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

    private fun learn(deviceId: Int): Kind {
        val device = InputDevice.getDevice(deviceId) ?: return Kind.OTHER
        val kind = classify(device)
        if (kinds.put(deviceId, kind) != kind) onLearnt("device $deviceId [${device.name}]: ${kind.words}${reasons(device)}")
        return kind
    }

    /**
     * From what it declares: touch (screen or touchpad) with relative
     * movement axes, or a touchpad, is a surface; plain touch is the screen.
     */
    private fun classify(device: InputDevice): Kind {
        if (device.isVirtual) return Kind.OTHER
        val touch = device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
        val pad = device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
        return when {
            (touch || pad) && (relative(device) || pad) -> Kind.SURFACE
            touch -> Kind.SCREEN
            device.supportsSource(InputDevice.SOURCE_MOUSE) -> Kind.MOUSE
            device.supportsSource(InputDevice.SOURCE_KEYBOARD) -> Kind.KEYS
            else -> Kind.OTHER
        }
    }

    private fun relative(device: InputDevice) =
        device.getMotionRange(MotionEvent.AXIS_RELATIVE_X) != null || device.getMotionRange(MotionEvent.AXIS_RELATIVE_Y) != null

    /** What it declared, briefly, for the log. */
    private fun reasons(device: InputDevice): String {
        val said = listOfNotNull(
            "touchscreen".takeIf { device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) },
            "touchpad".takeIf { device.supportsSource(InputDevice.SOURCE_TOUCHPAD) },
            "relative axes".takeIf { relative(device) },
            "mouse".takeIf { device.supportsSource(InputDevice.SOURCE_MOUSE) },
            "keyboard".takeIf { device.supportsSource(InputDevice.SOURCE_KEYBOARD) },
            "external".takeIf { device.isExternal },
        )
        return if (said.isEmpty()) "" else " (declares ${said.joinToString(", ")})"
    }
}
