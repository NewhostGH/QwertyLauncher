package com.newhost.qwertylauncher.ui.grid

import android.content.Context
import android.content.res.Resources
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.newhost.qwertylauncher.core.Align
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Responsive
import com.newhost.qwertylauncher.core.Track
import com.newhost.qwertylauncher.ui.ArtPlayer
import com.newhost.qwertylauncher.ui.ArtShape
import com.newhost.qwertylauncher.ui.ArtView
import com.newhost.qwertylauncher.ui.Speech
import com.newhost.qwertylauncher.ui.TtyFont
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * What cell views need from where they're shown: a [scope] that runs while
 * they're on screen (it changes each time home starts), the icon renderer,
 * and the art animator.
 */
internal class GridEnv(
    var scope: CoroutineScope,
    val renderer: () -> IconRenderer?,
    val animator: () -> ArtAnimator?,
    /** What controls run their actions with; none where controls can't act. */
    val host: LauncherHost? = null,
)

/** A view showing one [Cell], able to say how big it would like to be. */
internal interface CellView {
    val view: View

    /** New content of the same kind: updated in place, so animations carry on. */
    fun bind(cell: Cell)

    /**
     * How wide its content would like to be, in columns, in a grid [room]
     * columns wide and [roomLines] tall (null when the height is free).
     */
    fun naturalColumns(room: Int, roomLines: Int?): Int

    /** How tall its content is, in lines, at [columns] wide. */
    fun naturalLines(columns: Int): Int

    /** The area it got, in cells, just before it's measured to it. */
    fun place(columns: Int, lines: Int) {}

    /** Its tile or row is selected: art flickers. */
    fun select(on: Boolean) {}

    fun align(x: Align, y: Align) {}
}

internal fun cellViewFor(context: Context, env: GridEnv, cell: Cell): CellView = when (cell) {
    is Cell.Text -> TextCell(context)
    is Cell.Art -> ArtCell(context, env)
    is Cell.Picture -> PictureCell(context, env)
    is Cell.Nested -> NestedCell(context, env)
    is Cell.Flow -> FlowCell(context, env)
    is Cell.Chart -> ChartCell(context)
    is Cell.Control -> ControlCell(context, env)
}.also { it.bind(cell) }

/** The controls inside [view], in order: itself if it's one, or those of a nested grid or flow. */
internal fun controlsOf(view: CellView): List<ControlView> = when (view) {
    is ControlCell -> listOf(view.control)
    is NestedCell -> view.controls()
    is FlowCell -> view.controls()
    else -> emptyList()
}

/** Cells of the same kind can be updated in place; others need a new view. */
internal fun sameKind(a: Cell, b: Cell) = a::class == b::class

/**
 * How wide [cell]'s content is, in columns, with nothing in the way, worked
 * out from the content alone. At [lines] tall, art is as wide as its shape is
 * at that height.
 */
internal fun naturalColumns(cell: Cell, lines: Int? = null): Int = when (cell) {
    is Cell.Text -> cell.text.split('\n').maxOfOrNull { it.length } ?: 0
    is Cell.Art -> shapeWidth(cell.size, lines)
    is Cell.Picture -> shapeWidth(cell.size, lines)
    is Cell.Nested -> naturalColumns(cell.layout, lines)
    is Cell.Flow -> cell.minColumns
    is Cell.Chart -> cell.values.size
    is Cell.Control -> naturalColumns(cell.face, lines)
}

/** How wide [layout]'s roomiest variant is, every track at its content's size, [lines] tall if set. */
internal fun naturalColumns(layout: Responsive, lines: Int? = null): Int {
    val grid = layout.variants.firstOrNull()?.grid ?: return 0
    val needed = grid.items.maxOfOrNull { it.column + it.columnSpan } ?: 0
    val tracks = if (grid.columns.size >= needed) grid.columns else grid.columns + List(needed - grid.columns.size) { Track.Auto() }
    val sizes = GridSolver.solve(tracks, grid.columnGap, null) { i ->
        grid.items.filter { it.column == i && it.columnSpan == 1 }.maxOfOrNull { naturalColumns(it.cell, lines) } ?: 0
    }
    return GridSolver.total(sizes, grid.columnGap)
}

/**
 * How tall [cell] is at [columns] wide, in lines, worked out without touching
 * any view: measuring real views to find out resized their contents and made
 * them ask for another layout, over and over.
 */
internal fun naturalLines(cell: Cell, columns: Int, res: Resources): Int = when (cell) {
    is Cell.Text -> textLines(cell.text, cell.wrap, columns, res)
    is Cell.Art -> if (cell.fill) cell.size.lines else keepShape(cell.size, columns)
    is Cell.Picture -> keepShape(cell.size, columns)
    is Cell.Nested -> naturalLines(cell.layout, columns, res)
    is Cell.Flow -> {
        val perRow = ((columns + cell.gap) / (cell.minColumns + cell.gap)).coerceAtLeast(1)
        minOf(cell.rows, (cell.cells.size + perRow - 1) / perRow)
    }
    is Cell.Chart -> cell.lines + if (cell.axis.isEmpty()) 0 else 1
    is Cell.Control -> naturalLines(cell.face, columns, res)
}

/** How tall [layout] is at [columns] wide (its height free to grow), without touching any view. */
internal fun naturalLines(layout: Responsive, columns: Int, res: Resources): Int {
    val variant = layout.variants.firstOrNull { it.minColumns <= columns } ?: layout.variants.lastOrNull() ?: return 0
    val grid = variant.grid
    val needColumns = grid.items.maxOfOrNull { it.column + it.columnSpan } ?: 0
    val columnTracks = if (grid.columns.size >= needColumns) grid.columns else grid.columns + List(needColumns - grid.columns.size) { Track.Auto() }
    val columnSizes = GridSolver.solve(columnTracks, grid.columnGap, columns) { i ->
        grid.items.filter { it.column == i && it.columnSpan == 1 }.maxOfOrNull { naturalColumns(it.cell) } ?: 0
    }
    fun width(item: com.newhost.qwertylauncher.core.Item): Int {
        val last = minOf(item.column + item.columnSpan, columnSizes.size) - 1
        if (item.column > last) return 0
        return (item.column..last).sumOf { columnSizes[it] } + grid.columnGap * (last - item.column)
    }
    val needRows = grid.items.maxOfOrNull { it.row + it.rowSpan } ?: 0
    val rowTracks = if (grid.rows.size >= needRows) grid.rows else grid.rows + List(needRows - grid.rows.size) { Track.Auto() }
    val rowSizes = GridSolver.solve(rowTracks, grid.rowGap, null) { r ->
        grid.items.filter { it.row == r && it.rowSpan == 1 }.maxOfOrNull { naturalLines(it.cell, width(it), res) } ?: 0
    }
    grid.items.filter { it.rowSpan > 1 }.forEach { item ->
        GridSolver.fitSpan(rowSizes, rowTracks, grid.rowGap, item.row, item.rowSpan, naturalLines(item.cell, width(item), res))
    }
    return GridSolver.total(rowSizes, grid.rowGap)
}

/** Lines [text] takes at [columns] wide in the terminal font: wrapped, or one a line when it isn't. */
private fun textLines(text: CharSequence, wrap: Boolean, columns: Int, res: Resources): Int {
    if (text.isEmpty()) return 1
    if (!wrap) return text.count { it == '\n' } + 1
    val width = ceil(columns * TtyFont.cellWidth(res)).toInt().coerceAtLeast(1)
    val paint = TextPaint(TtyFont.paint(res))
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(false).build().lineCount
}

private fun shapeWidth(size: com.newhost.qwertylauncher.core.Size, lines: Int?): Int =
    if (lines == null || size.lines <= 0) size.columns else ceil(size.columns * lines.toFloat() / size.lines).toInt().coerceAtLeast(1)

internal fun gravity(x: Align, y: Align): Int {
    val horizontal = when (x) {
        Align.START -> Gravity.START
        Align.CENTER -> Gravity.CENTER_HORIZONTAL
        Align.END -> Gravity.END
    }
    val vertical = when (y) {
        Align.START -> Gravity.TOP
        Align.CENTER -> Gravity.CENTER_VERTICAL
        Align.END -> Gravity.BOTTOM
    }
    return horizontal or vertical
}


/** Lines art of [size] needs at [columns] wide, keeping its shape: it grows and shrinks with its width. */
private fun keepShape(size: com.newhost.qwertylauncher.core.Size?, columns: Int): Int {
    size ?: return 0
    if (size.columns <= 0) return size.lines
    return ceil(size.lines * columns.toFloat() / size.columns).toInt().coerceAtLeast(1)
}

/**
 * How wide art of [size] would like to be in a grid [room] columns wide: with
 * a set height ([roomLines]), as wide as its shape is at that height; without
 * one, about a third of the width (never less than its own size), so it grows
 * with the tile. Never more than half the room, nor under a cell.
 */
private fun shapedColumns(size: com.newhost.qwertylauncher.core.Size?, room: Int, roomLines: Int?): Int {
    size ?: return 0
    val wanted = if (roomLines != null && size.lines > 0) {
        ceil(size.columns * roomLines.toFloat() / size.lines).toInt()
    } else {
        maxOf(size.columns, room / GROW_DIVISOR)
    }
    return wanted.coerceIn(1, (room * MAX_ART_SHARE).toInt().coerceAtLeast(1))
}

private const val GROW_DIVISOR = 3
private const val MAX_ART_SHARE = 0.5f

/**
 * Characters of art per cell each way when a cell doesn't say: art characters
 * about [ART_SP] big, as fine as app icons, whatever the terminal font size.
 */
internal fun artDetail(): Int = ceil(TtyFont.sizeSp / ART_SP).toInt().coerceAtLeast(1)

private const val ART_SP = 2.4f

/** Half-block art fills this much of its area, leaving room for the blocks' overhang. */
private const val FINE_FILL = 0.88f

/** Terminal text at the terminal size: wrapped at the area's edge, or cut there. */
internal class TextCell(context: Context) : CellView {
    private val text = TextView(context).apply {
        includeFontPadding = false
        setTextColor(context.getColor(CoreR.color.tty_fg))
    }
    override val view: View get() = text
    private var content: Cell.Text? = null

    override fun bind(cell: Cell) {
        val new = cell as Cell.Text
        val old = content
        content = new
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        if (text.isSingleLine != !new.wrap) text.isSingleLine = !new.wrap
        if (!new.wrap) text.ellipsize = null
        // The same text (colours included) isn't set again: setting it asks for another layout.
        if (old == null || old.text != new.text) text.text = new.text
        // Meters and drawing characters, said as words by a screen reader; or what the module says it shows.
        text.contentDescription = new.spoken ?: if (Speech.needed(new.text)) Speech.speakable(new.text) else null
    }

    override fun naturalColumns(room: Int, roomLines: Int?): Int = content?.let { naturalColumns(it) } ?: 0

    override fun naturalLines(columns: Int): Int = content?.let { naturalLines(it, columns, text.resources) } ?: 0

    override fun place(columns: Int, lines: Int) {
        // Wrapped text shows what fits and cuts the rest, like a terminal pane. Only set when it
        // changes: setting it asks for another layout even when it's the same.
        val max = lines.coerceAtLeast(1)
        if (content?.wrap == true && text.maxLines != max) text.maxLines = max
    }

    override fun align(x: Align, y: Align) {
        text.gravity = gravity(x, y)
    }
}

/**
 * A drawing rendered to ASCII art for exactly the area it gets: [Cell.Art.detail]
 * characters of art per cell each way, so it's as sharp at a big size as at a
 * small one. A new picture rolls over or draws itself in; frames of its own
 * animation and new sizes just show.
 */
internal class ArtCell(context: Context, private val env: GridEnv) : CellView {
    // Pictures are decoration to a screen reader, which would read every character of them.
    private val art = ArtView(context).apply {
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override val view: View get() = art
    private var cell: Cell.Art? = null
    private var columns = 0
    private var lines = 0

    /** What's on screen, and what's being drawn: the picture, its frame, and the art size. */
    private var drawn: Triple<String, Int, Pair<Int, Int>>? = null
    private var drawing: Triple<String, Int, Pair<Int, Int>>? = null
    private var shownKey: String? = null
    private var render: Job? = null
    private var selected = false
    private var player: ArtPlayer? = null
    private var playerScope: CoroutineScope? = null

    override fun bind(cell: Cell) {
        this.cell = cell as Cell.Art
        spoken(art, cell.spoken)
        redraw()
    }

    override fun naturalColumns(room: Int, roomLines: Int?) =
        if (cell?.fill == true) room else shapedColumns(cell?.size, room, roomLines)

    /** It keeps its shape: as many lines as its width calls for, narrower or wider than it likes. Filling art takes its own size's. */
    override fun naturalLines(columns: Int): Int = cell?.let { if (it.fill) it.size.lines else null } ?: keepShape(cell?.size, columns)

    override fun place(columns: Int, lines: Int) {
        if (columns == this.columns && lines == this.lines) return
        this.columns = columns
        this.lines = lines
        redraw()
    }

    override fun select(on: Boolean) {
        selected = on
        player()?.select(on)
    }

    override fun align(x: Align, y: Align) {
        art.gravity = gravity(x, y)
    }

    /**
     * How many art characters fill the area edge to edge, measured at the
     * art's own size: small text isn't an exact fraction of a cell, so
     * counting by cells would run past the right edge.
     */
    private fun filling(): Pair<Int, Int> {
        val res = art.resources
        val charWidth = art.paint.measureText("M").takeIf { it > 0f } ?: return columns to lines
        val lineHeight = art.lineHeight.takeIf { it > 0 } ?: return columns to lines
        val width = columns * TtyFont.cellWidth(res)
        val height = lines * TtyFont.lineHeight(res)
        return (width / charWidth).toInt().coerceAtLeast(1) to (height / lineHeight).coerceAtLeast(1)
    }

    private fun player(): ArtPlayer? {
        val scope = env.scope
        if (player == null || playerScope !== scope) {
            player = ArtPlayer(art, scope, env.animator).also { it.select(selected) }
            playerScope = scope
            shownKey = null
        }
        return player
    }

    private fun redraw() {
        val cell = cell ?: return
        if (columns <= 0 || lines <= 0) return
        val renderer = env.renderer() ?: run {
            art.text = null
            return
        }
        val detail = if (cell.detail > 0) cell.detail else artDetail()
        // Characters of art at 1/detail of the terminal size, so [detail] of them fill a cell each way.
        // Half blocks reach past a line's edges, so they're drawn a touch smaller, centred, to not be cut off.
        val fill = if (cell.fine) FINE_FILL else 1f
        art.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp / detail * fill)
        val size = if (cell.fill) filling() else columns * detail to lines * detail
        val wanted = Triple(cell.key, cell.frame, size)
        if (wanted == drawn || (wanted == drawing && render?.isActive == true)) return
        drawing = wanted
        render?.cancel()
        render = env.scope.launch {
            // Filling art is told the area's shape: characters are twice as tall as wide.
            val drawable = cell.drawable().let { if (cell.fill) Filling(it, size.first, size.second * 2) else it }
            val text = renderer.render(drawable, size.first, size.second, cell.fine)
            val player = player() ?: return@launch
            // The same picture at a new size or frame just shows; a new picture rolls over.
            val same = shownKey == cell.key
            shownKey = cell.key
            player.show(text, live = same)
            drawn = wanted
        }
    }
}

/**
 * Art to a screen reader: what the module says it shows, or nothing at all,
 * as decoration, rather than every character of it.
 */
private fun spoken(art: View, spoken: String?) {
    art.contentDescription = spoken
    art.importantForAccessibility =
        if (spoken == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/** Art that's already text, scaled to fit its area. */
internal class PictureCell(context: Context, private val env: GridEnv) : CellView {
    private val art = ArtView(context).apply {
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override val view: View get() = art
    private var cell: Cell.Picture? = null
    private var selected = false
    private var player: ArtPlayer? = null
    private var playerScope: CoroutineScope? = null

    override fun bind(cell: Cell) {
        val new = cell as Cell.Picture
        this.cell = new
        spoken(art, new.spoken)
        player().show(new.art, new.live)
    }

    override fun naturalColumns(room: Int, roomLines: Int?) = shapedColumns(cell?.size, room, roomLines)

    override fun naturalLines(columns: Int): Int = keepShape(cell?.size, columns)

    override fun place(columns: Int, lines: Int) {
        val shape = ArtShape.of(cell?.art) ?: return
        val res = art.resources
        val width = (columns * TtyFont.cellWidth(res)).toInt()
        val height = lines * TtyFont.lineHeight(res)
        val px = min(shape.sizeForHeight(height, res), shape.sizeForWidth(width, res))
        if (kotlin.math.abs(art.textSize - px) > SIZE_EPSILON) art.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }

    override fun select(on: Boolean) {
        selected = on
        player().select(on)
    }

    override fun align(x: Align, y: Align) {
        art.gravity = gravity(x, y)
    }

    private fun player(): ArtPlayer {
        val scope = env.scope
        val current = player
        if (current != null && playerScope === scope) return current
        return ArtPlayer(art, scope, env.animator).also {
            it.select(selected)
            player = it
            playerScope = scope
        }
    }

    private companion object {
        const val SIZE_EPSILON = 0.25f
    }
}

/**
 * A bar chart drawn with block characters for exactly its area: the values
 * resampled to one bar a column, eighth blocks for height across its lines,
 * each bar coloured by its value along the chart's colours, and scale labels
 * down the left edge when there's room for them.
 */
internal class ChartCell(context: Context) : CellView {
    private val text = TextView(context).apply {
        includeFontPadding = false
        isSingleLine = false
        setTextColor(context.getColor(CoreR.color.tty_fg))
    }
    override val view: View get() = text
    private val dim = context.getColor(CoreR.color.tty_dim)
    private var chart: Cell.Chart? = null
    private var columns = 0
    private var lines = 0

    override fun bind(cell: Cell) {
        chart = cell as Cell.Chart
        text.contentDescription = Speech.chart(cell.values)
        redraw()
    }

    override fun naturalColumns(room: Int, roomLines: Int?) = chart?.values?.size ?: 0

    override fun naturalLines(columns: Int) = chart?.let { it.lines + if (it.axis.isEmpty()) 0 else 1 } ?: 0

    override fun place(columns: Int, lines: Int) {
        if (columns == this.columns && lines == this.lines) return
        this.columns = columns
        this.lines = lines
        redraw()
    }

    private fun redraw() {
        val chart = chart ?: return
        if (columns <= 0 || lines <= 0 || chart.values.isEmpty()) {
            text.text = null
            return
        }
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        val labels = chart.labels
        val labelWidth = labels?.let { max(it.first.length, it.second.length) + 1 } ?: 0
        // Labels only when the bars keep most of the width.
        val showLabels = labels != null && columns - labelWidth >= MIN_BARS
        val bars = resample(chart.values, columns - if (showLabels) labelWidth else 0)
        val low = chart.low ?: chart.values.min()
        val high = (chart.high ?: chart.values.max()).let { if (it - low < EPSILON) low + 1 else it }
        // The axis takes the last line, when there's one to spare.
        val withAxis = chart.axis.isNotEmpty() && this.lines > 1
        val lines = if (withAxis) this.lines - 1 else this.lines
        val levels = lines * EIGHTHS
        val heights = bars.map { value -> (((value - low) / (high - low)).coerceIn(0.0, 1.0) * (levels - 1)).toInt() + 1 }
        val out = SpannableStringBuilder()
        for (row in 0 until lines) {
            if (row > 0) out.append('\n')
            if (showLabels) {
                val label = when (row) {
                    0 -> labels!!.first
                    lines - 1 -> labels!!.second
                    else -> ""
                }
                out.append(label.padStart(labelWidth - 1) + " ", ForegroundColorSpan(dim), SPAN)
            }
            // Eighths still to fill above this line's bottom, counted from the top line down.
            val below = (lines - 1 - row) * EIGHTHS
            bars.forEachIndexed { i, value ->
                val filled = heights[i] - below
                val char = when {
                    filled >= EIGHTHS -> '█'
                    filled <= 0 -> ' '
                    else -> BLOCKS[filled]
                }
                val start = out.length
                out.append(char)
                if (char != ' ' && chart.colors.isNotEmpty()) {
                    out.setSpan(ForegroundColorSpan(gradient(chart.colors, (value - low) / (high - low))), start, start + 1, SPAN)
                }
            }
        }
        if (withAxis) {
            out.append('\n')
            out.append(" ".repeat(if (showLabels) labelWidth else 0) + axis(chart.axis, chart.values.size, bars.size), ForegroundColorSpan(dim), SPAN)
        }
        text.text = out
    }

    /** The [labels] each under the first bar of its value, skipping any that would touch the one before. */
    private fun axis(labels: List<String>, values: Int, bars: Int): String {
        val line = CharArray(bars) { ' ' }
        var free = 0
        labels.forEachIndexed { i, label ->
            if (label.isEmpty() || i >= values) return@forEachIndexed
            // resample() gives bar j the values from j * values / bars: this value's first bar.
            val at = (i * bars + values - 1) / values
            if (at < free || at + label.length > bars) return@forEachIndexed
            label.forEachIndexed { k, c -> line[at + k] = c }
            free = at + label.length + 1
        }
        return String(line)
    }

    /** [values] as [count] values: averaged into buckets when there are more, repeated when fewer. */
    private fun resample(values: List<Double>, count: Int): List<Double> {
        if (count <= 0) return emptyList()
        return List(count) { i ->
            val from = i * values.size / count
            val to = max(from + 1, (i + 1) * values.size / count).coerceAtMost(values.size)
            values.subList(from, to).average()
        }
    }

    /** The colour [t] (0 to 1) of the way along [colors]. */
    private fun gradient(colors: List<Int>, t: Double): Int {
        if (colors.size == 1) return colors[0]
        val position = t.coerceIn(0.0, 1.0) * (colors.size - 1)
        val i = position.toInt().coerceAtMost(colors.size - 2)
        val f = (position - i).toFloat()
        val a = colors[i]
        val b = colors[i + 1]
        fun mix(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt()
        return android.graphics.Color.rgb(mix(16), mix(8), mix(0))
    }

    private companion object {
        const val EIGHTHS = 8
        const val BLOCKS = " ▁▂▃▄▅▆▇"
        const val MIN_BARS = 8
        const val EPSILON = 1e-9
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}

/**
 * A button around its face: the home cursor can land on it (scrolling steps
 * through a tile's controls), enter or a tap runs its action, and while
 * selected it's marked with the frame's corners.
 */
internal class ControlCell(context: Context, private val env: GridEnv) : CellView {
    val control = ControlView(context)
    override val view: View get() = control
    private var face: CellView? = null
    private var cell: Cell.Control? = null

    override fun bind(cell: Cell) {
        val new = cell as Cell.Control
        val old = this.cell
        this.cell = new
        control.key = new.key
        control.contentDescription = new.label
        control.setOnClickListener { env.host?.let { host -> new.action.run(host) } }
        // Holding enter (or a long touch) shows its menu, if it has one.
        val menu = new.menu
        if (menu.isEmpty() || env.host == null) {
            control.setOnLongClickListener(null)
            control.isLongClickable = false
        } else {
            control.setOnLongClickListener {
                env.host?.showMenu(new.label.substringBefore(':'), menu)
                true
            }
        }
        // A face of the same kind is updated in place (its art keeps animating); another is rebuilt.
        val current = face
        if (current != null && old != null && sameKind(old.face, new.face)) {
            current.bind(new.face)
        } else {
            control.removeAllViews()
            face = cellViewFor(control.context, env, new.face).also { control.addView(it.view) }
        }
    }

    override fun naturalColumns(room: Int, roomLines: Int?) = face?.naturalColumns(room, roomLines) ?: 0

    override fun naturalLines(columns: Int) = face?.naturalLines(columns) ?: 0

    override fun place(columns: Int, lines: Int) {
        face?.place(columns, lines)
    }

    override fun select(on: Boolean) {
        face?.select(on)
    }

    override fun align(x: Align, y: Align) {
        face?.align(x, y)
    }
}

/** A control's view: focusable and clickable, its face filling it, corners drawn over it while focused. */
internal class ControlView(context: Context) : android.widget.FrameLayout(context) {
    /** The control's key, so the cursor can come back to it. */
    var key: String = ""

    private val corners = TtyFont.paint(resources)

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (isFocused) {
            corners.color = com.newhost.qwertylauncher.ui.FrameLook.selected
            com.newhost.qwertylauncher.ui.SelectionCorners.draw(canvas, width, height, corners)
        }
    }
}

/** A grid inside a grid. */
internal class NestedCell(context: Context, env: GridEnv) : CellView {
    private val grid = CellGridView(context, env)
    override val view: View get() = grid

    private var layout: Responsive? = null

    override fun bind(cell: Cell) {
        layout = (cell as Cell.Nested).layout
        grid.setLayout(cell.layout)
    }

    override fun naturalColumns(room: Int, roomLines: Int?) = layout?.let { naturalColumns(it, roomLines) } ?: 0

    override fun naturalLines(columns: Int): Int = layout?.let { naturalLines(it, columns, grid.resources) } ?: 0

    override fun select(on: Boolean) = grid.select(on)

    fun controls(): List<ControlView> = grid.controls()
}

/** Cells side by side in as many columns as fit; see [Cell.Flow]. */
internal class FlowCell(context: Context, env: GridEnv) : CellView {
    private val flow = FlowView(context, env)
    override val view: View get() = flow

    override fun bind(cell: Cell) = flow.bind(cell as Cell.Flow)

    override fun naturalColumns(room: Int, roomLines: Int?) = flow.minColumns()

    override fun naturalLines(columns: Int) = flow.plan(columns, null)

    override fun select(on: Boolean) = flow.select(on)

    fun controls(): List<ControlView> = flow.controls()
}

/**
 * Lays out a [Cell.Flow]. As a grid: columns of at least its `minColumns`
 * sharing the width, filled column after column like `ls`. Packed: each item
 * only as wide as it needs, row after row, wrapping at the edge. Either way,
 * as many rows as its `rows` without a set height, else as many as fit, and
 * items as tall as the room allows up to its `maxItemLines`.
 */
internal class FlowView(context: Context, private val env: GridEnv) : ViewGroup(context) {
    private var flow: Cell.Flow? = null
    private val cells = mutableListOf<CellView>()

    /** Where each shown item goes, in cells: its column, its row (of items), and its width. */
    private class Spot(val x: Int, val row: Int, val width: Int)

    private var spots: List<Spot> = emptyList()
    private var itemLines = 1

    fun minColumns() = flow?.minColumns ?: 0

    fun bind(new: Cell.Flow) {
        val reuse = new.cells.size == cells.size && new.cells.indices.all { sameKind(new.cells[it], flowCell(it)) }
        flow = new
        if (reuse) {
            new.cells.forEachIndexed { i, cell -> cells[i].bind(cell) }
        } else {
            removeAllViews()
            cells.clear()
            new.cells.forEach { cell ->
                val view = cellViewFor(context, env, cell)
                cells += view
                addView(view.view)
            }
        }
        requestLayout()
    }

    private fun flowCell(i: Int): Cell = flow!!.cells[i]

    fun select(on: Boolean) = cells.forEach { it.select(on) }

    /** The controls among the items that show, in order. */
    fun controls(): List<ControlView> = cells.filterIndexed { i, _ -> i < spots.size }.flatMap { controlsOf(it) }

    /** Works out where items go in [areaColumns] (and at most [maxLines]); returns the lines it takes. */
    fun plan(areaColumns: Int, maxLines: Int?): Int {
        val flow = flow ?: return 0
        if (cells.isEmpty() || areaColumns <= 0) {
            spots = emptyList()
            return 0
        }
        if (flow.pack) packed(flow, areaColumns, maxLines) else gridded(flow, areaColumns, maxLines)
        val rows = (spots.maxOfOrNull { it.row } ?: -1) + 1
        return rows * itemLines
    }

    /** Equal columns, filled column after column. */
    private fun gridded(flow: Cell.Flow, areaColumns: Int, maxLines: Int?) {
        val columns = ((areaColumns + flow.gap) / (flow.minColumns + flow.gap)).coerceAtLeast(1)
        val columnCells = ((areaColumns - flow.gap * (columns - 1)) / columns).coerceAtLeast(1)
        val natural = cells.maxOf { it.naturalLines(columnCells) }.coerceAtLeast(1)
        val tallest = max(natural, flow.maxItemLines)
        val rowsNeeded = (cells.size + columns - 1) / columns
        // Items grow with room to spare: height left over once every item has a row, else wide columns.
        itemLines = if (maxLines != null) {
            (maxLines / rowsNeeded.coerceAtLeast(1)).coerceIn(natural, tallest)
        } else {
            (columnCells / flow.minColumns.coerceAtLeast(1)).coerceIn(natural, tallest)
        }
        val maxRows = if (maxLines != null) maxLines / itemLines else flow.rows
        val shown = min(cells.size, maxRows.coerceAtLeast(0) * columns)
        val rows = if (shown == 0) 1 else (shown + columns - 1) / columns
        spots = (0 until shown).map { i -> Spot((i / rows) * (columnCells + flow.gap), i % rows, columnCells) }
    }

    /**
     * Items at their own width, wrapping at the edge. Tries every item height
     * from one line up and keeps the one showing the most items, the tallest
     * of those: squeezed first, bigger only when that costs nothing. Without a
     * set height it stays at one line, the most compact.
     */
    private fun packed(flow: Cell.Flow, areaColumns: Int, maxLines: Int?) {
        val natural = 1
        val tallest = if (maxLines == null) natural else max(natural, flow.maxItemLines)
        var best: Pair<Int, List<Spot>>? = null
        for (lines in natural..tallest) {
            val maxRows = if (maxLines != null) maxLines / lines else flow.rows
            if (maxRows <= 0) break
            val spots = pack(flow, areaColumns, maxRows, lines)
            if (best == null || spots.size >= best.second.size) best = lines to spots
        }
        itemLines = best?.first ?: natural
        spots = best?.second.orEmpty()
    }

    /** Items in order, each as wide as it needs at [lines] tall, into at most [maxRows] rows. */
    private fun pack(flow: Cell.Flow, areaColumns: Int, maxRows: Int, lines: Int): List<Spot> {
        val out = mutableListOf<Spot>()
        var row = 0
        var x = 0
        for (cell in cells) {
            val width = cell.naturalColumns(areaColumns, lines).coerceIn(1, areaColumns)
            if (x > 0 && x + width > areaColumns) {
                row++
                x = 0
            }
            if (row >= maxRows) break
            out += Spot(x, row, width)
            x += width + flow.gap
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val line = TtyFont.lineHeight(resources)
        val cell = TtyFont.cellWidth(resources)
        val lines = plan((width / cell).toInt(), if (bounded) MeasureSpec.getSize(heightMeasureSpec) / line else null)
        cells.forEachIndexed { i, view ->
            val spot = spots.getOrNull(i)
            view.view.visibility = if (spot != null) VISIBLE else GONE
            if (spot != null) {
                view.place(spot.width, itemLines)
                view.view.measure(
                    MeasureSpec.makeMeasureSpec(ceil(spot.width * cell).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(itemLines * line, MeasureSpec.EXACTLY),
                )
            }
        }
        setMeasuredDimension(width, lines * line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val line = TtyFont.lineHeight(resources)
        val cell = TtyFont.cellWidth(resources)
        spots.forEachIndexed { i, spot ->
            val child = cells[i].view
            val x = (spot.x * cell).toInt()
            val y = spot.row * itemLines * line
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

/** [inner], reporting the shape of the area it fills ([width] by [height]), so a renderer fits it to all of it. */
private class Filling(private val inner: android.graphics.drawable.Drawable, private val width: Int, private val height: Int) :
    android.graphics.drawable.Drawable() {
    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height

    override fun draw(canvas: android.graphics.Canvas) {
        inner.bounds = bounds
        inner.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
