package com.newhost.qwertylauncher.feature.weather

import android.view.KeyEvent
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.grid
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * `weather` as a full-screen pane: a full report for here and each added
 * city. Each place opens like its home tile, the big weather art with its
 * face beside the headline and every fact with its icon; then the next 24
 * hours as a graph (temperature over chance of rain, the hours along the
 * bottom) with a warning when rain is near, and the week, one line a day
 * with a small picture of its weather and its range drawn as a bar.
 * ```
 * ── Berlin, Germany (on home) ─────
 *   [big art]  12°C partly cloudy
 *              [icon] rain from 15:00 70%   [icon] high 15° low 8°
 *   [icon] 24 hours  8° to 15°  up to 70% rain
 *    15° ▃▄▆▇██▇▆▄▃▂▁▁▂▃▄
 *     8° ████████████████
 *   100%      ▂▅█▇▅▂
 *     0% ▁▁▁▁▁████████▁▁▁
 *        now   15    18    21
 * ── Berlin, Germany: 7 days ───────
 *   [art] mon   8° ───━━━━━━───  15°  40%
 * ```
 */
internal class WeatherPane(
    private val places: Places,
    private val forecasts: Forecasts,
    private val repository: WeatherRepository,
    private val enabler: WeatherEnabler,
    private val weatherArt: WeatherArt,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : ListPane {
    override val title = "weather"
    override val keysHelp = "⏎ show on home  d remove  r refresh  q back"

    override fun rows(): Flow<List<PaneRow>> = combine(
        places.cities,
        places.pinned,
        forecasts.states,
        settings.observe(WeatherSettings.units),
        // The warning settings change what the report says.
        combine(settings.observe(WeatherSettings.rainWarning), settings.observe(WeatherSettings.rainChance)) { on, chance -> on to chance },
    ) { cities, pinned, states, units, _ ->
        val imperial = units == "imperial"
        buildList {
            addAll(place(Forecasts.HERE, "here", pinned == null, states[Forecasts.HERE], imperial))
            cities.forEach { addAll(place(it.id, it.fullName, pinned == it.id, states[it.id], imperial)) }
            add(PaneRow.Header("cities"))
            add(
                PaneRow.Item(
                    ADD,
                    "add a city",
                    status = "⏎ types: weather add <city>",
                ),
            )
        }
    }.onStart { scope.launch { forecasts.refresh() } }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id == ADD) {
            host.setQuery("weather add ")
            return true
        }
        val key = item.id.substringBefore(':')
        if (key == Forecasts.HERE && item.id.endsWith(":$NEEDS_LOCATION")) {
            enabler.enable(host)
            return false
        }
        places.pin(key.takeUnless { it == Forecasts.HERE })
        scope.launch { repository.refresh(force = true) }
        return false
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                val key = item?.id?.substringBefore(':') ?: return true
                val city = places.cities.value.firstOrNull { it.id == key } ?: return true
                val wasPinned = places.pinned.value == key
                places.remove(key)
                if (wasPinned) scope.launch { repository.refresh(force = true) }
                host.console.println("Removed ${city.fullName} from the weather.")
            }
            KeyEvent.KEYCODE_R -> scope.launch {
                forecasts.refresh(force = true)
                repository.refresh(force = true)
            }
            else -> return false
        }
        return true
    }

    /** The place's picture with the headline and every fact: the same grid as its home tile. */
    private fun hero(forecast: Forecast, imperial: Boolean, warning: String?): PaneRow.Block {
        val now = forecast.now
        val headline = "%.0f%s %s".format(WeatherFormat.temp(now.tempC, imperial), unit(imperial), WeatherFormat.label(now.code))
        val facts = weatherArt.facts(Conditions.of(forecast), imperial, warning)
        // Every fact shows here: the pane has room for them all.
        return PaneRow.Block(
            weatherArt.layout(
                now.code,
                0,
                headline,
                facts,
                factRows = facts.size,
                // The hours get their own, bigger graph below.
                imperial = imperial,
            ),
        )
    }

    /** One place's two sections, or a line saying why there's no report yet. */
    private suspend fun place(key: String, name: String, onHome: Boolean, state: ForecastState?, imperial: Boolean): List<PaneRow> =
        buildList {
            val pinStatus = if (onHome) "on the home tile" else "⏎ show on the home tile"
            add(PaneRow.Header(if (onHome) "$name (on home)" else name))
            when (state) {
                null, ForecastState.Loading -> add(PaneRow.Item("$key:loading", "loading...", status = pinStatus))
                is ForecastState.Failed -> {
                    val needsLocation = key == Forecasts.HERE && "location" in state.message
                    val id = if (needsLocation) "$key:$NEEDS_LOCATION" else "$key:failed"
                    add(PaneRow.Item(id, state.message, status = if (needsLocation) "⏎ allow location" else pinStatus))
                }
                is ForecastState.Ready -> {
                    val outlook = Outlook.of(state.forecast)
                    val warning = WeatherSettings.warning(settings, outlook, state.forecast.now.code)
                    add(hero(state.forecast, imperial, warning))
                    addAll(now(key, state.forecast, outlook, warning, imperial, pinStatus))
                    add(PaneRow.Header("$name: 7 days"))
                    addAll(week(key, state.forecast, imperial))
                }
            }
        }

    /**
     * The next 24 hours: the warning, if rain is near, a line summing them
     * up, then the graph. Enter on either line shows the place on home.
     */
    private suspend fun now(
        key: String,
        forecast: Forecast,
        outlook: Outlook,
        warning: String?,
        imperial: Boolean,
        pinStatus: String,
    ): List<PaneRow> {
        if (outlook.isEmpty) {
            return listOf(PaneRow.Item("$key:now", "no hourly forecast", status = pinStatus))
        }
        val from = forecast.hours.first().time.format(DateTimeFormatter.ofPattern("HH:mm"))
        val low = deg(outlook.tempC.min(), imperial)
        val high = deg(outlook.tempC.max(), imperial)
        val wettest = outlook.rain.max()
        return buildList {
            if (warning != null) {
                add(
                    PaneRow.Item(
                        "$key:warning",
                        warning,
                        status = "Looking ${Outlook.WINDOW} hours ahead. Set when it warns in config under weather. $pinStatus",
                        icon = weatherArt.icon(FactIconDrawable.Kind.RAIN),
                    ),
                )
            }
            add(
                PaneRow.Item(
                    "$key:hours",
                    label("24 hours") + "$low° to $high°  up to $wettest% rain",
                    status = "From $from: temperature on top, chance of rain below, the hours along the bottom. $pinStatus",
                    icon = weatherArt.icon(FactIconDrawable.Kind.HIGH_LOW),
                ),
            )
            add(
                PaneRow.Block(
                    grid {
                        rows(auto(), auto())
                        add(outlook.temperatureChart(GRAPH_TEMP_LINES, imperial), row = 0)
                        add(outlook.rainChart(GRAPH_RAIN_LINES, top = "100%", bottom = "0%", axis = true), row = 1)
                    },
                ),
            )
        }
    }

    /** One line a day; each day's range sits on a bar spanning the whole week's lowest to highest. */
    private suspend fun week(key: String, forecast: Forecast, imperial: Boolean): List<PaneRow.Item> {
        val low = forecast.days.minOfOrNull { it.minC } ?: return emptyList()
        val high = forecast.days.maxOf { it.maxC }
        return forecast.days.mapIndexed { i, day ->
            val picture = weatherArt.small(day.code)
            val name = if (i == 0) "today" else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase()
            val full = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            PaneRow.Item(
                "$key:day:${day.date}",
                "%-5s %3s° %s %3s°  %3d%%".format(
                    name,
                    deg(day.minC, imperial),
                    range(day.minC, day.maxC, low, high),
                    deg(day.maxC, imperial),
                    day.rainChance,
                ),
                detail = WeatherFormat.label(day.code),
                status = "$full ${day.date.dayOfMonth}: ${WeatherFormat.label(day.code)}, " +
                    "${deg(day.minC, imperial)}° to ${deg(day.maxC, imperial)}°, ${day.rainChance}% chance of rain, " +
                    "UV %.0f.".format(day.uv),
                icon = picture,
            )
        }
    }

    /** `───━━━━━━───`: the day's low to high, on a scale from the week's low to high. */
    private fun range(min: Double, max: Double, low: Double, high: Double): String {
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val start = ((min - low) / span * (RANGE - 1)).roundToInt()
        val end = ((max - low) / span * (RANGE - 1)).roundToInt()
        return (0 until RANGE).joinToString("") { if (it in start..end) "━" else "─" }
    }

    private fun label(text: String) = text.padEnd(LABEL)

    private fun deg(celsius: Double, imperial: Boolean) = "%.0f".format(WeatherFormat.temp(celsius, imperial))

    private fun unit(imperial: Boolean) = if (imperial) "°F" else "°C"

    private companion object {
        const val ADD = "add"
        const val NEEDS_LOCATION = "location"
        const val LABEL = 9
        const val RANGE = 12
        const val GRAPH_TEMP_LINES = 6
        const val GRAPH_RAIN_LINES = 3
    }
}
