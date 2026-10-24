package com.newhost.qwertylauncher.ui.grid

import android.content.Context
import android.view.ViewGroup
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Grid
import com.newhost.qwertylauncher.core.Item
import com.newhost.qwertylauncher.core.Responsive
import com.newhost.qwertylauncher.core.Track
import com.newhost.qwertylauncher.ui.TtyFont
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [Responsive] grid layout on the terminal grid: picks the variant
 * that fits the room, sizes its tracks ([GridSolver]), and lays a view for
 * each item over its area. Content updates of the same shape update the
 * views in place, so art keeps animating; a different shape is rebuilt.
 *
 * Its width is always given; its height is set (a tile of a set height) or
 * free to grow (an automatic height), and `fr` rows behave accordingly.
 */
internal class CellGridView(context: Context, private val env: GridEnv) : ViewGroup(context) {

    private var layout: Responsive? = null

    /** The variant the views were built for, and a view for each of its items. */
    private var builtVariant = -1
    private val cells = mutableListOf<CellView>()

    // From the last measure, for layout: each item's area in cells, and the track starts.
    private var columnStarts = IntArray(0)
    private var columnSizes = IntArray(0)
    private var rowStarts = IntArray(0)
    private var rowSizes = IntArray(0)
    private var selected = false

    fun setLayout(new: Responsive) {
        val old = layout
        layout = new
        // Same variant, same shape: update in place now. Otherwise the next measure rebuilds.
        val variant = builtVariant
        if (old != null && variant in new.variants.indices && sameShape(new.variants[variant].grid, variant)) {
            new.variants[variant].grid.items.forEachIndexed { i, item -> cells[i].bind(item.cell) }
        } else {
            builtVariant = -1
        }
        requestLayout()
    }

    fun select(on: Boolean) {
        selected = on
        cells.forEach { it.select(on) }
    }

    /** Its controls, in the order the layout gives them: where the home cursor steps through. */
    fun controls(): List<ControlView> = cells.flatMap { controlsOf(it) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = layout ?: return setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val limit = MeasureSpec.getSize(heightMeasureSpec)
        val cell = TtyFont.cellWidth(resources)
        val line = TtyFont.lineHeight(resources)
        val room = (width / cell).toInt()
        val roomLines = if (bounded) limit / line else null

        // The first variant the room meets; the last one otherwise.
        val index = layout.variants.indexOfFirst { v ->
            v.minColumns <= room && (roomLines == null || v.minLines <= roomLines)
        }.takeIf { it >= 0 } ?: layout.variants.lastIndex
        val grid = layout.variants[index].grid
        val views = build(index)

        // Columns first: the width is always known.
        val columnTracks = tracks(grid.columns, grid.items.maxOfOrNull { it.column + it.columnSpan } ?: 0)
        columnSizes = GridSolver.solve(columnTracks, grid.columnGap, room) { i -> naturalColumnOf(grid, views, i, room, roomLines) }
        columnStarts = GridSolver.starts(columnSizes, grid.columnGap)

        // Then rows, from how tall the content is at those widths.
        val rowTracks = tracks(grid.rows, grid.items.maxOfOrNull { it.row + it.rowSpan } ?: 0)
        rowSizes = GridSolver.solve(rowTracks, grid.rowGap, roomLines) { r ->
            grid.items.indices
                .filter { grid.items[it].row == r && grid.items[it].rowSpan == 1 }
                .maxOfOrNull { views[it].naturalLines(areaColumns(grid.items[it])) } ?: 0
        }
        if (roomLines == null) {
            // Items across several rows still get the height they need.
            grid.items.forEachIndexed { i, item ->
                if (item.rowSpan > 1) {
                    GridSolver.fitSpan(rowSizes, rowTracks, grid.rowGap, item.row, item.rowSpan, views[i].naturalLines(areaColumns(item)))
                }
            }
        }
        rowStarts = GridSolver.starts(rowSizes, grid.rowGap)

        grid.items.forEachIndexed { i, item ->
            val view = views[i]
            val columns = areaColumns(item)
            val lines = areaLines(item)
            view.place(columns, lines)
            view.view.measure(
                MeasureSpec.makeMeasureSpec(pixelsAcross(item, width, cell), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lines * line, MeasureSpec.EXACTLY),
            )
        }
        val height = GridSolver.total(rowSizes, grid.rowGap) * line
        setMeasuredDimension(width, if (bounded) min(height, limit) else height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val grid = layout?.variants?.getOrNull(builtVariant)?.grid ?: return
        val cell = TtyFont.cellWidth(resources)
        val line = TtyFont.lineHeight(resources)
        grid.items.forEachIndexed { i, item ->
            val child = cells[i].view
            val x = (columnStarts.getOrElse(item.column) { 0 } * cell).roundToInt()
            val y = rowStarts.getOrElse(item.row) { 0 } * line
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    /** Views for variant [index], built if they aren't already. */
    private fun build(index: Int): List<CellView> {
        val grid = layout!!.variants[index].grid
        if (index == builtVariant && cells.size == grid.items.size) return cells
        removeAllViews()
        cells.clear()
        grid.items.forEach { item ->
            val view = cellViewFor(context, env, item.cell)
            view.align(item.alignX, item.alignY)
            view.select(selected)
            cells += view
            addView(view.view)
        }
        builtVariant = index
        return cells
    }

    /** Whether the views built for [variant] can show [grid]: the same kinds of cells in the same order. */
    private fun sameShape(grid: Grid, variant: Int): Boolean =
        variant == builtVariant && grid.items.size == cells.size &&
            grid.items.indices.all { kindOf(cells[it]) == grid.items[it].cell::class }

    private fun kindOf(view: CellView) = when (view) {
        is TextCell -> Cell.Text::class
        is ArtCell -> Cell.Art::class
        is PictureCell -> Cell.Picture::class
        is NestedCell -> Cell.Nested::class
        is FlowCell -> Cell.Flow::class
        is ChartCell -> Cell.Chart::class
        is ControlCell -> Cell.Control::class
        else -> null
    }

    /** The widest single-column item in column [i], in a grid of [room] by [roomLines]. */
    private fun naturalColumnOf(grid: Grid, views: List<CellView>, i: Int, room: Int, roomLines: Int?): Int =
        grid.items.indices
            .filter { grid.items[it].column == i && grid.items[it].columnSpan == 1 }
            .maxOfOrNull { views[it].naturalColumns(room, roomLines) } ?: 0

    /** [tracks], plus `auto` ones for items placed beyond them. */
    private fun tracks(tracks: List<Track>, needed: Int): List<Track> =
        if (tracks.size >= needed) tracks else tracks + List(needed - tracks.size) { Track.Auto() }

    private fun areaColumns(item: Item): Int = span(columnSizes, item.column, item.columnSpan, layoutGrid().columnGap)

    private fun areaLines(item: Item): Int = span(rowSizes, item.row, item.rowSpan, layoutGrid().rowGap)

    private fun layoutGrid(): Grid = layout!!.variants[builtVariant].grid

    private fun span(sizes: IntArray, from: Int, count: Int, gap: Int): Int {
        if (from >= sizes.size) return 0
        val last = min(from + count, sizes.size) - 1
        return (from..last).sumOf { sizes[it] } + gap * (last - from)
    }

    /** An item's width in pixels; items reaching the last column take the rest of the width, so nothing is left over. */
    private fun pixelsAcross(item: Item, width: Int, cell: Float): Int {
        val start = columnStarts.getOrElse(item.column) { 0 }
        val end = start + areaColumns(item)
        val left = (start * cell).roundToInt()
        val right = if (item.column + item.columnSpan >= columnSizes.size) max(width, left) else (end * cell).roundToInt()
        return (right - left).coerceAtLeast(0)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
