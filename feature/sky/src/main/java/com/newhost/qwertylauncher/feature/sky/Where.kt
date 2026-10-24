package com.newhost.qwertylauncher.feature.sky

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Roughly where the phone is, in degrees. */
internal data class Spot(val latitude: Double, val longitude: Double, val at: Long)

/**
 * Where the phone was last seen, kept in private preferences so the sun
 * still works offline and before a new fix. Only ever the phone's own last
 * known location, or one fresh coarse fix; nothing leaves the phone.
 */
internal class Where(private val context: Context) {
    private val prefs = context.getSharedPreferences("sky", Context.MODE_PRIVATE)
    private val _spot = MutableStateFlow(read())
    val spot: StateFlow<Spot?> = _spot.asStateFlow()

    fun allowed() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Looks again, when allowed and the last spot is old (or [force]). */
    suspend fun refresh(force: Boolean = false) {
        if (!allowed()) return
        val current = _spot.value
        if (!force && current != null && System.currentTimeMillis() - current.at < MAX_AGE_MS) return
        val location = locate() ?: return
        val spot = Spot(location.latitude, location.longitude, System.currentTimeMillis())
        _spot.value = spot
        prefs.edit()
            .putFloat(LAT, spot.latitude.toFloat())
            .putFloat(LON, spot.longitude.toFloat())
            .putLong(AT, spot.at)
            .apply()
    }

    @SuppressLint("MissingPermission") // refresh() checks it
    private suspend fun locate(): Location? {
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
        // A day-old fix is plenty for the sun: tens of kilometres move it a minute.
        if (last != null && System.currentTimeMillis() - last.time < MAX_AGE_MS) return last
        val provider = when {
            manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return last
        }
        val fresh = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }
            }
        } catch (e: SecurityException) {
            null
        }
        return fresh ?: last
    }

    private fun read(): Spot? {
        if (!prefs.contains(LAT)) return null
        return Spot(prefs.getFloat(LAT, 0f).toDouble(), prefs.getFloat(LON, 0f).toDouble(), prefs.getLong(AT, 0))
    }

    private companion object {
        const val LAT = "lat"
        const val LON = "lon"
        const val AT = "at"
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        const val TIMEOUT_MS = 10_000L
    }
}
