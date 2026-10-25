package com.newhost.qwertylauncher.ui

import com.newhost.qwertylauncher.core.Tty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {

    @Test
    fun metersAreSaidAsPercent() {
        assertEquals("battery 60 percent", Speech.speakable("battery ${Tty.meter(0.6f)}"))
        assertEquals("45 percent", Speech.speakable(Tty.meter(0.45f)))
        assertEquals("0 percent", Speech.speakable(Tty.meter(0f)))
        assertEquals("100 percent", Speech.speakable(Tty.meter(1f)))
    }

    @Test
    fun twoMetersOnALineStayApart() {
        assertEquals("C P U 20 percent ram 70 percent", Speech.speakable("cpu ${Tty.meter(0.2f)} ram ${Tty.meter(0.7f)}"))
    }

    @Test
    fun barsAreSaidAsPercent() {
        assertEquals("download 40 percent", Speech.speakable("download ${Tty.bar(0.4f)}"))
    }

    @Test
    fun signalBarsAreCounted() {
        assertEquals("wifi signal 3 of 4", Speech.speakable("wifi ${Tty.signal(3)}"))
        assertEquals("wifi signal 4 of 4", Speech.speakable("wifi ${Tty.signal(4)}"))
    }

    @Test
    fun drawingIsLeftOut() {
        assertEquals("title body", Speech.speakable("┌─ title ─┐\n│ body │\n└───┘"))
        assertEquals("rain", Speech.speakable("⣀⣤⣶⣿ rain"))
    }

    @Test
    fun plainTextNeedsNothing() {
        assertFalse(Speech.needed("12°C cloudy, wind 3 m/s"))
        assertTrue(Speech.needed(Tty.meter(0.5f)))
        assertTrue(Speech.needed("[#####-----]"))
    }

    @Test
    fun rowsLoseTheirMarkerAndDrawing() {
        assertEquals("terminal font JetBrains Mono", Speech.row("> terminal font   JetBrains Mono"))
        assertEquals("frames", Speech.row("── frames ───────────"))
        assertEquals("font size 9 20", Speech.row("  font size\n  9 ━━━━━●────── 20"))
    }

    @Test
    fun resultsSayWhatTheyAre() {
        assertEquals("command, module, installed modules", Speech.result("cmd", "module", "installed modules"))
        assertEquals("app, Firefox", Speech.result("app", "Firefox", null))
    }

    @Test
    fun chartsAreSummed() {
        assertEquals("chart from 3 to 9, latest 7", Speech.chart(listOf(3.0, 9.0, 7.0)))
        // Decimals as the phone's language writes them, which is how the screen reader reads them.
        val half = "%.1f".format(1.5)
        assertEquals("chart from $half to 2, latest $half", Speech.chart(listOf(2.0, 1.5)))
    }

    @Test
    fun wordsAreSaidAsMeant() {
        assertEquals("to do, 3 left", Speech.speakable("todo, 3 left"))
        assertEquals("ask A I", Speech.speakable("ask AI"))
        assertEquals("bluetooth on", Speech.words("bt on"))
        // Only whole words: inside another word they're left alone.
        assertEquals("OpenAI mobt", Speech.words("OpenAI mobt"))
        assertTrue(Speech.needed("todo"))
    }

    @Test
    fun keysAreSaidByName() {
        assertEquals("up down move enter open esc back", Speech.speakable("↑↓ move  ⏎ open  esc back"))
        assertEquals("type enter send control c stop", Speech.speakable("type ⏎ send  ^c stop"))
        assertEquals("arrows wasd", Speech.speakable("←↑↓→ wasd"))
    }
}
