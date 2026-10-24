package com.newhost.qwertylauncher.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * An icon drawn with a few plain shapes on a 100 × 100 canvas, for a
 * [Cell.Art]: the shell renders it to ASCII art, like every icon.
 *
 *     ShapeIcon {
 *         fill(RED); round(40f, 6f, 60f, 70f, 10f); circle(50f, 76f, 18f)
 *     }
 */
class ShapeIcon(private val draw: Pen.() -> Unit) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** What an icon draws with: pick a [fill] or a [stroke], then shapes in 0..100 units. */
    class Pen internal constructor(val canvas: Canvas, private val paint: Paint) {
        fun fill(color: Int) {
            paint.xfermode = null
            paint.style = Paint.Style.FILL
            paint.color = color
        }

        fun stroke(color: Int, width: Float) {
            paint.xfermode = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = color
        }

        /**
         * Rubs out instead of painting, for holes and gaps: a moon's bite, the
         * gap beside a slash. Shapes fill, or with a [width], are outlined.
         */
        fun erase(width: Float = 0f) {
            paint.xfermode = CLEAR
            paint.color = Color.BLACK
            if (width > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width
                paint.strokeCap = Paint.Cap.ROUND
            } else {
                paint.style = Paint.Style.FILL
            }
        }

        fun circle(cx: Float, cy: Float, r: Float) = canvas.drawCircle(cx, cy, r, paint)

        fun rect(left: Float, top: Float, right: Float, bottom: Float) = canvas.drawRect(left, top, right, bottom, paint)

        fun round(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)

        /** An arc of the oval in the box, from [start] degrees (0 is 3 o'clock) through [sweep]; [pie] closes it to the centre. */
        fun arc(left: Float, top: Float, right: Float, bottom: Float, start: Float, sweep: Float, pie: Boolean = false) =
            canvas.drawArc(RectF(left, top, right, bottom), start, sweep, pie, paint)

        /** A closed shape through the points: x1, y1, x2, y2, ... */
        fun polygon(vararg points: Float) {
            if (points.size < 4) return
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size - 1 step 2) lineTo(points[i], points[i + 1])
                close()
            }
            canvas.drawPath(path, paint)
        }

        /**
         * An SVG path ([data], as in a vector drawable's pathData, drawn in a
         * [viewport]-unit square) fitted into the [size]-unit square at
         * ([left], [top]). Material icons paste straight in.
         */
        fun path(data: String, left: Float = 0f, top: Float = 0f, size: Float = 100f, viewport: Float = 24f) {
            val path = PathParser.createPathFromPathData(data)
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(size / viewport, size / viewport)
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        /** Bold text centred on ([cx], [cy]), [size] units tall. */
        fun text(text: String, cx: Float, cy: Float, size: Float) {
            val style = paint.style
            paint.style = Paint.Style.FILL
            paint.textSize = size
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            val metrics = paint.fontMetrics
            canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2, paint)
            paint.style = style
        }

        /** Draws [block] turned [degrees] about ([cx], [cy]). */
        fun turned(degrees: Float, cx: Float = 50f, cy: Float = 50f, block: Pen.() -> Unit) {
            canvas.save()
            canvas.rotate(degrees, cx, cy)
            block()
            canvas.restore()
        }
    }

    override fun getIntrinsicWidth() = SIZE
    override fun getIntrinsicHeight() = SIZE

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / SIZE.toFloat(), bounds.height() / SIZE.toFloat())
        // A layer of its own, so rubbing out only takes this icon's paint away.
        canvas.saveLayer(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), null)
        paint.xfermode = null
        Pen(canvas, paint).draw()
        canvas.restore()
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SIZE = 100
        val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
}

/** A drawable maker for [Cell.Art] and [fact]: `icon { fill(RED); circle(50f, 50f, 40f) }`. */
fun icon(draw: ShapeIcon.Pen.() -> Unit): () -> Drawable = { ShapeIcon(draw) }

/** Vivid colours for icons; the renderer keeps them. */
object IconColors {
    const val RED = 0xFFFF5555.toInt()
    const val ORANGE = 0xFFFF9F1C.toInt()
    const val YELLOW = 0xFFFFD23F.toInt()
    const val GREEN = 0xFF50FA7B.toInt()
    const val CYAN = 0xFF5FFFFF.toInt()
    const val BLUE = 0xFF4DA3FF.toInt()
    const val LIGHT_BLUE = 0xFF9AD0FF.toInt()
    const val VIOLET = 0xFFC792EA.toInt()
    const val PINK = 0xFFFF79C6.toInt()
    const val WHITE = 0xFFE8EEF5.toInt()
    const val GREY = 0xFF9AA5B1.toInt()
    const val DARK = 0xFF37414B.toInt()
}

/**
 * A progress bar to render as art: a dark track, filled [fraction] of the
 * way in a colour taken from [colors] by how full it is (green to red by
 * default). Much wider than tall, so it fills a line of text across.
 */
class BarDrawable(
    private val fraction: Float,
    private val colors: List<Int> = listOf(IconColors.GREEN, IconColors.YELLOW, IconColors.RED),
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = WIDTH
    override fun getIntrinsicHeight() = HEIGHT

    override fun draw(canvas: Canvas) {
        val b = bounds
        val radius = b.height() / 2f
        paint.color = IconColors.DARK
        canvas.drawRoundRect(RectF(b), radius, radius, paint)
        val filled = fraction.coerceIn(0f, 1f)
        if (filled <= 0f) return
        paint.color = colorAt(filled)
        canvas.drawRoundRect(RectF(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * filled, b.bottom.toFloat()), radius, radius, paint)
    }

    /** The colour [t] (0 to 1) of the way along [colors]. */
    private fun colorAt(t: Float): Int {
        if (colors.size == 1) return colors[0]
        val position = t * (colors.size - 1)
        val i = position.toInt().coerceAtMost(colors.size - 2)
        val f = position - i
        val a = colors[i]
        val b = colors[i + 1]
        fun mix(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt()
        return android.graphics.Color.rgb(mix(16), mix(8), mix(0))
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        // Sixteen to one: across a line of text it fills the width, about half a line tall.
        const val WIDTH = 1600
        const val HEIGHT = 100
    }
}

/** An icon for something without one of its own: a grey rounded tile with [initial] on it. */
fun placeholderIcon(initial: String): () -> Drawable = icon {
    fill(IconColors.GREY); round(8f, 8f, 92f, 92f, 20f)
    fill(IconColors.DARK); text(initial.take(1).uppercase(), 50f, 52f, 60f)
}
