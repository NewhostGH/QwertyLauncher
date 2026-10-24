package com.newhost.qwertylauncher.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * [inner] cut to what it really shows: an icon drawn with a margin of
 * transparency around it (many apps' older icons) is trimmed to its visible
 * part, which then fills whatever box it's given, so icons with and
 * without margins come out the same size. Its intrinsic size is the visible
 * part's shape, so renderers keep that.
 */
class TrimmedDrawable(private val inner: Drawable) : Drawable() {

    /** The visible part, as fractions of the whole: found once, by drawing it small and looking. */
    private val visible: RectF by lazy { findVisible() }

    private fun findVisible(): RectF {
        val bitmap = Bitmap.createBitmap(PROBE, PROBE, Bitmap.Config.ARGB_8888)
        val saved = Rect(inner.bounds)
        inner.setBounds(0, 0, PROBE, PROBE)
        inner.draw(Canvas(bitmap))
        inner.bounds = saved
        val pixels = IntArray(PROBE * PROBE)
        bitmap.getPixels(pixels, 0, PROBE, 0, 0, PROBE, PROBE)
        bitmap.recycle()
        var left = PROBE
        var top = PROBE
        var right = -1
        var bottom = -1
        for (y in 0 until PROBE) for (x in 0 until PROBE) {
            if ((pixels[y * PROBE + x] ushr ALPHA_SHIFT) > MIN_ALPHA) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        // Nothing to see (or everything): the whole of it.
        if (right < left || bottom < top) return RectF(0f, 0f, 1f, 1f)
        return RectF(left / PROBE.toFloat(), top / PROBE.toFloat(), (right + 1) / PROBE.toFloat(), (bottom + 1) / PROBE.toFloat())
    }

    override fun getIntrinsicWidth() = (visible.width() * SIZE).toInt().coerceAtLeast(1)
    override fun getIntrinsicHeight() = (visible.height() * SIZE).toInt().coerceAtLeast(1)

    /** Draws [inner] scaled so its visible part fills [bounds], the margins falling outside and cut off. */
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val v = visible
        val wholeWidth = b.width() / v.width()
        val wholeHeight = b.height() / v.height()
        val left = b.left - v.left * wholeWidth
        val top = b.top - v.top * wholeHeight
        canvas.save()
        canvas.clipRect(b)
        inner.setBounds(left.toInt(), top.toInt(), (left + wholeWidth).toInt(), (top + wholeHeight).toInt())
        inner.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** Drawn this big to find its edges: fine enough, and cheap. */
        const val PROBE = 96
        const val SIZE = 192
        const val ALPHA_SHIFT = 24

        /** A pixel counts as seen from about a tenth opaque: soft shadows at the edge don't. */
        const val MIN_ALPHA = 24
    }
}
