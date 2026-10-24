package com.newhost.qwertylauncher.feature.sensors

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.newhost.qwertylauncher.core.IconColors
import kotlin.math.abs
import kotlin.math.hypot

/** Pictures for the ascii art module to draw as coloured characters, on a 100 x 100 canvas. */
internal abstract class Picture : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun getIntrinsicWidth() = SIZE
    override fun getIntrinsicHeight() = SIZE

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / SIZE.toFloat(), bounds.height() / SIZE.toFloat())
        draw100(canvas)
        canvas.restore()
    }

    protected abstract fun draw100(c: Canvas)

    protected fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    protected fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
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
    }
}

/**
 * A compass rose turned so its N points north, for a phone pointing
 * [heading] degrees: the red needle's tip to the north, a yellow mark at
 * the top for where the phone points.
 */
internal class CompassDrawable(private val heading: Float) : Picture() {
    override fun draw100(c: Canvas) {
        // The ring, and a tick every 30 degrees, longer at the four points.
        stroke(IconColors.GREY, 5f)
        c.drawCircle(50f, 50f, 44f, paint)
        c.save()
        c.rotate(-heading, 50f, 50f)
        for (i in 0 until 12) {
            c.save()
            c.rotate(i * 30f, 50f, 50f)
            stroke(if (i % 3 == 0) IconColors.WHITE else IconColors.GREY, if (i % 3 == 0) 4f else 2.5f)
            c.drawLine(50f, 8f, 50f, if (i % 3 == 0) 17f else 13f, paint)
            c.restore()
        }
        // The letters, upright wherever they are round the ring.
        fill(IconColors.WHITE)
        paint.textSize = 15f
        for ((i, letter) in listOf("N", "E", "S", "W").withIndex()) {
            c.save()
            c.rotate(i * 90f, 50f, 50f)
            fill(if (letter == "N") IconColors.RED else IconColors.WHITE)
            c.drawText(letter, 50f, 33f, paint)
            c.restore()
        }
        // The needle: red to the north, white to the south.
        fill(IconColors.RED)
        c.drawPath(Path().apply {
            moveTo(50f, 20f)
            lineTo(57f, 50f)
            lineTo(43f, 50f)
            close()
        }, paint)
        fill(IconColors.WHITE)
        c.drawPath(Path().apply {
            moveTo(50f, 80f)
            lineTo(57f, 50f)
            lineTo(43f, 50f)
            close()
        }, paint)
        c.restore()
        fill(IconColors.YELLOW)
        c.drawCircle(50f, 50f, 4f, paint)
        // Where the phone points.
        c.drawPath(Path().apply {
            moveTo(50f, 1f)
            lineTo(55f, 8f)
            lineTo(45f, 8f)
            close()
        }, paint)
    }
}

/**
 * A bullseye spirit level: rings and a cross, and the bubble, off-centre
 * as the phone is tipped ([tilt], up to [RANGE] degrees to the edge); green
 * when it's level, yellow when nearly, red when not.
 */
internal class LevelDrawable(private val tilt: Tilt) : Picture() {
    override fun draw100(c: Canvas) {
        stroke(IconColors.GREY, 4f)
        c.drawCircle(50f, 50f, 44f, paint)
        stroke(IconColors.DARK, 3f)
        c.drawCircle(50f, 50f, 28f, paint)
        c.drawLine(50f, 8f, 50f, 92f, paint)
        c.drawLine(8f, 50f, 92f, 50f, paint)
        stroke(IconColors.WHITE, 3f)
        c.drawCircle(50f, 50f, 13f, paint)
        // The bubble floats to the high side: opposite the tilt.
        var dx = -tilt.roll / RANGE * 36f
        var dy = tilt.pitch / RANGE * 36f
        val reach = hypot(dx, dy)
        if (reach > 36f) {
            dx *= 36f / reach
            dy *= 36f / reach
        }
        val off = maxOf(abs(tilt.pitch), abs(tilt.roll))
        fill(
            when {
                off < LEVEL -> IconColors.GREEN
                off < NEARLY -> IconColors.YELLOW
                else -> IconColors.RED
            },
        )
        c.drawCircle(50f + dx, 50f + dy, 10f, paint)
        fill(IconColors.WHITE)
        c.drawCircle(47f + dx, 47f + dy, 3f, paint)
    }

    companion object {
        const val RANGE = 20f
        const val LEVEL = 1f
        const val NEARLY = 5f
    }
}
