package com.newhost.qwertylauncher.ui

import android.content.res.Resources
import android.util.TypedValue
import kotlin.math.ceil

/** Art's size in characters, and how big it comes out at a text size, in the terminal font's proportions. */
internal class ArtShape(private val rows: Int, private val cols: Int) {
    fun width(px: Float, res: Resources) = ceil(cols * px * widthRatio(res)).toInt()

    fun height(px: Float, res: Resources) = ceil(rows * px * heightRatio(res)).toInt()

    fun sizeForHeight(height: Int, res: Resources) = height / (rows * heightRatio(res))

    fun sizeForWidth(width: Int, res: Resources) = width / (cols * widthRatio(res))

    private fun basePx(res: Resources) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp, res.displayMetrics)

    /** Line height and cell width per pixel of text size. */
    private fun heightRatio(res: Resources) = TtyFont.lineHeight(res) / basePx(res)

    private fun widthRatio(res: Resources) = TtyFont.cellWidth(res) / basePx(res)

    companion object {
        fun of(text: CharSequence?): ArtShape? {
            if (text.isNullOrEmpty()) return null
            val lines = text.split('\n')
            return ArtShape(lines.size, lines.maxOf { it.length }.coerceAtLeast(1))
        }
    }
}
