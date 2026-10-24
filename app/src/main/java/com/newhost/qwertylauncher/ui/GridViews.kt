package com.newhost.qwertylauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The selection highlight for boxes without a frame of their own: box-drawing
 * corners in the four corners, drawn as text, like a TUI marking a field.
 * ```
 * ┌      ┐
 *   art
 * └      ┘
 * ```
 */
internal object SelectionCorners {
    fun draw(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        val c = FrameLook.chars
        val cell = paint.measureText(c.topLeft.toString())
        val metrics = paint.fontMetricsInt
        val topBaseline = -metrics.ascent.toFloat()
        val bottomBaseline = (height - metrics.descent).toFloat()
        val right = width - cell
        canvas.drawText(c.topLeft.toString(), 0f, topBaseline, paint)
        canvas.drawText(c.topRight.toString(), right, topBaseline, paint)
        canvas.drawText(c.bottomLeft.toString(), 0f, bottomBaseline, paint)
        canvas.drawText(c.bottomRight.toString(), right, bottomBaseline, paint)
    }
}

/**
 * ASCII art at its own tiny size, in a box of whole terminal cells: however
 * big the art comes out, the box around it is rounded up to full columns and
 * lines, so the text next to and below it stays on the grid.
 */
class ArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    /** Highlighted even without focus, e.g. while it's being moved. */
    var forceHighlight = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * A box around it, in the frame characters (the "button frames" setting):
     * lit when selected, in place of the selection corners. The owner makes
     * room for it with padding.
     */
    var framed = false
        set(value) {
            field = value
            invalidate()
        }

    private val cornerPaint = TtyFont.paint(resources).apply { color = context.getColor(CoreR.color.tty_accent) }
    private val framePaint = TtyFont.paint(resources)

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }

    /** Selected (a focusable button), it's marked with text corners, not a tinted background. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val selected = isFocused || forceHighlight
        if (framed) {
            framePaint.color = if (selected) FrameLook.selected else FrameLook.idle
            FrameLook.drawBox(canvas, width, height, framePaint)
        } else if (selected) {
            SelectionCorners.draw(canvas, width, height, cornerPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (visibility == GONE) return
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            measuredWidth
        } else {
            TtyFont.snap(measuredWidth, TtyFont.cellWidth(resources)).coerceAtMost(limit(widthMeasureSpec))
        }
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            measuredHeight
        } else {
            TtyFont.snap(measuredHeight, TtyFont.lineHeight(resources).toFloat())
        }
        setMeasuredDimension(width, height)
    }

    /** The most an AT_MOST spec allows; no limit otherwise. */
    private fun limit(spec: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.AT_MOST) MeasureSpec.getSize(spec) else Int.MAX_VALUE
}

/**
 * A scroll view that moves like a terminal: no momentum after a swipe, no
 * smooth scrolling, no scrollbars, and no stretch or glow at the ends.
 */
class TtyScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = false
    }

    override fun fling(velocityY: Int) = Unit
}
