package com.newhost.qwertylauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.widget.FrameLayout
import com.newhost.qwertylauncher.core.Pose
import com.newhost.qwertylauncher.core.Stage
import com.newhost.qwertylauncher.core.StageTile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * Lays tiles out like tmux panes, sized in terminal cells: each tile is some
 * columns wide and some lines tall, from one cell up to the whole screen.
 * Tiles fill rows left to right while they fit, and a row's tiles share its
 * height, like panes side by side in tmux. A size of 0 means automatic: the
 * tile's default width (a wide tile takes the row, a narrow one half of it),
 * and the height its content needs.
 */
class TileLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /**
     * [columns] and [lines] are the user's size, 0 for automatic; [wide] is
     * the tile's default width, used while [columns] is automatic. The tile is
     * never smaller than [minColumns] by [minLines].
     */
    class LayoutParams(
        var columns: Int,
        var lines: Int,
        val wide: Boolean,
        val minColumns: Int = 1,
        val minLines: Int = 1,
    ) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Size of one character cell in pixels, at the terminal font size. */
    var cellWidth = 1f
        private set
    var lineHeight = 1
        private set

    /** How many columns fit across; the widest a tile can be. */
    var totalColumns = 1
        private set

    /** The screen's height in lines; the tallest a tile can be. */
    val maxLines: Int get() = max(1, resources.displayMetrics.heightPixels / lineHeight)

    /** Rows of visible children, worked out in [onMeasure] and used by [onLayout]. */
    private var rows: List<List<View>> = emptyList()

    /** A tile's id and title, for the [Stage]; null for a view that isn't a tile. */
    var tileOf: (View) -> Pair<String, String>? = { null }

    /** Hears the [Stage] whenever the tiles move, or scroll. */
    var onStage: (Stage) -> Unit = {}

    /** Hears a tap on a character, by its id. */
    var onPoke: (String) -> Unit = {}

    /** Hears a character dragged (its id, where its top left is now, in cells, and whether it was let go). */
    var onDrag: (String, Int, Int, Boolean) -> Unit = { _, _, _, _ -> }

    /** The characters' latest poses, by id, drawn over the tiles. */
    private val poses = linkedMapOf<String, Pose>()

    /** Where each character (and its speech) was last drawn, in pixels, for taps. */
    private val poseBounds = mutableMapOf<String, List<Rect>>()

    /** The character a touch started on, until it ends; where, and whether it's being dragged. */
    private var touching: String? = null
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private var dragFrom: Pose? = null
    private var heldAt: Pair<Int, Int>? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private var actorPaint = TtyFont.paint(resources)
    private val background = context.getColor(CoreR.color.tty_bg)
    private val foreground = context.getColor(CoreR.color.tty_fg)

    init {
        refreshCells()
    }

    /** Takes the cell size from the terminal font, after it changed. */
    fun refreshCells() {
        cellWidth = TtyFont.cellWidth(resources)
        lineHeight = TtyFont.lineHeight(resources)
        actorPaint = TtyFont.paint(resources)
        requestLayout()
    }

    /**
     * Whether a touch at [rawX], [rawY] (on the screen) lands on a
     * character, which takes it: a drag from there carries the character
     * rather than scrolling.
     */
    fun actorAt(rawX: Float, rawY: Float): Boolean {
        if (poses.isEmpty()) return false
        val origin = IntArray(2).also { getLocationOnScreen(it) }
        val x = (rawX - origin[0]).toInt()
        val y = (rawY - origin[1]).toInt()
        val grace = cellWidth.roundToInt().coerceAtLeast(lineHeight)
        return poseBounds.any { (id, rects) ->
            id in poses && rects.firstOrNull()?.let { Rect(it.left - grace, it.top - grace, it.right + grace, it.bottom + grace).contains(x, y) } == true
        }
    }

    /** Shows [pose] for the character [id], or takes it away when null. */
    fun setPose(id: String, pose: Pose?) {
        if (pose == null) poses.remove(id) else poses[id] = pose
        if (pose == null) poseBounds.remove(id)
        invalidate()
    }

    /** Tells [onStage] where the tiles are now, and which lines are in view. */
    fun updateStage() {
        val tiles = (0 until childCount).mapNotNull { i ->
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) return@mapNotNull null
            val (id, title) = tileOf(child) ?: return@mapNotNull null
            StageTile(
                id,
                title,
                column = ((child.left - paddingLeft) / cellWidth).roundToInt(),
                line = (child.top - paddingTop) / lineHeight,
                columns = (child.width / cellWidth).roundToInt(),
                lines = child.height / lineHeight,
            )
        }
        val scroller = parent as? View
        val top = ((scroller?.scrollY ?: 0) - paddingTop).coerceAtLeast(0) / lineHeight
        val visible = ((scroller?.height ?: height) / lineHeight).coerceAtLeast(1)
        onStage(Stage(totalColumns, (height - paddingTop - paddingBottom) / lineHeight, tiles, top, visible))
    }

    /** The size [child] has on screen now, in columns and lines. */
    fun cellsOf(child: View): Pair<Int, Int> =
        (child.width / cellWidth).roundToInt().coerceIn(1, totalColumns) to
            (child.height.toFloat() / lineHeight).roundToInt().coerceIn(1, maxLines)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        totalColumns = max(1, (inner / cellWidth).toInt())
        rows = packRows()
        var height = paddingTop + paddingBottom
        for (row in rows) {
            var rowHeight = 0
            for (child in row) {
                val params = child.layoutParams as LayoutParams
                val lines = if (params.lines > 0) params.lines.coerceIn(params.minLines, maxLines) else 0
                val heightSpec = if (lines > 0) exactly(lines * lineHeight) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                child.measure(exactly(pixelWidth(child, inner)), heightSpec)
                rowHeight = max(rowHeight, max(child.measuredHeight, params.minLines * lineHeight))
            }
            // Whole lines only, so the next row starts on the text grid.
            rowHeight = TtyFont.snap(rowHeight, lineHeight.toFloat())
            // Same height across the row, so frames line up like tmux panes.
            for (child in row) child.measure(exactly(pixelWidth(child, inner)), exactly(rowHeight))
            height += rowHeight
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        for (row in rows) {
            var x = paddingLeft
            var rowHeight = 0
            for (child in row) {
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth
                rowHeight = max(rowHeight, child.measuredHeight)
            }
            y += rowHeight
        }
        // After this layout pass, so the tiles' places are final.
        post { updateStage() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        for ((id, pose) in poses) {
            // Under the finger straight away while dragged, before the character catches up.
            val held = heldAt?.takeIf { touching == id && dragging }
            val shown = if (held != null) pose.copy(column = held.first, line = held.second, props = emptyList()) else pose
            poseBounds[id] = drawPose(canvas, shown)
        }
    }

    /**
     * [pose] on the text grid, over the tiles: each character of its art on
     * a cell of background (spaces stay see-through), then its speech in a
     * frame above it (below, when there's no room above), with a tail
     * pointing at it. Returns what it covers, in pixels.
     */
    private fun drawPose(canvas: Canvas, pose: Pose): List<Rect> {
        val paint = actorPaint
        val ascent = -paint.fontMetricsInt.ascent.toFloat()
        fun cell(column: Int, line: Int, char: Char, color: Int) {
            if (column !in 0 until totalColumns || line < 0) return
            val x = paddingLeft + column * cellWidth
            val y = (paddingTop + line * lineHeight).toFloat()
            paint.color = background
            canvas.drawRect(x, y, x + cellWidth, y + lineHeight, paint)
            paint.color = color
            canvas.drawText(char.toString(), x, y + ascent, paint)
        }
        fun rect(column: Int, line: Int, columns: Int, lines: Int) = Rect(
            (paddingLeft + column * cellWidth).toInt(),
            paddingTop + line * lineHeight,
            (paddingLeft + (column + columns) * cellWidth).toInt(),
            paddingTop + (line + lines) * lineHeight,
        )

        val color = pose.color ?: foreground
        val width = pose.width
        for (prop in pose.props) {
            prop.art.forEachIndexed { row, text ->
                text.forEachIndexed { col, char -> if (char != ' ') cell(prop.column + col, prop.line + row, char, prop.color) }
            }
        }
        pose.picture?.let { drawPicture(canvas, pose, it) }
        pose.art.forEachIndexed { row, text ->
            text.forEachIndexed { col, char ->
                if (char == ' ') return@forEachIndexed
                val key = pose.paint.getOrNull(row)?.getOrNull(col)
                cell(pose.column + col, pose.line + row, char, key?.let { pose.palette[it] } ?: color)
            }
        }
        // The art first: a touch on it drags; one on the speech pokes.
        val covered = mutableListOf(rect(pose.column, pose.line, width, pose.height))

        val says = pose.says ?: return covered
        val lines = wrap(says, (totalColumns - SPEECH_SIDES).coerceIn(1, SPEECH_MAX))
        val inner = lines.maxOf { it.length }
        val boxColumns = inner + SPEECH_SIDES
        val boxLines = lines.size + 2
        val center = pose.column + width / 2
        val left = (center - boxColumns / 2).coerceIn(0, (totalColumns - boxColumns).coerceAtLeast(0))
        // Above, with the tail between, when that stays in view; else below.
        val stage = (parent as? View)?.scrollY?.let { (it - paddingTop).coerceAtLeast(0) / lineHeight } ?: 0
        val above = pose.line - 1 - boxLines >= stage
        val top = if (above) pose.line - 1 - boxLines else pose.line + pose.height + 1
        val tailLine = if (above) pose.line - 1 else pose.line + pose.height
        val c = FrameLook.chars
        val frame = FrameLook.idle.takeIf { it != 0 } ?: foreground
        for (row in 0 until boxLines) {
            val text = when (row) {
                0 -> "${c.topLeft}${c.horizontal.toString().repeat(boxColumns - 2)}${c.topRight}"
                boxLines - 1 -> "${c.bottomLeft}${c.horizontal.toString().repeat(boxColumns - 2)}${c.bottomRight}"
                else -> "${c.vertical} ${lines[row - 1].padEnd(inner)} ${c.vertical}"
            }
            text.forEachIndexed { col, char ->
                val border = row == 0 || row == boxLines - 1 || col == 0 || col == boxColumns - 1
                cell(left + col, top + row, char, if (border) frame else foreground)
            }
        }
        val tail = when {
            center < left + boxColumns / 2 -> if (above) '/' else '\\'
            center > left + boxColumns / 2 -> if (above) '\\' else '/'
            else -> '|'
        }
        cell(center.coerceIn(left + 1, left + boxColumns - 2), tailLine, tail, frame)
        covered += rect(left, top, boxColumns, boxLines)
        return covered
    }

    /** A picture's characters and their colours, worked out once per picture. */
    private class Picture(val lines: List<String>, val colors: List<IntArray>)

    private val pictures = java.util.IdentityHashMap<CharSequence, Picture>()
    private val picturePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun pictureOf(text: CharSequence): Picture = pictures.getOrPut(text) {
        // A character's own colour, from its span; the terminal's without one.
        if (pictures.size > MAX_PICTURES) pictures.clear()
        val lines = text.toString().split('\n')
        val spanned = text as? android.text.Spanned
        var offset = 0
        val colors = lines.map { line ->
            val row = IntArray(line.length) { foreground }
            spanned?.getSpans(offset, offset + line.length, android.text.style.ForegroundColorSpan::class.java)?.forEach { span ->
                val from = (spanned.getSpanStart(span) - offset).coerceAtLeast(0)
                val to = (spanned.getSpanEnd(span) - offset).coerceAtMost(line.length)
                for (i in from until to) row[i] = span.foregroundColor
            }
            offset += line.length + 1
            row
        }
        Picture(lines, colors)
    }

    /**
     * [text] (ASCII art) shrunk into the pose's box of cells, like art
     * elsewhere: each character in its colour, on background, so what's
     * under it doesn't show through; blanks stay see-through.
     */
    private fun drawPicture(canvas: Canvas, pose: Pose, text: CharSequence) {
        val picture = pictureOf(text)
        val rows = picture.lines.size
        val cols = picture.lines.maxOfOrNull { it.length } ?: 0
        if (rows == 0 || cols == 0 || pose.columns <= 0 || pose.lines <= 0) return
        val left = paddingLeft + pose.column * cellWidth
        val top = (paddingTop + pose.line * lineHeight).toFloat()
        val charWidth = pose.columns * cellWidth / cols
        val charHeight = pose.lines * lineHeight.toFloat() / rows
        val paint = picturePaint
        paint.typeface = actorPaint.typeface
        paint.textSize = charHeight
        paint.textScaleX = 1f
        val natural = paint.measureText("M").takeIf { it > 0f } ?: return
        paint.textScaleX = charWidth / natural
        val ascent = -paint.fontMetrics.ascent
        val descent = paint.fontMetrics.descent
        // Squeezed to the line's height.
        val squeeze = charHeight / (ascent + descent)
        canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), 0f, paddingLeft + totalColumns * cellWidth, height.toFloat())
        picture.lines.forEachIndexed { row, line ->
            val y = top + row * charHeight
            val colors = picture.colors[row]
            // Runs of characters in one colour, drawn at once; blanks end a run.
            var start = 0
            while (start < line.length) {
                if (line[start] == ' ') {
                    start++
                    continue
                }
                var end = start + 1
                while (end < line.length && line[end] != ' ' && colors[end] == colors[start]) end++
                val x = left + start * charWidth
                paint.color = background
                canvas.drawRect(x, y, x + (end - start) * charWidth + 0.5f, y + charHeight + 0.5f, paint)
                paint.color = colors[start]
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(1f, squeeze)
                canvas.drawText(line, start, end, 0f, ascent, paint)
                canvas.restore()
                start = end
            }
        }
        canvas.restore()
    }

    /** [text] in lines of at most [width], broken between words. */
    private fun wrap(text: String, width: Int): List<String> = text.split('\n').flatMap { paragraph ->
        val out = mutableListOf<String>()
        var line = ""
        for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > width) {
                if (line.isNotEmpty()) out += line.also { line = "" }
                out += rest.take(width)
                rest = rest.drop(width)
            }
            line = when {
                line.isEmpty() -> rest
                line.length + 1 + rest.length <= width -> "$line $rest"
                else -> {
                    out += line
                    rest
                }
            }
        }
        if (line.isNotEmpty() || out.isEmpty()) out += line
        out
    }

    /**
     * A tap on a character pokes it, instead of reaching the tile under it;
     * a drag from it picks it up and carries it (the home screen doesn't
     * scroll meanwhile), and letting go drops it there.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            // A cell of grace around the character, so it's easy to catch.
            val grace = cellWidth.roundToInt().coerceAtLeast(lineHeight)
            fun Rect.near(x: Int, y: Int) = Rect(left - grace, top - grace, right + grace, bottom + grace).contains(x, y)
            touching = poseBounds.entries.firstOrNull { (id, rects) -> id in poses && rects.any { it.near(x, y) } }?.key
            touchX = event.x
            touchY = event.y
            dragging = false
            heldAt = null
            // Only the character itself can be picked up, not its speech.
            dragFrom = touching?.let { id -> poses[id]?.takeIf { poseBounds[id]?.firstOrNull()?.near(x, y) == true } }
            if (touching != null) parent?.requestDisallowInterceptTouchEvent(true)
        }
        val id = touching ?: return super.dispatchTouchEvent(event)
        val from = dragFrom
        val column = from?.let { it.column + ((event.x - touchX) / cellWidth).roundToInt() } ?: 0
        val line = from?.let { it.line + ((event.y - touchY) / lineHeight).roundToInt() } ?: 0
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val moved = kotlin.math.abs(event.x - touchX) > touchSlop || kotlin.math.abs(event.y - touchY) > touchSlop
                if (from != null && (dragging || moved)) {
                    dragging = true
                    if (heldAt != column to line) {
                        heldAt = column to line
                        invalidate()
                        onDrag(id, column, line, false)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = null
                if (dragging) onDrag(id, column, line, true) else onPoke(id)
                dragging = false
                heldAt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = null
                if (dragging) onDrag(id, column, line, true)
                dragging = false
                heldAt = null
            }
        }
        return true
    }

    /** Columns [child] takes: the user's width, or its default. */
    private fun columnsOf(child: View): Int {
        val params = child.layoutParams as LayoutParams
        val columns = when {
            params.columns > 0 -> params.columns
            params.wide -> totalColumns
            else -> totalColumns / 2
        }
        return columns.coerceIn(params.minColumns.coerceAtMost(totalColumns), totalColumns)
    }

    /** A full-width tile takes every pixel, so no sliver is left over at the edge. */
    private fun pixelWidth(child: View, inner: Int): Int {
        val columns = columnsOf(child)
        return if (columns >= totalColumns) inner else (columns * cellWidth).roundToInt()
    }

    private fun packRows(): List<List<View>> {
        val result = mutableListOf<List<View>>()
        var current = mutableListOf<View>()
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val columns = columnsOf(child)
            if (current.isNotEmpty() && used + columns > totalColumns) {
                result += current
                current = mutableListOf()
                used = 0
            }
            current += child
            used += columns
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        /** A speech box's frame and padding take this many columns; its words wrap at [SPEECH_MAX]. */
        const val SPEECH_SIDES = 4
        const val SPEECH_MAX = 30

        /** Pictures remembered at once: a character's poses, both ways round. */
        const val MAX_PICTURES = 256
    }

    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, wide = false)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?) = LayoutParams(0, 0, wide = false)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
}

/** The characters a frame is drawn with. */
class FrameChars(
    val horizontal: Char,
    val vertical: Char,
    val topLeft: Char,
    val topRight: Char,
    val bottomLeft: Char,
    val bottomRight: Char,
) {
    companion object {
        val SINGLE = FrameChars('─', '│', '┌', '┐', '└', '┘')
        val DOUBLE = FrameChars('═', '║', '╔', '╗', '╚', '╝')
        val HEAVY = FrameChars('━', '┃', '┏', '┓', '┗', '┛')
        val ROUNDED = FrameChars('─', '│', '╭', '╮', '╰', '╯')
        val ASCII = FrameChars('-', '|', '+', '+', '+', '+')
    }
}

/**
 * How frames look everywhere (tiles, panes, buttons, selection corners), from
 * the general settings: their characters, and their colours when idle and
 * when selected. Main thread only.
 */
object FrameLook {
    var chars: FrameChars = FrameChars.SINGLE
    var idle: Int = 0
    var selected: Int = 0

    /**
     * A box around [width] × [height] pixels in [paint]'s font, with [label]
     * set into its top line; the box's lines fall on the text grid.
     */
    fun drawBox(canvas: Canvas, width: Int, height: Int, paint: Paint, label: String = "") {
        val cell = paint.measureText("M")
        if (cell <= 0f) return
        val metrics = paint.fontMetricsInt
        val line = metrics.descent - metrics.ascent
        val ascent = -metrics.ascent.toFloat()
        val cols = (width / cell).toInt()
        if (cols < 2 || height < line * 2) return
        val c = chars
        val h = c.horizontal.toString()
        val right = (cols - 1) * cell
        val top = "${c.topLeft}$h${label.take((cols - 4).coerceAtLeast(0))}".padEnd(cols - 1, c.horizontal) + c.topRight
        canvas.drawText(top, 0f, ascent, paint)
        // The bottom line sits on the bottom edge; side bars fill the lines in between.
        val bottomTop = height - line
        var lineTop = line
        while (lineTop < bottomTop) {
            canvas.drawText(c.vertical.toString(), 0f, lineTop + ascent, paint)
            canvas.drawText(c.vertical.toString(), right, lineTop + ascent, paint)
            lineTop += line
        }
        canvas.drawText("${c.bottomLeft}${h.repeat(cols - 2)}${c.bottomRight}", 0f, bottomTop + ascent, paint)
    }
}

/**
 * A tile's frame, drawn with box-drawing characters in the terminal font.
 * [Style.COMPACT], the default, is just a title rule above the tile, like
 * tmux pane borders; [Style.FULL] boxes it in:
 * ```
 * ─ battery ─────────     ┌─ battery ──────┐
 * content                 │ content        │
 *                         └────────────────┘
 * ```
 * Everything is text, not lines or shapes. The rule is bright while the tile
 * is selected, and shows [status] while it's being moved or resized.
 */
class TileFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Style { COMPACT, FULL, NONE }

    var title: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var style = Style.COMPACT
        set(value) {
            field = value
            updatePadding()
            invalidate()
        }

    /** What the user is doing with the tile (hold enter, then move or resize), e.g. "moving"; null if nothing. */
    var status: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Drawn as selected whatever the focus: a pane that's open is the one in use. */
    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private var paint = TtyFont.paint(resources)
    private val accent = context.getColor(CoreR.color.tty_accent)
    private var charWidth = 0f
    private var lineHeight = 0

    init {
        setWillNotDraw(false)
        isFocusable = true
        refreshCells()
    }

    /** The frame is drawn in the terminal font, on its cells, so it matches the text grid. */
    fun refreshCells() {
        paint = TtyFont.paint(resources)
        charWidth = TtyFont.cellWidth(resources)
        lineHeight = TtyFont.lineHeight(resources)
        updatePadding()
    }

    private fun updatePadding() {
        when (style) {
            // Room for "│ " on each side and a line above and below.
            Style.FULL -> ceil(charWidth * 2).toInt().let { side -> setPadding(side, lineHeight, side, lineHeight) }
            // The title rule above, one character of air on the right so columns don't touch.
            Style.COMPACT -> setPadding(0, lineHeight, ceil(charWidth).toInt(), 0)
            Style.NONE -> setPadding(0, 0, ceil(charWidth).toInt(), 0)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    /**
     * Lit while anything inside has focus (an app in a grid), so redrawn
     * whenever focus comes or goes: the cursor can go from an app here
     * straight to one in another tile, which tells no frame on the way.
     */
    private val focusWatch = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
        if (old.isInside() || new.isInside()) invalidate()
    }

    private fun View?.isInside(): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === this@TileFrame) return true
            view = view.parent as? View
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (charWidth <= 0f) return
        if (style == Style.NONE) {
            // No frame to light up: a selected tile gets text corners instead.
            if (hasFocus() || status != null || active) {
                paint.color = if (status != null) accent else FrameLook.selected
                SelectionCorners.draw(canvas, width, height, paint)
            }
            return
        }
        val cols = (width / charWidth).toInt()
        if (cols < MIN_COLS) return

        val shown = status
        val selected = hasFocus() || shown != null || active
        paint.color = when {
            shown != null -> accent
            selected -> FrameLook.selected
            else -> FrameLook.idle
        }
        // Baseline of a line, from its top: the same integer metrics text views use.
        val ascent = -paint.fontMetricsInt.ascent.toFloat()
        val label = when {
            title.isEmpty() -> ""
            shown != null -> "[ $title: $shown ]"
            selected -> "[ $title ]"
            else -> " $title "
        }

        if (style == Style.COMPACT) {
            val h = FrameLook.chars.horizontal
            val rule = "$h${label.take(cols - 2)}".padEnd(cols - 1, h)
            canvas.drawText(rule, 0f, ascent, paint)
            return
        }
        FrameLook.drawBox(canvas, width, height, paint, label)
    }

    /** The title is drawn in the frame, out of a screen reader's sight: it says the tile's name instead. */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        AccessibilityNodeInfoCompat.wrap(info).roleDescription = if (title.isEmpty()) "tile" else "$title tile"
    }

    private companion object {
        const val MIN_COLS = 6
    }
}
