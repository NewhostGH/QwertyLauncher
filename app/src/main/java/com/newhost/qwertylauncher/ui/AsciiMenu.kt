package com.newhost.qwertylauncher.ui

import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import kotlin.math.ceil
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * A box-drawn menu over the home screen:
 * ```
 * ┌─ whatsapp ───[x]┐
 * │>1 unpin         │
 * │ 2 move          │
 * └─────────────────┘
 * ```
 * Each line is three views, left border, content and right border, so the
 * right edge lines up by layout: box-drawing characters can come from a
 * fallback font a little wider or narrower than letters. The [x] closes the
 * menu; scrolling up past the first item selects it. A menu taller than the
 * screen shows a window of its items that scrolls to keep the selected one
 * in the middle, with where it is (`5/24`) in the bottom border. Tapping outside the box
 * closes it too. Menus hold lambdas, so they aren't kept across rotation; the
 * host closes them.
 *
 * A screen reader hears the menu's title as it opens, then only the items
 * and the [x], never the borders; its focus and the selection move together.
 */
internal class AsciiMenu(
    private val overlay: FrameLayout,
    private val box: LinearLayout,
    private val host: LauncherHost,
) {
    private var items: List<MenuItem> = emptyList()
    private var lines: List<TextView> = emptyList()
    private var closeButton: TextView? = null
    private var bottomRule: TextView? = null

    /** The first item shown: the menu scrolls when it has more items than [lines]. */
    private var first = 0

    /** The selected item, or [CLOSE] for the [x]. */
    private var selected = 0

    private val context = overlay.context
    private val fg = context.getColor(CoreR.color.tty_fg)
    private val accent = context.getColor(CoreR.color.tty_accent)
    private val red = context.getColor(CoreR.color.tty_red)
    private val bg = context.getColor(CoreR.color.tty_bg)

    val isOpen: Boolean get() = overlay.isVisible

    init {
        overlay.setOnClickListener { close() }
        box.isClickable = true // taps on the box itself don't close the menu
    }

    fun show(title: String, newItems: List<MenuItem>) {
        if (newItems.isEmpty()) return
        items = newItems
        selected = 0
        first = 0

        box.removeAllViews()
        // Wide enough for the title with its [x], and for the longest item.
        val sample = text("", fg)
        val widest = maxOf(
            sample.paint.measureText("─ $title ─" + CLOSE_LABEL),
            newItems.maxOf { sample.paint.measureText(">9 ${it.label} ") },
            sample.paint.measureText("─".repeat(MIN_WIDTH)),
        )
        // The frame characters from the settings, like every frame.
        val c = FrameLook.chars
        val h = c.horizontal.toString()
        val border = sample.paint.measureText(c.vertical.toString())
        box.layoutParams = box.layoutParams.apply { width = ceil(widest + 2 * border).toInt() }

        // The rules are longer than needed; each line cuts its own at the right border.
        val rule = h.repeat(RULE_LENGTH)
        val close = text(CLOSE_LABEL, red, spoken = true).apply {
            setOnClickListener { close() }
            contentDescription = "close"
            followReader(this) { CLOSE }
        }
        closeButton = close
        box.addView(row(text(c.topLeft.toString(), accent), text("$h $title $rule", accent), text(c.topRight.toString(), accent), extra = close))
        // Only as many lines as fit on screen, the borders and a line of margin above and below included.
        lines = List(newItems.size.coerceAtMost(visibleLines())) { line ->
            val content = text("", fg, spoken = true).apply {
                setOnClickListener { pick(first + line) }
                followReader(this) { first + line }
            }
            box.addView(row(text(c.vertical.toString(), accent), content, text(c.vertical.toString(), accent)))
            content
        }
        val bottom = text(rule, accent)
        bottomRule = bottom
        box.addView(row(text(c.bottomLeft.toString(), accent), bottom, text(c.bottomRight.toString(), accent)))
        ViewCompat.setAccessibilityPaneTitle(overlay, "${Speech.words(title)} menu")
        render()
        overlay.isVisible = true
        takeReader()
    }

    fun close() {
        overlay.isVisible = false
        box.removeAllViews()
        items = emptyList()
        lines = emptyList()
        closeButton = null
        bottomRule = null
    }

    /** Handles a key while open; everything is swallowed so nothing leaks to the screen below. */
    fun onKey(event: KeyEvent): Boolean {
        // A menu opened by holding enter keeps getting that key's repeats; they mustn't pick an item.
        val confirm = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_SPACE
        if (confirm && event.repeatCount > 0) return true
        when {
            VimKeys.isUp(event) -> move(-1)
            VimKeys.isDown(event) -> move(+1)
            confirm -> if (selected == CLOSE) close() else pick(selected)
            event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> pick(event.keyCode - KeyEvent.KEYCODE_1)
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_Q ||
                event.keyCode == KeyEvent.KEYCODE_X ||
                event.keyCode == KeyEvent.KEYCODE_MENU -> close()
            // Let Back reach the activity, which closes the menu too.
            event.keyCode == KeyEvent.KEYCODE_BACK -> return false
        }
        return true
    }

    /** Moves the selection; up from the first item reaches the [x]. */
    fun move(delta: Int) {
        if (items.isEmpty()) return
        selected = (selected + delta).coerceIn(CLOSE, items.lastIndex)
        render()
        takeReader()
    }

    /** Takes a screen reader, when one's on, to the selected line (or the [x]), which it then reads. */
    private fun takeReader() {
        if (!ReaderCursor.readerOn(context)) return
        box.post {
            val view = if (selected == CLOSE) closeButton else lines.getOrNull(selected - first)
            if (view != null && !view.isAccessibilityFocused) view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    /** A screen reader landing on [view] selects what it shows, so enter and a double tap agree. */
    private fun followReader(view: View, index: () -> Int) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED && items.isNotEmpty() && selected != index()) {
                    selected = index()
                    render()
                }
                super.sendAccessibilityEvent(host, eventType)
            }
        }
    }

    private fun pick(index: Int) {
        val item = items.getOrNull(index) ?: return
        close()
        item.action.run(host)
    }

    private fun render() {
        // The selected item in the middle of the window, which stops at either end of the list.
        val shown = lines.size
        if (selected != CLOSE) first = (selected - shown / 2).coerceIn(0, (items.size - shown).coerceAtLeast(0))
        if (shown < items.size) {
            val h = FrameLook.chars.horizontal.toString()
            bottomRule?.text = "$h ${selected.coerceAtLeast(0) + 1}/${items.size} ${h.repeat(RULE_LENGTH)}"
        }
        lines.forEachIndexed { line, view ->
            val index = first + line
            val marker = if (index == selected) ">" else " "
            val number = if (index < 9) "${index + 1}" else " "
            view.text = "$marker$number ${items[index].label}"
            view.contentDescription = Speech.speakable(items[index].label)
            // Reverse video for the selected line, inside the borders only.
            view.setBackgroundColor(if (index == selected) fg else bg)
            view.setTextColor(if (index == selected) bg else fg)
        }
        closeButton?.apply {
            setBackgroundColor(if (selected == CLOSE) red else bg)
            setTextColor(if (selected == CLOSE) bg else red)
        }
    }

    /** How many item lines fit on screen: all but the two borders and a line of margin above and below. */
    private fun visibleLines(): Int {
        val height = overlay.height.takeIf { it > 0 }
            ?: overlay.rootView.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return (height / TtyFont.lineHeight(context.resources) - FRAME_LINES).coerceAtLeast(1)
    }

    /** One line of the box: its content fills the space between the two borders. */
    private fun row(left: TextView, content: TextView, right: TextView, extra: TextView? = null) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            extra?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    /** One piece of the box; only the items and the [x] are [spoken], borders aren't. */
    private fun text(value: String, color: Int, spoken: Boolean = false) = TextView(context).apply {
        text = value
        setTextAppearance(CoreR.style.TtyText)
        setTextColor(color)
        // The terminal font size, no padding or extra height: the box-drawing characters must touch to join up.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        // One line, cut at the right edge rather than wrapped or ended with "...".
        isSingleLine = true
        ellipsize = null
        importantForAccessibility = if (spoken) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private companion object {
        const val CLOSE = -1
        const val CLOSE_LABEL = "[x]"
        const val MIN_WIDTH = 20
        const val RULE_LENGTH = 200

        /** The top and bottom borders, and a line of margin beyond each. */
        const val FRAME_LINES = 4
    }
}
