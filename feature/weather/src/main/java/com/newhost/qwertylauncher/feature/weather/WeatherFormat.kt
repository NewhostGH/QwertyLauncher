package com.newhost.qwertylauncher.feature.weather

import com.newhost.qwertylauncher.core.Tty

internal enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, STORM }

internal object WeatherFormat {

    private class Condition(val label: String, val sky: Sky)

    /** WMO weather interpretation codes, as returned by Open-Meteo. */
    private fun condition(code: Int): Condition = when (code) {
        0 -> Condition("clear", Sky.CLEAR)
        1 -> Condition("mostly clear", Sky.CLEAR)
        2 -> Condition("partly cloudy", Sky.PARTLY_CLOUDY)
        3 -> Condition("overcast", Sky.CLOUDY)
        45, 48 -> Condition("fog", Sky.FOG)
        in 51..57 -> Condition("drizzle", Sky.RAIN)
        in 61..67 -> Condition("rain", Sky.RAIN)
        in 71..77 -> Condition("snow", Sky.SNOW)
        in 80..82 -> Condition("showers", Sky.RAIN)
        85, 86 -> Condition("snow showers", Sky.SNOW)
        in 95..99 -> Condition("thunderstorm", Sky.STORM)
        else -> Condition("code $code", Sky.CLOUDY)
    }

    fun sky(state: WeatherState.Ready): Sky = condition(state.code).sky

    fun sky(code: Int): Sky = condition(code).sky

    /** "partly cloudy", "rain", ... for a WMO weather code. */
    fun label(code: Int): String = condition(code).label

    /** Temperatures arrive in °C and wind in km/h; convert only for display. */
    fun temp(celsius: Double, imperial: Boolean) = if (imperial) celsius * 9 / 5 + 32 else celsius

    fun degrees(imperial: Boolean) = if (imperial) "°F" else "°C"

    /** Degrees to a direction in words: 0 is north, 90 east. */
    fun compass(degrees: Int): String = COMPASS[((degrees % 360 + 360) % 360 + 22) / 45 % 8]

    /** The same as [compass], in letters: "NW". */
    fun compassShort(degrees: Int): String = COMPASS_SHORT[((degrees % 360 + 360) % 360 + 22) / 45 % 8]

    private val COMPASS = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    private val COMPASS_SHORT = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** "14 km/h" or "9 mph". */
    fun wind(kmh: Double, imperial: Boolean) =
        if (imperial) "%.0f mph".format(kmh / KMH_PER_MPH) else "%.0f km/h".format(kmh)

    /** The text lines shown next to the art. */
    fun details(state: WeatherState, imperial: Boolean): String = when (state) {
        WeatherState.Unknown -> "loading..."
        WeatherState.NeedsPermission -> "Tap here to allow location for the weather."
        is WeatherState.Failed -> state.message
        is WeatherState.Ready -> listOf(
            "%.0f%s  %s".format(temp(state.tempC, imperial), degrees(imperial), condition(state.code).label) +
                // A pinned city says where it is; here needs no name.
                (state.place?.let { "\nin $it" } ?: ""),
            "high %.0f° low %.0f°  ".format(temp(state.maxC, imperial), temp(state.minC, imperial)) +
                if (imperial) "wind %.0f mph".format(state.windKmh / KMH_PER_MPH) else "wind %.0f km/h".format(state.windKmh),
        ).joinToString("\n")
    }

    /** Details with small hand-drawn art: the fallback when no icon renderer is installed. */
    fun withTextArt(state: WeatherState, imperial: Boolean): String =
        if (state is WeatherState.Ready) {
            Tty.sideBySide(textArt(sky(state)), details(state, imperial).lines())
        } else {
            details(state, imperial)
        }

    fun oneLine(state: WeatherState, imperial: Boolean): String = when (state) {
        is WeatherState.Ready ->
            "%.0f%s %s".format(temp(state.tempC, imperial), degrees(imperial), condition(state.code).label)
        else -> details(state, imperial)
    }

    private fun textArt(sky: Sky): List<String> = when (sky) {
        Sky.CLEAR -> listOf("  \\ | /", "  - O -", "  / | \\")
        Sky.PARTLY_CLOUDY, Sky.CLOUDY -> listOf("   .--.", ".-(    ).", "(___.__)_")
        Sky.FOG -> listOf(" _ - _ -", "  _ - _", " _ - _ -")
        Sky.RAIN -> listOf("   .--.", " (___.__)", "  ' ' ' '")
        Sky.SNOW -> listOf("   .--.", " (___.__)", "  * * * *")
        Sky.STORM -> listOf("   .--.", " (___.__)", "   / /")
    }

    private const val KMH_PER_MPH = 1.609344
}
