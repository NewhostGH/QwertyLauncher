package com.newhost.qwertylauncher.core

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tile of apps drawn as ASCII art icons with their names, in pages you
 * swipe or step through: the favourites grid, and any other set of apps
 * (suggestions). Only which apps, and what their menus add, differ.
 *
 * It fits itself to its tile, with nothing to set, at any height. The
 * height makes the rows and the icons' size: as few rows as keep each
 * within [MAX_ROW_LINES] lines (its icons, then their names), so up to 7
 * lines is one row, 8 to 14 two, and so on. Rows too tight for a name under
 * the icon (under [MIN_ROW_LINES], a line less for the pages' line) drop
 * the names and give the icons the room. With
 * the height left to it, rows of [DEFAULT_ROW_LINES], as many as the apps
 * need up to [maxRows]. The width is free: as many apps across as fit, each
 * icon square. With [onePage], just what fits, no more pages.
 *
 * [apps] are the apps to show, in order, from the apps module's catalog;
 * [menu] adds entries to an app's menu (after "open"); [emptyText] shows
 * while there are none. Icons come from the [AppCatalog], drawn by
 * whichever [IconRenderer] is installed.
 */
open class AppGridPanel(
    override val id: String,
    override val title: String,
    override val order: Int,
    private val services: Services,
    private val apps: (AppCatalog) -> Flow<List<AppEntry>>,
    private val menu: (AppEntry) -> List<MenuItem> = { emptyList() },
    private val emptyText: String = "",
    private val maxRows: Int = 2,
    private val onePage: Boolean = false,
    /** Hears which apps are on screen now (the page shown), and none once the grid's gone. */
    private val onShown: (List<AppEntry>) -> Unit = {},
    /** One app, its icon a few lines tall without its name, and the frame: the smallest it works at. */
    override val minColumns: Int = MIN_TILE_COLUMNS,
    override val minLines: Int = MIN_ROW_LINES - 1,
) : HomePanel {

    private val iconCache = LruCache<String, CharSequence>(ICON_CACHE_SIZE)

    /** The terminal's cell, in pixels, and the art's text size: known once the grid is shown. */
    @Volatile private var metrics: Metrics? = null

    private class Metrics(val column: Float, val line: Int, val artSp: Float, val artChar: Float)

    /** The installed apps; with the apps module off, none. */
    private val catalog: AppCatalog get() = services.find<AppCatalog>() ?: NoApps

    /**
     * How the grid fits its tile: columns, rows, each icon's box (in lines),
     * whether the names show under the icons, and the width it was fitted to.
     */
    private data class Fit(val columns: Int, val rows: Int, val iconLines: Int, val width: Int, val labels: Boolean = true)

    override fun createView(host: LauncherHost, parent: ViewGroup): View {
        val owner = host.lifecycleOwner
        val container = GridHost(parent.context)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Icon renderer settings, or the apps themselves, may have changed while we were away.
                iconCache.evictAll()
                var fit: Fit? = null
                var view: AppGridView? = null
                combine(container.space.filterNotNull(), apps(catalog)) { space, list -> space to list }.collect { (space, list) ->
                    val wanted = fit(container.context, host.textSizeSp, space, list.size)
                    // A new shape (or width: the icons are drawn to fit it) rebuilds the grid; the same one just shows the apps.
                    val grid = view.takeIf { wanted == fit } ?: buildGrid(host, container, wanted).also {
                        view = it
                        fit = wanted
                    }
                    grid.setApps(if (onePage) list.take(wanted.columns * wanted.rows) else list)
                }
            }
        }
        return container
    }

    private fun metrics(context: Context, textSp: Float): Metrics {
        val terminal = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp) }.paint
        // The art is as fine as elsewhere: its characters about [ART_SP] big.
        val artSp = textSp / ceil(textSp / ART_SP)
        val art = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, artSp) }.paint
        return Metrics(
            column = terminal.measureText("M").coerceAtLeast(1f),
            line = terminal.fontMetricsInt.let { it.descent - it.ascent }.coerceAtLeast(1),
            artSp = artSp,
            artChar = art.measureText("M").coerceAtLeast(0.1f),
        ).also { metrics = it }
    }

    /** How many apps fit across [width] pixels with icons [iconLines] lines tall: square, with a column of air each side. */
    private fun columnsFor(m: Metrics, width: Int, iconLines: Int): Int {
        val cell = iconLines * m.line + 2 * m.column
        return (width / cell).toInt().coerceIn(1, MAX_COLUMNS)
    }

    /**
     * The grid for [space] and [count] apps. The height sets the rows and
     * so the icons' size: as few rows as keep each within [MAX_ROW_LINES]
     * lines, an icon the row less its name's line (a line less again for
     * the pages, if there are more apps than fit). Then as many across as fit.
     */
    private fun fit(context: Context, textSp: Float, space: Space, count: Int): Fit {
        val m = metrics(context, textSp)
        val height = space.height
        if (height == null) {
            val icon = DEFAULT_ROW_LINES - 1
            val columns = columnsFor(m, space.width, icon)
            return Fit(columns, ceil(count / columns.toFloat()).toInt().coerceIn(1, maxRows), icon, space.width)
        }
        fun within(lines: Int): Fit {
            val rows = ceil(lines / MAX_ROW_LINES.toFloat()).toInt().coerceAtLeast(1)
            val perRow = lines / rows
            // Too tight for an icon and its name: the name goes, the icon keeps the room.
            val labels = perRow >= MIN_ROW_LINES
            val icon = (if (labels) perRow - 1 else perRow).coerceAtLeast(MIN_ICON_LINES)
            return Fit(columnsFor(m, space.width, icon), rows, icon, space.width, labels)
        }
        val lines = (height / m.line).coerceAtLeast(MIN_ROW_LINES)
        val all = within(lines)
        // More apps than fit: pages, and a line for saying which.
        return if (!onePage && count > all.columns * all.rows) within((lines - 1).coerceAtLeast(MIN_ROW_LINES)) else all
    }

    private fun buildGrid(host: LauncherHost, container: GridHost, fit: Fit): AppGridView {
        val view = AppGridView(
            context = container.context,
            columns = fit.columns,
            rows = fit.rows,
            iconLines = fit.iconLines,
            labels = fit.labels,
            artSp = metrics?.artSp ?: ART_SP,
            textSp = host.textSizeSp,
            scope = host.lifecycleOwner.lifecycleScope,
            emptyText = emptyText,
        )
        view.iconLoader = { app, cols, rows -> icon(app, cols, rows) }
        view.animator = services.find<ArtAnimator>()
        view.onOpen = { app ->
            host.clearQuery()
            catalog.launch(app, host)
        }
        view.onMenu = { app -> host.showMenu(app.label.lowercase(), catalog.menu(app, extra = menu(app))) }
        view.onPage = onShown
        container.show(view)
        return view
    }

    /** The room the grid has: its width, and its height when the tile's is set (null when it's up to the grid). */
    private data class Space(val width: Int, val height: Int?)

    /** Holds the current grid (rebuilt when it must change shape), passes shell scrolling on, and measures its room. */
    private class GridHost(context: Context) : FrameLayout(context), ScrollTarget {
        private var grid: AppGridView? = null

        val space = MutableStateFlow<Space?>(null)

        /**
         * A tile left to size itself is measured twice: first with no height
         * (how tall would you like to be?), then at its row's height. One
         * with a set height is only ever measured at it.
         */
        private var lastFree = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // The frame wraps the grid, so a set height arrives as "at most" it, not "exactly":
            // only no limit at all means the height is up to the grid.
            val free = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
            val auto = free || lastFree
            lastFree = free
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (width > 0) {
                val room = Space(width, if (auto) null else MeasureSpec.getSize(heightMeasureSpec))
                if (space.value != room) space.value = room
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        fun show(newGrid: AppGridView) {
            removeAllViews()
            addView(newGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            grid = newGrid
        }

        override fun onScroll(down: Int, right: Int) = grid?.step(down, right) ?: false

        override val selectionKey: String? get() = grid?.selectedKey()

        override fun restoreSelection(key: String) = grid?.select(key) ?: false

        override fun enter(fromEnd: Boolean) = grid?.enter(fromEnd) ?: false
    }

    /** [app]'s icon in [cols] by [rows] characters: its box, exactly. */
    private suspend fun icon(app: AppEntry, cols: Int, rows: Int): CharSequence {
        val key = "${app.key}:${cols}x$rows"
        iconCache.get(key)?.let { return it }
        val art = withContext(Dispatchers.Default) {
            val renderer = services.find<IconRenderer>()
            if (renderer != null) {
                // The same icons as everywhere, and the same placeholder when an app has none; every one
                // (adaptive ones too, in their mask's shape) trimmed to what it shows, so all come out the same size.
                val icon = catalog.loadIcon(app.packageName) ?: placeholderIcon(app.label)()
                renderer.render(if (icon is TrimmedDrawable) icon else TrimmedDrawable(icon), cols, rows)
            } else {
                glyphBox(app.label, cols, rows)
            }
        }
        iconCache.put(key, art)
        return art
    }

    /** Fallback icon when no renderer is installed: the app's initials in a box, [cols] by [rows]. */
    private fun glyphBox(label: String, cols: Int, rows: Int): String {
        val words = label.split(' ').filter { it.isNotEmpty() }
        val initials = (if (words.size > 1) words.take(2).joinToString("") { it.take(1) } else label.take(2))
            .ifEmpty { "?" }
        // Square on screen, in the middle of its box.
        val size = minOf(cols, rows * 2).coerceAtLeast(4)
        val tall = (size / 2).coerceAtLeast(3)
        val inner = size - 2
        val middle = (tall - 2) / 2
        return buildList {
            add("┌" + "─".repeat(inner) + "┐")
            repeat(tall - 2) { row ->
                val content = if (row == middle) initials else ""
                val left = (inner - content.length) / 2
                add("│" + " ".repeat(left) + content + " ".repeat(inner - left - content.length) + "│")
            }
            add("└" + "─".repeat(inner) + "┘")
        }.joinToString("\n")
    }

    companion object {
        /** How many lines a row of apps may have (its icons and their names); with the height left to it; rows at most. */
        const val MIN_ROW_LINES = 5
        const val MAX_ROW_LINES = 7
        const val DEFAULT_ROW_LINES = 6
        const val MAX_ROWS = 8

        /** Apps across at most, and the narrowest the tile can be. */
        const val MAX_COLUMNS = 12
        const val MIN_TILE_COLUMNS = 8

        /** The art's characters are about this big, in sp: as fine as art elsewhere. */
        private const val ART_SP = 2.4f

        /** An icon's box is at least this many lines tall. */
        private const val MIN_ICON_LINES = 2

        private const val ICON_CACHE_SIZE = 64
    }
}

/** Stands in for the apps module while it's off: no apps, so an empty grid. */
private object NoApps : AppCatalog {
    override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(emptyList())

    override fun find(key: String): AppEntry? = null

    override fun launch(app: AppEntry, host: LauncherHost) = Unit

    override fun loadIcon(app: AppEntry): Drawable? = null
}
