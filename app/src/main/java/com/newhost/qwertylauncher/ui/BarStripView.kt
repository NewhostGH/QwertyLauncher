package com.newhost.qwertylauncher.ui

import android.content.Context
import android.view.ViewGroup
import com.newhost.qwertylauncher.core.ScrollTarget
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A module's quick buttons in the top bar ([com.newhost.qwertylauncher.core.BarStrip]):
 * [ArtView]s drawn like the help and config buttons, side by side a column
 * apart. Only the first [room] show, as many as fit in the bar. The home
 * cursor steps through them one at a time, like a tile's controls.
 */
internal class BarStripView(context: Context) : ViewGroup(context), ScrollTarget {

    /** How many buttons fit; the rest are hidden until there's room. */
    var room: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            applyRoom()
        }

    /** Lit even without a button selected, while the strip is being moved. */
    var highlight = false
        set(value) {
            field = value
            buttons().forEach { it.forceHighlight = value }
        }

    fun buttons(): List<ArtView> = (0 until childCount).map { getChildAt(it) as ArtView }

    /** The buttons that fit, the ones the cursor stops on. */
    fun shown(): List<ArtView> = buttons().filter { it.visibility == VISIBLE }

    /** Keeps [count] buttons, adding new ones with [create] or dropping the last. */
    fun resize(count: Int, create: () -> ArtView) {
        while (childCount > count) removeViewAt(childCount - 1)
        while (childCount < count) addView(create().also { it.forceHighlight = highlight })
        applyRoom()
    }

    private fun applyRoom() {
        buttons().forEachIndexed { i, button -> button.visibility = if (i < room) VISIBLE else GONE }
        requestLayout()
    }

    private val gap get() = TtyFont.cellWidth(resources).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var width = 0
        var height = 0
        for (button in shown()) {
            button.measure(unbounded, unbounded)
            width += button.measuredWidth + (if (width > 0) gap else 0)
            height = max(height, button.measuredHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        for (button in shown()) {
            // On the bottom, like the buttons sit right on the status line.
            val top = height - button.measuredHeight
            button.layout(x, top, x + button.measuredWidth, top + button.measuredHeight)
            x += button.measuredWidth + gap
        }
    }

    // --- The home cursor ---

    override fun onScroll(down: Int, right: Int): Boolean {
        val buttons = shown()
        if (buttons.isEmpty()) return false
        val step = if (down + right > 0) 1 else -1
        val current = buttons.indexOfFirst { it.isFocused }
        val target = if (current < 0) (if (step > 0) 0 else buttons.lastIndex) else current + step
        // Past either end: the cursor moves on to the next stop.
        if (target !in buttons.indices) return false
        return buttons[target].requestFocusFromTouch()
    }

    override val selectionKey: String? get() = shown().firstOrNull { it.isFocused }?.tag as? String

    override fun restoreSelection(key: String): Boolean = shown().firstOrNull { it.tag == key }?.requestFocusFromTouch() == true

    override fun enter(fromEnd: Boolean): Boolean {
        val buttons = shown()
        if (buttons.isEmpty()) return false
        return (if (fromEnd) buttons.last() else buttons.first()).requestFocusFromTouch()
    }
}
