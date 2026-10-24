package com.newhost.qwertylauncher.feature.sky

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroTest {

    /** [actual] within [minutes] of [expected]. */
    private fun assertNear(expected: String, actual: Instant?, minutes: Long = 3) {
        assertNotNull(actual)
        val off = Duration.between(Instant.parse(expected), actual).abs()
        assertTrue("$actual is ${off.toMinutes()} min from $expected", off.toMinutes() <= minutes)
    }

    @Test
    fun londonMidsummer() {
        val day = Astro.sun(LocalDate.of(2024, 6, 21), 51.5074, -0.1278)
        assertNear("2024-06-21T03:43:00Z", day.rise)
        assertNear("2024-06-21T20:21:00Z", day.set)
        assertNear("2024-06-21T12:02:00Z", day.noon)
        assertTrue(day.dawn!! < day.rise)
        assertTrue(day.dusk!! > day.set)
        assertTrue(day.goldenEnds!! > day.rise)
        assertTrue(day.goldenStarts!! < day.set)
        assertEquals(998.0, day.lengthMinutes.toDouble(), 5.0)
    }

    @Test
    fun midnightSunAndPolarNight() {
        // Tromsø.
        val summer = Astro.sun(LocalDate.of(2024, 6, 21), 69.65, 18.96)
        assertNull(summer.rise)
        assertNull(summer.set)
        assertTrue(summer.allDay)
        assertEquals(24 * 60L, summer.lengthMinutes)

        val winter = Astro.sun(LocalDate.of(2024, 12, 21), 69.65, 18.96)
        assertNull(winter.rise)
        assertFalse(winter.allDay)
        assertEquals(0L, winter.lengthMinutes)
    }

    @Test
    fun moonPhases() {
        // A full moon on 25 January 2024 and a new moon on 11 January.
        val full = Astro.moon(Instant.parse("2024-01-25T17:54:00Z"))
        assertEquals("full moon", full.name)
        assertEquals(1.0, full.lit, 0.02)

        val new = Astro.moon(Instant.parse("2024-01-11T11:57:00Z"))
        assertEquals("new moon", new.name)
        assertEquals(0.0, new.lit, 0.02)

        assertTrue(Astro.moon(Instant.parse("2024-01-15T00:00:00Z")).waxing)
        assertFalse(Astro.moon(Instant.parse("2024-01-30T00:00:00Z")).waxing)
    }

    @Test
    fun nextFullMoon() {
        val next = Astro.next(0.5, Instant.parse("2024-01-12T00:00:00Z"))
        assertNear("2024-01-25T17:54:00Z", next, minutes = 36 * 60)
    }

    @Test
    fun nextIsAlwaysAhead() {
        val from = Instant.parse("2024-01-25T17:54:00Z")
        val now = Astro.moon(from).age
        val next = Astro.next(now, from)
        // Already there: the one after, a whole month on.
        assertEquals(Astro.SYNODIC_DAYS, Duration.between(from, next).toMinutes() / 1440.0, 0.01)
    }
}
