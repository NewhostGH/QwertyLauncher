package com.newhost.qwertylauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TtyTest {

    @Test
    fun bar() {
        assertEquals("[#####-----]", Tty.bar(0.5f))
        assertEquals("[----]", Tty.bar(-1f, width = 4))
        assertEquals("[####]", Tty.bar(2f, width = 4))
    }

    @Test
    fun meterUsesEighthsForTheLastCell() {
        assertEquals("▕██░░▏", Tty.meter(0.5f, width = 4))
        assertEquals("▕▋░▏", Tty.meter(0.3f, width = 2))
        assertEquals("▕░░░▏", Tty.meter(0f, width = 3))
        assertEquals("▕███▏", Tty.meter(1f, width = 3))
    }

    @Test
    fun signal() {
        assertEquals("    ", Tty.signal(0))
        assertEquals("▂▄  ", Tty.signal(2))
        assertEquals("▂▄▆█", Tty.signal(4))
    }

    @Test
    fun box() {
        assertEquals(
            listOf("┌─ t ──┐", "│ ab   │", "│ abcd │", "└──────┘"),
            Tty.box(listOf("ab", "abcd"), title = "t"),
        )
        assertEquals(listOf("┌──────┐", "│ abcd │", "└──────┘"), Tty.box(listOf("abcd")))
    }

    @Test
    fun sideBySide() {
        assertEquals("a    x\nbbb  y\n     z", Tty.sideBySide(listOf("a", "bbb"), listOf("x", "y", "z")))
        assertEquals("a    x\nbbb", Tty.sideBySide(listOf("a", "bbb"), listOf("x")))
    }
}
