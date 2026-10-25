package com.newhost.qwertylauncher.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.newhost.qwertylauncher.core.DragAxis
import com.newhost.qwertylauncher.core.DragPath
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.newhost.qwertylauncher.databinding.ViewPaneBinding
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns scroll events (touchpad, keyboard scroll, mouse wheel) into whole
 * steps. Touchpads report many fractional values, so fractions carry over
 * between events.
 */
internal class ScrollSteps {
    private var vertical = 0f
    private var horizontal = 0f

    /** Steps from a [MotionEvent.ACTION_SCROLL]: positive = down/right. */
    fun consume(event: MotionEvent): Steps {
        // AXIS_VSCROLL is positive for "wheel away from you", i.e. up.
        vertical -= event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        horizontal += event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val down = vertical.toInt()
        val right = horizontal.toInt()
        vertical -= down
        horizontal -= right
        return Steps(down, right)
    }

    data class Steps(val down: Int, val right: Int) {
        val isEmpty get() = down == 0 && right == 0
    }
}

/**
 * Turns vertical drags into whole-line steps, so keyboards that scroll by
 * faking finger swipes (and drags while arranging things) move things line
 * by line like a terminal. Taps are left alone, and so are sideways drags
 * unless [sideways] says they're wanted (e.g. while resizing a tile), so
 * pages can still be swiped.
 *
 * Drags are read as people make them, not straight: the whole path is kept
 * ([DragPath]) and judged for which way it goes; then movement off that way
 * is wobble and doesn't count, unless both ways are wanted (resizing) and
 * the path clearly turns.
 *
 * Feed it every touch event from Activity.dispatchTouchEvent; see [onTouch].
 */
internal class DragSteps(
    context: Context,
    private val lineHeightPx: () -> Int,
    private val columnWidthPx: () -> Int = lineHeightPx,
    private val sideways: () -> Boolean = { false },
) {
    private val path = DragPath(context)
    private var lastX = 0f
    private var lastY = 0f
    private var carryX = 0f
    private var carryY = 0f
    private var tracking = false
    private var axis = DragAxis.VERTICAL

    /** Whether this drag steps sideways too: read on the touch down. */
    private var bothWays = false

    /** True from the moment a gesture is recognised as a drag until it ends. */
    var dragging = false
        private set

    /** The last drag, in words, for the input log. */
    var lastDrag: String = ""
        private set

    /**
     * Returns the whole steps this event scrolled (positive = down, or right
     * for a sideways drag), or null if the event isn't part of a drag and
     * should reach the views as usual. [allowed] and [bothWays] are read on
     * ACTION_DOWN: not [allowed] leaves the whole gesture alone; [bothWays]
     * steps sideways as well as up and down, never passing a sideways drag
     * on (the keyboard's touch surface: nothing of it is for the screen).
     */
    fun onTouch(event: MotionEvent, allowed: Boolean, bothWays: Boolean = false): ScrollSteps.Steps? {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                this.bothWays = bothWays
                path.start(event)
                lastX = event.x
                lastY = event.y
                carryX = 0f
                carryY = 0f
                dragging = false
                tracking = allowed
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                // Every drag is recorded, taken or not, for the input log.
                path.add(event)
                if (!tracking) return null
                if (!dragging) {
                    // The keyboard's slides arc: sideways ones reach 55 degrees, up-and-down ones start at 65.
                    val judged = path.judge(split = if (bothWays) KEYBOARD_SPLIT else null) ?: return null
                    // A sideways swipe that isn't wanted is left to the views: pages to swipe.
                    if (judged == DragAxis.HORIZONTAL && !sideways() && !this.bothWays) {
                        tracking = false
                        return null
                    }
                    dragging = true
                    axis = judged
                    lastX = event.x
                    lastY = event.y
                    return NONE
                }
                val moveX = event.x - lastX
                val moveY = event.y - lastY
                lastX = event.x
                lastY = event.y
                // Along the way it goes; across it too only when both are wanted (resizing) and it really turns.
                // Not the keyboard's: its slides arc, which would read as turning.
                val both = sideways() && !bothWays && path.turning(axis)
                if (axis == DragAxis.VERTICAL || both) carryY -= moveY // finger up scrolls the content down
                if (axis == DragAxis.HORIZONTAL || both) carryX += moveX // finger right means right
                val line = lineHeightPx().coerceAtLeast(1)
                val column = columnWidthPx().coerceAtLeast(1)
                val down = (carryY / line).toInt()
                val right = (carryX / column).toInt()
                carryY -= down * line
                carryX -= right * column
                return ScrollSteps.Steps(down, right)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.add(event)
                lastDrag = path.describe() + if (dragging) " (steps)" else " (to the views)"
                val wasDragging = dragging
                dragging = false
                tracking = false
                return if (wasDragging) NONE else null
            }
        }
        return if (dragging) NONE else null
    }

    private companion object {
        val NONE = ScrollSteps.Steps(0, 0)

        /** Degrees from sideways: a keyboard slide steeper than this goes up or down, else sideways. */
        const val KEYBOARD_SPLIT = 62f
    }
}

/** Sends ACTION_CANCEL to the views, e.g. once a touch turned out to be a drag we handle ourselves. */
internal fun Activity.cancelTouch(event: MotionEvent) {
    val cancel = MotionEvent.obtain(event)
    cancel.action = MotionEvent.ACTION_CANCEL
    window.superDispatchTouchEvent(cancel)
    cancel.recycle()
}

/** Terminal screens are full screen: the status bar only shows while the user swipes it in. */
internal fun Activity.hideStatusBar() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.statusBars())
    }
}

/** The general setting to show the phone's status bar. */
internal fun Activity.showStatusBar() {
    WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
}

/** Lists that move line by line like a terminal: no fling, no overscroll glow, no animations. */
internal fun RecyclerView.terminalStyle() {
    overScrollMode = View.OVER_SCROLL_NEVER
    itemAnimator = null
    onFlingListener = object : RecyclerView.OnFlingListener() {
        override fun onFling(velocityX: Int, velocityY: Int) = true // swallow flings
    }
}

/** Vim-style aliases, so a QWERTY keyboard can drive lists without arrow keys. */
internal object VimKeys {
    fun isUp(event: KeyEvent) = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_K
    fun isDown(event: KeyEvent) = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_J
    fun isLeft(event: KeyEvent) = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_H
    fun isRight(event: KeyEvent) = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_L
}

/**
 * The debug pane's "input events" switch: while on, the home screen logs
 * every input event it receives, to find out what a device's keyboard,
 * touchpad or scroll gesture actually sends.
 */
internal object InputMonitor {
    private val _enabled = MutableStateFlow(false)
    val changes: StateFlow<Boolean> = _enabled.asStateFlow()

    var enabled: Boolean
        get() = _enabled.value
        set(value) {
            _enabled.value = value
        }

    fun describe(event: KeyEvent): String =
        "key ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}" +
            " scan=${event.scanCode} meta=0x${Integer.toHexString(event.metaState)}" +
            " ${sources(event.source)} [${event.device?.name ?: "?"}]"

    fun describe(event: MotionEvent): String {
        val axes = buildList {
            for ((axis, name) in AXES) {
                val value = event.getAxisValue(axis)
                if (value != 0f) add("$name=%.2f".format(value))
            }
        }.joinToString(" ")
        return "motion ${MotionEvent.actionToString(event.actionMasked).removePrefix("ACTION_")} $axes" +
            " ${sources(event.source)} [${event.device?.name ?: "?"}]"
    }

    private val AXES = listOf(
        MotionEvent.AXIS_VSCROLL to "vscroll",
        MotionEvent.AXIS_HSCROLL to "hscroll",
        MotionEvent.AXIS_X to "x",
        MotionEvent.AXIS_Y to "y",
        MotionEvent.AXIS_RELATIVE_X to "dx",
        MotionEvent.AXIS_RELATIVE_Y to "dy",
    )

    private val SOURCES = listOf(
        InputDevice.SOURCE_KEYBOARD to "keyboard",
        InputDevice.SOURCE_DPAD to "dpad",
        InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
        InputDevice.SOURCE_MOUSE to "mouse",
        InputDevice.SOURCE_TOUCHPAD to "touchpad",
        InputDevice.SOURCE_TRACKBALL to "trackball",
        InputDevice.SOURCE_ROTARY_ENCODER to "rotary",
        InputDevice.SOURCE_JOYSTICK to "joystick",
    )

    private fun sources(source: Int): String =
        SOURCES.filter { (flag, _) -> (source and flag) == flag }.joinToString("|") { it.second }.ifEmpty { "src=0x" + Integer.toHexString(source) }
}

/** Pane title, status bar and key help at the terminal font size. */
internal fun ViewPaneBinding.applyTtyFont() {
    listOf(title, status, keys).forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp) }
    syncFrame()
}

/**
 * Puts the pane's title where it belongs: in the frame's top line when the
 * pane is framed, else on its own line. Call after changing the title or keys.
 *
 * A screen reader says the title and the keys once, as the pane opens (its
 * pane title), and leaves the key line out after.
 */
internal fun ViewPaneBinding.syncFrame() {
    val frame = root
    frame.isFocusable = false
    frame.title = title.text?.toString().orEmpty()
    title.visibility = if (frame.style == TileFrame.Style.NONE) View.VISIBLE else View.GONE
    keys.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    ViewCompat.setAccessibilityPaneTitle(frame, Speech.speakable("${frame.title}. ${keys.text}"))
}

/**
 * The terminal font (general settings): its size, which everything but ASCII
 * art uses, its typeface, and the size of one character cell, the grid all
 * text sits on. Main thread only.
 */
internal object TtyFont {
    var sizeSp = 12f
        set(value) {
            field = value
            cell = null
        }

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            cell = null
        }

    private class Cell(val width: Float, val height: Int)

    private var cell: Cell? = null

    /** A paint set up like terminal text, for views that draw characters themselves. */
    fun paint(resources: Resources, sp: Float = sizeSp) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@TtyFont.typeface
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }

    /** Width of one character cell in pixels. */
    fun cellWidth(resources: Resources): Float = cell(resources).width

    /** Height of one text line in pixels, exactly as a TextView without font padding stacks them. */
    fun lineHeight(resources: Resources): Int = cell(resources).height

    private fun cell(resources: Resources): Cell = cell ?: paint(resources).let { paint ->
        val metrics = paint.fontMetricsInt
        Cell(paint.measureText("M").coerceAtLeast(1f), (metrics.descent - metrics.ascent).coerceAtLeast(1))
    }.also { cell = it }

    /** Rounds [px] up to whole cells of [cell] pixels. */
    fun snap(px: Int, cell: Float): Int = if (cell <= 0f) px else (ceil(px / cell - SNAP_SLACK) * cell).roundToInt()

    /** Measuring can be a hair over a whole cell; that shouldn't cost a whole extra one. */
    private const val SNAP_SLACK = 0.02f
}
