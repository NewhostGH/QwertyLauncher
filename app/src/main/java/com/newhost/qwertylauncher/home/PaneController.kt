package com.newhost.qwertylauncher.home

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.newhost.qwertylauncher.core.HomeScreen
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.help.HelpScreen
import com.newhost.qwertylauncher.settings.ConfigScreen
import com.newhost.qwertylauncher.settings.MultiChoicePane
import com.newhost.qwertylauncher.shell.ShellSettings

/**
 * What takes home's place: the full-screen panes (config, help, a module's
 * list pane) and a module's own screen over everything (a game). Leaving a
 * pane is also when changes to home's layout are applied.
 */
internal class PaneController(private val activity: HomeActivity) {
    private val binding get() = activity.binding
    private val viewModel get() = activity.viewModel
    private val registry get() = activity.registry

    private lateinit var configScreen: ConfigScreen
    private lateinit var helpScreen: HelpScreen
    private lateinit var modulePaneScreen: ListPaneScreen

    /** The module screen showing over everything, if one is. */
    var screen: HomeScreen? = null
        private set

    /** What home was built with (modules, tiles, layout settings); a change means rebuilding it. */
    private lateinit var builtWith: String

    private val open: Pane? get() = viewModel.state.value.pane

    /** A value is being edited in config, which sideways drags slide. */
    val isEditing: Boolean get() = open == Pane.CONFIG && configScreen.isEditing

    /** The full-screen panes: config (the wrench) and help (the `help` command). */
    fun setup() {
        val settings = activity.settings
        // Frames first: each screen puts its title into its frame as it's made.
        val paneStyle = frameStyle(settings[ShellSettings.paneFrames])
        for (pane in listOf(binding.config, binding.help, binding.modulePane)) {
            pane.root.style = paneStyle
            pane.root.active = true
        }
        configScreen = ConfigScreen(
            binding.config,
            settings,
            { registry.sections },
            viewModel.configState,
            // A multiple choice opens as a list to tick; esc there comes back here.
            onPick = { setting -> showPane(MultiChoicePane(setting, settings) { viewModel.openPane(Pane.CONFIG) }) },
        ) { close() }
        helpScreen = HelpScreen(
            binding = binding.help,
            commands = { registry.commands },
            state = viewModel.helpState,
            onPick = { command ->
                close()
                activity.setQuery("${command.name} ")
            },
            onClose = { close() },
        )
        modulePaneScreen = ListPaneScreen(binding.modulePane, viewModel.modulePaneState, activity, activity, activity.gridEnv) { close() }
        activity.whileStarted {
            settings.snapshot.collect { configScreen.refresh() }
        }
    }

    /** Leaving a pane applies what was changed in config: the screen is rebuilt if the layout changed. */
    fun close() {
        viewModel.closePane()
        if (registry.homeLayoutKey() != builtWith) activity.recreate()
    }

    /**
     * Tiles and panels are built once, so rebuild the screen when a module is
     * switched on or off, or the tiles or font change; but not while config is
     * open: that happens on close.
     */
    fun rebuildWhenLayoutChanges() {
        builtWith = registry.homeLayoutKey()
        activity.whileStarted {
            registry.homeLayoutChanges.collect {
                if (it != builtWith && open == null) activity.recreate()
            }
        }
    }

    /** Shows [pane] (or none), hiding the others. */
    fun render(pane: Pane?) {
        toggle(binding.config.root, pane == Pane.CONFIG) { configScreen.show() }
        toggle(binding.help.root, pane == Pane.HELP) { helpScreen.show() }
        toggle(binding.modulePane.root, pane == Pane.MODULE, onHidden = { modulePaneScreen.hide() }) {
            viewModel.modulePane?.let(modulePaneScreen::show)
        }
    }

    /** Shows or hides a pane, running [onShown] when it appears and [onHidden] when it goes. */
    private inline fun toggle(view: View, visible: Boolean, onHidden: () -> Unit = {}, onShown: () -> Unit) {
        val wasVisible = view.isVisible
        view.isVisible = visible
        if (visible && !wasVisible) onShown()
        if (!visible && wasVisible) onHidden()
    }

    fun showPane(pane: ListPane) {
        activity.menu.close()
        val replacing = open == Pane.MODULE
        viewModel.openModulePane(pane)
        // Already on screen, so render() won't re-show it: swap the content directly.
        if (replacing) modulePaneScreen.show(pane)
    }

    fun showScreen(screen: HomeScreen) {
        closeScreen()
        activity.menu.close()
        this.screen = screen
        val overlay = binding.screenOverlay
        overlay.removeAllViews()
        overlay.addView(screen.createView(activity, overlay), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.isVisible = true
        overlay.requestFocus()
        activity.keyboard().hide(WindowInsetsCompat.Type.ime())
    }

    fun closeScreen() {
        val shown = screen ?: return
        screen = null
        binding.screenOverlay.removeAllViews()
        binding.screenOverlay.isVisible = false
        shown.onClose()
    }

    /** Esc on a module's screen: it may take it (a game's own menu), else the screen closes. */
    fun backFromScreen() {
        if (screen?.onBack() != true) closeScreen()
    }

    /**
     * Esc in a pane: a module's pane may step back to a parent pane, config steps back a
     * level (search, a section), and otherwise the pane closes. False when none is open.
     */
    fun back(): Boolean {
        when {
            open == Pane.MODULE -> modulePaneScreen.back()
            open == Pane.CONFIG && configScreen.back() -> Unit
            open != null -> close()
            else -> return false
        }
        return true
    }

    /** The open pane's keys; null when no pane is open. */
    fun onKey(event: KeyEvent): Boolean? = when (open) {
        Pane.CONFIG -> configScreen.onKey(event)
        Pane.HELP -> helpScreen.onKey(event)
        Pane.MODULE -> modulePaneScreen.onKey(event)
        null -> null
    }

    /** Scrolls the open pane; false when no pane is open. */
    fun scroll(down: Int, right: Int): Boolean {
        when (open) {
            Pane.CONFIG -> configScreen.scroll(down, right)
            Pane.HELP -> helpScreen.move(down)
            Pane.MODULE -> modulePaneScreen.move(down)
            null -> return false
        }
        return true
    }

    /** The height of one of the open pane's rows, in pixels; null when no pane is open. */
    fun rowHeight(): Int? = when (open) {
        Pane.CONFIG -> configScreen.rowHeight()
        Pane.HELP -> helpScreen.rowHeight()
        Pane.MODULE -> modulePaneScreen.rowHeight()
        null -> null
    }
}
