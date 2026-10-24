package com.newhost.qwertylauncher.feature.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.newhost.qwertylauncher.core.DragAxis
import com.newhost.qwertylauncher.core.DragPath
import com.newhost.qwertylauncher.core.IconColors
import kotlin.math.abs
import kotlin.math.min

/** The keys a game hears, whatever the keyboard (w a s d, the arrows, the keyboard's swipes). */
internal enum class Key { UP, DOWN, LEFT, RIGHT, ACTION, PAUSE, RESTART }

/**
 * The screen of a game: [cols] by [rows] character cells, each a character
 * in a colour on a background colour (0 for none). A game draws a frame
 * into it; [GameView] puts it on screen, as big as fits.
 */
internal class Cells(val cols: Int, val rows: Int) {
    val chars = CharArray(cols * rows) { ' ' }
    val fg = IntArray(cols * rows)
    val bg = IntArray(cols * rows)

    fun clear() {
        chars.fill(' ')
        fg.fill(0)
        bg.fill(0)
    }

    fun put(x: Int, y: Int, char: Char, color: Int, back: Int = 0) {
        if (x !in 0 until cols || y !in 0 until rows) return
        val i = y * cols + x
        chars[i] = char
        fg[i] = color
        bg[i] = back
    }

    fun text(x: Int, y: Int, text: String, color: Int, back: Int = 0) {
        text.forEachIndexed { i, c -> put(x + i, y, c, color, back) }
    }

    /** [text] in the middle of line [y]. */
    fun centre(y: Int, text: String, color: Int, back: Int = 0) = text((cols - text.length) / 2, y, text, color, back)

    /** A box of `+ - |` from ([x], [y]), [w] by [h] cells, its inside cleared. */
    fun box(x: Int, y: Int, w: Int, h: Int, color: Int, fill: Int = 0) {
        for (i in 0 until w) for (j in 0 until h) {
            val edgeX = i == 0 || i == w - 1
            val edgeY = j == 0 || j == h - 1
            val c = when {
                edgeX && edgeY -> '+'
                edgeY -> '-'
                edgeX -> '|'
                else -> ' '
            }
            put(x + i, y + j, c, color, if (edgeX || edgeY) 0 else fill)
        }
    }

    /** A message box in the middle, over whatever's there. */
    fun banner(lines: List<String>, color: Int) {
        val w = lines.maxOf { it.length } + 4
        val h = lines.size + 2
        val x = (cols - w) / 2
        val y = (rows - h) / 2
        box(x, y, w, h, color, fill = BANNER_BACK)
        lines.forEachIndexed { i, line -> text(x + (w - line.length) / 2, y + 1 + i, line, color, BANNER_BACK) }
    }

    private companion object {
        val BANNER_BACK = 0xFF15191D.toInt()
    }
}

/**
 * A game on the engine: it moves on with time ([update], a few
 * milliseconds at a time) and with keys ([press], [release]; which are
 * [held] is kept), and draws itself into [Cells] of its own size.
 */
internal abstract class Game {
    abstract val id: String
    abstract val title: String

    /** Which keys do what, in a line. */
    abstract val help: String
    abstract val cols: Int
    abstract val rows: Int
    abstract val score: Int
    abstract val over: Boolean

    var paused = false

    /** The keys down now. */
    val held = mutableSetOf<Key>()

    abstract fun reset()

    /** [ms] have passed (never while paused or over). */
    abstract fun update(ms: Long)

    abstract fun draw(cells: Cells, best: Int)

    /** A key went down (once, not its repeats). */
    open fun press(key: Key) {}

    open fun release(key: Key) {}

    /** The standard keys: pause and again; the rest to [press]. */
    fun keyDown(key: Key) {
        when (key) {
            Key.PAUSE -> if (!over) paused = !paused
            Key.RESTART -> {
                paused = false
                reset()
            }
            else -> {
                if (!held.add(key)) return
                if (over && key == Key.ACTION) reset() else if (!paused && !over) press(key)
            }
        }
    }

    fun keyUp(key: Key) {
        if (held.remove(key) && !paused && !over) release(key)
    }

    /** A key tapped: down and straight up (a swipe on the keyboard, or on the screen). */
    fun tap(key: Key) {
        keyDown(key)
        keyUp(key)
    }

    /** Whether a drag on the screen moves it step by step as it goes (tetris), rather than once when it ends. */
    open val continuous: Boolean = false

    /** A tap on the screen: goes on when paused, again when over; else [touched]. */
    fun tapped() {
        when {
            over -> tap(Key.ACTION)
            paused -> keyDown(Key.PAUSE)
            else -> touched()
        }
    }

    /** A tap on the screen while playing. */
    protected open fun touched() {}

    /** The usual paused and game over boxes, over the board. */
    protected fun overlays(cells: Cells, best: Int) {
        when {
            over -> cells.banner(
                listOf("game over", "score $score" + if (score >= best && score > 0) "  new best!" else "  best $best", "tap or enter: again"),
                IconColors.RED,
            )
            paused -> cells.banner(listOf("paused", "tap or p: go on"), IconColors.YELLOW)
        }
    }
}

/**
 * Draws a [Game] full screen, as big as its cells fit, keeping characters
 * twice as tall as wide; and runs it, a frame each screen refresh, while
 * it's on screen. Each run of one colour is drawn at once, its background
 * behind it.
 */
internal class GameView(context: Context, private val game: Game, private val best: () -> Int, private val onOver: () -> Unit) : View(context) {
    private val cells = Cells(game.cols, game.rows)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TextView(context).typeface }
    private val helpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = TextView(context).typeface
        color = HELP_COLOR
        textAlign = Paint.Align.CENTER
    }

    /** One cell on screen, in pixels, as last drawn: for how far a drag goes. */
    private var cellWidth = 1f
    private var cellHeight = 1f

    private val swipe = DragPath(context)
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val flick = android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLICK_FACTOR
    private var lastX = 0f
    private var lastY = 0f
    private var carryX = 0f
    private var carryY = 0f
    private val back = Paint()
    private var last = 0L
    private var running = false
    private var wasOver = game.over

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!running) return
            val now = nanos / 1_000_000
            val ms = if (last == 0L) 0L else (now - last).coerceAtMost(MAX_STEP_MS)
            last = now
            if (!game.paused && !game.over) game.update(ms)
            if (game.over && !wasOver) onOver()
            wasOver = game.over
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        // Away, it waits: keys let go, and paused.
        game.held.clear()
        if (!game.over) game.paused = true
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        cells.clear()
        game.draw(cells, best())
        // The keys, small and dim along the bottom, as big as fits the width (and no bigger than it should be).
        helpPaint.textSize = PROBE
        val helpScale = min(width * HELP_WIDTH / helpPaint.measureText(game.help).coerceAtLeast(1f), height * HELP_HEIGHT / PROBE)
        helpPaint.textSize = PROBE * helpScale
        val helpMetrics = helpPaint.fontMetrics
        val helpHeight = (helpMetrics.descent - helpMetrics.ascent) * HELP_GAP
        canvas.drawText(game.help, width / 2f, height - helpMetrics.descent, helpPaint)
        // The board as big as fits above it: a character's width and height at this size, scaled to the view.
        paint.textSize = PROBE
        val w = paint.measureText("M")
        val metrics = paint.fontMetrics
        val h = metrics.descent - metrics.ascent
        val room = height - helpHeight
        val scale = min(width / (cells.cols * w), room / (cells.rows * h))
        paint.textSize = PROBE * scale
        val cw = w * scale
        val lh = h * scale
        val ascent = -metrics.ascent * scale
        val left = (width - cw * cells.cols) / 2
        val top = (room - lh * cells.rows) / 2
        cellWidth = cw
        cellHeight = lh
        val line = StringBuilder()
        for (y in 0 until cells.rows) {
            var x = 0
            while (x < cells.cols) {
                val i = y * cells.cols + x
                val fg = cells.fg[i]
                val bg = cells.bg[i]
                var end = x + 1
                while (end < cells.cols && cells.fg[y * cells.cols + end] == fg && cells.bg[y * cells.cols + end] == bg) end++
                val px = left + x * cw
                val py = top + y * lh
                if (bg != 0) {
                    back.color = bg
                    canvas.drawRect(px, py, left + end * cw + 0.5f, py + lh + 0.5f, back)
                }
                line.setLength(0)
                for (k in x until end) line.append(cells.chars[y * cells.cols + k])
                if (line.isNotBlank()) {
                    paint.color = if (fg == 0) IconColors.WHITE else fg
                    canvas.drawText(line, 0, line.length, px, py + ascent, paint)
                }
                x = end
            }
        }
    }

    /**
     * Touch: a swipe is a tap of its arrow when it ends (read from the whole
     * path: people don't swipe straight); for a [Game.continuous] game, a
     * drag moves it a step for each cell dragged, and a quick flick down is
     * its action (tetris: drop). A tap is [Game.tapped].
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // every touch has a key that does the same
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipe.start(event)
                lastX = event.x
                lastY = event.y
                carryX = 0f
                carryY = 0f
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                swipe.add(event)
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (game.continuous && !game.paused && !game.over) {
                    when (swipe.judge()) {
                        DragAxis.HORIZONTAL -> {
                            carryX += dx
                            // A board cell is two characters wide.
                            val step = cellWidth * 2
                            while (abs(carryX) >= step) {
                                game.tap(if (carryX > 0) Key.RIGHT else Key.LEFT)
                                carryX -= if (carryX > 0) step else -step
                            }
                        }
                        DragAxis.VERTICAL -> if (dy > 0) {
                            carryY += dy
                            while (carryY >= cellHeight) {
                                game.tap(Key.DOWN)
                                carryY -= cellHeight
                            }
                        }
                        null -> Unit
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                swipe.add(event)
                val (_, vy) = swipe.velocity()
                when {
                    swipe.distance < slop -> game.tapped()
                    game.continuous -> if (swipe.axis == DragAxis.VERTICAL && swipe.dy > 0 && vy > flick) game.tap(Key.ACTION)
                    else -> when (swipe.judge()) {
                        DragAxis.HORIZONTAL -> game.tap(if (swipe.dx > 0) Key.RIGHT else Key.LEFT)
                        DragAxis.VERTICAL -> game.tap(if (swipe.dy > 0) Key.DOWN else Key.UP)
                        null -> Unit
                    }
                }
            }
        }
        return true
    }

    private companion object {
        const val PROBE = 100f
        /** The longest step at once, so a hiccup doesn't make everything jump. */
        const val MAX_STEP_MS = 100L

        /** The keys' line: this much of the width at most, this much of the height, a little air above it. */
        const val HELP_WIDTH = 0.94f
        const val HELP_HEIGHT = 0.028f
        const val HELP_GAP = 1.4f
        val HELP_COLOR = 0xFF6B7885.toInt()

        /** How much faster than Android's fling a flick down must be to drop. */
        const val FLICK_FACTOR = 3
    }
}

/** A keyboard key as a game key: w a s d or the arrows, space enter or x, p, r. */
internal fun keyOf(code: Int): Key? = when (code) {
    KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_K -> Key.UP
    KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_J -> Key.DOWN
    KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_H -> Key.LEFT
    KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> Key.RIGHT
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_X -> Key.ACTION
    KeyEvent.KEYCODE_P -> Key.PAUSE
    KeyEvent.KEYCODE_R -> Key.RESTART
    else -> null
}
