package com.newhost.qwertylauncher.home

import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.shell.ShellHistory
import com.newhost.qwertylauncher.shell.ShellSettings
import com.newhost.qwertylauncher.ui.InputMonitor
import com.newhost.qwertylauncher.ui.terminalStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The prompt and what belongs to it: typing, the search results above it, the
 * console, history, the idle line with its blinking cursor (and the messages
 * flashed there), and the tmux-style status line.
 */
internal class PromptController(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel
    private val settings get() = activity.settings

    private val history by lazy { ShellHistory(activity) }
    private val host = Build.MODEL.lowercase().replace(' ', '-')

    /** Position in [history] while walking it with up/down; -1 when not. */
    private var historyIndex = -1

    /** The blinking block cursor on the idle prompt line, and whether a message is showing there instead. */
    private var cursorVisible = true
    private var flashing = false

    /** The console text last rendered, to spot new output; and the job restoring the idle line. */
    private var lastConsole: CharSequence? = null
    private var flashJob: Job? = null

    /** Set when the prompt is opened by touch, so the next render also shows the soft keyboard. */
    private var showKeyboardOnOpen = false

    /** Enter went down on a result and hasn't come up: a short press runs it, holding opens its menu. */
    private var enterPending = false

    private val resultsAdapter = ResultsAdapter(
        onClick = { index ->
            viewModel.select(index)
            runSelected()
        },
        onLongClick = { index ->
            viewModel.select(index)
            showResultMenu()
        },
    )

    fun setup() {
        binding.results.layoutManager = LinearLayoutManager(activity).apply { reverseLayout = true }
        binding.results.adapter = resultsAdapter
        binding.results.terminalStyle()

        // Touch users have no key to press: tapping the idle line opens the prompt with the soft keyboard.
        binding.idleHint.setOnClickListener {
            showKeyboardOnOpen = true // before openPrompt(): the render may run synchronously
            viewModel.openPrompt()
        }

        binding.query.doAfterTextChanged {
            // Keyboards that route keys through an input method could still type here while
            // config or a menu is up; ignore that (render() puts the real query back).
            if (viewModel.state.value.pane != null || activity.menu.isOpen) return@doAfterTextChanged
            val text = it?.toString().orEmpty()
            // Editing a recalled line leaves history mode, like a shell.
            if (historyIndex >= 0 && text != history[historyIndex]) historyIndex = -1
            viewModel.onQueryChanged(text)
        }
        // A hardware enter on a result is taken before the keyboard app can turn it into "go",
        // so a short press and a held one can be told apart (see handlePromptEnter).
        binding.query.onPreImeKey = { event -> isEnter(event) && handlePromptEnter(event) }
        // Soft keyboard "Go"; the hardware Enter key is handled in dispatchKeyEvent.
        binding.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runSelected()
                true
            } else {
                false
            }
        }
    }

    /** The login banner, each line decoding itself out of noise (with the ascii art module's animations). */
    fun printBootBanner(version: String) {
        val lines = history.bootBanner(version, host)
        val console = activity.console
        val animator = activity.registry.find<ArtAnimator>()
        if (animator == null) {
            lines.forEach { console.println(it) }
            return
        }
        val printed = lines.map { console.newLine().also { line -> line.set(" ".repeat(it.length)) } }
        viewModel.viewModelScope.launch {
            lines.zip(printed).forEach { (text, line) ->
                launch { animator.decode(text).collect { line.set(it) } }
                delay(BANNER_LINE_MS)
            }
        }
    }

    // --- Rendering ---

    /** The prompt's part of the screen: results, the prompt line itself, and the console when [consoleVisible]. */
    fun render(state: HomeUiState, consoleVisible: Boolean) {
        val paneOpen = state.pane != null
        binding.promptLabel.text = promptText()
        binding.results.isVisible = state.searching && !paneOpen

        if (binding.query.text.toString() != state.query) {
            binding.query.setText(state.query)
            binding.query.setSelection(state.query.length)
        }

        // The prompt only exists while in use, so arrow keys and scrolling reach the home screen otherwise.
        val wasOpen = binding.prompt.isVisible
        // A closed prompt must not keep keyboard focus: input methods get keys before the app
        // does, and would type into it (e.g. a space in config opening search).
        binding.query.isFocusable = state.promptOpen
        binding.query.isFocusableInTouchMode = state.promptOpen
        if (!state.promptOpen && binding.query.hasFocus()) binding.query.clearFocus()
        binding.prompt.isVisible = state.promptOpen
        binding.idleHint.isVisible = !state.promptOpen && !paneOpen
        renderIdleLine()
        if (state.promptOpen && !binding.query.hasFocus()) {
            binding.query.requestFocus()
            if (showKeyboardOnOpen) activity.keyboard().show(WindowInsetsCompat.Type.ime())
        }
        if (!state.promptOpen && wasOpen) activity.keyboard().hide(WindowInsetsCompat.Type.ime())
        showKeyboardOnOpen = false

        val shown = state.shownResults
        resultsAdapter.submit(shown, state.selected)
        if (shown.isNotEmpty()) binding.results.scrollToPosition(state.selected)

        // The console belongs to the prompt: on the home screen new output only flashes in the idle line.
        binding.console.text = state.console
        binding.consoleScroll.isVisible = consoleVisible
        if (binding.consoleScroll.isVisible) {
            binding.consoleScroll.post { binding.consoleScroll.scrollTo(0, binding.console.height) }
        }
        val newOutput = state.console !== lastConsole && state.console.isNotEmpty()
        lastConsole = state.console
        if (newOutput && !binding.consoleScroll.isVisible && !paneOpen) flash(lastLine(state.console))
    }

    private fun promptText(): String = when (settings[ShellSettings.prompt]) {
        "path" -> "~$ "
        "bare" -> "$ "
        else -> "${ShellHistory.USER}@$host:~$ "
    }

    /** The idle line is the prompt with a block cursor, like an empty console line. */
    private fun renderIdleLine() {
        if (flashing) return
        binding.idleHint.text = SpannableStringBuilder().apply {
            append(promptText(), ForegroundColorSpan(activity.getColor(CoreR.color.tty_accent)), SPAN)
            append(if (cursorVisible) "█" else " ", ForegroundColorSpan(activity.getColor(CoreR.color.tty_fg)), SPAN)
        }
    }

    fun blinkCursor() {
        activity.whileStarted {
            while (true) {
                cursorVisible = !cursorVisible || !settings[ShellSettings.cursorBlink]
                renderIdleLine()
                delay(BLINK_MS)
            }
        }
    }

    /**
     * tmux-style: `[tty1] 0:home* 1:config 2:help`, the current pane marked
     * with a star; while moving the top bar or resizing a tile, what's happening instead.
     */
    fun renderStatusLine(pane: Pane? = viewModel.state.value.pane) {
        val arranging = activity.topBar.statusText() ?: activity.tiles.statusText()
        if (arranging != null) {
            binding.hostLabel.text = " [tty1] $arranging"
            return
        }
        // Home first, then the panes of the top bar's buttons in their order along the top.
        val buttonWindows = listOf("help" to (pane == Pane.HELP), "config" to (pane == Pane.CONFIG))
        val windows = listOf("home" to (pane == null)) + buttonWindows +
            listOfNotNull(viewModel.modulePane?.let { it.title to (pane == Pane.MODULE) })
        binding.hostLabel.text = " [tty1] " + windows.withIndex().joinToString(" ") { (i, window) ->
            "$i:${window.first}${if (window.second) "*" else ""}"
        }
    }

    /** Shows [message] in the idle line for a few seconds, then puts the hint back. */
    fun flash(message: CharSequence) {
        flashJob?.cancel()
        flashing = true
        binding.idleHint.text = message
        flashJob = activity.lifecycleScope.launch {
            delay(FLASH_MS)
            flashing = false
            renderIdleLine()
        }
    }

    private fun lastLine(text: CharSequence): CharSequence {
        val start = text.lastIndexOf('\n') + 1
        return text.subSequence(start, text.length)
    }

    // --- Keys ---

    /**
     * The prompt's keys, once nothing else (a menu, a pane, arranging) has taken them:
     * console shortcuts, history, enter, tab and the arrows on results, and type-to-search.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.isCtrlPressed && handleControlKey(event)) return true
        // Up/down on an open, empty prompt (or while already walking it) go through history.
        val promptState = viewModel.state.value
        if (promptState.promptOpen && (promptState.query.isEmpty() || historyIndex >= 0)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> return walkHistory(+1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return walkHistory(-1)
            }
        }
        val searching = viewModel.state.value.searching
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (searching) {
                // Shift or alt with enter opens the menu straight away, for keyboards where holding is awkward.
                if (event.isShiftPressed || event.isAltPressed) {
                    if (event.repeatCount == 0) showResultMenu()
                    return true
                }
                // Decide on key up: a short press runs the result, holding opens its menu.
                if (event.repeatCount == 0) {
                    enterPending = true
                } else if (enterPending) {
                    enterPending = false
                    showResultMenu()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> if (searching) {
                showResultMenu()
                return true
            }
            // Results stack upwards from the prompt, so Up moves to the next result.
            KeyEvent.KEYCODE_DPAD_UP -> if (searching) {
                viewModel.moveSelection(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (searching) {
                viewModel.moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_TAB -> if (searching) {
                viewModel.selectedResult()?.completion?.let(activity::setQuery)
                return true
            }
        }
        // Type-to-search: a printable key pressed anywhere else opens the prompt with that character.
        // A leading space is left alone: on a selected favourite it opens the app, like Enter.
        val char = event.unicodeChar
        val leadingSpace = char == ' '.code && viewModel.state.value.query.isEmpty()
        if (!binding.query.hasFocus() && char > 0 && !Character.isISOControl(char) && !leadingSpace &&
            !event.isCtrlPressed && !event.isMetaPressed
        ) {
            activity.setQuery(viewModel.state.value.query + String(Character.toChars(char)))
            return true
        }
        return false
    }

    /** Enter coming up after it went down on a result: runs it, or opens its menu if it was held. */
    fun onEnterUp(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_UP && isEnter(event) && enterPending && enterReleased(event)

    /** Forgets an enter that went down, e.g. when home is left before it came up. */
    fun cancelEnter() {
        enterPending = false
    }

    /**
     * A short enter on a result runs it when the key comes up. Holding it opens the menu:
     * on the repeats if the keyboard sends them, else here, from how long it was down.
     */
    private fun enterReleased(event: KeyEvent): Boolean {
        enterPending = false
        if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) showResultMenu() else runSelected()
        return true
    }

    /**
     * Enter in the prompt, seen before the keyboard app. Taken only while there
     * are results to act on; otherwise the keyboard app handles it as before.
     */
    private fun handlePromptEnter(event: KeyEvent): Boolean {
        val menu = activity.menu
        // The menu a held enter just opened: it ignores the rest of that press, and the keyboard app
        // mustn't see it either, or it could still turn it into "go".
        if (menu.isOpen) return event.action != KeyEvent.ACTION_DOWN || menu.onKey(event)
        if (viewModel.state.value.pane != null) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (InputMonitor.enabled && event.repeatCount == 0) activity.input.log.d(InputMonitor.describe(event) + " (prompt)")
                viewModel.state.value.searching && activity.input.handleKey(event)
            }
            KeyEvent.ACTION_UP -> enterPending && enterReleased(event)
            else -> false
        }
    }

    /** Holding enter on a search result: its menu (for apps: favourites, rename, info, uninstall). */
    private fun showResultMenu() {
        val result = viewModel.selectedResult() ?: return
        if (result.menu.isEmpty()) return
        activity.showMenu(result.title.toString(), result.menu)
    }

    private fun runSelected() {
        val result = viewModel.selectedResult() ?: return
        history.add(viewModel.state.value.query)
        historyIndex = -1
        viewModel.ran()
        result.action.run(activity)
    }

    /** Ctrl+P / up walks back through history, Ctrl+N / down forward; past the newest the line is empty. */
    private fun walkHistory(direction: Int): Boolean {
        val next = historyIndex + direction
        if (next >= history.size) return true
        historyIndex = next.coerceAtLeast(-1)
        viewModel.openPrompt()
        viewModel.compactResults()
        activity.setQuery(if (historyIndex >= 0) history[historyIndex].orEmpty() else "")
        return true
    }

    /** Console shortcuts: Ctrl+C cancels the line, Ctrl+U clears it, Ctrl+L clears the screen, Ctrl+D closes an empty prompt. */
    private fun handleControlKey(event: KeyEvent): Boolean {
        val state = viewModel.state.value
        return when (event.keyCode) {
            KeyEvent.KEYCODE_C -> {
                historyIndex = -1
                activity.clearQuery()
                true
            }
            KeyEvent.KEYCODE_U -> {
                historyIndex = -1
                viewModel.openPrompt()
                activity.setQuery("")
                true
            }
            KeyEvent.KEYCODE_L -> {
                activity.console.clear()
                true
            }
            KeyEvent.KEYCODE_D -> {
                if (state.query.isEmpty()) activity.clearQuery()
                true
            }
            KeyEvent.KEYCODE_P -> walkHistory(+1)
            KeyEvent.KEYCODE_N -> walkHistory(-1)
            else -> false
        }
    }

    private companion object {
        // The boot banner's lines start decoding one after another.
        const val BANNER_LINE_MS = 90L
        const val BLINK_MS = 530L
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        const val FLASH_MS = 4_000L
    }
}
