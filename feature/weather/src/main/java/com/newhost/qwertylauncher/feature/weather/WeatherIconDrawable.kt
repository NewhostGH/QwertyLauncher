package com.newhost.qwertylauncher.feature.weather

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * A weather symbol drawn with plain shapes on a 100×100 grid, meant to be run
 * through the ASCII renderer. Colours are vivid because the renderer keeps them.
 * [frame] moves it: rain and snow fall, the sun's rays turn, clouds and fog
 * drift, lightning flickers. Frame 0 is the still picture.
 */
internal class WeatherIconDrawable(private val sky: Sky, private val frame: Int = 0) : Drawable() {

    /** Clouds sway a few units either side. */
    private val drift = if (frame == 0) 0f else sin(frame * 0.35).toFloat() * 3f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = SIZE
    override fun getIntrinsicHeight() = SIZE

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / SIZE.toFloat(), bounds.height() / SIZE.toFloat())
        when (sky) {
            Sky.CLEAR -> {
                sun(canvas, 50f, 50f, 24f)
                face(canvas, 50f, 50f, 24f, Mood.HAPPY, SUN_FACE)
            }
            Sky.PARTLY_CLOUDY -> {
                sun(canvas, 36f, 36f, 18f)
                face(canvas, 36f, 34f, 18f, Mood.HAPPY, SUN_FACE)
                cloud(canvas, 58f + drift, 64f, 0.8f, LIGHT_CLOUD)
                cloudFace(canvas, 58f + drift, 64f, 0.8f, Mood.CALM)
            }
            Sky.CLOUDY -> {
                cloud(canvas, 38f - drift, 42f, 0.65f, DARK_CLOUD)
                cloud(canvas, 56f + drift, 60f, 0.9f, LIGHT_CLOUD)
                cloudFace(canvas, 56f + drift, 60f, 0.9f, Mood.CALM)
            }
            Sky.FOG -> {
                fog(canvas)
                face(canvas, 50f, 50f, 20f, Mood.SLEEPY, CLOUD_FACE)
            }
            Sky.RAIN -> {
                precipitation(canvas, RAIN_BLUE, drops = true)
                cloud(canvas, 50f + drift, 40f, 0.9f, DARK_CLOUD)
                cloudFace(canvas, 50f + drift, 40f, 0.9f, Mood.SAD)
            }
            Sky.SNOW -> {
                precipitation(canvas, SNOW_WHITE, drops = false)
                cloud(canvas, 50f + drift, 40f, 0.9f, LIGHT_CLOUD)
                cloudFace(canvas, 50f + drift, 40f, 0.9f, Mood.COLD)
            }
            Sky.STORM -> {
                cloud(canvas, 50f + drift, 38f, 0.9f, DARK_CLOUD)
                cloudFace(canvas, 50f + drift, 38f, 0.9f, Mood.ANGRY)
                // Lightning flickers: on for two frames, off for the rest of a beat.
                if (frame == 0 || frame % STORM_BEAT < 2) bolt(canvas)
            }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = SUN_ORANGE
        // The rays turn a quarter of the gap between them each frame.
        val turn = (frame % 4) * 11.25
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0 + turn)
            val inner = radius * 1.35f
            val outer = radius * 1.85f
            canvas.drawLine(
                cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = SUN_YELLOW
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private enum class Mood { HAPPY, CALM, SAD, ANGRY, SLEEPY, COLD }

    /** A face on a cloud drawn by [cloud] with the same centre and scale. */
    private fun cloudFace(canvas: Canvas, cx: Float, cy: Float, scale: Float, mood: Mood) =
        face(canvas, cx + 2f * scale, cy + 4f * scale, 16f * scale, mood, CLOUD_FACE)

    /** Two eyes and a mouth around ([cx], [cy]), [s] about the face's radius. */
    private fun face(canvas: Canvas, cx: Float, cy: Float, s: Float, mood: Mood, color: Int) {
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        val eyeY = cy - s * 0.2f
        val eyeX = s * 0.4f
        if (mood == Mood.SLEEPY) {
            // Closed eyes: two short lines.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.12f
            canvas.drawLine(cx - eyeX - s * 0.14f, eyeY, cx - eyeX + s * 0.14f, eyeY, paint)
            canvas.drawLine(cx + eyeX - s * 0.14f, eyeY, cx + eyeX + s * 0.14f, eyeY, paint)
        } else {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx - eyeX, eyeY, s * 0.14f, paint)
            canvas.drawCircle(cx + eyeX, eyeY, s * 0.14f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.12f
        when (mood) {
            Mood.HAPPY -> canvas.drawArc(RectF(cx - s * 0.4f, cy - s * 0.2f, cx + s * 0.4f, cy + s * 0.45f), 20f, 140f, false, paint)
            Mood.CALM -> canvas.drawArc(RectF(cx - s * 0.25f, cy, cx + s * 0.25f, cy + s * 0.3f), 20f, 140f, false, paint)
            Mood.SAD, Mood.ANGRY -> canvas.drawArc(RectF(cx - s * 0.3f, cy + s * 0.2f, cx + s * 0.3f, cy + s * 0.6f), 200f, 140f, false, paint)
            Mood.SLEEPY, Mood.COLD -> canvas.drawCircle(cx, cy + s * 0.3f, s * 0.12f, paint)
        }
        if (mood == Mood.ANGRY) {
            // Brows slanting down to the middle.
            canvas.drawLine(cx - eyeX - s * 0.2f, eyeY - s * 0.35f, cx - eyeX + s * 0.2f, eyeY - s * 0.2f, paint)
            canvas.drawLine(cx + eyeX + s * 0.2f, eyeY - s * 0.35f, cx + eyeX - s * 0.2f, eyeY - s * 0.2f, paint)
        }
    }

    /** A cloud centred at ([cx], [cy]); [scale] 1 is about 70 units wide. */
    private fun cloud(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx - 16f * scale, cy + 4f * scale, 14f * scale, paint)
        canvas.drawCircle(cx + 2f * scale, cy - 8f * scale, 20f * scale, paint)
        canvas.drawCircle(cx + 20f * scale, cy + 4f * scale, 14f * scale, paint)
        canvas.drawRect(cx - 16f * scale, cy + 4f * scale, cx + 20f * scale, cy + 18f * scale, paint)
    }

    private fun precipitation(canvas: Canvas, color: Int, drops: Boolean) {
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        // Falling: each frame moves everything down, wrapping back up under the cloud.
        val fall = if (drops) frame * 6f else frame * 3f
        for (i in 0 until 4) {
            // Snow sways as it falls.
            val sway = if (drops || frame == 0) 0f else sin(frame * 0.8 + i).toFloat() * 3f
            val x = 28f + i * 15f + sway
            for (row in 0 until 2) {
                val y = 60f + (row * 16f + (i % 2) * 6f + 6f + fall) % FALL_SPAN
                if (drops) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 4f
                    canvas.drawLine(x, y, x - 4f, y + 9f, paint)
                } else {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x, y + 4f, 3.5f, paint)
                }
            }
        }
    }

    private fun bolt(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = SUN_YELLOW
        val path = Path().apply {
            moveTo(54f, 52f)
            lineTo(40f, 76f)
            lineTo(50f, 76f)
            lineTo(44f, 96f)
            lineTo(64f, 68f)
            lineTo(54f, 68f)
            lineTo(60f, 52f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun fog(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 7f
        paint.color = FOG_GREY
        for (i in 0 until 5) {
            val y = 22f + i * 14f
            val inset = if (i % 2 == 0) 14f else 24f
            // Banks of fog slide back and forth, neighbours in opposite directions.
            val shift = if (i % 2 == 0) drift else -drift
            canvas.drawLine(inset + shift, y, SIZE - inset + shift, y, paint)
        }
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
        const val SUN_YELLOW = 0xFFFFD23F.toInt()
        const val SUN_ORANGE = 0xFFFF9F1C.toInt()
        const val LIGHT_CLOUD = 0xFFE8EEF5.toInt()
        const val DARK_CLOUD = 0xFF9AA5B1.toInt()
        const val RAIN_BLUE = 0xFF4DA3FF.toInt()
        const val SNOW_WHITE = 0xFFFFFFFF.toInt()
        const val FOG_GREY = 0xFFB8C0C8.toInt()
        const val SUN_FACE = 0xFF7A4A00.toInt()
        const val CLOUD_FACE = 0xFF37414B.toInt()

        /** Height of the band rain and snow fall through, below the cloud. */
        const val FALL_SPAN = 32f

        /** Frames between flashes of lightning. */
        const val STORM_BEAT = 7
    }
}
