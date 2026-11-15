package com.newhost.qwertylauncher.core

import android.view.KeyEvent

/**
 * Who gets a key while something is open over home: a module's [ListPane]
 * or [HomeScreen], a menu, config, help, or a tile being arranged.
 *
 * The rule, applied by the shell to every one of them:
 * 1. **The open surface owns the keyboard.** It's offered every key first.
 * 2. **A key it doesn't use is dropped**, never passed on. Otherwise a stray
 *    letter can type into a text field behind it, start a search, or reach
 *    the home screen underneath.
 * 3. **System keys always reach Android** unless the surface used them:
 *    volume, media, calls, power, Back and the like ([isSystemKey]).
 *    Back then comes round again as Esc does.
 * 4. **A surface with its own text line** (a pane's [ListPane.input]) lets the
 *    keys it didn't use go to that line, so they type there.
 *
 * So a module only says whether it used a key: return true from `onKey` for
 * keys it acted on and false for the rest. It never swallows keys itself, and
 * never relies on a key it ignored reaching anything else.
 */
object KeyRouting {
    /** A key Android itself must get when the open surface didn't use it. */
    fun isSystemKey(keyCode: Int): Boolean = keyCode in SYSTEM_KEYS

    /**
     * What the shell answers for a key the open surface was offered: true
     * (kept from everything else) if it was [used], or if it isn't a system key.
     */
    fun keep(used: Boolean, keyCode: Int): Boolean = used || !isSystemKey(keyCode)

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_SOFT_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_FOCUS,
        KeyEvent.KEYCODE_BRIGHTNESS_UP,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_NOTIFICATION,
        KeyEvent.KEYCODE_SYSRQ,
    )
}
