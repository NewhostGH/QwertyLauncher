package com.newhost.qwertylauncher.feature.weather

import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Extra
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.Responsive
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.tile

/** What the facts are made from, from the tile's reading or the pane's full report. Metric. */
internal data class Conditions(
    val maxC: Double,
    val minC: Double,
    val windKmh: Double,
    val windFrom: Int?,
    val rainChance: Int?,
    val feelsC: Double?,
    val humidity: Int?,
    val sunrise: String?,
    val sunset: String?,
    val uv: Double?,
    val pressureHpa: Double?,
) {
    companion object {
        fun of(state: WeatherState.Ready) = Conditions(
            state.maxC, state.minC, state.windKmh, state.windFrom, state.rainChance, state.feelsC,
            state.humidity, state.sunrise, state.sunset, state.uv, state.pressureHpa,
        )

        fun of(forecast: Forecast): Conditions {
            val now = forecast.now
            val today = forecast.days.firstOrNull()
            return Conditions(
                maxC = today?.maxC ?: now.tempC,
                minC = today?.minC ?: now.tempC,
                windKmh = now.windKmh,
                windFrom = now.windFrom,
                rainChance = today?.rainChance,
                feelsC = now.feelsC,
                humidity = now.humidity,
                sunrise = today?.sunrise?.toLocalTime()?.toString(),
                sunset = today?.sunset?.toLocalTime()?.toString(),
                uv = today?.uv,
                pressureHpa = now.pressureHpa,
            )
        }
    }
}

/**
 * The weather's look, the same on the tile and in the pane: a grid with the
 * big picture (with its face) beside the headline and the facts when there's
 * room, stacked when there isn't; the shell fits it to the space. Also the
 * small pictures for the pane's rows.
 */
internal class WeatherArt(private val services: Services) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CharSequence>()

    /**
     * The layout: [frame] moves the picture (0 is still); [factRows] rows of
     * facts at an automatic height (all the room there is at a set one).
     * The [outlook], the next hours, becomes charts with the hours under
     * them when the tile is big enough.
     */
    fun layout(
        code: Int,
        frame: Int,
        headline: CharSequence,
        facts: List<Cell>,
        factRows: Int,
        outlook: Outlook? = null,
        imperial: Boolean = false,
    ): Responsive {
        val sky = WeatherFormat.sky(code)
        val picture = Cell.Art("sky:$sky", Size(BIG_COLUMNS, BIG_LINES), frame = frame) { WeatherIconDrawable(sky, frame) }
        // The next hours, charted when the tile is big enough.
        val charts = if (outlook == null || outlook.tempC.size < MIN_CHART_HOURS) {
            emptyList()
        } else {
            listOf(
                Extra(outlook.temperatureChart(TEMP_CHART_LINES, imperial), TEMP_CHART_LINES),
                // The hours go under the last chart, on a line of their own.
                Extra(outlook.rainChart(RAIN_CHART_LINES, axis = true), RAIN_CHART_LINES + 1, RAIN_CHART_SHARE),
            )
        }
        return tile(picture, headline, facts, factRows = factRows, extras = charts)
    }

    /**
     * Each fact with its icon, most useful first, a [warning] of rain before
     * them all; the grid shows as many as it has room for.
     */
    fun facts(c: Conditions, imperial: Boolean, warning: String? = null): List<Cell> {
        fun t(celsius: Double) = "%.0f°".format(WeatherFormat.temp(celsius, imperial))
        fun icon(kind: FactIconDrawable.Kind, angle: Int = 0) = { FactIconDrawable(kind, angle.toFloat()) }
        // The wind arrow points where the wind blows to: opposite where it comes from.
        val blowsTo = ((c.windFrom ?: 0) + 180) % 360
        return listOfNotNull(
            warning?.let { fact("fact:warning", it, icon(FactIconDrawable.Kind.RAIN)) },
            fact("fact:high", "high ${t(c.maxC)} low ${t(c.minC)}", icon(FactIconDrawable.Kind.HIGH_LOW)),
            fact(
                "fact:wind:$blowsTo",
                WeatherFormat.wind(c.windKmh, imperial) + (c.windFrom?.let { " ${WeatherFormat.compassShort(it)}" } ?: ""),
                icon(FactIconDrawable.Kind.WIND, blowsTo),
            ),
            c.rainChance?.let { fact("fact:rain", "$it% rain", icon(FactIconDrawable.Kind.RAIN)) },
            c.feelsC?.let { fact("fact:feels", "feels ${t(it)}", icon(FactIconDrawable.Kind.FEELS)) },
            c.humidity?.let { fact("fact:humidity", "$it% humid", icon(FactIconDrawable.Kind.HUMIDITY)) },
            c.sunrise?.let { fact("fact:sunrise", "sunrise $it", icon(FactIconDrawable.Kind.SUNRISE)) },
            c.sunset?.let { fact("fact:sunset", "sunset $it", icon(FactIconDrawable.Kind.SUNSET)) },
            c.uv?.let { fact("fact:uv", "UV %.0f".format(it), icon(FactIconDrawable.Kind.UV)) },
            c.pressureHpa?.let { fact("fact:pressure", "%.0f hPa".format(it), icon(FactIconDrawable.Kind.PRESSURE)) },
        )
    }

    /** A small picture of a day's weather for a pane row, in half blocks so it stays crisp. */
    suspend fun small(code: Int): CharSequence? {
        val renderer = services.find<IconRenderer>() ?: return null
        val sky = WeatherFormat.sky(code)
        return cache.getOrPut("small:$sky") { renderer.render(WeatherIconDrawable(sky), ROW_ICON_COLS, ROW_ICON_ROWS, fine = true) }
    }

    /** A fact's icon for a pane row, in half blocks. */
    suspend fun icon(kind: FactIconDrawable.Kind): CharSequence? {
        val renderer = services.find<IconRenderer>() ?: return null
        return cache.getOrPut("icon:$kind") { renderer.render(FactIconDrawable(kind), ROW_ICON_COLS, ROW_ICON_ROWS, fine = true) }
    }

    companion object {
        /** The big picture's own size, in cells: about square on screen. */
        const val BIG_COLUMNS = 10
        const val BIG_LINES = 5

        // Charts of the next hours, when the tile is big.
        const val MIN_CHART_HOURS = 6
        const val TEMP_CHART_LINES = 3
        const val RAIN_CHART_LINES = 2
        const val RAIN_CHART_SHARE = 0.6f

        /** Cold to warm, low to high. */
        val TEMP_COLORS = listOf(0xFF5FAFFF.toInt(), 0xFFFFD75F.toInt(), 0xFFFF5555.toInt())
        val RAIN_COLORS = listOf(0xFF9AD0FF.toInt(), 0xFF4D7FFF.toInt())

        const val SUMMARY_FACTS = 3

        // A pane row's icon, one text line tall: 8 × 8 pixels in half blocks.
        const val ROW_ICON_COLS = 8
        const val ROW_ICON_ROWS = 4
    }
}
