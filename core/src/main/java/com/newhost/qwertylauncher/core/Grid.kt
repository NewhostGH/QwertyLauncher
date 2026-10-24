package com.newhost.qwertylauncher.core

import android.graphics.drawable.Drawable

/*
 * A grid layout for widget tiles and panes, like CSS grid but measured in
 * terminal cells (columns across, lines down), so everything stays on the
 * text grid. Widgets describe what goes where; the shell sizes it to the room
 * there is and draws it.
 *
 *     responsive {
 *         atLeast(columns = 34) {                 // wide: art beside the text
 *             columns(auto(), fr())
 *             rows(auto(), fr())
 *             art("sky", Size(12, 5), row = 0, column = 0, rowSpan = 2) { SkyDrawable() }
 *             text("12°C partly cloudy", row = 0, column = 1)
 *             flow(facts, minColumns = 16, row = 1, column = 1)
 *         }
 *         otherwise {                             // narrow: stacked
 *             columns(fr())
 *             rows(auto(), auto(), fr())
 *             ...
 *         }
 *     }
 */

/** The size of one column or row of a [Grid], in terminal cells. */
sealed interface Track {
    /** Exactly [count] cells. */
    data class Cells(val count: Int) : Track

    /** A share of the room the other tracks leave, like CSS `fr`, never under [min]. Without a set height, as tall as its content. */
    data class Fr(val weight: Float = 1f, val min: Int = 0) : Track

    /** As big as its content, like CSS `auto`, up to [max]. */
    data class Auto(val max: Int = Int.MAX_VALUE) : Track
}

fun cells(count: Int): Track = Track.Cells(count)

fun fr(weight: Float = 1f, min: Int = 0): Track = Track.Fr(weight, min)

fun auto(max: Int = Int.MAX_VALUE): Track = Track.Auto(max)

enum class Align { START, CENTER, END }

/** A size in terminal cells. */
data class Size(val columns: Int, val lines: Int)

/** What fills one area of a [Grid]. */
sealed interface Cell {
    /** Terminal text. It wraps at the area's edge, or with [wrap] off is cut there. */
    data class Text(val text: CharSequence, val wrap: Boolean = true) : Cell

    /**
     * A drawing rendered to ASCII art to fit its area exactly, so it looks
     * right at any size. [size] is what it would like, for `auto` tracks.
     * [key] names the picture: when it changes, the art rolls over to the new
     * one; [frame] counts frames of its own animation, which show as they come.
     * [fine] draws with half blocks, two coloured pixels a character, for
     * small icons; [detail] is how many characters of art go in a cell each
     * way, 0 for the shell's choice (small characters, as fine as app icons),
     * so a bigger area also means more detail. It keeps its shape: in an
     * `auto` column its width follows the room's height when that's set, and
     * is about a third of the width when it isn't; it's never under a cell.
     *
     * With [fill], it doesn't keep its shape: it takes all the room it's
     * given, and the drawable is drawn to that shape (its bounds tell it),
     * so it should lay itself out to any, like a scene that widens.
     */
    class Art(
        val key: String,
        val size: Size,
        val fine: Boolean = false,
        val detail: Int = 0,
        val frame: Int = 0,
        val fill: Boolean = false,
        val drawable: () -> Drawable,
    ) : Cell

    /**
     * Art already drawn as text (e.g. by an [IconRenderer]), scaled to fit its
     * area; [size] is what it would like, for `auto` tracks. [live] marks a
     * frame of its own animation.
     */
    data class Picture(val art: CharSequence, val size: Size, val live: Boolean = false) : Cell

    /** Another grid inside this area. */
    data class Nested(val layout: Responsive) : Cell

    /**
     * [cells] side by side in as many columns of at least [minColumns] as fit
     * (like CSS `repeat(auto-fill, minmax(n, 1fr))`), [rows] rows of them
     * without a set height, else as many rows as fit. The rest are left out,
     * so put the most important first. Items grow up to [maxItemLines] tall
     * when there's room to spare (height, or wide columns), so their art gets
     * bigger and more detailed, and shrink back when there isn't.
     *
     * With [pack], items take only the width they need instead of equal
     * columns, and wrap like CSS `flex-wrap`: the most items in the least
     * room. They only grow taller when that still shows as many.
     */
    data class Flow(
        val cells: List<Cell>,
        val minColumns: Int,
        val rows: Int = 3,
        val gap: Int = 2,
        val maxItemLines: Int = 1,
        val pack: Boolean = false,
    ) : Cell

    /**
     * A bar chart of [values], drawn with block characters to fit its area:
     * the values are resampled to one bar a column, and eighth blocks
     * (`▁▂▃▄▅▆▇█`) give the bars their height across [lines] lines, so a
     * bigger area shows more. [low] and [high] fix the scale (else it's the
     * values' own); [colors] run from low to high values (else terminal
     * colour); [labels] (top, bottom) go down the left edge when it's wide enough.
     */
    /**
     * A button: [face] (usually small [Art]) that the home cursor can land on.
     * Scrolling steps through a tile's controls like favourites, and enter or
     * a tap runs [action]. [key] names it, so the cursor stays on it across
     * updates; [label] says what it does, for accessibility. Holding enter on
     * it (or a long touch) shows [menu], when it has one.
     */
    class Control(
        val key: String,
        val label: String,
        val face: Cell,
        val action: ResultAction,
        val menu: List<MenuItem> = emptyList(),
    ) : Cell

    data class Chart(
        val values: List<Double>,
        val lines: Int = 3,
        val low: Double? = null,
        val high: Double? = null,
        val colors: List<Int> = emptyList(),
        val labels: Pair<String, String>? = null,
        /**
         * A label under some values, like the hour (`now`, `15`, `18`), on
         * an extra line below the bars; "" for none. Labels that would
         * touch the one before are left out.
         */
        val axis: List<String> = emptyList(),
    ) : Cell
}

/** A [cell] placed on a [Grid]: from ([row], [column]), spanning [rowSpan] rows and [columnSpan] columns. */
class Item(
    val cell: Cell,
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
    val alignX: Align = Align.START,
    val alignY: Align = Align.START,
)

/** Tracks and the items on them. Items beyond the tracks get `auto` ones. */
class Grid(
    val columns: List<Track>,
    val rows: List<Track>,
    val items: List<Item>,
    val columnGap: Int = 1,
    val rowGap: Int = 0,
)

/**
 * A grid for each amount of room, like CSS media queries: the first variant
 * whose minimums the room meets is drawn; the last is the fallback.
 */
class Responsive(val variants: List<Variant>) {
    class Variant(val minColumns: Int, val minLines: Int, val grid: Grid)

    constructor(grid: Grid) : this(listOf(Variant(0, 0, grid)))
}

fun responsive(block: ResponsiveBuilder.() -> Unit): Responsive = Responsive(ResponsiveBuilder().apply(block).variants)

fun grid(block: GridBuilder.() -> Unit): Responsive = Responsive(GridBuilder().apply(block).build())

class ResponsiveBuilder {
    internal val variants = mutableListOf<Responsive.Variant>()

    /** A grid for when there are at least [columns] and [lines]. Earlier ones win. */
    fun atLeast(columns: Int = 0, lines: Int = 0, block: GridBuilder.() -> Unit) {
        variants += Responsive.Variant(columns, lines, GridBuilder().apply(block).build())
    }

    /** The fallback, for any room. */
    fun otherwise(block: GridBuilder.() -> Unit) = atLeast(0, 0, block)
}

class GridBuilder {
    private var columns: List<Track> = listOf(Track.Fr())
    private var rows: List<Track> = emptyList()
    private var columnGap = 1
    private var rowGap = 0
    private val items = mutableListOf<Item>()

    fun columns(vararg tracks: Track) {
        columns = tracks.toList()
    }

    fun rows(vararg tracks: Track) {
        rows = tracks.toList()
    }

    fun gap(columns: Int = 1, lines: Int = 0) {
        columnGap = columns
        rowGap = lines
    }

    fun add(
        cell: Cell,
        row: Int = 0,
        column: Int = 0,
        rowSpan: Int = 1,
        columnSpan: Int = 1,
        alignX: Align = Align.START,
        alignY: Align = Align.START,
    ) {
        items += Item(cell, row, column, rowSpan, columnSpan, alignX, alignY)
    }

    fun text(text: CharSequence, row: Int = 0, column: Int = 0, rowSpan: Int = 1, columnSpan: Int = 1, wrap: Boolean = true) =
        add(Cell.Text(text, wrap), row, column, rowSpan, columnSpan)

    fun art(
        key: String,
        size: Size,
        row: Int = 0,
        column: Int = 0,
        rowSpan: Int = 1,
        columnSpan: Int = 1,
        fine: Boolean = false,
        frame: Int = 0,
        alignX: Align = Align.CENTER,
        alignY: Align = Align.CENTER,
        drawable: () -> Drawable,
    ) = add(Cell.Art(key, size, fine, frame = frame, drawable = drawable), row, column, rowSpan, columnSpan, alignX, alignY)

    fun picture(
        art: CharSequence,
        size: Size,
        row: Int = 0,
        column: Int = 0,
        rowSpan: Int = 1,
        columnSpan: Int = 1,
        live: Boolean = false,
    ) = add(Cell.Picture(art, size, live), row, column, rowSpan, columnSpan, Align.CENTER, Align.START)

    fun nested(layout: Responsive, row: Int = 0, column: Int = 0, rowSpan: Int = 1, columnSpan: Int = 1) =
        add(Cell.Nested(layout), row, column, rowSpan, columnSpan)

    fun flow(
        cells: List<Cell>,
        minColumns: Int,
        row: Int = 0,
        column: Int = 0,
        rows: Int = 3,
        maxItemLines: Int = 1,
        pack: Boolean = false,
        rowSpan: Int = 1,
        columnSpan: Int = 1,
    ) = add(Cell.Flow(cells, minColumns, rows, maxItemLines = maxItemLines, pack = pack), row, column, rowSpan, columnSpan)

    fun chart(
        values: List<Double>,
        row: Int = 0,
        column: Int = 0,
        lines: Int = 3,
        low: Double? = null,
        high: Double? = null,
        colors: List<Int> = emptyList(),
        labels: Pair<String, String>? = null,
        axis: List<String> = emptyList(),
        rowSpan: Int = 1,
        columnSpan: Int = 1,
    ) = add(Cell.Chart(values, lines, low, high, colors, labels, axis), row, column, rowSpan, columnSpan)

    internal fun build() = Grid(columns, rows, items.toList(), columnGap, rowGap)
}

/**
 * A small fact: an icon drawn [fine], and a short text beside it. The usual
 * thing to put in a [Cell.Flow]: one line tall at least, and as the flow gives
 * it more lines the icon grows with them (keeping its shape, gaining detail).
 */
fun fact(key: String, text: CharSequence, icon: (() -> Drawable)?): Cell =
    if (icon == null) {
        Cell.Text(text, wrap = false)
    } else {
        Cell.Nested(
            grid {
                columns(auto(), fr())
                rows(fr())
                art(key, Size(FACT_ICON_COLUMNS, 1), fine = true, alignX = Align.START, drawable = icon)
                add(Cell.Text(text, wrap = false), column = 1, alignY = Align.CENTER)
            },
        )
    }

private const val FACT_ICON_COLUMNS = 2

/** Something shown across the bottom of a big [tile]: a chart, a list, a calendar. [lines] is its least height. */
class Extra(val cell: Cell, val lines: Int, val weight: Float = 1f)

/**
 * The usual widget tile, fitted to any size. [picture] is never left out:
 *
 * - Wide: the picture beside the [headline] and [facts]. It sizes itself by
 *   its shape (see [Cell.Art]), and the facts fill the rest, squeezed in
 *   ([pack], else in columns of [factColumns]), bigger when there's room.
 * - Narrow and tall: the picture on top, then the headline and facts.
 * - Narrow and short: beside again, the picture small.
 * - Big ([bigColumns] wide and, at a set height, [bigLines] tall): as wide,
 *   with the [extras] across the bottom, sharing any spare height.
 *
 * [factRows] rows of facts show at an automatic height; at a set one, all
 * that fit. [badge] is terminal text laid over the picture's top right
 * corner, such as a count: sharp at any size, where text drawn in the art
 * would blur.
 */
fun tile(
    picture: Cell.Art,
    headline: CharSequence,
    facts: List<Cell> = emptyList(),
    factRows: Int = 3,
    factColumns: Int = FACT_COLUMNS,
    pack: Boolean = true,
    maxFactLines: Int = MAX_FACT_LINES,
    extras: List<Extra> = emptyList(),
    wide: Int = TILE_WIDE,
    tall: Int = TILE_TALL,
    bigColumns: Int = TILE_BIG_COLUMNS,
    bigLines: Int = TILE_BIG_LINES,
    badge: CharSequence? = null,
): Responsive {
    // With no headline, the facts move up and nothing is spent on an empty line.
    val hasHeadline = headline.isNotEmpty()
    val top = if (hasHeadline) 1 else 0
    fun GridBuilder.beside(extraRows: List<Extra>) {
        columns(auto(), fr())
        rows(*(List(top) { auto() } + fr() + extraRows.map { fr(it.weight, min = it.lines) }).toTypedArray())
        gap(columns = 2)
        add(picture, rowSpan = top + 1, alignX = Align.CENTER, alignY = Align.CENTER)
        if (badge != null) add(Cell.Text(badge, wrap = false), rowSpan = top + 1, alignX = Align.END, alignY = Align.START)
        if (hasHeadline) text(headline, column = 1)
        flow(facts, factColumns, row = top, column = 1, rows = factRows, maxItemLines = maxFactLines, pack = pack)
        extraRows.forEachIndexed { i, extra -> add(extra.cell, row = top + 1 + i, columnSpan = 2) }
    }
    return responsive {
        if (extras.isNotEmpty()) atLeast(columns = bigColumns, lines = bigLines) { beside(extras) }
        atLeast(columns = wide) { beside(emptyList()) }
        atLeast(lines = tall) {
            columns(fr())
            rows(*(listOf(auto()) + List(top) { auto() } + fr()).toTypedArray())
            add(picture, alignX = Align.CENTER, alignY = Align.CENTER)
            if (badge != null) add(Cell.Text(badge, wrap = false), alignX = Align.END, alignY = Align.START)
            if (hasHeadline) text(headline, row = 1)
            flow(facts, factColumns, row = 1 + top, rows = factRows, maxItemLines = maxFactLines, pack = pack)
        }
        otherwise { beside(emptyList()) }
    }
}

const val FACT_COLUMNS = 17
const val MAX_FACT_LINES = 3
const val TILE_WIDE = 26
const val TILE_TALL = 9
const val TILE_BIG_COLUMNS = 40
const val TILE_BIG_LINES = 12

/**
 * A progress bar as a fact: an optional [icon], a [label], and a bar drawn
 * as art ([BarDrawable], in half blocks) filling the rest of the width,
 * [fraction] full, coloured by how full it is.
 */
fun meter(
    key: String,
    label: CharSequence,
    fraction: Float,
    icon: (() -> Drawable)? = null,
    colors: List<Int> = listOf(IconColors.GREEN, IconColors.YELLOW, IconColors.RED),
): Cell {
    val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
    return Cell.Nested(
        grid {
            val parts = listOfNotNull(if (icon != null) auto() else null, if (label.isNotEmpty()) auto() else null, fr(min = METER_MIN_COLUMNS))
            columns(*parts.toTypedArray())
            rows(fr())
            var column = 0
            if (icon != null) art("$key:icon", Size(FACT_ICON_COLUMNS, 1), column = column++, fine = true, alignX = Align.START, drawable = icon)
            if (label.isNotEmpty()) add(Cell.Text(label, wrap = false), column = column++, alignY = Align.CENTER)
            // The bar changes picture with every percent, so it's redrawn as it moves.
            art("$key:bar:$percent", Size(METER_BAR_COLUMNS, 1), column = column, fine = true, alignX = Align.START) {
                BarDrawable(fraction, colors)
            }
        },
    )
}

private const val METER_MIN_COLUMNS = 6
private const val METER_BAR_COLUMNS = 10

/**
 * A button drawn as art: [icon] (fine half blocks) [columns] cells wide, one
 * line tall at least. Its picture is named by [label] too, so a button that
 * changes (play becoming pause) redraws while keeping its [key].
 */
fun control(
    key: String,
    label: String,
    icon: () -> Drawable,
    columns: Int = CONTROL_COLUMNS,
    menu: List<MenuItem> = emptyList(),
    action: ResultAction,
): Cell = Cell.Control(key, label, Cell.Art("$key:$label", Size(columns, 1), fine = true, drawable = icon), action, menu)

private const val CONTROL_COLUMNS = 3
