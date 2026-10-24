package com.newhost.qwertylauncher.home

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.newhost.qwertylauncher.core.ScrollTarget
import com.newhost.qwertylauncher.ui.grid.GridControls
import kotlin.math.abs

/**
 * The home cursor: the one line the keyboard's scrolling steps along, from
 * the top bar's buttons through every tile and into panels (one favourite at a
 * time). It's tracked here, not left to Android's focus, so it survives taps,
 * leaving home and rebuilding the screen.
 */
internal class HomeCursor(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel

    /**
     * One place the home cursor can stop: a focusable view (a title button, a tile's
     * frame) or the inside of a panel (the favourites grid, which moves its own
     * selection). A panel's tile has both: its frame, then its inside.
     */
    private sealed interface CursorStop {
        val id: String

        class Plain(override val id: String, val view: View) : CursorStop
        class Inside(override val id: String, val target: ScrollTarget) : CursorStop
    }

    /**
     * Touch mode (any tap, or a keyboard that scrolls with fake swipes) clears focus, and
     * leaving home does too. So every focus change on home is saved, and nothing ever clears
     * the saved spot.
     */
    fun track() {
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            // Only while resumed: on start the grid is rebuilt, and Android parking focus on the
            // wrench meanwhile must not overwrite the saved spot.
            if (newFocus != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) save()
        }
    }

    /** Shows or hides home; hiding it clears focus, so the cursor is saved first and comes back with it. */
    fun showHome(visible: Boolean) {
        val wasVisible = binding.home.isVisible
        if (wasVisible && !visible) save()
        binding.home.isVisible = visible
        if (!wasVisible && visible) restore()
    }

    /** The cursor's stops in screen order: the top bar's buttons left to right, then every tile as arranged. */
    private fun stops(): List<CursorStop> = buildList {
        for ((id, _) in activity.topBar.order()) {
            val strip = activity.topBar.strip(id)
            // A strip is its buttons, stepped through one at a time.
            if (strip != null) {
                if (strip.shown().isNotEmpty()) add(CursorStop.Inside(id, strip))
            } else {
                add(CursorStop.Plain(HELP_BUTTON, binding.helpButton))
                add(CursorStop.Plain(WRENCH, binding.configButton))
            }
        }
        for (i in 0 until binding.widgets.childCount) {
            val frame = binding.widgets.getChildAt(i)
            val id = activity.tiles.idOf(frame) ?: continue
            if (!frame.isVisible) continue
            add(CursorStop.Plain(id, frame))
            // A widget without controls is just its frame.
            activity.tiles.insideOf(frame)?.takeUnless { it is GridControls && it.isEmpty }?.let { add(CursorStop.Inside(id, it)) }
        }
    }

    private fun CursorStop.isCurrent(): Boolean = when (this) {
        is CursorStop.Plain -> view.isFocused
        is CursorStop.Inside -> (target as? View)?.hasFocus() == true || (target !is View && target.selectionKey != null)
    }

    private fun enter(stop: CursorStop, fromEnd: Boolean): Boolean = when (stop) {
        is CursorStop.Plain -> stop.view.requestFocusFromTouch()
        is CursorStop.Inside -> stop.target.enter(fromEnd)
    }

    /** Saves where the home cursor is, if it's anywhere on home; otherwise keeps the last spot. */
    fun save() {
        if (!binding.home.isVisible) return
        val stop = stops().firstOrNull { it.isCurrent() } ?: return
        viewModel.homeSelection = when (stop) {
            is CursorStop.Plain -> HomeSelection.Stop(stop.id)
            is CursorStop.Inside -> stop.target.selectionKey?.let { HomeSelection.Panel(stop.id, it) } ?: HomeSelection.Stop(stop.id)
        }
    }

    private fun isOnHome() = stops().any { it.isCurrent() }

    /** Puts the home cursor back, once the views (and the grid, which rebuilds on start) are ready. */
    fun restore() {
        val selection = viewModel.homeSelection ?: return
        binding.root.post {
            // Applied even if something has focus: Android may have parked it on the wrench meanwhile.
            if (binding.home.isVisible) apply(selection)
        }
    }

    private fun apply(selection: HomeSelection) {
        val stops = stops()
        when (selection) {
            is HomeSelection.Stop ->
                stops.firstOrNull { it is CursorStop.Plain && it.id == selection.id }?.let { enter(it, fromEnd = false) }
            is HomeSelection.Panel ->
                (stops.firstOrNull { it is CursorStop.Inside && it.id == selection.id } as? CursorStop.Inside)
                    ?.target?.restoreSelection(selection.key)
        }
    }

    /**
     * Home is one line: the question mark (help), the wrench (config), each widget, then each favourite
     * in reading order across pages. Scrolling moves along it one item per
     * step, in one direction only: down and right both mean "next", up and
     * left "previous". The page scrolls to follow; at the ends it scrolls by
     * itself if it's taller than the screen. (Arrow keys still move in 2D.)
     */
    fun scroll(down: Int, right: Int) {
        // A touch (including keyboards that scroll by faking swipes) drops focus; continue from the saved cursor.
        if (!isOnHome()) viewModel.homeSelection?.let(::apply)
        val steps = down + right
        val direction = if (steps > 0) 1 else -1
        repeat(abs(steps)) {
            if (!step(direction) && binding.home.canScrollVertically(direction)) {
                binding.home.scrollBy(0, direction * activity.input.lineHeight())
            }
        }
    }

    /** Moves the home cursor one step along the line. Returns false at either end. */
    private fun step(direction: Int): Boolean {
        val stops = stops()
        val current = stops.indexOfFirst { it.isCurrent() }
        // Nothing selected yet: start at the first tile; one step up from there is the wrench.
        if (current < 0) {
            val topStops = stops.count { it.id == HELP_BUTTON || it.id == WRENCH || it.id.startsWith(TopBarController.STRIP) }
            return enter(stops.getOrNull(topStops) ?: stops.last(), fromEnd = false)
        }
        val stop = stops[current]
        // Inside a panel, move within it first (one app at a time) until its end.
        if (stop is CursorStop.Inside && stop.target.onScroll(0, direction)) return true
        val next = stops.getOrNull(current + direction) ?: return false
        return enter(next, fromEnd = direction < 0)
    }

    private companion object {
        const val HELP_BUTTON = "help_button"
        const val WRENCH = "wrench"
    }
}
