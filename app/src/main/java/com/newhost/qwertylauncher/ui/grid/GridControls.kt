package com.newhost.qwertylauncher.ui.grid

import com.newhost.qwertylauncher.core.ScrollTarget

/**
 * A tile's controls as a stop for the home cursor, like the favourites grid:
 * scrolling steps through them in order and moves on past the ends; the
 * cursor comes back to the same control by its key.
 */
internal class GridControls(private val grid: CellGridView) : ScrollTarget {

    /** Whether there's anything to stop on; a tile without controls is just its frame. */
    val isEmpty: Boolean get() = grid.controls().isEmpty()

    override fun onScroll(down: Int, right: Int): Boolean {
        val controls = grid.controls()
        if (controls.isEmpty()) return false
        val step = if (down + right > 0) 1 else -1
        val current = controls.indexOfFirst { it.isFocused }
        val target = if (current < 0) (if (step > 0) 0 else controls.lastIndex) else current + step
        // Past either end: the home cursor moves on to the next stop.
        if (target !in controls.indices) return false
        return controls[target].requestFocusFromTouch()
    }

    override val selectionKey: String? get() = grid.controls().firstOrNull { it.isFocused }?.key

    override fun restoreSelection(key: String): Boolean =
        grid.controls().firstOrNull { it.key == key }?.requestFocusFromTouch() == true

    override fun enter(fromEnd: Boolean): Boolean {
        val controls = grid.controls()
        if (controls.isEmpty()) return false
        return (if (fromEnd) controls.last() else controls.first()).requestFocusFromTouch()
    }
}
