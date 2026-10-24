package com.newhost.qwertylauncher.feature.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How the phone is tipped, in degrees: [pitch] forward (+) or back, [roll] right (+) or left. 0, 0 is flat. */
internal data class Tilt(val pitch: Float, val roll: Float)

/**
 * The phone's sensors, as flows that listen only while collected (so only
 * while something showing them is on screen), each at most a few times a
 * second. A sensor the phone doesn't have is null here.
 */
internal class Readings(private val context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)

    private fun sensor(type: Int): Sensor? = manager.getDefaultSensor(type)

    val hasCompass = sensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        (sensor(Sensor.TYPE_ACCELEROMETER) != null && sensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
    val hasLevel = sensor(Sensor.TYPE_GRAVITY) != null || sensor(Sensor.TYPE_ACCELEROMETER) != null
    val hasPressure = sensor(Sensor.TYPE_PRESSURE) != null
    val hasSteps = sensor(Sensor.TYPE_STEP_COUNTER) != null
    val hasLight = sensor(Sensor.TYPE_LIGHT) != null

    fun stepsAllowed() =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** A sensor's values as they come, [rate] apart at most, until the flow is cancelled. */
    private fun listen(type: Int, rate: Int = SensorManager.SENSOR_DELAY_UI): Flow<FloatArray> {
        val sensor = sensor(type) ?: return emptyFlow()
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(event.values.clone())
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, rate)
            awaitClose { manager.unregisterListener(listener) }
        }.conflate()
    }

    /** Where the top of the phone points, in degrees from magnetic north, clockwise; smoothed. */
    fun heading(): Flow<Float> {
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        fun azimuth() = Math.toDegrees(SensorManager.getOrientation(matrix, angles)[0].toDouble()).toFloat()
        val raw: Flow<Float> = if (sensor(Sensor.TYPE_ROTATION_VECTOR) != null) {
            listen(Sensor.TYPE_ROTATION_VECTOR).map { v ->
                SensorManager.getRotationMatrixFromVector(matrix, v)
                azimuth()
            }
        } else {
            // The older way: gravity and the magnetic field together.
            var gravity: FloatArray? = null
            merge(
                listen(Sensor.TYPE_ACCELEROMETER).mapNotNull { gravity = it; null },
                listen(Sensor.TYPE_MAGNETIC_FIELD).mapNotNull { field ->
                    val g = gravity ?: return@mapNotNull null
                    if (SensorManager.getRotationMatrix(matrix, null, g, field)) azimuth() else null
                },
            )
        }
        // Smoothed around the circle, so 359 to 1 doesn't swing the long way round.
        var smooth: Float? = null
        return raw.map { reading ->
            val degrees = (reading + 360) % 360
            val last = smooth ?: degrees
            var delta = degrees - last
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            ((last + delta * SMOOTHING + 360) % 360).also { smooth = it }
        }
    }

    /** How the phone is tipped; smoothed. */
    fun tilt(): Flow<Tilt> {
        val type = if (sensor(Sensor.TYPE_GRAVITY) != null) Sensor.TYPE_GRAVITY else Sensor.TYPE_ACCELEROMETER
        var pitch = 0f
        var roll = 0f
        return listen(type).map { g ->
            val (x, y, z) = Triple(g[0], g[1], g[2])
            val p = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()
            val r = Math.toDegrees(atan2(x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
            pitch += (p - pitch) * SMOOTHING
            roll += (-r - roll) * SMOOTHING
            Tilt(pitch, roll)
        }
    }

    /** Air pressure, in hPa; each reading also goes into [log]. */
    fun pressure(log: PressureLog): Flow<Float> =
        listen(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL).map { it[0] }.onEach { log.add(it) }

    /** Light, in lux. */
    fun light(): Flow<Float> = listen(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL).map { it[0] }

    /** Steps taken today, from the step counter (which counts since the phone started); null without permission. */
    fun steps(day: StepDay): Flow<Int?> {
        if (!stepsAllowed()) return flowOf(null)
        return listen(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL)
            .map { day.today(it[0].toLong()) }
            .distinctUntilChanged()
    }

    private companion object {
        /** How much of each new reading goes into the smoothed one. */
        const val SMOOTHING = 0.25f
    }
}

/**
 * Today's steps from the step counter, which counts from when the phone
 * started: its value at the start of the day is remembered (and carried
 * over when the phone restarts during the day).
 */
internal class StepDay(context: Context) {
    private val prefs = context.getSharedPreferences("sensors", Context.MODE_PRIVATE)

    @Synchronized
    fun today(counter: Long): Int {
        val day = LocalDate.now().toEpochDay()
        var start = prefs.getLong(START, counter)
        val last = prefs.getLong(LAST, counter)
        when {
            prefs.getLong(DAY, -1) != day -> start = counter
            // The phone restarted: the counter began again; today's count so far carries on.
            counter < last -> start = counter - (last - start)
        }
        prefs.edit().putLong(DAY, day).putLong(START, start).putLong(LAST, counter).apply()
        return (counter - start).toInt().coerceAtLeast(0)
    }

    private companion object {
        const val DAY = "steps_day"
        const val START = "steps_start"
        const val LAST = "steps_last"
    }
}

/**
 * Air pressure over the last hours, one reading every [EVERY_MS] at most,
 * kept across restarts: for the chart and whether it's rising or falling.
 */
internal class PressureLog(context: Context) {
    private val prefs = context.getSharedPreferences("sensors", Context.MODE_PRIVATE)
    private val readings = ArrayDeque(read())

    /** (when, hPa), oldest first. */
    @Synchronized
    fun all(): List<Pair<Long, Float>> = readings.toList()

    @Synchronized
    fun add(hpa: Float) {
        val now = System.currentTimeMillis()
        if (readings.isNotEmpty() && now - readings.last().first < EVERY_MS) return
        readings.addLast(now to hpa)
        while (readings.isNotEmpty() && now - readings.first().first > KEEP_MS) readings.removeFirst()
        val array = JSONArray()
        readings.forEach { (at, value) -> array.put(JSONObject().put("at", at).put("hpa", value.toDouble())) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** How much it changed over about the last three hours, in hPa; null without readings that old. */
    @Synchronized
    fun change(now: Float): Float? {
        val since = System.currentTimeMillis() - TREND_MS
        val then = readings.firstOrNull { it.first >= since - EVERY_MS } ?: return null
        if (System.currentTimeMillis() - then.first < TREND_MS / 2) return null
        return now - then.second
    }

    private fun read(): List<Pair<Long, Float>> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { it.getLong("at") to it.getDouble("hpa").toFloat() } }
    } catch (e: JSONException) {
        emptyList()
    }

    private companion object {
        const val KEY = "pressure"
        const val EVERY_MS = 10 * 60_000L
        const val KEEP_MS = 24 * 60 * 60_000L
        const val TREND_MS = 3 * 60 * 60_000L
    }
}
