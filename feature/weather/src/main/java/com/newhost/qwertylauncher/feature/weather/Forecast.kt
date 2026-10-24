package com.newhost.qwertylauncher.feature.weather

import com.newhost.qwertylauncher.core.DebugLog
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** A full report for one place: now, the next 24 hours, and the week. Temperatures in °C, wind in km/h. */
internal data class Forecast(
    val now: Now,
    val hours: List<Hour>,
    val days: List<Day>,
    val fetchedAt: Long,
) {
    data class Now(
        val tempC: Double,
        val feelsC: Double,
        val humidity: Int,
        val code: Int,
        val windKmh: Double,
        /** Where the wind comes from, in degrees. */
        val windFrom: Int,
        val precipitationMm: Double,
        val pressureHpa: Double,
    )

    data class Hour(val time: LocalDateTime, val tempC: Double, val rainChance: Int, val code: Int)

    data class Day(
        val date: LocalDate,
        val code: Int,
        val maxC: Double,
        val minC: Double,
        val rainChance: Int,
        val sunrise: LocalDateTime?,
        val sunset: LocalDateTime?,
        val uv: Double,
    )
}

internal sealed interface ForecastState {
    data object Loading : ForecastState
    data class Failed(val message: String) : ForecastState
    data class Ready(val forecast: Forecast) : ForecastState
}

/**
 * Full reports for here and every added city, for the weather pane. Kept in
 * memory; loaded when the pane opens and again once they're a while old.
 */
internal class Forecasts(
    private val places: Places,
    private val repository: WeatherRepository,
    private val log: DebugLog,
) {
    private val _states = MutableStateFlow<Map<String, ForecastState>>(emptyMap())

    /** By place key: [HERE] or a city's id. */
    val states: StateFlow<Map<String, ForecastState>> = _states.asStateFlow()

    /** Loads every place whose report is missing or old; all of them if [force]. */
    suspend fun refresh(force: Boolean = false) = coroutineScope {
        val targets = listOf<Place?>(null) + places.cities.value
        targets.map { place ->
            async {
                val key = place?.id ?: HERE
                val current = _states.value[key]
                val fresh = current is ForecastState.Ready && System.currentTimeMillis() - current.forecast.fetchedAt < MAX_AGE_MS
                if (fresh && !force) return@async
                if (current !is ForecastState.Ready) set(key, ForecastState.Loading)
                set(key, load(place, current))
            }
        }.awaitAll()
    }

    private fun set(key: String, state: ForecastState) = _states.update { it + (key to state) }

    /** A new report, or the last good one if this attempt fails. */
    private suspend fun load(place: Place?, current: ForecastState?): ForecastState {
        val coordinates = if (place != null) {
            place.latitude to place.longitude
        } else {
            val location = repository.locate()
                ?: return current as? ForecastState.Ready ?: ForecastState.Failed(repository.locateProblem())
            location.latitude to location.longitude
        }
        return try {
            ForecastState.Ready(withContext(Dispatchers.IO) { fetch(coordinates.first, coordinates.second) })
        } catch (e: IOException) {
            log.e("forecast fetch failed", e)
            current as? ForecastState.Ready ?: ForecastState.Failed("Offline. Press r to try again.")
        } catch (e: JSONException) {
            log.e("forecast unreadable", e)
            current as? ForecastState.Ready ?: ForecastState.Failed("The weather service sent something unreadable.")
        }
    }

    /** Blocking; call on [Dispatchers.IO]. */
    private fun fetch(latitude: Double, longitude: Double): Forecast {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code," +
                "wind_speed_10m,wind_direction_10m,precipitation,pressure_msl" +
                "&hourly=temperature_2m,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                "sunrise,sunset,uv_index_max" +
                "&forecast_days=$DAYS&timezone=auto",
            latitude,
            longitude,
        )
        return parse(JSONObject(Http.get(url)))
    }

    private fun parse(root: JSONObject): Forecast {
        val current = root.getJSONObject("current")
        val now = Forecast.Now(
            tempC = current.getDouble("temperature_2m"),
            feelsC = current.getDouble("apparent_temperature"),
            humidity = current.getInt("relative_humidity_2m"),
            code = current.getInt("weather_code"),
            windKmh = current.getDouble("wind_speed_10m"),
            windFrom = current.getInt("wind_direction_10m"),
            precipitationMm = current.getDouble("precipitation"),
            pressureHpa = current.getDouble("pressure_msl"),
        )
        // The next 24 hours, from the hour we're in (times are the place's local time).
        val nowTime = LocalDateTime.parse(current.getString("time"))
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val rain = hourly.getJSONArray("precipitation_probability")
        val codes = hourly.optJSONArray("weather_code")
        val hours = (0 until times.length())
            .map { i ->
                Forecast.Hour(LocalDateTime.parse(times.getString(i)), temps.getDouble(i), rain.optInt(i, 0), codes?.optInt(i, 0) ?: 0)
            }
            .filter { !it.time.isBefore(nowTime.withMinute(0)) }
            .take(HOURS)
        val daily = root.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            Forecast.Day(
                date = LocalDate.parse(dates.getString(i)),
                code = daily.getJSONArray("weather_code").getInt(i),
                maxC = daily.getJSONArray("temperature_2m_max").getDouble(i),
                minC = daily.getJSONArray("temperature_2m_min").getDouble(i),
                rainChance = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                sunrise = daily.getJSONArray("sunrise").optString(i).takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
                sunset = daily.getJSONArray("sunset").optString(i).takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
                uv = daily.getJSONArray("uv_index_max").optDouble(i, 0.0),
            )
        }
        return Forecast(now, hours, days, System.currentTimeMillis())
    }

    companion object {
        const val HERE = "here"
        private const val DAYS = 7
        private const val HOURS = 24
        private const val MAX_AGE_MS = 15 * 60_000L
    }
}
