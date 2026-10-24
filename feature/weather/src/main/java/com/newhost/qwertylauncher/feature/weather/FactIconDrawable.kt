package com.newhost.qwertylauncher.feature.weather

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The small icons in front of the weather tile's facts, drawn with plain
 * shapes on a 100×100 grid and then turned into ASCII art by the renderer,
 * like every icon in the launcher. [angle] turns the wind arrow: the way the
 * wind blows, in degrees (0 is north, up).
 */
internal class FactIconDrawable(private val kind: Kind, private val angle: Float = 0f) : Drawable() {

    enum class Kind { HIGH_LOW, FEELS, WIND, RAIN, HUMIDITY, SUNRISE, SUNSET, UV, PRESSURE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = SIZE
    override fun getIntrinsicHeight() = SIZE

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / SIZE.toFloat(), bounds.height() / SIZE.toFloat())
        when (kind) {
            Kind.HIGH_LOW -> thermometer(canvas, RED)
            Kind.FEELS -> thermometer(canvas, ORANGE)
            Kind.WIND -> arrow(canvas)
            Kind.RAIN -> umbrella(canvas)
            Kind.HUMIDITY -> drop(canvas, 50f, 55f, 34f, LIGHT_BLUE)
            Kind.SUNRISE -> horizonSun(canvas, rising = true)
            Kind.SUNSET -> horizonSun(canvas, rising = false)
            Kind.UV -> uvSun(canvas)
            Kind.PRESSURE -> gauge(canvas)
        }
        canvas.restore()
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
    }

    private fun thermometer(canvas: Canvas, color: Int) {
        fill(GLASS)
        canvas.drawRoundRect(RectF(40f, 6f, 60f, 70f), 10f, 10f, paint)
        canvas.drawCircle(50f, 76f, 18f, paint)
        fill(color)
        canvas.drawRect(45f, 30f, 55f, 72f, paint)
        canvas.drawCircle(50f, 76f, 13f, paint)
    }

    /** An arrow pointing the way the wind blows. */
    private fun arrow(canvas: Canvas) {
        canvas.save()
        canvas.rotate(angle, 50f, 50f)
        stroke(CYAN, 12f)
        canvas.drawLine(50f, 88f, 50f, 30f, paint)
        fill(CYAN)
        canvas.drawPath(
            Path().apply {
                moveTo(50f, 6f)
                lineTo(76f, 40f)
                lineTo(24f, 40f)
                close()
            },
            paint,
        )
        canvas.restore()
    }

    private fun umbrella(canvas: Canvas) {
        fill(BLUE)
        canvas.drawArc(RectF(8f, 14f, 92f, 86f), 180f, 180f, true, paint)
        stroke(GLASS, 7f)
        canvas.drawLine(50f, 50f, 50f, 84f, paint)
        canvas.drawArc(RectF(50f, 74f, 66f, 94f), 0f, 180f, false, paint)
    }

    private fun drop(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill(color)
        canvas.drawCircle(cx, cy, r * 0.8f, paint)
        canvas.drawPath(
            Path().apply {
                moveTo(cx, cy - r * 1.35f)
                lineTo(cx + r * 0.72f, cy - r * 0.2f)
                lineTo(cx - r * 0.72f, cy - r * 0.2f)
                close()
            },
            paint,
        )
    }

    private fun horizonSun(canvas: Canvas, rising: Boolean) {
        fill(if (rising) YELLOW else ORANGE)
        canvas.drawArc(RectF(22f, 34f, 78f, 90f), 180f, 180f, true, paint)
        stroke(GLASS, 7f)
        canvas.drawLine(6f, 64f, 94f, 64f, paint)
        // A small arrow above the sun: up at sunrise, down at sunset.
        fill(if (rising) YELLOW else ORANGE)
        canvas.drawPath(
            Path().apply {
                if (rising) {
                    moveTo(50f, 2f)
                    lineTo(64f, 22f)
                    lineTo(36f, 22f)
                } else {
                    moveTo(50f, 24f)
                    lineTo(64f, 4f)
                    lineTo(36f, 4f)
                }
                close()
            },
            paint,
        )
    }

    private fun uvSun(canvas: Canvas) {
        stroke(VIOLET, 9f)
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f, 50f, 50f)
            canvas.drawLine(50f, 6f, 50f, 18f, paint)
            canvas.restore()
        }
        fill(VIOLET)
        canvas.drawCircle(50f, 50f, 24f, paint)
    }

    private fun gauge(canvas: Canvas) {
        stroke(GLASS, 10f)
        canvas.drawArc(RectF(10f, 18f, 90f, 98f), 180f, 180f, false, paint)
        stroke(RED, 8f)
        canvas.drawLine(50f, 58f, 74f, 30f, paint)
        fill(GLASS)
        canvas.drawCircle(50f, 58f, 8f, paint)
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
        const val RED = 0xFFFF5555.toInt()
        const val ORANGE = 0xFFFF9F1C.toInt()
        const val YELLOW = 0xFFFFD23F.toInt()
        const val CYAN = 0xFF5FFFFF.toInt()
        const val BLUE = 0xFF4DA3FF.toInt()
        const val LIGHT_BLUE = 0xFF9AD0FF.toInt()
        const val VIOLET = 0xFFC792EA.toInt()
        const val GLASS = 0xFFD0D8E0.toInt()
    }
}
