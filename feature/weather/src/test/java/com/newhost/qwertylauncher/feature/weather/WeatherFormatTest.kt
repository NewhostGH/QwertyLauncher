package com.newhost.qwertylauncher.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherFormatTest {

    @Test
    fun weatherCodes() {
        assertEquals("clear", WeatherFormat.label(0))
        assertEquals("rain", WeatherFormat.label(63))
        assertEquals("thunderstorm", WeatherFormat.label(95))
        assertEquals("code 999", WeatherFormat.label(999))
        assertEquals(Sky.CLEAR, WeatherFormat.sky(1))
        assertEquals(Sky.RAIN, WeatherFormat.sky(81))
        assertEquals(Sky.SNOW, WeatherFormat.sky(86))
        assertEquals(Sky.STORM, WeatherFormat.sky(99))
        assertEquals(Sky.FOG, WeatherFormat.sky(48))
    }

    @Test
    fun compass() {
        assertEquals("north", WeatherFormat.compass(0))
        assertEquals("north", WeatherFormat.compass(22))
        assertEquals("northeast", WeatherFormat.compass(23))
        assertEquals("north", WeatherFormat.compass(350))
        assertEquals("west", WeatherFormat.compass(-90))
        assertEquals("north", WeatherFormat.compass(720))
        assertEquals("SE", WeatherFormat.compassShort(135))
        assertEquals("NW", WeatherFormat.compassShort(315))
    }

    @Test
    fun units() {
        assertEquals(212.0, WeatherFormat.temp(100.0, imperial = true), 1e-9)
        assertEquals(100.0, WeatherFormat.temp(100.0, imperial = false), 1e-9)
        assertEquals("°F", WeatherFormat.degrees(true))
        assertEquals("16 km/h", WeatherFormat.wind(16.0, imperial = false))
        assertEquals("10 mph", WeatherFormat.wind(16.0934, imperial = true))
    }
}
