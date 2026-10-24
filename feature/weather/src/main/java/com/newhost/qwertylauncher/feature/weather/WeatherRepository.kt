package com.newhost.qwertylauncher.feature.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.SettingsStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal sealed interface WeatherState {
    data object Unknown : WeatherState
    data object NeedsPermission : WeatherState
    data class Failed(val message: String) : WeatherState
    data class Ready(
        val tempC: Double,
        val code: Int,
        val windKmh: Double,
        val maxC: Double,
        val minC: Double,
        val fetchedAt: Long,
        /** The city shown, or null for here. */
        val place: String? = null,
        val feelsC: Double? = null,
        val humidity: Int? = null,
        /** Where the wind comes from, in degrees. */
        val windFrom: Int? = null,
        /** Today's highest chance of rain, in percent. */
        val rainChance: Int? = null,
        /** "07:12". */
        val sunrise: String? = null,
        val sunset: String? = null,
        val uv: Double? = null,
        val pressureHpa: Double? = null,
        /** The next 24 hours, from the hour it was fetched in: temperature, chance of rain, weather code. */
        val hourlyC: List<Double> = emptyList(),
        val hourlyRain: List<Int> = emptyList(),
        val hourlyCodes: List<Int> = emptyList(),
        /** When the first of those hours starts (epoch ms), and its hour of day at the place. */
        val hourlyStart: Long? = null,
        val hourlyStartHour: Int? = null,
    ) : WeatherState
}

/**
 * Single source of truth for the home tile's weather: here, or the city
 * pinned in the weather pane. Keeps the last good reading on disk and keeps
 * showing it when a refresh fails.
 */
internal class WeatherRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val places: Places,
    private val log: DebugLog,
) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readCache() ?: WeatherState.Unknown)
    val state: StateFlow<WeatherState> = _state.asStateFlow()
    private val mutex = Mutex()

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            val current = _state.value
            val maxAgeMs = settings[WeatherSettings.refreshMinutes] * 60_000L
            val pinned = places.pinnedPlace()
            // A reading for another place than the one now shown is stale, whatever its age.
            val samePlace = current is WeatherState.Ready && current.place == pinned?.name
            val fresh = samePlace && System.currentTimeMillis() - (current as WeatherState.Ready).fetchedAt < maxAgeMs
            if (fresh && !force) return

            val coordinates = if (pinned != null) {
                pinned.latitude to pinned.longitude
            } else {
                if (!hasPermission()) {
                    if (current !is WeatherState.Ready) _state.value = WeatherState.NeedsPermission
                    return
                }
                val location = currentLocation()
                if (location == null) {
                    log.d("no location fix; keeping the last reading")
                    if (current !is WeatherState.Ready) _state.value = WeatherState.Failed("no location fix")
                    return
                }
                location.latitude to location.longitude
            }
            _state.value = try {
                withContext(Dispatchers.IO) { fetch(coordinates.first, coordinates.second) }
                    .copy(place = pinned?.name)
                    .also(::writeCache)
            } catch (e: IOException) {
                log.e("weather fetch failed", e)
                current as? WeatherState.Ready ?: WeatherState.Failed("offline")
            } catch (e: JSONException) {
                log.e("weather response unreadable", e)
                current as? WeatherState.Ready ?: WeatherState.Failed("bad response")
            }
        }
    }

    /** The phone's location for the weather pane's "here", or null (see [locateProblem]). */
    suspend fun locate(): Location? = if (hasPermission()) currentLocation() else null

    /** Why [locate] found nothing, in words. */
    fun locateProblem(): String =
        if (hasPermission()) "No location fix yet. Press r to try again." else "Needs your location: tap the weather tile to allow it."

    @SuppressLint("MissingPermission") // callers check the permission first
    private suspend fun currentLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java)
        val last = manager.getProviders(true)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < LOCATION_MAX_AGE_MS) return last

        val provider = when {
            manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return last
        }
        val current = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        continuation.resume(location)
                    }
                }
            }
        } catch (e: SecurityException) {
            null
        }
        return current ?: last
    }

    /** Blocking; call on [Dispatchers.IO]. */
    private fun fetch(latitude: Double, longitude: Double): WeatherState.Ready {
        // Two decimals is about 1 km: plenty for weather, and less precise location leaves the device.
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,apparent_temperature," +
                    "relative_humidity_2m,wind_direction_10m,pressure_msl" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=24" +
                    "&forecast_days=1&timezone=auto",
                latitude,
                longitude,
            ),
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): WeatherState.Ready {
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        // The first hour, in the place's own time.
        val firstHour = root.optJSONObject("hourly")?.optJSONArray("time")?.optString(0)
            ?.takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse)
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
        return WeatherState.Ready(
            tempC = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            windKmh = current.getDouble("wind_speed_10m"),
            maxC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            minC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            fetchedAt = System.currentTimeMillis(),
            feelsC = current.optDouble("apparent_temperature").takeUnless { it.isNaN() },
            humidity = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
            windFrom = current.optInt("wind_direction_10m", -1).takeIf { it >= 0 },
            rainChance = daily.optJSONArray("precipitation_probability_max")?.optInt(0, -1)?.takeIf { it >= 0 },
            sunrise = daily.optJSONArray("sunrise")?.optString(0)?.substringAfter('T')?.takeIf { it.isNotEmpty() },
            sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T')?.takeIf { it.isNotEmpty() },
            uv = daily.optJSONArray("uv_index_max")?.optDouble(0)?.takeUnless { it.isNaN() },
            pressureHpa = current.optDouble("pressure_msl").takeUnless { it.isNaN() },
            hourlyC = root.optJSONObject("hourly")?.optJSONArray("temperature_2m")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optDouble(i).takeUnless { it.isNaN() } }
            }.orEmpty(),
            hourlyRain = root.optJSONObject("hourly")?.optJSONArray("precipitation_probability")?.let { a ->
                (0 until a.length()).map { i -> a.optInt(i, 0) }
            }.orEmpty(),
            hourlyCodes = root.optJSONObject("hourly")?.optJSONArray("weather_code")?.let { a ->
                (0 until a.length()).map { i -> a.optInt(i, 0) }
            }.orEmpty(),
            hourlyStart = firstHour?.toEpochSecond(offset)?.times(1000),
            hourlyStartHour = firstHour?.hour,
        )
    }

    private fun writeCache(w: WeatherState.Ready) {
        val json = JSONObject()
            .put("t", w.tempC).put("c", w.code).put("w", w.windKmh)
            .put("hi", w.maxC).put("lo", w.minC).put("at", w.fetchedAt).put("place", w.place)
            .put("feels", w.feelsC).put("hum", w.humidity).put("from", w.windFrom).put("rain", w.rainChance)
            .put("rise", w.sunrise).put("set", w.sunset).put("uv", w.uv).put("hpa", w.pressureHpa)
            .put("hours", JSONArray(w.hourlyC)).put("hrain", JSONArray(w.hourlyRain)).put("hcode", JSONArray(w.hourlyCodes))
            .put("hstart", w.hourlyStart).put("hhour", w.hourlyStartHour)
        prefs.edit().putString(CACHE_KEY, json.toString()).apply()
    }

    private fun readCache(): WeatherState.Ready? {
        val raw = prefs.getString(CACHE_KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            WeatherState.Ready(
                o.getDouble("t"), o.getInt("c"), o.getDouble("w"), o.getDouble("hi"), o.getDouble("lo"), o.getLong("at"),
                o.optString("place").takeIf { it.isNotEmpty() },
                feelsC = o.optDouble("feels").takeUnless { it.isNaN() },
                humidity = o.optInt("hum", -1).takeIf { it >= 0 },
                windFrom = o.optInt("from", -1).takeIf { it >= 0 },
                rainChance = o.optInt("rain", -1).takeIf { it >= 0 },
                sunrise = o.optString("rise").takeIf { it.isNotEmpty() },
                sunset = o.optString("set").takeIf { it.isNotEmpty() },
                uv = o.optDouble("uv").takeUnless { it.isNaN() },
                pressureHpa = o.optDouble("hpa").takeUnless { it.isNaN() },
                hourlyC = o.optJSONArray("hours")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }.orEmpty(),
                hourlyRain = o.optJSONArray("hrain")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
                hourlyCodes = o.optJSONArray("hcode")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
                hourlyStart = o.optLong("hstart", -1).takeIf { it >= 0 },
                hourlyStartHour = o.optInt("hhour", -1).takeIf { it >= 0 },
            )
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        const val CACHE_KEY = "last"
        const val LOCATION_MAX_AGE_MS = 3 * 60 * 60_000L
        const val LOCATION_TIMEOUT_MS = 10_000L
        const val NETWORK_TIMEOUT_MS = 10_000
    }
}
