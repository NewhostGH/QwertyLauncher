package com.newhost.qwertylauncher.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * A paged grid of ASCII app icons.
 *
 * Touch: swipe sideways to change page. Keyboard / trackpad: arrow keys move
 * focus and flip the page at the edges; scrolling moves the selection (via
 * [step]); Enter opens; Menu opens the context menu.
 */
@SuppressLint("ViewConstructor")
internal class AppGridView(
    context: Context,
    private val columns: Int,
    private val rows: Int,
    /** How many terminal lines each icon's box is tall. */
    private val iconLines: Int,
    /** Whether each app's name shows under its icon: not when the rows are too tight for it. */
    private val labels: Boolean,
    /** The art's text size: the icon is drawn in as many of these characters as fill its box. */
    private val artSp: Float,
    /** The terminal font size: labels and the pager use it, only the icons differ. */
    private val textSp: Float,
    private val scope: CoroutineScope,
    /** What to say while there are no apps to show. */
    private val emptyText: String = "",
) : LinearLayout(context) {

    var onOpen: (AppEntry) -> Unit = {}

    /** Hears which apps the page shown has, whenever it changes; none once the grid is off screen. */
    var onPage: (List<AppEntry>) -> Unit = {}

    override fun onDetachedFromWindow() {
        onPage(emptyList())
        super.onDetachedFromWindow()
    }
    var onMenu: (AppEntry) -> Unit = {}
    /** An app's icon drawn in [cols] by [rows] characters: exactly its box. */
    var iconLoader: suspend (app: AppEntry, cols: Int, rows: Int) -> CharSequence = { _, _, _ -> "" }

    /** Draws icons in as a page shows and flickers the selected one; null for still icons. */
    var animator: ArtAnimator? = null
    private var shimmerJob: Job? = null

    private val pageSize = columns * rows
    private val fg = context.getColor(CoreR.color.tty_fg)
    private val dim = context.getColor(CoreR.color.tty_dim)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = dp(48)
    private val flickSpeed = ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLICK_FACTOR

    private val grid = GridLayout(context).apply { columnCount = columns }
    // Text views take the terminal font from the theme; only sizes are set here.
    private val footer = TextView(context).apply {
        setTextColor(dim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        includeFontPadding = false
        gravity = Gravity.CENTER_HORIZONTAL
        // Tapping the pager is the accessible alternative to swiping.
        setOnClickListener { showPage(if (page + 1 < pageCount) page + 1 else 0) }
    }

    private var apps: List<AppEntry> = emptyList()
    private var page = 0
    private var iconJob: Job? = null

    private val pageCount: Int get() = max(1, (apps.size + pageSize - 1) / pageSize)

    init {
        orientation = VERTICAL
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** An app to select once it's in [apps]: set by [select] before the list has loaded. */
    private var pendingSelection: String? = null

    fun setApps(newApps: List<AppEntry>) {
        val keep = selectedKey() // rebuilding the page loses focus; put it back
        apps = newApps
        showPage(page.coerceAtMost(pageCount - 1), force = true)
        (pendingSelection ?: keep)?.let { if (select(it)) pendingSelection = null }
    }

    /** Key of the selected app, or null. */
    fun selectedKey(): String? = selectedIndex().takeIf { it >= 0 }?.let { apps[it].key }

    /** Selects the app with [key], turning to its page. If the apps aren't loaded yet, does it when they are. */
    fun select(key: String): Boolean {
        val index = apps.indexOfFirst { it.key == key }
        if (index < 0) {
            if (apps.isEmpty()) pendingSelection = key
            return false
        }
        if (index / pageSize != page) showPage(index / pageSize)
        return grid.getChildAt(index - page * pageSize)?.requestFocusFromTouch() == true
    }

    private fun showPage(target: Int, focusIndex: Int = -1, force: Boolean = false) {
        val newPage = target.coerceIn(0, pageCount - 1)
        if (newPage == page && !force) return
        page = newPage

        iconJob?.cancel()
        grid.removeAllViews()
        val slice = apps.drop(page * pageSize).take(pageSize)
        onPage(slice)
        val iconViews = slice.mapIndexed { index, app -> addCell(app, index) }
        // Pad the page with empty cells so columns keep their width on a partial page.
        if (slice.isNotEmpty()) for (index in slice.size until pageSize) grid.addView(View(context), cellParams(index))

        footer.text = when {
            apps.isEmpty() -> emptyText
            pageCount > 1 -> "page ${page + 1} of $pageCount"
            else -> ""
        }
        // No line for nothing to say: the grid has all its height.
        footer.visibility = if (footer.text.isEmpty()) View.GONE else View.VISIBLE

        shimmerJob?.cancel()
        iconJob = scope.launch {
            slice.forEachIndexed { i, app ->
                val view = iconViews[i]
                // Drawn to its box, once the box is laid out and its size is known.
                val (cols, rows) = artSize(view)
                val art = iconLoader(app, cols, rows)
                view.tag = art
                val animator = animator
                if (animator == null) {
                    view.text = art
                } else {
                    launch {
                        animator.drawIn(art).collect { view.text = it }
                        if ((view.parent as? View)?.isFocused == true) shimmer(view)
                    }
                }
            }
        }
        if (focusIndex >= 0 && slice.isNotEmpty()) grid.getChildAt(min(focusIndex, slice.lastIndex)).requestFocus()
    }

    private fun addCell(app: AppEntry, index: Int): TextView {
        val icon = TextView(context).apply {
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, artSp)
            includeFontPadding = false
            // Its art is made to fit: never wrapped.
            setHorizontallyScrolling(true)
            gravity = Gravity.CENTER
        }
        val label = TextView(context).apply {
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            text = app.label
        }
        val cell = SelectableCell(context, cornerPaint).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            // A column of space each side: room for the selection corners, and neighbouring labels don't run together.
            setPadding(cellWidth, 0, cellWidth, 0)
            // The icon's box: the cell's width (less its air), and whole lines tall, so the label stays on the grid.
            addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, iconLines * lineHeight))
            if (labels) addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setOnClickListener { onOpen(app) }
            setOnLongClickListener {
                onMenu(app)
                true
            }
            setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN && onCellKey(keyCode, index, app)
            }
            // The selected app's icon flickers; unselected, it's still again.
            setOnFocusChangeListener { _, focused ->
                if (focused) {
                    shimmer(icon)
                } else {
                    shimmerJob?.cancel()
                    (icon.tag as? CharSequence)?.let { icon.text = it }
                }
            }
        }
        grid.addView(cell, cellParams(index))
        return icon
    }

    private fun cellParams(index: Int) =
        GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f)).apply {
            width = 0
        }

    private fun onCellKey(keyCode: Int, index: Int, app: AppEntry): Boolean {
        val column = index % columns
        val lastOnPage = min(pageSize, apps.size - page * pageSize) - 1
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (column == 0 && page > 0) {
                showPage(page - 1, focusIndex = index + columns - 1)
                true
            } else {
                false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if ((column == columns - 1 || index == lastOnPage) && page < pageCount - 1) {
                showPage(page + 1, focusIndex = index - column)
                true
            } else {
                false
            }
            KeyEvent.KEYCODE_MENU -> {
                onMenu(app)
                true
            }
            else -> false
        }
    }

    /** The swipe so far, as the finger went: people don't swipe straight. */
    private val swipe = DragPath(context)

    // Steal sideways drags from the cells so a swipe doesn't also count as a tap: sideways
    // judged from the whole path (within a slant), so a swipe that dips still counts.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipe.start(ev)
            MotionEvent.ACTION_MOVE -> {
                swipe.add(ev)
                if (swipe.judge() == DragAxis.HORIZONTAL) {
                    // Keep the home screen's vertical scroll from stealing a sideways swipe.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility") // swiping has a keyboard and footer-tap equivalent
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipe.start(ev)
            MotionEvent.ACTION_MOVE -> swipe.add(ev)
            MotionEvent.ACTION_UP -> {
                swipe.add(ev)
                val dx = swipe.dx
                // Far enough, or a quick flick that's clearly sideways.
                val flick = abs(swipe.velocity().first) > flickSpeed && abs(dx) > touchSlop
                if (swipe.axis == DragAxis.HORIZONTAL && (abs(dx) > swipeDistance || flick)) {
                    showPage(if (dx < 0) page + 1 else page - 1)
                }
            }
        }
        return true
    }

    /**
     * Moves the selection like a cursor: [down] rows and [right] cells, in
     * reading order across pages. The first scroll selects the first app on
     * the page. Returns false at either end, so the shell can move on.
     */
    fun step(down: Int, right: Int): Boolean {
        if (apps.isEmpty()) return false
        val current = selectedIndex()
        var target = if (current < 0) page * pageSize else current + down * columns + right
        // Moving down into a shorter last row lands on its last app.
        if (current >= 0 && down > 0 && target > apps.lastIndex && current / columns < apps.lastIndex / columns) {
            target = apps.lastIndex
        }
        // Past either end: let the shell move on (e.g. up into the widgets).
        if (target !in apps.indices || target == current) return false
        if (target / pageSize != page) showPage(target / pageSize)
        // Scrolling doesn't leave touch mode by itself; this does, so the selection shows and Enter opens it.
        grid.getChildAt(target - page * pageSize)?.requestFocusFromTouch()
        return true
    }

    /** Selects the first app on the page, or the last if [fromEnd]: the cursor arriving from outside. */
    fun enter(fromEnd: Boolean): Boolean {
        if (apps.isEmpty()) return false
        val onPage = min(pageSize, apps.size - page * pageSize)
        val index = if (fromEnd) onPage - 1 else 0
        return grid.getChildAt(index)?.requestFocusFromTouch() == true
    }

    /** No flicker while home is out of sight; it picks up again on return. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            shimmerJob?.cancel()
        } else {
            val cell = grid.focusedChild as? LinearLayout ?: return
            (cell.getChildAt(0) as? TextView)?.let(::shimmer)
        }
    }

    private fun shimmer(icon: TextView) {
        shimmerJob?.cancel()
        val art = icon.tag as? CharSequence ?: return
        val animator = animator ?: return
        shimmerJob = scope.launch { animator.shimmer(art).collect { icon.text = it } }
    }

    /** Index into [apps] of the focused cell, or -1. */
    private fun selectedIndex(): Int {
        val child = grid.focusedChild ?: return -1
        val onPage = grid.indexOfChild(child)
        val cellsOnPage = min(pageSize, apps.size - page * pageSize)
        return if (onPage in 0 until cellsOnPage) page * pageSize + onPage else -1
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** One line and one column of terminal text, in pixels: the grid the grid sits on. */
    private val lineHeight: Int
    private val cellWidth: Int

    init {
        val probe = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp) }
        val metrics = probe.paint.fontMetricsInt
        lineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        cellWidth = probe.paint.measureText("M").roundToInt()
    }

    /** The selection corners' paint: the terminal font at the terminal size, in the accent colour. */
    private val cornerPaint: Paint by lazy {
        Paint(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp) }.paint).apply {
            color = context.getColor(CoreR.color.tty_accent)
        }
    }

    /**
     * A grid cell that, while selected, is marked by box-drawing corners drawn
     * as text in its four corners, not a tinted background.
     */
    private class SelectableCell(context: Context, private val corners: Paint) : LinearLayout(context) {
        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!isFocused) return
            val cell = corners.measureText("┌")
            val metrics = corners.fontMetricsInt
            val top = -metrics.ascent.toFloat()
            val bottom = (height - metrics.descent).toFloat()
            canvas.drawText("┌", 0f, top, corners)
            canvas.drawText("┐", width - cell, top, corners)
            canvas.drawText("└", 0f, bottom, corners)
            canvas.drawText("┘", width - cell, bottom, corners)
        }
    }

    /**
     * How many art characters fill [view]'s box, across and down, once it's
     * laid out: measured at the art's own size, so the icon fits exactly.
     */
    private suspend fun artSize(view: TextView): Pair<Int, Int> {
        if (view.width == 0 || view.height == 0) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        if (v.width > 0 && v.height > 0) {
                            v.removeOnLayoutChangeListener(this)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                view.addOnLayoutChangeListener(listener)
                continuation.invokeOnCancellation { view.removeOnLayoutChangeListener(listener) }
            }
        }
        val charWidth = view.paint.measureText("M").coerceAtLeast(1f)
        val lineHeight = view.lineHeight.coerceAtLeast(1)
        val cols = ((view.width - view.paddingLeft - view.paddingRight) / charWidth).toInt().coerceAtLeast(2)
        val rows = ((view.height - view.paddingTop - view.paddingBottom) / lineHeight).coerceAtLeast(1)
        return cols to rows
    }

    private companion object {
        /** How much faster than Android's own fling speed a short swipe must be to turn the page. */
        const val FLICK_FACTOR = 4
    }
}
