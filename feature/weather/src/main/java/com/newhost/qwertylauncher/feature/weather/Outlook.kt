package com.newhost.qwertylauncher.feature.weather

import com.newhost.qwertylauncher.core.Cell
import kotlin.math.min

/**
 * The next hours, from the hour we're in: temperature, chance of rain and
 * the weather code of each, and the place's hour of day of the first
 * ([firstHour], null if unknown). Charted on the tile and in the pane, and
 * watched for rain.
 */
internal class Outlook(val tempC: List<Double>, val rain: List<Int>, val codes: List<Int>, val firstHour: Int?) {

    val isEmpty get() = tempC.isEmpty()

    /**
     * A warning when rain (or snow, or storms) is near: `rain from 15:00 70%`
     * when it's dry now and an hour within the next [WINDOW] has at least
     * [threshold] percent chance; `rain until 17:00` while it's raining.
     */
    fun warning(nowCode: Int, threshold: Int): String? {
        val window = min(rain.size, WINDOW)
        if (window == 0) return null
        if (WeatherFormat.sky(nowCode) in WET) {
            val word = word(nowCode)
            val dry = (1 until window).firstOrNull { rain[it] < threshold } ?: return "$word for ${window}h or more"
            return "$word ${until(dry)}"
        }
        val start = (0 until window).firstOrNull { rain[it] >= threshold } ?: return null
        val peak = rain.subList(start, min(window, start + PEAK_HOURS)).max()
        val word = word(codes.getOrNull(start))
        return if (start == 0) "$word likely now $peak%" else "$word ${from(start)} $peak%"
    }

    /** Under the hours: `now`, then every third hour of the day (`15`, `18`). */
    fun axis(): List<String> = List(tempC.size) { i ->
        when {
            i == 0 -> "now"
            firstHour == null -> if (i % AXIS_STEP == 0) "+$i" else ""
            (firstHour + i) % AXIS_STEP == 0 -> "%02d".format((firstHour + i) % HOURS_A_DAY)
            else -> ""
        }
    }

    /** Temperature bars, cold blue to hot red, the range on the left. */
    fun temperatureChart(lines: Int, imperial: Boolean, axis: Boolean = false): Cell.Chart {
        val shown = tempC.map { WeatherFormat.temp(it, imperial) }
        return Cell.Chart(
            shown,
            lines = lines,
            colors = WeatherArt.TEMP_COLORS,
            labels = pad("%.0f°".format(shown.max())) to pad("%.0f°".format(shown.min())),
            axis = if (axis) axis() else emptyList(),
        )
    }

    /** Chance-of-rain bars from 0 to 100%, labelled [top] and [bottom] (by default `rain` and the highest chance). */
    fun rainChart(
        lines: Int,
        top: String = "rain",
        bottom: String = "${rain.maxOrNull() ?: 0}%",
        axis: Boolean = false,
    ): Cell.Chart = Cell.Chart(
        rain.map { it.toDouble() },
        lines = lines,
        low = 0.0,
        high = 100.0,
        colors = WeatherArt.RAIN_COLORS,
        labels = pad(top) to pad(bottom),
        axis = if (axis) axis() else emptyList(),
    )

    /** `from 15:00`, or `in 3h` without the place's time. */
    private fun from(hours: Int) = at(hours)?.let { "from $it" } ?: "in ${hours}h"

    /** `until 15:00`, or `for 3h` without the place's time. */
    private fun until(hours: Int) = at(hours)?.let { "until $it" } ?: "for ${hours}h"

    /** `15:00`, [hours] from the first; null without the place's time. */
    private fun at(hours: Int) = firstHour?.let { "%02d:00".format((it + hours) % HOURS_A_DAY) }

    private fun word(code: Int?): String = when (code?.let { WeatherFormat.sky(it) }) {
        Sky.SNOW -> "snow"
        Sky.STORM -> "storms"
        else -> "rain"
    }

    companion object {
        /** How far ahead a warning looks, in hours. */
        const val WINDOW = 12
        private const val PEAK_HOURS = 3
        private const val AXIS_STEP = 3
        private const val HOURS_A_DAY = 24
        private const val HOUR_MS = 3_600_000L

        /** Chart labels all this wide, so stacked charts' bars line up. */
        private const val LABEL = 4

        private val WET = setOf(Sky.RAIN, Sky.SNOW, Sky.STORM)

        private fun pad(label: String) = label.padStart(LABEL)

        /** The tile's reading, less the hours gone by since it was fetched. */
        fun of(state: WeatherState.Ready, now: Long = System.currentTimeMillis()): Outlook {
            val start = state.hourlyStart
            val gone = if (start == null) 0 else ((now - start) / HOUR_MS).toInt().coerceIn(0, state.hourlyC.size)
            return Outlook(
                state.hourlyC.drop(gone),
                state.hourlyRain.drop(gone),
                state.hourlyCodes.drop(gone),
                state.hourlyStartHour?.let { (it + gone) % HOURS_A_DAY },
            )
        }

        fun of(forecast: Forecast) = Outlook(
            forecast.hours.map { it.tempC },
            forecast.hours.map { it.rainChance },
            forecast.hours.map { it.code },
            forecast.hours.firstOrNull()?.time?.hour,
        )
    }
}
