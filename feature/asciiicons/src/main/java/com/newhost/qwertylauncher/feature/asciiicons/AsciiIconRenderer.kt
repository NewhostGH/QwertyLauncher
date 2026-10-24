package com.newhost.qwertylauncher.feature.asciiicons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.SettingsStore
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws a drawable to a small bitmap, maps each character cell's density onto
 * a character ramp, and colours each character with the cell's colour.
 *
 * Cells are sampled at 1:2 (w:h), the shape of a monospace glyph, so a square
 * icon comes out square on screen.
 */
internal class AsciiIconRenderer(private val settings: SettingsStore) : IconRenderer {

    override suspend fun render(icon: Drawable, cols: Int, rows: Int): CharSequence =
        withContext(Dispatchers.Default) { toAscii(icon, cols, rows) }

    override suspend fun render(icon: Drawable, cols: Int, rows: Int, fine: Boolean): CharSequence =
        if (fine) withContext(Dispatchers.Default) { toHalfBlocks(icon, cols, rows) } else render(icon, cols, rows)

    /**
     * Two square pixels per character, one above the other: `▀` in the top
     * pixel's colour over a background of the bottom one's (`▄` or a space
     * where one is empty). Crisp for small icons, like terminal image viewers.
     */
    private fun toHalfBlocks(icon: Drawable, cols: Int, rows: Int): CharSequence {
        val width = cols * PIXEL
        val height = rows * 2 * PIXEL
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        icon.bounds = targetBounds(icon, isLayer = false, width, height)
        icon.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        /** The average colour of one pixel block, or null where it's mostly empty. */
        fun block(px: Int, py: Int): Int? {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (y in py * PIXEL until (py + 1) * PIXEL) {
                for (x in px * PIXEL until (px + 1) * PIXEL) {
                    val p = pixels[y * width + x]
                    val alpha = Color.alpha(p) / 255f
                    a += alpha
                    r += alpha * Color.red(p)
                    g += alpha * Color.green(p)
                    b += alpha * Color.blue(p)
                }
            }
            if (a < PIXEL * PIXEL * HALF_COVERAGE) return null
            return brighten(Color.rgb((r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt()))
        }

        val colored = settings[AsciiSettings.colored]
        val text = SpannableStringBuilder()
        for (row in 0 until rows) {
            if (row > 0) text.append('\n')
            for (col in 0 until cols) {
                val top = block(col, row * 2)
                val bottom = block(col, row * 2 + 1)
                val start = text.length
                when {
                    top == null && bottom == null -> text.append(' ')
                    top != null && bottom != null -> {
                        // Same colour top and bottom: a full block, which needs no background.
                        val same = top == bottom
                        text.append(if (same) '█' else '▀')
                        if (colored) {
                            text.setSpan(ForegroundColorSpan(top), start, start + 1, SPAN)
                            if (!same) text.setSpan(BackgroundColorSpan(bottom), start, start + 1, SPAN)
                        }
                    }
                    top != null -> {
                        text.append('▀')
                        if (colored) text.setSpan(ForegroundColorSpan(top), start, start + 1, SPAN)
                    }
                    else -> {
                        text.append('▄')
                        if (colored) text.setSpan(ForegroundColorSpan(bottom!!), start, start + 1, SPAN)
                    }
                }
            }
        }
        return text
    }

    /** Lifts dark colours off the black background, and quantises so neighbours match. */
    private fun brighten(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2].coerceAtLeast(settings[AsciiSettings.brightness] / 100f)
        return Color.HSVToColor(hsv) and QUANTIZE_MASK
    }

    /** Per-cell sums: coverage, and alpha-weighted channels (all 0..1 per pixel). */
    private class Cells(count: Int) {
        val alpha = FloatArray(count)
        val red = FloatArray(count)
        val green = FloatArray(count)
        val blue = FloatArray(count)

        /** Average colour of the covered pixels, or null if the cell is (nearly) empty. */
        fun color(i: Int, pixelsPerCell: Int): Int? {
            val a = alpha[i]
            if (a < pixelsPerCell * MIN_COVERAGE) return null
            return Color.rgb(red[i] / a, green[i] / a, blue[i] / a)
        }
    }

    private fun toAscii(icon: Drawable, cols: Int, rows: Int): CharSequence {
        val colored = settings[AsciiSettings.colored]
        val ramp = AsciiSettings.ramp(settings[AsciiSettings.charset])
        val width = cols * CELL_W
        val height = rows * CELL_H
        val perCell = CELL_W * CELL_H

        // Adaptive icons: the foreground carries the shape; the background often carries the brand colour.
        val adaptive = icon as? AdaptiveIconDrawable
        val foreground = adaptive?.foreground
        val backLayer = if (foreground != null) adaptive.background else null
        val bounds = targetBounds(icon, isLayer = foreground != null, width, height)

        val front = sample(foreground ?: icon, bounds, width, height, cols)
        val back = backLayer?.let { sample(it, bounds, width, height, cols) }

        val cells = cols * rows
        val density = FloatArray(cells) { i ->
            val color = front.color(i, perCell)
            if (color == null) {
                0f
            } else {
                val coverage = front.alpha[i] / perCell
                val lum = luminance(color)
                // Layers are mostly flat glyphs: shape matters more than shading.
                if (back != null) coverage * (0.35f + 0.65f * lum) else coverage * lum
            }
        }
        normalize(density)

        val text = SpannableStringBuilder()
        var runStart = 0
        var runColor = NO_COLOR
        for (row in 0 until rows) {
            if (row > 0) text.append('\n')
            for (col in 0 until cols) {
                val i = row * cols + col
                val char = ramp[(density[i] * (ramp.length - 1)).roundToInt()]
                // Spaces are invisible, so they just extend the current run.
                val color = if (!colored || char == ' ') runColor else cellColor(front, back, i, perCell)
                if (color != runColor) {
                    if (runColor != NO_COLOR) text.setSpan(ForegroundColorSpan(runColor), runStart, text.length, SPAN)
                    runStart = text.length
                    runColor = color
                }
                text.append(char)
            }
        }
        if (runColor != NO_COLOR) text.setSpan(ForegroundColorSpan(runColor), runStart, text.length, SPAN)
        return text
    }

    private fun sample(layer: Drawable, bounds: Rect, width: Int, height: Int, cols: Int): Cells {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layer.bounds = bounds
        layer.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val cells = Cells(cols * (height / CELL_H))
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val a = Color.alpha(p) / 255f
                if (a == 0f) continue
                val i = (y / CELL_H) * cols + x / CELL_W
                cells.alpha[i] += a
                cells.red[i] += a * Color.red(p) / 255f
                cells.green[i] += a * Color.green(p) / 255f
                cells.blue[i] += a * Color.blue(p) / 255f
            }
        }
        return cells
    }

    /** Where to draw so the result is centred and keeps its aspect ratio. */
    private fun targetBounds(icon: Drawable, isLayer: Boolean, width: Int, height: Int): Rect {
        if (isLayer) {
            // Adaptive layers are 108dp with the visible 72dp in the middle: overdraw by a quarter on each side.
            val size = min(width, height)
            val left = (width - size) / 2
            val top = (height - size) / 2
            val bleed = size / 4
            return Rect(left - bleed, top - bleed, left + size + bleed, top + size + bleed)
        }
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        val (w, h) = if (iw > 0 && ih > 0) {
            val scale = min(width.toFloat() / iw, height.toFloat() / ih)
            (iw * scale).roundToInt() to (ih * scale).roundToInt()
        } else {
            min(width, height).let { it to it }
        }
        val left = (width - w) / 2
        val top = (height - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Picks a readable colour for a cell: a saturated foreground colour first,
     * else the background's (white glyph on a coloured tile), else the grey itself.
     * Brightened for a black background and quantised so neighbouring cells share spans.
     */
    private fun cellColor(front: Cells, back: Cells?, i: Int, perCell: Int): Int {
        val brightness = settings[AsciiSettings.brightness] / 100f
        val fg = front.color(i, perCell)
        val bg = back?.color(i, perCell)
        val picked = when {
            fg != null && saturation(fg) > MIN_SATURATION -> fg
            bg != null && saturation(bg) > MIN_SATURATION -> bg
            else -> fg ?: bg ?: Color.WHITE
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(picked, hsv)
        hsv[2] = hsv[2].coerceAtLeast(brightness)
        return Color.HSVToColor(hsv) and QUANTIZE_MASK
    }

    private fun saturation(color: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1]
    }

    /** Stretches values to 0..1 so faint or washed-out icons still use the whole ramp. */
    private fun normalize(values: FloatArray) {
        val lo = values.min()
        val hi = values.max()
        val range = hi - lo
        if (range < MIN_CONTRAST) return
        for (i in values.indices) values[i] = ((values[i] - lo) / range).coerceIn(0f, 1f)
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    private companion object {
        const val CELL_W = 4
        const val CELL_H = 8

        /** A half-block pixel's side in bitmap pixels. */
        const val PIXEL = 4

        /** A half-block pixel shows when at least this much of it is covered. */
        const val HALF_COVERAGE = 0.4f
        const val MIN_CONTRAST = 0.05f
        const val MIN_COVERAGE = 0.05f
        const val MIN_SATURATION = 0.25f
        const val QUANTIZE_MASK = 0xFFF0F0F0.toInt()
        const val NO_COLOR = 0
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}
