package com.newhost.qwertylauncher.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The row at the very top of the screen, where the status bar would be: the
 * help and config buttons, each on the left, in the middle or on the right,
 * one column apart, sitting right on the status line below. Home draws up
 * here too, so the row steps around the display cutouts (notch, camera hole)
 * the system declares, and is at least as tall as they reach down, so the
 * status line below it is always clear.
 */
class TopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    enum class Slot { START, CENTER, END }

    /** Where each child goes; children in the same slot keep their order in the row. */
    class LayoutParams(var slot: Slot) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    /** The display cutouts, in window coordinates. */
    private var cutouts: List<Rect> = emptyList()
    private val location = IntArray(2)

    fun setCutouts(rects: List<Rect>) {
        if (rects == cutouts) return
        cutouts = rects.map(::Rect)
        requestLayout()
    }

    /** How much of the row's width the cutouts take (a camera hole, rounded corners), with a column beside each. */
    fun blockedWidth(): Int {
        if (width == 0) return 0
        getLocationInWindow(location)
        val left = location[0]
        val top = location[1]
        val gap = TtyFont.cellWidth(resources).roundToInt()
        return cutouts.filter { it.top < top + height && it.bottom > top }.sumOf { cutout ->
            val overlap = minOf(cutout.right, left + width) - maxOf(cutout.left, left)
            if (overlap > 0) overlap + gap else 0
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            height = max(height, child.measuredHeight)
        }
        // Down past the lowest cutout at the top of the screen.
        getLocationInWindow(location)
        val top = location[1] + paddingTop
        for (cutout in cutouts) {
            if (cutout.top <= top + height) height = max(height, cutout.bottom - top)
        }
        height = TtyFont.snap(height, TtyFont.lineHeight(resources).toFloat())
        setMeasuredDimension(width, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getLocationInWindow(location)
        val gap = TtyFont.cellWidth(resources).roundToInt()
        val left = paddingLeft
        val right = width - paddingRight
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        val bySlot = visible.groupBy { (it.layoutParams as LayoutParams).slot }

        // From the left edge rightwards, stepping past cutouts.
        var x = left
        for (child in bySlot[Slot.START].orEmpty()) {
            x = clearRight(x, child)
            place(child, x)
            x += child.measuredWidth + gap
        }
        // From the right edge leftwards.
        x = right
        for (child in bySlot[Slot.END].orEmpty().asReversed()) {
            x = clearLeft(x - child.measuredWidth, child) + child.measuredWidth
            place(child, x - child.measuredWidth)
            x -= child.measuredWidth + gap
        }
        // Centred as a group; beside the cutout if one is in the way (a centred camera hole usually is).
        val middle = bySlot[Slot.CENTER].orEmpty()
        if (middle.isNotEmpty()) {
            val total = middle.sumOf { it.measuredWidth } + gap * (middle.size - 1)
            var start = (left + right - total) / 2
            val tallest = middle.maxOf { it.measuredHeight }
            val blocking = cutouts.firstOrNull { overlaps(it, start, total, height - paddingBottom - tallest, tallest) }
            if (blocking != null) {
                val blockLeft = blocking.left - location[0]
                val blockRight = blocking.right - location[0]
                start = if (right - blockRight >= total) blockRight + gap else blockLeft - gap - total
            }
            for (child in middle) {
                place(child, start)
                start += child.measuredWidth + gap
            }
        }
    }

    /** Buttons sit on the bottom of the row, right on the status line below. */
    private fun top(child: View) = height - paddingBottom - child.measuredHeight

    private fun place(child: View, x: Int) = child.layout(x, top(child), x + child.measuredWidth, top(child) + child.measuredHeight)

    /** The first x at or right of [x] where [child] clears every cutout. */
    private fun clearRight(x: Int, child: View): Int {
        var at = x
        for (cutout in cutouts.sortedBy { it.left }) {
            if (overlaps(cutout, at, child.measuredWidth, top(child), child.measuredHeight)) at = cutout.right - location[0]
        }
        return at
    }

    /** The first x at or left of [x] where [child] clears every cutout. */
    private fun clearLeft(x: Int, child: View): Int {
        var at = x
        for (cutout in cutouts.sortedByDescending { it.right }) {
            if (overlaps(cutout, at, child.measuredWidth, top(child), child.measuredHeight)) {
                at = cutout.left - location[0] - child.measuredWidth
            }
        }
        return at
    }

    /** Whether a box at ([x], [y]) in this view, [w] wide and [h] tall, touches [cutout]. */
    private fun overlaps(cutout: Rect, x: Int, w: Int, y: Int, h: Int): Boolean {
        val boxLeft = location[0] + x
        val boxTop = location[1] + y
        return cutout.left < boxLeft + w && boxLeft < cutout.right && cutout.top < boxTop + h && boxTop < cutout.bottom
    }

    override fun generateDefaultLayoutParams() = LayoutParams(Slot.END)

    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(Slot.END)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?) = LayoutParams(Slot.END)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
}
