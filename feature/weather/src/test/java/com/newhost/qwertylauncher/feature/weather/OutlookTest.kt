package com.newhost.qwertylauncher.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlookTest {

    private fun outlook(rain: List<Int>, codes: List<Int> = List(rain.size) { 61 }, firstHour: Int? = 14) =
        Outlook(List(rain.size) { 10.0 }, rain, codes, firstHour)

    @Test
    fun dryNowRainLater() {
        val o = outlook(listOf(10, 20, 80, 90, 50, 10))
        assertEquals("rain from 16:00 90%", o.warning(nowCode = 0, threshold = 50))
        // Without the place's time: how long until.
        assertEquals("rain in 2h 80%", outlook(listOf(10, 20, 80), firstHour = null).warning(0, 50))
    }

    @Test
    fun dryNowRainStartingNow() {
        assertEquals("rain likely now 70%", outlook(listOf(60, 70, 10)).warning(nowCode = 0, threshold = 50))
    }

    @Test
    fun theWordFollowsTheWeather() {
        assertEquals("snow from 15:00 80%", outlook(listOf(0, 80), codes = listOf(0, 73)).warning(0, 50))
        assertEquals("storms from 15:00 80%", outlook(listOf(0, 80), codes = listOf(0, 95)).warning(0, 50))
    }

    @Test
    fun rainingNow() {
        assertEquals("rain until 16:00", outlook(listOf(90, 80, 10, 90)).warning(nowCode = 61, threshold = 50))
        assertEquals("rain for 12h or more", outlook(List(24) { 90 }).warning(nowCode = 61, threshold = 50))
        assertEquals("rain for 2h", outlook(listOf(90, 80, 10), firstHour = null).warning(nowCode = 61, threshold = 50))
    }

    @Test
    fun noWarning() {
        assertNull(outlook(listOf(10, 20, 30)).warning(nowCode = 0, threshold = 50))
        // Only the next 12 hours count.
        assertNull(outlook(List(12) { 0 } + 90).warning(nowCode = 0, threshold = 50))
        assertNull(outlook(emptyList()).warning(nowCode = 61, threshold = 50))
    }

    @Test
    fun axis() {
        assertEquals(listOf("now", "15", "", "", "18"), outlook(List(5) { 0 }).axis())
        assertEquals(listOf("now", "", "", "+3"), outlook(List(4) { 0 }, firstHour = null).axis())
        assertEquals(listOf("now", "", "00"), outlook(List(3) { 0 }, firstHour = 22).axis())
    }
}
