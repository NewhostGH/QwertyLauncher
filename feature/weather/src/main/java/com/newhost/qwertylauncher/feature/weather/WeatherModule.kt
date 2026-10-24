package com.newhost.qwertylauncher.feature.weather

import android.Manifest
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.find
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONException

/**
 * Weather from Open-Meteo (no API key): a home tile for here (the device's
 * coarse location) or a pinned city, and a full report for every place in
 * the `weather` pane.
 */
class WeatherModule : LauncherModule {
    override val id = "weather"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*WeatherSettings.all)
        val places = Places(scope.appContext)
        val repository = WeatherRepository(scope.appContext, scope.settings, places, scope.log)
        val forecasts = Forecasts(places, repository, scope.log)
        val enabler = WeatherEnabler(repository, forecasts, scope.appScope)
        val art = WeatherArt(scope.services)
        val pane = WeatherPane(places, forecasts, repository, enabler, art, scope.settings, scope.appScope)
        scope.widget(WeatherWidget(repository, enabler, pane, art, scope.services, scope.settings))
        scope.command(WeatherCommand(repository, places, forecasts, pane, scope.settings, scope.appScope))
        // Tells whoever cares (Sir Alex, sheltering from the rain) what the sky is doing.
        scope.appScope.launch {
            repository.state.collect { state ->
                if (state !is WeatherState.Ready) return@collect
                val happenings = scope.services.find<Happenings>() ?: return@collect
                happenings.post(SKY, sky(state.code))
                happenings.post(CELSIUS, "%.1f".format(java.util.Locale.US, state.tempC))
            }
        }
    }

    private companion object {
        const val SKY = "weather.sky"
        const val CELSIUS = "weather.celsius"

        /** A WMO weather code, as one word: clear, cloudy, fog, rain, snow or storm. */
        fun sky(code: Int): String = when (code) {
            0 -> "clear"
            1, 2, 3 -> "cloudy"
            45, 48 -> "fog"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "storm"
            in 51..67, in 80..82 -> "rain"
            else -> "cloudy"
        }
    }
}

/** Asks for location if needed, then forces a refresh. */
internal class WeatherEnabler(
    private val repository: WeatherRepository,
    private val forecasts: Forecasts,
    private val scope: CoroutineScope,
) {
    fun enable(host: LauncherHost) {
        val console = host.console
        host.requestPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { granted ->
            if (granted) {
                scope.launch {
                    repository.refresh(force = true)
                    forecasts.refresh(force = true)
                }
            } else {
                console.println("weather: needs approximate location")
            }
        }
    }
}

internal class WeatherWidget(
    private val repository: WeatherRepository,
    private val enabler: WeatherEnabler,
    private val pane: WeatherPane,
    private val weatherArt: WeatherArt,
    private val services: Services,
    private val settings: SettingsStore,
) : HomeWidget {
    override val id = "weather"
    override val order = 20
    override val size = TileSize.WIDE

    /** Below this the headline and a fact no longer fit. */
    override val minColumns = MIN_COLUMNS
    override val minLines = MIN_LINES

    override fun render(): Flow<WidgetContent> = channelFlow {
        // Refresh only while visible; the repository skips the network while its cache is fresh.
        launch {
            while (true) {
                repository.refresh()
                delay(CHECK_INTERVAL_MS)
            }
        }
        // Re-render on new weather, a change of units, and each frame of the moving art.
        val frames = services.find<ArtAnimator>()?.frames(FRAME_MS) ?: flowOf(0)
        // The warning settings count too: they change what the tile says.
        val warning = combine(settings.observe(WeatherSettings.rainWarning), settings.observe(WeatherSettings.rainChance)) { on, chance ->
            on to chance
        }
        combine(repository.state, settings.observe(WeatherSettings.units), frames, warning) { state, units, frame, _ ->
            Triple(state, units, frame)
        }.collect { (state, units, frame) -> send(content(state, imperial = units == "imperial", frame)) }
    }

    /** The full report; first the location permission, if here is shown and it isn't granted yet. */
    override fun onClick(host: LauncherHost) {
        if (repository.state.value == WeatherState.NeedsPermission) enabler.enable(host) else host.showPane(pane)
    }

    /** Detailed coloured art through the installed renderer, or small text art without one. */
    private suspend fun content(state: WeatherState, imperial: Boolean, frame: Int): WidgetContent {
        val renderer = services.find<IconRenderer>()
        if (state !is WeatherState.Ready || renderer == null) {
            return WidgetContent(WeatherFormat.withTextArt(state, imperial))
        }
        val degrees = WeatherFormat.degrees(imperial)
        val headline = "%.0f%s %s".format(WeatherFormat.temp(state.tempC, imperial), degrees, WeatherFormat.label(state.code)) +
            (state.place?.let { "\nin $it" } ?: "")
        val outlook = Outlook.of(state)
        val facts = weatherArt.facts(Conditions.of(state), imperial, WeatherSettings.warning(settings, outlook, state.code))
        return WidgetContent(
            text = headline,
            layout = weatherArt.layout(
                state.code,
                frame,
                headline,
                facts,
                WeatherArt.SUMMARY_FACTS,
                outlook = outlook,
                imperial = imperial,
            ),
        )
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 5 * 60_000L

        const val MIN_COLUMNS = 18
        const val MIN_LINES = 3

        /** The moving art redraws about four times a second: enough to see rain fall. */
        const val FRAME_MS = 250L
    }
}

/**
 * `weather` opens the full report; `weather add <city>` finds a city by name
 * and adds it; `weather rm <city>` removes one.
 */
internal class WeatherCommand(
    private val repository: WeatherRepository,
    private val places: Places,
    private val forecasts: Forecasts,
    private val pane: WeatherPane,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : Command {
    override val name = "weather"
    override val aliases = listOf("wx")
    override val usage = "weather [add|rm <city>]"
    override val summary = "the full weather report, and your cities"

    override suspend fun suggest(args: String): List<SearchResult> {
        val verb = args.substringBefore(' ').lowercase()
        val rest = args.substringAfter(' ', "").trim()
        return when {
            verb == "add" -> add(rest)
            verb == "rm" -> remove(rest)
            else -> buildList {
                val imperial = settings[WeatherSettings.units] == "imperial"
                add(
                    SearchResult(
                        key = "weather",
                        tag = "wx",
                        title = "weather",
                        detail = WeatherFormat.oneLine(repository.state.value, imperial),
                        action = { host ->
                            host.clearQuery()
                            host.showPane(pane)
                        },
                    ),
                )
                for (sub in listOf("add", "rm")) {
                    if (verb.isNotEmpty() && sub.startsWith(verb)) {
                        add(
                            SearchResult(
                                key = "weather:$sub",
                                tag = "wx",
                                title = "weather $sub <city>",
                                completion = "weather $sub ",
                                action = { host -> host.setQuery("weather $sub ") },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Cities matching [name] from the place search; picking one adds it. */
    private suspend fun add(name: String): List<SearchResult> {
        if (name.length < MIN_SEARCH) return emptyList()
        val found = try {
            Geocoder.search(name)
        } catch (e: IOException) {
            return listOf(SearchResult(key = "weather:add:offline", tag = "wx", title = "can't reach the place search", action = {}))
        } catch (e: JSONException) {
            return emptyList()
        }
        return found.mapIndexed { i, place ->
            SearchResult(
                key = "weather:add:${place.id}",
                tag = "wx",
                title = "add ${place.fullName}",
                detail = "%.2f, %.2f".format(place.latitude, place.longitude),
                // The place search's own order: the most likely city first.
                score = Score.EXACT - i,
                action = { host ->
                    places.add(place)
                    host.clearQuery()
                    host.console.println("Added ${place.fullName} to the weather.")
                    scope.launch { forecasts.refresh() }
                },
            )
        }
    }

    private fun remove(name: String): List<SearchResult> =
        places.cities.value
            .filter { name.isEmpty() || Fuzzy.score(name, it.fullName) > 0 }
            .map { place ->
                SearchResult(
                    key = "weather:rm:${place.id}",
                    tag = "wx",
                    title = "remove ${place.fullName}",
                    action = { host ->
                        val wasPinned = places.pinned.value == place.id
                        places.remove(place.id)
                        if (wasPinned) scope.launch { repository.refresh(force = true) }
                        host.clearQuery()
                        host.console.println("Removed ${place.fullName} from the weather.")
                    },
                )
            }

    private companion object {
        const val MIN_SEARCH = 2
    }
}
