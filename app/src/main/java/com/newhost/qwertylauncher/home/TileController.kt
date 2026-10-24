package com.newhost.qwertylauncher.home

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.ScrollTarget
import com.newhost.qwertylauncher.core.SizeSteps
import com.newhost.qwertylauncher.core.Stage
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.shell.ShellSettings
import com.newhost.qwertylauncher.shell.Tile
import com.newhost.qwertylauncher.ui.TileFrame
import com.newhost.qwertylauncher.ui.TileLayout
import com.newhost.qwertylauncher.ui.VimKeys
import com.newhost.qwertylauncher.ui.grid.CellGridView
import com.newhost.qwertylauncher.ui.grid.GridControls
import com.newhost.qwertylauncher.ui.grid.defaultLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The tiles on home: widgets drawn as grids and panels (the favourites),
 * framed tmux-style, with the characters (the herald) living over them; and
 * arranging them: holding enter on a tile moves, resizes or hides it.
 */
internal class TileController(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel
    private val registry get() = activity.registry

    /** The tile being moved (hold enter on a tile, then "move"), or null. */
    private var movingTile: TileFrame? = null

    /** The tile being resized (hold enter on a tile, then "resize"), with its size before, or null. */
    private var resizing: Resizing? = null

    private class Resizing(val frame: TileFrame, val id: String, val columns: Int, val lines: Int)

    private val tileIds = mutableMapOf<View, String>()

    /** Panels' scrollable insides (the favourites grid), by their tile's frame. */
    private val panelTargets = mutableMapOf<View, ScrollTarget>()

    val isMoving get() = movingTile != null
    val isResizing get() = resizing != null

    /** The id of the tile [frame] frames, if it's a tile. */
    fun idOf(frame: View): String? = tileIds[frame]

    /** What the home cursor steps through inside [frame]'s tile (a panel's grid, a widget's controls), if anything. */
    fun insideOf(frame: View): ScrollTarget? = panelTargets[frame]

    /** Widgets as tmux-style tiles: the user's order and sizes, framed with text borders. */
    fun setup() {
        binding.widgets.refreshCells()
        val style = frameStyle(activity.settings[ShellSettings.tileFrame])
        val bound = mutableListOf<Triple<HomeWidget, TileFrame, CellGridView>>()
        for (tile in viewModel.tiles) {
            val widget = tile.widget
            val panel = tile.panel
            val frame: TileFrame = when {
                widget != null -> {
                    // Every widget is drawn as a grid, fitted to its tile.
                    val frame = activity.layoutInflater.inflate(R.layout.item_widget, binding.widgets, false) as TileFrame
                    val grid = CellGridView(activity, activity.gridEnv)
                    frame.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    frame.setOnClickListener { widget.onClick(activity) }
                    // Its controls, if it has any, are stops for the home cursor, like favourites.
                    panelTargets[frame] = GridControls(grid)
                    bound += Triple(widget, frame, grid)
                    frame
                }
                panel != null -> {
                    // A panel (the favourites grid) is a view of its own, framed like any tile.
                    val frame = TileFrame(activity)
                    val view = panel.createView(activity, frame)
                    frame.addView(
                        view,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                    )
                    if (view is ScrollTarget) panelTargets[frame] = view
                    frame
                }
                else -> continue
            }
            frame.title = tile.title
            frame.style = style
            // With a frame, selection lights the frame up; without one, text corners mark it.
            // Holding enter on a selected tile (or a long touch) opens its layout menu.
            frame.setOnLongClickListener {
                showTileMenu(frame, tile)
                true
            }
            binding.widgets.addView(frame, TileLayout.LayoutParams(tile.columns, tile.lines, tile.wide, tile.minColumns, tile.minLines))
            tileIds[frame] = tile.id
        }
        setupActors()
        // Sizes change in place, from config or a resize, without rebuilding home.
        activity.whileStarted {
            activity.settings.snapshot.collect { applyTileSizes() }
        }
        activity.whileStarted {
            // Art draws itself in on every start, rolls over when it changes, and flickers while
            // its tile is selected (with the ascii art module's animations), all while started.
            activity.gridEnv.scope = this
            for ((widget, frame, grid) in bound) {
                frame.setOnFocusChangeListener { _, focused -> grid.select(focused) }
                grid.select(frame.hasFocus())
                launch {
                    widget.render()
                        .catch { e -> emit(WidgetContent("error: ${e.message ?: e.javaClass.simpleName}")) }
                        .collect { content ->
                            grid.setLayout(content.layout ?: defaultLayout(content, widget.artTextSizeSp))
                            // A widget can hide itself by emitting nothing to show.
                            frame.isVisible = content.layout != null || content.text.isNotEmpty() || content.art != null
                        }
                }
            }
        }
    }

    /**
     * Characters living on home (the herald): they hear where the tiles are, in cells, and
     * their poses are drawn over the tiles. A tap on one pokes it.
     */
    private fun setupActors() {
        val actors = registry.actors
        if (actors.isEmpty()) return
        val stage = MutableStateFlow(Stage.EMPTY)
        val tiles = binding.widgets
        tiles.tileOf = { view -> tileIds[view]?.let { id -> id to ((view as? TileFrame)?.title ?: id) } }
        tiles.onStage = { stage.value = it }
        tiles.onPoke = { id -> actors.firstOrNull { it.id == id }?.onPoke(activity) }
        tiles.onDrag = { id, column, line, dropped -> actors.firstOrNull { it.id == id }?.onDrag(column, line, dropped) }
        binding.home.setOnScrollChangeListener { _, _, _, _, _ -> tiles.updateStage() }
        activity.whileStarted {
            for (actor in actors) {
                launch {
                    try {
                        actor.perform(stage)
                            .catch { e -> registry.log.e("${actor.id} failed", e) }
                            .collect { tiles.setPose(actor.id, it) }
                    } finally {
                        tiles.setPose(actor.id, null)
                    }
                }
            }
        }
    }

    // --- Arranging tiles: hold enter on a tile ---

    private fun showTileMenu(frame: TileFrame, tile: Tile) {
        activity.showMenu(
            tile.title,
            listOfNotNull(
                tile.widget?.let { widget -> MenuItem("open") { widget.onClick(activity) } },
                MenuItem("move") { startMoving(frame) },
                MenuItem("resize") { startResizing(frame) },
                // Just this tile: its module stays on (commands, search, other tiles).
                MenuItem("hide this tile") {
                    registry.hideTile(tile.id)
                    val module = registry.tileModule(tile.id)?.title ?: tile.title
                    activity.console.println("Hid the ${tile.title} tile; $module is still on. Show it again in config, under $module.")
                },
                MenuItem("reset layout") {
                    registry.resetTileLayout()
                    activity.recreate()
                },
            ),
        )
    }

    private fun startMoving(frame: TileFrame) {
        movingTile = frame
        frame.status = "moving"
        frame.requestFocusFromTouch()
        activity.prompt.flash("move ${frame.title}: ↑↓ move  ⏎ drop")
    }

    /** Moves the tile one place earlier or later, live; the new order is saved right away. */
    private fun moveTile(delta: Int) {
        val frame = movingTile ?: return
        val tiles = binding.widgets
        val from = tiles.indexOfChild(frame)
        val to = (from + delta).coerceIn(0, tiles.childCount - 1)
        if (from < 0 || from == to) return
        val params = frame.layoutParams
        tiles.removeViewAt(from)
        tiles.addView(frame, to, params)
        frame.requestFocusFromTouch()
        registry.saveTileOrder((0 until tiles.childCount).mapNotNull { tileIds[tiles.getChildAt(it)] })
    }

    fun stopMoving() {
        movingTile?.status = null
        movingTile = null
    }

    /** Puts the saved sizes on the tiles, unless one is being resized right now. */
    private fun applyTileSizes() {
        if (resizing != null) return
        val tiles = viewModel.tiles.associateBy { it.id }
        for ((view, id) in tileIds) {
            val tile = tiles[id] ?: continue
            val params = view.layoutParams as? TileLayout.LayoutParams ?: continue
            if (params.columns == tile.columns && params.lines == tile.lines) continue
            params.columns = tile.columns
            params.lines = tile.lines
            view.layoutParams = params
        }
    }

    private fun startResizing(frame: TileFrame) {
        val id = tileIds[frame] ?: return
        val params = frame.layoutParams as? TileLayout.LayoutParams ?: return
        resizing = Resizing(frame, id, params.columns, params.lines)
        frame.status = "resizing"
        frame.requestFocusFromTouch()
        activity.prompt.renderStatusLine()
        activity.prompt.flash(
            "resize ${frame.title}: ←→ width  ↑↓ height  shift x5  0 auto  ⏎ keep  esc undo",
        )
    }

    /**
     * Grows or shrinks the tile, live, from its minimum up to the whole
     * screen, a cell at a time; for a tile that only works at some sizes
     * (a grid of apps), within them, jumping over the sizes between.
     * [columns] and [lines] are how many steps, and which way. A size that
     * was automatic starts from what's on screen.
     */
    private fun resizeTile(columns: Int, lines: Int) {
        val frame = resizing?.frame ?: return
        val layout = binding.widgets
        val params = frame.layoutParams as TileLayout.LayoutParams
        val (shownColumns, shownLines) = layout.cellsOf(frame)
        // The frame's own columns and lines, around the inside the steps are for.
        val frameColumns = ((frame.paddingLeft + frame.paddingRight) / layout.cellWidth).roundToInt()
        val frameLines = (frame.paddingTop + frame.paddingBottom) / layout.lineHeight
        val steps = sizeSteps(frame)
        if (columns != 0) {
            val from = if (params.columns > 0) params.columns.coerceAtMost(layout.totalColumns) else shownColumns
            val min = params.minColumns.coerceAtMost(layout.totalColumns)
            val max = layout.totalColumns
            val ranges = steps?.columnRanges(max - frameColumns)?.map { it.first + frameColumns..it.last + frameColumns }
            params.columns = step(from, columns, min, max, ranges)
        }
        if (lines != 0) {
            val from = if (params.lines > 0) params.lines.coerceAtMost(layout.maxLines) else shownLines
            val min = params.minLines.coerceAtMost(layout.maxLines)
            val max = layout.maxLines
            val inside = (if (params.columns > 0) params.columns else shownColumns) - frameColumns
            val ranges = steps?.lineRanges(inside, max - frameLines)?.map { it.first + frameLines..it.last + frameLines }
            params.lines = step(from, lines, min, max, ranges)
        }
        frame.layoutParams = params
        activity.prompt.renderStatusLine()
    }

    /** The sizes [frame]'s tile works at, if it says; null when every size does. */
    private fun sizeSteps(frame: View): SizeSteps? {
        val tile = tileIds[frame]?.let { id -> viewModel.tiles.firstOrNull { it.id == id } } ?: return null
        return tile.panel as? SizeSteps ?: tile.widget as? SizeSteps
    }

    private fun autoSize() {
        val frame = resizing?.frame ?: return
        val params = frame.layoutParams as TileLayout.LayoutParams
        params.columns = 0
        params.lines = 0
        frame.layoutParams = params
        activity.prompt.renderStatusLine()
    }

    /** Ends resizing: [keep] saves the new size, otherwise the old one comes back. */
    fun stopResizing(keep: Boolean) {
        val r = resizing ?: return
        resizing = null
        r.frame.status = null
        activity.prompt.renderStatusLine()
        val params = r.frame.layoutParams as TileLayout.LayoutParams
        if (keep) {
            registry.setTileSize(r.id, params.columns, params.lines)
        } else {
            params.columns = r.columns
            params.lines = r.lines
            r.frame.layoutParams = params
        }
    }

    /** While resizing a tile, arrows (h j k l), 0 and enter/esc belong to the resize. */
    fun onResizeKey(event: KeyEvent): Boolean {
        val step = if (event.isShiftPressed) BIG_RESIZE_STEP else 1
        when {
            VimKeys.isLeft(event) -> resizeTile(-step, 0)
            VimKeys.isRight(event) -> resizeTile(step, 0)
            VimKeys.isUp(event) -> resizeTile(0, -step)
            VimKeys.isDown(event) -> resizeTile(0, step)
            event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_A -> autoSize()
            // Not a held enter's repeats: the press that picked "resize" may still be down.
            event.repeatCount > 0 && isEnter(event) -> Unit
            isEnter(event) || event.keyCode == KeyEvent.KEYCODE_SPACE -> stopResizing(keep = true)
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_Q ||
                event.keyCode == KeyEvent.KEYCODE_BACK -> stopResizing(keep = false)
        }
        return true
    }

    /** While moving a tile, arrows, j/k and enter/esc belong to the move. */
    fun onMoveKey(event: KeyEvent): Boolean {
        when {
            VimKeys.isUp(event) || VimKeys.isLeft(event) -> moveTile(-1)
            VimKeys.isDown(event) || VimKeys.isRight(event) -> moveTile(+1)
            isEnter(event) || event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_SPACE ||
                event.keyCode == KeyEvent.KEYCODE_Q -> stopMoving()
            event.keyCode == KeyEvent.KEYCODE_BACK -> return false
        }
        return true
    }

    /**
     * Scrolling while arranging: moves the tile being moved a place, or resizes the one being
     * resized (sideways the width, up and down the height). False when neither is happening.
     */
    fun onScroll(down: Int, right: Int): Boolean {
        when {
            movingTile != null -> moveTile(if (down + right > 0) 1 else -1)
            resizing != null -> resizeTile(right, down)
            else -> return false
        }
        return true
    }

    /** The status line while a tile is being resized: its size now. Null when none is. */
    fun statusText(): String? {
        val r = resizing ?: return null
        val params = r.frame.layoutParams as TileLayout.LayoutParams
        val width = if (params.columns > 0) "${params.columns} columns" else "auto"
        val height = if (params.lines > 0) "${params.lines} lines" else "auto"
        return "resize ${r.frame.title}: width $width, height $height"
    }

    private companion object {
        const val BIG_RESIZE_STEP = 5

        /**
         * [count] steps from [from] (negative: smaller), between [min] and [max].
         * With [ranges] (the sizes the tile works at), a cell at a time within a
         * range, and from the end of one straight to the start of the next (or,
         * shrinking, to the end of the one before); without, a cell at a time.
         */
        fun step(from: Int, count: Int, min: Int, max: Int, ranges: List<IntRange>?): Int {
            val usable = ranges?.map { maxOf(it.first, min)..minOf(it.last, max) }?.filter { !it.isEmpty() }
            if (usable.isNullOrEmpty()) return (from + count).coerceIn(min, max)
            fun works(size: Int) = usable.any { size in it }
            var size = from
            repeat(abs(count)) {
                size = if (count > 0) {
                    if (works(size + 1)) size + 1 else usable.map { it.first }.filter { it > size }.minOrNull() ?: size
                } else {
                    if (works(size - 1)) size - 1 else usable.map { it.last }.filter { it < size }.maxOrNull() ?: size
                }
            }
            return size
        }
    }
}
