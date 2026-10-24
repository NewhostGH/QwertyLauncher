package com.newhost.qwertylauncher.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope

/**
 * What the home screen offers to result actions, widgets and panels.
 *
 * Don't hold on to a host beyond the call you received it in: it is the
 * activity and is recreated on configuration changes.
 */
interface LauncherHost {
    /** Activity context: use it for dialogs, popups and starting activities. */
    val context: Context

    val lifecycleOwner: LifecycleOwner

    /**
     * Outlives configuration changes: use it for work started by a result, such as
     * streaming an answer into the [console]. Don't capture the host in that work.
     */
    val scope: CoroutineScope

    val console: Console

    fun setQuery(text: String)
    fun clearQuery()

    /**
     * Opens the prompt with nothing typed. The [console] only shows while the
     * prompt is open (on the home screen new lines just flash briefly), so
     * call this after printing output the user needs to read.
     */
    fun openPrompt()

    /** Runs the current query again, e.g. after a permission was granted. */
    fun rerunSearch()

    fun requestPermission(permission: String, onResult: (granted: Boolean) -> Unit)

    /**
     * Shows an ASCII menu over the home screen. Keys (↑↓/jk, Enter, digits,
     * Esc), scrolling and taps pick an item; the menu closes before the
     * item's action runs.
     */
    fun showMenu(title: String, items: List<MenuItem>)

    /** Shows [pane] full screen in place of the home screen, until the user closes it. */
    fun showPane(pane: ListPane)

    /** Shows [screen] over everything (the top bar too), with every key, until it's closed. */
    fun showScreen(screen: HomeScreen) {}

    /** Starts [intent] in a new task. Prints an error to the console and returns false on failure. */
    fun startActivitySafely(intent: Intent): Boolean

    /**
     * Options to pass when starting another app's activity yourself: the
     * launcher opens apps instantly, without an animation, like running a command.
     */
    val launchOptions: Bundle? get() = null

    /** The terminal font size in sp. Text a panel draws itself should use it; only ASCII art may differ. */
    val textSizeSp: Float get() = DEFAULT_TEXT_SP

    companion object {
        const val DEFAULT_TEXT_SP = 12f
    }
}

/** One entry of [LauncherHost.showMenu]. */
class MenuItem(val label: String, val action: ResultAction)

/** The scrollback area above the prompt. Main thread only. */
interface Console {
    fun println(text: CharSequence)

    /** Starts a line that can keep growing, e.g. for streamed output. */
    fun newLine(prefix: CharSequence = ""): ConsoleLine

    fun clear()
}

/** A console line that can keep changing after it's printed. */
interface ConsoleLine {
    fun append(text: CharSequence)

    /** Replaces everything after the line's prefix, e.g. to redraw it for an animation. */
    fun set(text: CharSequence)
}
