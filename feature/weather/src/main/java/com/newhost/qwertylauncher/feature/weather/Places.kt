package com.newhost.qwertylauncher.feature.weather

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A city the user added, from Open-Meteo's place search. */
internal data class Place(
    val id: String,
    val name: String,
    /** Region and country, e.g. "Bavaria, Germany". */
    val region: String,
    val latitude: Double,
    val longitude: Double,
) {
    val fullName: String get() = if (region.isEmpty()) name else "$name, $region"
}

/**
 * The user's cities, and which place the home tile shows: a city, or null
 * for "here" (the phone's location). Main thread only.
 */
internal class Places(context: Context) {
    private val prefs = context.getSharedPreferences("weather_places", Context.MODE_PRIVATE)

    private val _cities = MutableStateFlow(read())
    val cities: StateFlow<List<Place>> = _cities.asStateFlow()

    private val _pinned = MutableStateFlow(prefs.getString(PINNED, null))

    /** The id of the city shown on home, or null for here. */
    val pinned: StateFlow<String?> = _pinned.asStateFlow()

    fun pinnedPlace(): Place? = _pinned.value?.let { id -> _cities.value.firstOrNull { it.id == id } }

    fun add(place: Place) {
        if (_cities.value.any { it.id == place.id }) return
        write(_cities.value + place)
    }

    fun remove(id: String) {
        write(_cities.value.filterNot { it.id == id })
        if (_pinned.value == id) pin(null)
    }

    /** Shows [id] on the home tile; null shows here. */
    fun pin(id: String?) {
        _pinned.value = id
        prefs.edit().putString(PINNED, id).apply()
    }

    private fun write(list: List<Place>) {
        _cities.value = list
        val json = JSONArray()
        list.forEach {
            json.put(
                JSONObject().put("id", it.id).put("name", it.name).put("region", it.region)
                    .put("lat", it.latitude).put("lon", it.longitude),
            )
        }
        prefs.edit().putString(CITIES, json.toString()).apply()
    }

    private fun read(): List<Place> {
        val raw = prefs.getString(CITIES, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                Place(o.getString("id"), o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private companion object {
        const val CITIES = "cities"
        const val PINNED = "pinned"
    }
}

/** Finds cities by name with Open-Meteo's free place search. */
internal object Geocoder {
    suspend fun search(name: String): List<Place> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(name.trim(), "UTF-8")
        val body = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$query&count=$MAX_RESULTS&language=en&format=json")
        val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            val region = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.distinct()
            Place(
                id = o.get("id").toString(),
                name = o.getString("name"),
                region = region.joinToString(", "),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
            )
        }
    }

    private const val MAX_RESULTS = 6
}

/** Plain HTTPS GET for Open-Meteo's JSON. Blocking; call on [Dispatchers.IO]. */
internal object Http {
    fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 10_000
}
