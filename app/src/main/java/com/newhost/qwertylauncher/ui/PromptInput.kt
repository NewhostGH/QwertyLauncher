package com.newhost.qwertylauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Selection
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatEditText
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The prompt's text field, made to behave like a terminal line:
 *
 * - The cursor is a character cell in reverse video, drawn with text, not
 *   Android's thin line or drawable. It blinks if [blink] is on.
 * - There is no text selection: no highlight, handles or copy-and-paste bar.
 * - Hardware keys reach [onPreImeKey] before the keyboard app. Some (the
 *   Titan's) turn enter into an instant "go", so holding it can't be seen;
 *   returning true keeps the key from the keyboard app.
 */
class PromptInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    var onPreImeKey: ((KeyEvent) -> Boolean)? = null

    var blink = true
        set(value) {
            field = value
            restartBlink()
        }

    private val fg = context.getColor(CoreR.color.tty_fg)
    private val bg = context.getColor(CoreR.color.tty_bg)
    private var blinkStart = 0L
    private val blinker = object : Runnable {
        override fun run() {
            invalidate()
            if (blink && isFocused) postDelayed(this, BLINK_MS)
        }
    }

    init {
        isCursorVisible = false
        isLongClickable = false
        val noMenu = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        customSelectionActionModeCallback = noMenu
        customInsertionActionModeCallback = noMenu
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        onPreImeKey?.invoke(event) == true || super.onKeyPreIme(keyCode, event)

    /** A selection can't be made: it collapses to the cursor, at its end. */
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart != selEnd) text?.let { Selection.setSelection(it, selEnd) }
        restartBlink()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        restartBlink()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinker)
        super.onDetachedFromWindow()
    }

    /** Typing shows the cursor at once, then it blinks again, like a console. */
    private fun restartBlink() {
        blinkStart = SystemClock.uptimeMillis()
        removeCallbacks(blinker)
        invalidate()
        if (blink && isFocused) postDelayed(blinker, BLINK_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isFocused) return
        val on = !blink || ((SystemClock.uptimeMillis() - blinkStart) / BLINK_MS) % 2 == 0L
        if (!on) return
        val layout = layout ?: return
        val offset = selectionEnd.coerceIn(0, length())
        val line = layout.getLineForOffset(offset)
        val x = layout.getPrimaryHorizontal(offset) + compoundPaddingLeft
        val baseline = (layout.getLineBaseline(line) + extendedPaddingTop).toFloat()
        val under = if (offset < length()) text!!.substring(offset, offset + 1) else " "
        val cursorPaint = paint
        val oldColor = cursorPaint.color
        // A reverse-video cell: a full block, then the character under it in the background colour.
        cursorPaint.color = fg
        canvas.drawText("█", x, baseline, cursorPaint)
        cursorPaint.color = bg
        canvas.drawText(under, x, baseline, cursorPaint)
        cursorPaint.color = oldColor
    }

    private companion object {
        const val BLINK_MS = 530L
    }
}
