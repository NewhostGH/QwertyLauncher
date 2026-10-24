package com.newhost.qwertylauncher.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.newhost.qwertylauncher.core.Console
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.shell.ModuleRegistry
import com.newhost.qwertylauncher.shell.SearchEngine
import com.newhost.qwertylauncher.shell.Tile
import com.newhost.qwertylauncher.ui.PaneState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the home cursor was, by tile id (tiles can move): a tile or the wrench, or a panel's own selection. */
sealed interface HomeSelection {
    data class Stop(val id: String) : HomeSelection
    data class Panel(val id: String, val key: String) : HomeSelection
}

/** Full-screen panes: the shell's config and help, and one opened by a module. */
enum class Pane { CONFIG, HELP, MODULE }

data class HomeUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val selected: Int = 0,
    val console: CharSequence = "",
    /** Opened by touch with nothing typed yet. */
    val promptPinned: Boolean = false,
    /** A full-screen pane (config, help) shown in place of the home content. */
    val pane: Pane? = null,
    /**
     * Few suggestions, so the console shows: once a command has printed
     * something, or while walking the command history. Until the prompt closes.
     */
    val compact: Boolean = false,
) {
    /** The suggestions on show: all of them, or the first few when [compact]. */
    val shownResults: List<SearchResult> get() = if (compact) results.take(COMPACT_RESULTS) else results

    val configOpen: Boolean get() = pane == Pane.CONFIG
    val searching: Boolean get() = query.isNotBlank()

    /**
     * Opened by a key press, the prompt closes again once the query is empty;
     * opened by touch, it stays open until closed with Esc/Back.
     */
    val promptOpen: Boolean get() = promptPinned || query.isNotEmpty()

    companion object {
        const val COMPACT_RESULTS = 2
    }
}

class HomeViewModel(private val registry: ModuleRegistry) : ViewModel() {

    private val engine = SearchEngine({ registry.searchProviders }, registry.log)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Read on every use: the lists change when modules are switched on or off.
    val tiles: List<Tile> get() = registry.tiles

    // Pane cursors live here so they survive rotation and module rebuilds.
    val configState = PaneState()
    val helpState = PaneState()
    val modulePaneState = PaneState()

    /** The home cursor, kept while home is out of view (an app, a pane, search) and across rebuilds. */
    var homeSelection: HomeSelection? = null

    /** The module pane for [Pane.MODULE]; kept here so it survives rotation. */
    var modulePane: ListPane? = null
        private set

    fun openModulePane(pane: ListPane) {
        modulePane = pane
        openPane(Pane.MODULE)
    }

    /** Lives here, not in the activity, so output survives configuration changes. */
    /** When a command was last run: output soon after is its answer. */
    private var ranAt = 0L

    /**
     * Output from a command just run, or while the prompt is open, is there
     * to be read: fewer suggestions in its way. A command that closed the
     * prompt before printing (most do) gets it opened again on its answer,
     * rather than leaving it to flash on the home screen. Not over a pane
     * the command opened.
     */
    val console: Console = ConsoleBuffer { text ->
        val answer = text.isNotEmpty() && System.currentTimeMillis() - ranAt < OUTPUT_WINDOW_MS
        _state.update {
            it.copy(
                console = text,
                promptPinned = it.promptPinned || (answer && it.pane == null),
                compact = it.compact || answer || (text.isNotEmpty() && it.promptOpen),
            )
        }
    }

    /** A command (or any result) was just run. */
    fun ran() {
        ranAt = System.currentTimeMillis()
    }

    /** Fewer suggestions until the prompt closes (walking the command history). */
    fun compactResults() = _state.update { it.copy(compact = true) }

    /** [generation] lets [rerunSearch] repeat an unchanged query. */
    private data class SearchRequest(val text: String, val generation: Int)

    private val requests = MutableStateFlow(SearchRequest("", 0))

    init {
        viewModelScope.launch {
            requests.collectLatest { request ->
                if (request.text.isBlank()) {
                    _state.update { it.copy(results = emptyList(), selected = 0) }
                    return@collectLatest
                }
                delay(SEARCH_DEBOUNCE_MS)
                val results = engine.search(Query(request.text))
                _state.update { it.copy(results = results, selected = 0) }
            }
        }
    }

    fun onQueryChanged(text: String) {
        if (text == _state.value.query) return
        _state.update { it.copy(query = text) }
        requests.update { it.copy(text = text) }
    }

    fun openPrompt() = _state.update { it.copy(promptPinned = true) }

    fun openPane(pane: Pane) {
        closePrompt()
        _state.update { it.copy(pane = pane) }
    }

    fun closePane() = _state.update { it.copy(pane = null) }

    fun closePrompt() {
        _state.update { it.copy(promptPinned = false, compact = false) }
        onQueryChanged("")
    }

    fun rerunSearch() = requests.update { it.copy(generation = it.generation + 1) }

    fun moveSelection(delta: Int) = _state.update { s ->
        if (s.shownResults.isEmpty()) s else s.copy(selected = (s.selected + delta).coerceIn(0, s.shownResults.lastIndex))
    }

    fun select(index: Int) = _state.update { s ->
        if (index in s.shownResults.indices) s.copy(selected = index) else s
    }

    fun selectedResult(): SearchResult? = _state.value.let { it.shownResults.getOrNull(it.selected) }

    class Factory(private val registry: ModuleRegistry) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(registry) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 40L
        const val OUTPUT_WINDOW_MS = 3_000L
    }
}
