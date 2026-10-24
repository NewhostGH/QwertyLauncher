package com.newhost.qwertylauncher.feature.toggles

import com.newhost.qwertylauncher.core.BarButton
import com.newhost.qwertylauncher.core.BarStrip
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Quick toggles in the top bar, beside help and config, drawn like them:
 * airplane mode, bluetooth, ring / vibrate / silent and the flashlight by
 * default, and any of twenty or so more, ticked in config. No more can be
 * ticked than fit in the top bar, which the shell works out from the
 * screen. Android lets apps switch only some of these; the others open the
 * system's own switch for them.
 */
class TogglesModule : LauncherModule {
    override val id = "toggles"
    override val title = "quick toggles"

    override fun install(scope: ModuleScope) {
        // Only what this phone has is offered.
        val toggles = allToggles(scope.appContext).filter { it.available }
        val strip = ToggleStrip(scope.settings, toggles)
        scope.declareSettings(strip.shown)
        scope.barStrip(strip)
    }
}

/** The ticked toggles as the top bar's buttons, as many as fit. */
internal class ToggleStrip(private val settings: SettingsStore, private val toggles: List<Toggle>) : BarStrip {
    override val id = "toggles"
    override val title = "toggles"

    /** How many fit in the top bar, once the shell has worked it out. */
    @Volatile private var room: Int? = null

    val shown = MultiChoiceSetting(
        "shown",
        "quick toggles in the top bar",
        toggles.map { Choice(it.id, it.name) },
        default = DEFAULT.filter { id -> toggles.any { it.id == id } },
        summary = "Tick the toggles to show at the top, as many as fit beside help and config. " +
            "Hold enter on one there to move them.",
        limit = { room },
    )

    override fun onRoom(buttons: Int) {
        room = buttons
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun buttons(): Flow<List<BarButton>> = settings.observe(shown).flatMapLatest { ids ->
        val chosen = ids.mapNotNull { id -> toggles.firstOrNull { it.id == id } }
        if (chosen.isEmpty()) flowOf(emptyList()) else combine(chosen.map { t -> t.looks().map { look -> button(t, look) } }) { it.toList() }
    }

    private fun button(toggle: Toggle, look: Look): BarButton {
        val menu = toggle.menu() + listOfNotNull(
            toggle.settingsIntent()?.let { intent -> MenuItem("${toggle.name} settings") { it.startActivitySafely(intent) } },
            MenuItem("hide ${toggle.name}") { settings.set(shown, settings[shown] - toggle.id) },
        )
        return BarButton("toggle:${toggle.id}", look.label, toggle.short, look.icon, menu) { host -> toggle.press(host) }
    }

    private companion object {
        val DEFAULT = listOf("airplane", "bluetooth", "ringer", "flashlight")
    }
}
