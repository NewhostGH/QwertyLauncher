package com.newhost.qwertylauncher.core

import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A full-screen list a module shows in place of the home screen with
 * [LauncherHost.showPane], like the shell's own config and help panes.
 *
 * The shell draws it as a terminal list with a cursor and a status bar, and
 * handles moving (j/k ↑↓, PgUp/PgDn, g/G, Tab between sections, scrolling),
 * opening (Enter, Space, l →, tap) and closing (q, Esc, Back). Those keys are
 * taken; [onKey] gets the rest.
 */
interface ListPane {
    /** Shown as `~$ title`. */
    val title: String

    /** One line of key help under the status bar. */
    val keysHelp: String

    /** The rows; re-emit whenever they change. The cursor stays on the same [PaneRow.Item.id]. */
    fun rows(): Flow<List<PaneRow>>

    /** The user opened [item]. Return true to close the pane afterwards. */
    fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean

    /** Any other key, with the item under the cursor (null if none). Return true if handled. */
    fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean = false

    /**
     * The pane's input line, when it has one (it may come and go): typed keys
     * go into it, enter hands the text over. While it's there, only arrows,
     * page keys and scrolling move the cursor, since letters are typed.
     */
    fun input(): Flow<PaneInput?> = flowOf(null)

    /**
     * Follows the newest row, like a chat: when rows are added while the
     * cursor is on the last one, it moves to the new last one.
     */
    val followsEnd: Boolean get() = false

    /**
     * Esc, q or back. Return true if handled, e.g. by going back to a parent
     * pane with [LauncherHost.showPane]; false closes the pane.
     */
    fun onBack(host: LauncherHost): Boolean = false
}

/**
 * A line to type into at the bottom of a pane, like a chat box. Enter hands
 * the text to [onSubmit] and clears the line (with nothing typed, enter opens
 * the row under the cursor as usual). [secret] hides what's typed, for keys.
 */
class PaneInput(
    val hint: String,
    val secret: Boolean = false,
    val onSubmit: (text: String, host: LauncherHost) -> Unit,
)

sealed interface PaneRow {
    /** Starts a section; the cursor skips it. */
    data class Header(val title: String) : PaneRow

    /** A block laid out as a grid (see [Grid]), like a widget tile, as wide as the pane. The cursor skips it. */
    data class Block(val layout: Responsive) : PaneRow

    data class Item(
        /** Stable across updates, so the cursor can stay put. */
        val id: String,
        val text: CharSequence,
        /** Dimmed after [text]. */
        val detail: CharSequence? = null,
        /** Up to two lines in the status bar while this item is under the cursor. */
        val status: CharSequence = "",
        /** A small ASCII-art icon before [text], drawn one line tall. */
        val icon: CharSequence? = null,
    ) : PaneRow
}
