package com.newhost.qwertylauncher.home

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.view.isVisible
import com.newhost.qwertylauncher.ui.DragSteps
import com.newhost.qwertylauncher.ui.InputDevices
import com.newhost.qwertylauncher.ui.InputMonitor
import com.newhost.qwertylauncher.ui.ScrollSteps
import com.newhost.qwertylauncher.ui.cancelTouch

/**
 * Keys, scrolling and touch on home, handed to whichever part of the screen
 * has them: a module's screen, a menu, something being arranged, a pane, the
 * prompt, or the home cursor.
 */
internal class HomeInput(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel

    /** The input channel of the debug log, for the `debug input` monitor. */
    val log by lazy { activity.log.tagged("input") }

    /** The input devices and what each is for, learnt as home starts: fingers from keyboards. */
    private val devices by lazy { InputDevices(activity).apply { onLearnt = { log.d(it) } } }

    private val scrollSteps = ScrollSteps()

    /** The touch going on is the keyboard's surface's: kept from the screen whole, taps too. */
    private var surfaceGesture = false
    private val dragSteps by lazy {
        DragSteps(
            activity,
            lineHeightPx = { lineHeight() },
            columnWidthPx = { (RESIZE_DRAG_DP * activity.resources.displayMetrics.density).toInt() },
            // Sideways swipes resize a tile, or slide a value being edited in config.
            sideways = { activity.tiles.isResizing || activity.panes.isEditing },
        )
    }

    fun start() = devices.start()

    fun stop() = devices.stop()

    /** Every key, before the views see it. False leaves it to them. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        // A module's own screen (a game) has every key; Esc and Back close it unless it takes them.
        activity.panes.screen?.let { open ->
            if (InputMonitor.enabled && event.action == KeyEvent.ACTION_DOWN) log.d(InputMonitor.describe(event) + " (screen)")
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) activity.panes.backFromScreen()
                return true
            }
            open.onKey(event)
            return true
        }
        if (activity.prompt.onEnterUp(event)) return true
        if (InputMonitor.enabled && event.action == KeyEvent.ACTION_DOWN) log.d(InputMonitor.describe(event))
        return event.action == KeyEvent.ACTION_DOWN && handleKey(event)
    }

    /** A key going down: to the menu, whatever is being arranged, the open pane, or else the prompt. */
    fun handleKey(event: KeyEvent): Boolean {
        val menu = activity.menu
        if (menu.isOpen) return menu.onKey(event)
        if (activity.topBar.movingGroup != null) return activity.topBar.onMoveKey(event)
        if (activity.tiles.isMoving) return activity.tiles.onMoveKey(event)
        if (activity.tiles.isResizing) return activity.tiles.onResizeKey(event)
        activity.panes.onKey(event)?.let { return it }
        val searching = viewModel.state.value.searching
        when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (searching) viewModel.moveSelection(PAGE_STEPS) else scroll(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (searching) viewModel.moveSelection(-PAGE_STEPS) else scroll(1, 0)
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                escape()
                return true
            }
        }
        return activity.prompt.onKey(event)
    }

    /** Esc/Back: close a menu, a pane or the prompt first, then clear the console. */
    fun escape() {
        val panes = activity.panes
        when {
            panes.screen != null -> panes.backFromScreen()
            activity.menu.isOpen -> activity.menu.close()
            panes.back() -> Unit
            viewModel.state.value.promptOpen -> activity.clearQuery()
            else -> activity.console.clear()
        }
    }

    /** Scroll from any source (touchpad, keyboard scroll gesture, wheel) moves whatever is on screen. */
    fun onGenericMotion(event: MotionEvent): Boolean {
        if (InputMonitor.enabled && event.actionMasked != MotionEvent.ACTION_HOVER_MOVE) {
            log.d(InputMonitor.describe(event))
        }
        // The Titan's keyboard starts a touch as a touch screen's and may end it as a touchpad's,
        // which comes this way instead: end the touch here, or it hangs open till Android cancels it.
        if (surfaceGesture && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            surfaceGesture = false
            dragSteps.onTouch(event, allowed = false)
            if (InputMonitor.enabled && dragSteps.lastDrag.isNotEmpty()) log.d(dragSteps.lastDrag + ", ended as a touchpad's")
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val steps = scrollSteps.consume(event)
        if (!steps.isEmpty) scroll(steps.down, steps.right)
        return true
    }

    /**
     * A finger scrolls the screen itself: the home screen, a pane's list,
     * the results, the console, as Android does. The keyboard's scroll
     * gesture (which arrives as touches, from another device), the touchpad
     * and the wheel step the cursor from tile to tile, as does any drag
     * while arranging something (moving or resizing a tile, moving the top
     * bar, a menu, a value being slid in config). False leaves the touch to the views.
     */
    fun onTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (InputMonitor.enabled && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)) {
            log.d(InputMonitor.describe(event))
        }
        val wasDragging = dragSteps.dragging
        val arranging = activity.tiles.isResizing || activity.tiles.isMoving || activity.topBar.movingGroup != null ||
            activity.menu.isOpen || activity.panes.isEditing
        val keyboard = !devices.isFinger(event)
        // Read on the touch down: a drag from a character always carries it; the keyboard's always steps,
        // either way (never reaching the screen); a finger's only while arranging something.
        val allowed = action != MotionEvent.ACTION_DOWN ||
            ((arranging || keyboard) && !(binding.widgets.isVisible && binding.widgets.actorAt(event.rawX, event.rawY)))
        if (action == MotionEvent.ACTION_DOWN) surfaceGesture = allowed && devices.isSurface(event)
        if (InputMonitor.enabled && action == MotionEvent.ACTION_DOWN) {
            log.d("touch down from device ${event.deviceId}: " + if (keyboard) "keyboard (steps)" else "finger (scrolls the screen)")
        }
        val result = dragSteps.onTouch(event, allowed, bothWays = keyboard)
        if (InputMonitor.enabled && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && dragSteps.lastDrag.isNotEmpty()) {
            log.d(dragSteps.lastDrag)
        }
        // Not a drag (a tap, a double tap): the screen still mustn't see it, or a tap on the
        // keyboard taps whatever is under it on screen (a config row). Except on a character: a poke.
        val surface = surfaceGesture
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) surfaceGesture = false
        val steps = result ?: return surface
        if (!wasDragging && dragSteps.dragging) activity.cancelTouch(event)
        if (!steps.isEmpty) scroll(steps.down, steps.right)
        return true
    }

    /**
     * While searching, scrolling moves the selection (down = toward the prompt,
     * where the best match is). On the home screen it moves the home cursor;
     * see [HomeCursor.scroll].
     */
    private fun scroll(down: Int, right: Int) {
        activity.panes.screen?.let {
            it.onScroll(down, right)
            return
        }
        if (activity.topBar.movingGroup != null) {
            activity.topBar.onMoveScroll(down, right)
            return
        }
        if (activity.tiles.onScroll(down, right)) return
        if (activity.menu.isOpen) {
            activity.menu.move(down + right)
            return
        }
        if (activity.panes.scroll(down, right)) return
        if (viewModel.state.value.searching) {
            viewModel.moveSelection(-(down + right))
            return
        }
        activity.cursor.scroll(down, right)
    }

    /** Pixels of drag per scroll step: a row in a pane, a grid row on home, a text line elsewhere. */
    fun lineHeight(): Int {
        activity.panes.rowHeight()?.let { return it }
        val state = viewModel.state.value
        return when {
            !state.searching && !activity.menu.isOpen -> (HOME_DRAG_STEP_DP * activity.resources.displayMetrics.density).toInt()
            else -> binding.query.lineHeight.coerceAtLeast(1)
        }
    }

    private companion object {
        const val PAGE_STEPS = 5

        // Sideways drag per column while resizing: finer than a line, since tiles are wider than tall.
        const val RESIZE_DRAG_DP = 12
        const val HOME_DRAG_STEP_DP = 48
    }
}

internal fun isEnter(event: KeyEvent) =
    event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
