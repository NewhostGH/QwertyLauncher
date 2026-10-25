package com.newhost.qwertylauncher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate

/**
 * Keeps a list's own cursor (the reverse-video line the keys move) and a
 * screen reader's focus on the same row. Without it they're two cursors:
 * the arrows move one silently, and a double tap on a row the reader chose
 * lands on a row the list's cursor isn't on.
 *
 * The reader choosing a row tells [onReaderFocus]; the cursor moving by
 * keys should call [follow] to take the reader along.
 */
internal class ReaderCursor(private val list: RecyclerView, private val onReaderFocus: (position: Int) -> Unit) {

    init {
        list.setAccessibilityDelegateCompat(object : RecyclerViewAccessibilityDelegate(list) {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    val position = list.getChildAdapterPosition(child)
                    if (position != RecyclerView.NO_POSITION) onReaderFocus(position)
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        })
    }

    /** Takes a screen reader, when one's on, to [position]'s row, which it then reads; once the row is laid out. */
    fun follow(position: Int) {
        if (!readerOn(list.context)) return
        list.post {
            val row = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@post
            if (!row.isAccessibilityFocused) row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    companion object {
        /** Whether a screen reader (TalkBack) is on: touching explores rather than presses. */
        fun readerOn(context: Context): Boolean =
            context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
}
