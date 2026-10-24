package com.newhost.qwertylauncher.ui

import android.view.KeyEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** A pane's cursor position; kept by the home ViewModel so it survives rotation and rebuilds. */
class PaneState {
    var cursor = -1
}

/**
 * The cursor of a full-screen TUI pane (config, help): a list of rows grouped
 * into sections by header rows, a cursor that skips headers, and scrolling by
 * whole lines, only as far as the cursor needs.
 *
 * Handles the navigation keys every pane shares; the pane handles the rest.
 */
internal class PaneCursor(
    private val list: RecyclerView,
    private val state: PaneState,
    private val rowCount: () -> Int,
    private val isHeader: (Int) -> Boolean,
    private val onMoved: (position: Int) -> Unit,
) {
    val position: Int get() = state.cursor

    init {
        list.layoutManager = LinearLayoutManager(list.context)
        list.terminalStyle()
    }

    /** Call when the pane becomes visible: puts the cursor back (or on the first row) and shows it. */
    fun show() {
        if (state.cursor !in 0 until rowCount() || isHeader(state.cursor)) {
            state.cursor = (0 until rowCount()).firstOrNull { !isHeader(it) } ?: -1
        }
        moveTo(state.cursor)
    }

    /** Up/down (j/k), PgUp/PgDn, g/G (Home/End), Tab/Shift+Tab. Returns false for other keys. */
    fun onKey(event: KeyEvent): Boolean {
        when {
            VimKeys.isUp(event) -> move(-1)
            VimKeys.isDown(event) -> move(+1)
            event.keyCode == KeyEvent.KEYCODE_PAGE_UP -> move(-visibleRows())
            event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> move(+visibleRows())
            event.keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                (event.keyCode == KeyEvent.KEYCODE_G && !event.isShiftPressed) -> move(-rowCount())
            event.keyCode == KeyEvent.KEYCODE_MOVE_END ||
                (event.keyCode == KeyEvent.KEYCODE_G && event.isShiftPressed) -> move(+rowCount())
            event.keyCode == KeyEvent.KEYCODE_TAB -> jumpSection(if (event.isShiftPressed) -1 else +1)
            else -> return false
        }
        return true
    }

    /**
     * Moves [delta] rows up or down, skipping headers and stopping at the ends.
     * A row taller than the list (a pane's widget block) scrolls a line at a
     * time first, until its far edge is in view; then the cursor moves on.
     */
    fun move(delta: Int) {
        if (delta == 0) return
        val direction = if (delta > 0) 1 else -1
        repeat(abs(delta)) {
            if (scrollWithin(direction)) return@repeat
            var next = state.cursor + direction
            while (next in 0 until rowCount() && isHeader(next)) next += direction
            if (next !in 0 until rowCount()) return@repeat
            moveTo(next)
        }
    }

    /** Scrolls a line through the row under the cursor if it runs past the list's edge that way. */
    private fun scrollWithin(direction: Int): Boolean {
        val row = list.layoutManager?.findViewByPosition(state.cursor) ?: return false
        val line = TtyFont.lineHeight(list.resources)
        val hidden = if (direction > 0) row.bottom - list.height else -row.top
        if (hidden <= 0) return false
        list.scrollBy(0, direction * minOf(line, hidden))
        return true
    }

    /** Back to the first row that isn't a header, as when the rows are all new. */
    fun first() {
        state.cursor = -1
        show()
    }

    fun moveTo(index: Int) {
        if (index !in 0 until rowCount()) return
        val fromBelow = index < state.cursor
        state.cursor = index
        if (!showTallRow(index, fromBelow)) keepVisible(index)
        onMoved(index)
    }

    /**
     * A row taller than the list shows the edge the cursor comes from: its
     * top going down, its bottom going up; scrolling then goes through it.
     */
    private fun showTallRow(index: Int, fromBelow: Boolean): Boolean {
        val layout = list.layoutManager as LinearLayoutManager
        val row = layout.findViewByPosition(index) ?: return false
        if (row.height <= list.height) return false
        layout.scrollToPositionWithOffset(index, if (fromBelow) list.height - row.height else 0)
        return true
    }

    /**
     * The unit for drag scrolling, in pixels: one line of text, which is what
     * a row is. (The first row's own height was used before, and a pane that
     * opens with a tall block, like weather's, then barely scrolled at all.)
     */
    fun rowHeight(): Int = TtyFont.lineHeight(list.resources)

    /** Moves to the first row of the next (or previous) section. */
    private fun jumpSection(direction: Int) {
        // Every section starts with a header, so its first row is the one after a header.
        val starts = (1 until rowCount()).filter { isHeader(it - 1) && !isHeader(it) }
        val cursor = state.cursor
        val target = if (direction > 0) {
            starts.firstOrNull { it > cursor }
        } else {
            val current = starts.lastOrNull { it <= cursor }
            // From inside a section, go to its start first, like a terminal pager.
            if (current != null && current < cursor) current else starts.lastOrNull { it < cursor }
        }
        target?.let(::moveTo)
    }

    /** Scrolls by whole lines, only as far as needed; shows a section's header with its first row. */
    private fun keepVisible(index: Int) {
        val layout = list.layoutManager as LinearLayoutManager
        val first = layout.findFirstCompletelyVisibleItemPosition()
        val last = layout.findLastCompletelyVisibleItemPosition()
        // Everything the cursor skips just above (a header, an art block) comes into view with the row.
        var top = index
        while (top > 0 && isHeader(top - 1)) top--
        when {
            first == RecyclerView.NO_POSITION -> layout.scrollToPosition(index)
            top < first -> layout.scrollToPositionWithOffset(top, 0)
            index > last -> layout.scrollToPositionWithOffset(first + (index - last), 0)
        }
    }

    private fun visibleRows(): Int {
        val layout = list.layoutManager as LinearLayoutManager
        val first = layout.findFirstCompletelyVisibleItemPosition()
        val last = layout.findLastCompletelyVisibleItemPosition()
        return if (first == RecyclerView.NO_POSITION) 1 else (last - first).coerceAtLeast(1)
    }
}
