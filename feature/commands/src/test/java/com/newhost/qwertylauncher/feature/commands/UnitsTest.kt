package com.newhost.qwertylauncher.feature.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {

    private fun convert(value: Double, from: String, to: String): Double {
        val a = checkNotNull(Units.find(from)) { "no unit $from" }
        val b = checkNotNull(Units.find(to)) { "no unit $to" }
        assertEquals(a.kind, b.kind)
        return b.fromBase(a.toBase(value))
    }

    @Test
    fun findsEveryWayOfTypingAUnit() {
        assertEquals("km", Units.find("kilometres")?.name)
        assertEquals("km", Units.find("KM")?.name)
        assertEquals("lb", Units.find("pounds")?.name)
        assertEquals("c", Units.find("°c")?.name)
        assertNull(Units.find("parsec"))
    }

    @Test
    fun namesAreLongestFirst() {
        val lengths = Units.names.map { it.first.length }
        assertEquals(lengths.sortedDescending(), lengths)
    }

    @Test
    fun conversions() {
        assertEquals(1.609344, convert(1.0, "mi", "km"), 1e-9)
        assertEquals(12.0, convert(1.0, "ft", "in"), 1e-9)
        assertEquals(2.20462262185, convert(1.0, "kg", "lb"), 1e-9)
        assertEquals(1024.0, convert(1.0, "mib", "kib"), 1e-9)
        assertEquals(8.0, convert(1.0, "byte", "bit"), 1e-9)
        assertEquals(1013.25, convert(1.0, "atm", "hpa"), 1e-9)
        assertEquals(3.6, convert(1.0, "m/s", "km/h"), 1e-9)
        assertEquals(180.0, convert(Math.PI, "rad", "deg"), 1e-9)
    }

    @Test
    fun temperaturesUseOffsets() {
        assertEquals(212.0, convert(100.0, "c", "f"), 1e-9)
        assertEquals(-40.0, convert(-40.0, "f", "c"), 1e-9)
        assertEquals(0.0, convert(-273.15, "c", "k"), 1e-9)
    }

    @Test
    fun aRoundTripGivesBackTheValue() {
        for ((_, unit) in Units.names) {
            assertTrue(unit.name, Math.abs(unit.fromBase(unit.toBase(12.5)) - 12.5) < 1e-9)
        }
    }
}
