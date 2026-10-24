package com.newhost.qwertylauncher.core

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.flow.Flow

/**
 * What a widget shows: [text] at normal size, and optional [art] drawn at a much
 * smaller size to its left (see [HomeWidget.artTextSizeSp]), e.g. from an [IconRenderer].
 */
data class WidgetContent(
    val text: CharSequence,
    val art: CharSequence? = null,
    /**
     * [art] is a frame of the widget's own animation (see [ArtAnimator.frames]):
     * show it as it comes, without the shell's draw-in or transition.
     */
    val live: Boolean = false,
    /**
     * The tile as a grid (see [Grid]), fitted to its size by the shell. When
     * set, [text] and [art] are ignored; without it, the shell lays them out
     * itself: the art beside the text when there's room, above it when not.
     */
    val layout: Responsive? = null,
)

/** A tile's smallest size when its widget or panel doesn't say. */
const val DEFAULT_MIN_COLUMNS = 8
const val DEFAULT_MIN_LINES = 2

/**
 * How wide a tile starts out. The user can resize any tile to any size, in
 * terminal columns and lines, by holding enter on it.
 */
enum class TileSize {
    /** Half the row. */
    NARROW,

    /** The whole row. */
    WIDE,
}

/**
 * A tile at the top of the home screen: clock, battery, weather... The shell
 * lays tiles out tmux-style, each in a box-drawn frame titled [title], and
 * draws its text in the terminal font. Content should be graphical, drawn
 * with characters (bars, boxes, art), not plain sentences.
 */
interface HomeWidget {
    val id: String

    /** Shown in the tile's top border. */
    val title: String get() = id

    /** Default position; lower comes first. The user can override it in config. */
    val order: Int get() = 0

    /** Default width; the user can resize the tile. */
    val size: TileSize get() = TileSize.NARROW

    /** The smallest the user can make the tile, in terminal columns and lines (frame included). */
    val minColumns: Int get() = DEFAULT_MIN_COLUMNS
    val minLines: Int get() = DEFAULT_MIN_LINES

    /** Size of [WidgetContent.art]. Tiny sizes buy detail; the art's column count must fit the screen. */
    val artTextSizeSp: Float get() = 2.6f

    /** Emits the widget's content. Collected only while the home screen is visible. */
    fun render(): Flow<WidgetContent>

    fun onClick(host: LauncherHost) {}
}

/**
 * Optional for widgets and panels that only work at some sizes, like a grid
 * of apps (one app fits 5 to 7 columns, two 10 to 14...): resizing goes a
 * cell at a time within a range, and jumps from the end of one to the start
 * of the next, over the sizes between. Sizes are of the tile's inside,
 * without its frame; ranges come smallest first, and may touch or overlap.
 */
interface SizeSteps {
    /** The widths it works at, in columns, up to [room]. */
    fun columnRanges(room: Int): List<IntRange>

    /** The heights it works at, [columns] wide, in lines, up to [room]. */
    fun lineRanges(columns: Int, room: Int): List<IntRange>
}

/**
 * Optional for views returned by [HomePanel.createView]: receives keyboard,
 * touchpad and wheel scrolling from the shell as whole steps.
 */
interface ScrollTarget {
    /**
     * Positive [down]/[right] mean down/right. Returns false if there was
     * nothing to move (e.g. already at the end), so the shell can scroll instead.
     */
    fun onScroll(down: Int, right: Int): Boolean

    /** A stable key for what's selected now (e.g. an app key), so the shell can bring it back; null if nothing. */
    val selectionKey: String? get() = null

    /** Selects [key] again, e.g. when the user returns to home. Returns false if it's no longer there. */
    fun restoreSelection(key: String): Boolean = false

    /** The cursor arrives from outside: select the first item, or the last if [fromEnd]. */
    fun enter(fromEnd: Boolean): Boolean = onScroll(0, 1)
}

/**
 * An interactive tile, such as the favourites grid: a view of its own rather
 * than text. It's framed, ordered, sized and arranged like a [HomeWidget].
 */
interface HomePanel {
    val id: String

    /** Shown in the tile's frame. */
    val title: String get() = id

    /** Default position among all tiles; lower comes first. */
    val order: Int get() = 0

    /** Default width; the user can resize the tile. */
    val size: TileSize get() = TileSize.WIDE

    /** The smallest the user can make the tile, in terminal columns and lines (frame included). */
    val minColumns: Int get() = DEFAULT_MIN_COLUMNS
    val minLines: Int get() = DEFAULT_MIN_LINES

    /**
     * Builds the panel's view. Observe data with [LauncherHost.lifecycleOwner]
     * so collection stops while the launcher is in the background.
     */
    fun createView(host: LauncherHost, parent: ViewGroup): View
}

/**
 * A full screen of a module's own, over everything, for what a list can't
 * be (a game): its own view, drawn as it likes, and every key, presses and
 * releases both, and every scroll (the keyboard's touch surface, a wheel).
 * Esc and Back close it, unless [onBack] takes them. Shown with
 * [LauncherHost.showScreen].
 */
interface HomeScreen {
    fun createView(host: LauncherHost, parent: ViewGroup): View

    /** A key going down (with its repeats) or up. Return true if it was used. */
    fun onKey(event: KeyEvent): Boolean = false

    /** Scrolling, in whole steps: positive is down, or right. */
    fun onScroll(down: Int, right: Int) {}

    /** Esc or Back: return true if handled; false closes the screen. */
    fun onBack(): Boolean = false

    /** It's been closed. */
    fun onClose() {}
}
