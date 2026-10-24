package com.newhost.qwertylauncher.feature.sky

import java.time.Instant
import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The sun's day at a place: when it rises and sets, and when it passes the
 * other heights that matter (twilight, the golden hour). Worked out on the
 * phone with the sunrise equation, good to a minute or two. A time is null
 * when the sun never gets there that day (midnight sun, polar night).
 */
internal class SunDay(
    val date: LocalDate,
    val rise: Instant?,
    val set: Instant?,
    /** Civil twilight: light enough to see by, from dawn to dusk. */
    val dawn: Instant?,
    val dusk: Instant?,
    /** The golden hour: the sun low, under 6° high; it ends in the morning and starts in the evening. */
    val goldenEnds: Instant?,
    val goldenStarts: Instant?,
    val noon: Instant,
    /** The sun is up all day, or down all day, when it doesn't rise. */
    val allDay: Boolean,
) {
    /** How long the sun is up, in minutes. */
    val lengthMinutes: Long
        get() = when {
            rise != null && set != null -> (set.epochSecond - rise.epochSecond) / 60
            allDay -> 24 * 60
            else -> 0
        }
}

/** The moon's phase at a moment: [age] of the way (0 to 1) from new moon round to the next. */
internal class MoonPhase(val age: Double) {
    /** How much of it is lit, 0 to 1. */
    val lit: Double get() = (1 - cos(2 * Math.PI * age)) / 2

    val waxing: Boolean get() = age < 0.5

    val name: String get() = NAMES[(floor(age * NAMES.size + 0.5).toInt()) % NAMES.size]

    private companion object {
        val NAMES = listOf(
            "new moon", "waxing crescent", "first quarter", "waxing gibbous",
            "full moon", "waning gibbous", "last quarter", "waning crescent",
        )
    }
}

internal object Astro {
    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val DAY_MS = 86_400_000.0
    private const val EPOCH_DAY_2000 = 10957L

    /** A new moon (6 January 2000, 18:14 UTC) and the mean time between two. */
    private const val NEW_MOON_JD = 2451550.1
    const val SYNODIC_DAYS = 29.530588853

    private const val TILT = 23.4397

    fun julian(instant: Instant) = instant.toEpochMilli() / DAY_MS + UNIX_EPOCH_JD

    private fun instant(julian: Double): Instant = Instant.ofEpochMilli(((julian - UNIX_EPOCH_JD) * DAY_MS).toLong())

    /** The sunrise equation for [date] at [latitude], [longitude] (east positive), in degrees. */
    fun sun(date: LocalDate, latitude: Double, longitude: Double): SunDay {
        val n = (date.toEpochDay() - EPOCH_DAY_2000).toDouble()
        val mean = n - longitude / 360
        val anomaly = Math.toRadians((357.5291 + 0.98560028 * mean).mod(360.0))
        val centre = 1.9148 * sin(anomaly) + 0.0200 * sin(2 * anomaly) + 0.0003 * sin(3 * anomaly)
        val ecliptic = Math.toRadians((Math.toDegrees(anomaly) + centre + 180 + 102.9372).mod(360.0))
        val transit = J2000 + mean + 0.0053 * sin(anomaly) - 0.0069 * sin(2 * ecliptic)
        val declination = asin(sin(ecliptic) * sin(Math.toRadians(TILT)))
        val phi = Math.toRadians(latitude)

        /** The hour angle where the sun is [height] degrees high, or null if it never is. */
        fun at(height: Double): Double? {
            val c = (sin(Math.toRadians(height)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
            return if (c < -1 || c > 1) null else Math.toDegrees(acos(c))
        }
        fun morning(height: Double) = at(height)?.let { instant(transit - it / 360) }
        fun evening(height: Double) = at(height)?.let { instant(transit + it / 360) }

        val rise = morning(RISE)
        // No sunrise: up all day if it's high at noon, else down all day.
        val allDay = rise == null && sin(phi) * sin(declination) + cos(phi) * cos(declination) > sin(Math.toRadians(RISE))
        return SunDay(
            date = date,
            rise = rise,
            set = evening(RISE),
            dawn = morning(CIVIL),
            dusk = evening(CIVIL),
            goldenEnds = morning(GOLDEN),
            goldenStarts = evening(GOLDEN),
            noon = instant(transit),
            allDay = allDay,
        )
    }

    fun moon(at: Instant): MoonPhase = MoonPhase(((julian(at) - NEW_MOON_JD) / SYNODIC_DAYS).mod(1.0))

    /** When the moon next reaches [age] (0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter) after [from]; about, to within a day. */
    fun next(age: Double, from: Instant): Instant {
        val now = moon(from).age
        val ahead = (age - now).mod(1.0).let { if (it < 1e-6) 1.0 else it }
        return instant(julian(from) + ahead * SYNODIC_DAYS)
    }

    /** The sun's centre at the horizon, allowing for its size and the air bending its light. */
    private const val RISE = -0.833
    private const val CIVIL = -6.0
    private const val GOLDEN = 6.0
}
