package com.newhost.qwertylauncher.feature.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcTest {

    private fun calc(text: String, live: Boolean = false, ans: Double? = null, degrees: Boolean = false) =
        Calc.solve(text, live, ans, degrees)?.text

    @Test
    fun sums() {
        assertEquals("7", calc("1 + 2 * 3"))
        assertEquals("9", calc("(1 + 2) * 3"))
        assertEquals("2.5", calc("5 / 2"))
        assertEquals("1", calc("7 % 3"))
        assertEquals("-4", calc("-(2 + 2)"))
        assertEquals("8", calc("2 * 4,0"))
        assertEquals("6", calc("2 × 3"))
        assertEquals("2", calc("6 ÷ 3"))
    }

    @Test
    fun powersAreRightAssociative() {
        assertEquals("512", calc("2 ^ 3 ^ 2"))
        assertEquals("-4", calc("-2 ^ 2"))
    }

    @Test
    fun factorials() {
        assertEquals("120", calc("5!"))
        assertEquals("720", calc("3!!"))
        assertNull(calc("2.5!"))
    }

    @Test
    fun functionsAndConstants() {
        assertEquals("1.41421356237", calc("sqrt 2"))
        assertEquals("3", calc("log2(8)"))
        assertEquals("3.14159265359", calc("pi"))
        assertEquals("1", calc("sin 90", degrees = true))
        assertEquals("0.5", calc("sin30", degrees = true))
        assertEquals("90", calc("asin(1)", degrees = true))
        assertEquals("1", calc("cos 0"))
    }

    @Test
    fun lastAnswer() {
        assertEquals("84", calc("ans * 2", ans = 42.0))
        assertNull(calc("ans + 1", ans = null))
    }

    @Test
    fun otherBasesIn() {
        assertEquals("255", calc("0xff"))
        assertEquals("5", calc("0b101"))
        assertEquals("15", calc("0o17"))
    }

    @Test
    fun otherBasesOut() {
        assertEquals("0xff", calc("255 in hex"))
        assertEquals("0b1010", calc("10 to binary"))
        assertEquals("-0o10", calc("-8 in oct"))
        assertEquals("255", calc("0xff in dec"))
        // Only whole numbers can be shown in another base.
        assertNull(calc("2.5 in hex"))
    }

    @Test
    fun unitConversions() {
        assertEquals("3.10685596119 mi", calc("5 km in mi"))
        assertEquals("100 c", calc("212 f to c"))
        assertEquals("32 f", calc("0 c in f"))
        assertEquals("273.15 k", calc("0 c in k"))
        assertEquals("1 km", calc("1000 m in km"))
        assertEquals("1.5 h", calc("90 min in h"))
        // Nautical miles, not miles: the longest unit name wins.
        assertEquals("1.852 km", calc("1 nmi in km"))
        // A sum before the unit.
        assertEquals("2 m", calc("100 * 2 cm in m"))
    }

    @Test
    fun conversionsBetweenKindsDoNotWork() {
        assertNull(calc("5 kg in km"))
        assertNull(calc("5 parsecs in km"))
    }

    @Test
    fun whileTypingOnlyClearMathsCounts() {
        // A plain number, a phone number or a word gets no answer as you type.
        assertNull(calc("42", live = true))
        assertNull(calc("0123456789", live = true))
        assertNull(calc("pi", live = true))
        assertNull(calc("spotify", live = true))
        assertEquals("4", calc("2+2", live = true))
        assertEquals("1.41421356237", calc("sqrt 2", live = true))
        assertEquals("255", calc("0xff", live = true))
        // Conversions always count.
        assertEquals("0xff", calc("255 in hex", live = true))
    }

    @Test
    fun notSums() {
        assertNull(calc(""))
        assertNull(calc("   "))
        assertNull(calc("hello"))
        assertNull(calc("1 +"))
        assertNull(calc("(1 + 2"))
        assertNull(calc("1 / 0"))
    }

    @Test
    fun formatting() {
        assertEquals("0", Calc.format(0.0))
        assertEquals("0.3", Calc.format(0.1 + 0.2))
        assertEquals("1234567", Calc.format(1234567.0))
        assertEquals("1e15", Calc.format(1e15))
        assertEquals("1.5e-9", Calc.format(1.5e-9))
        assertEquals("-2.5", Calc.format(-2.5))
    }
}
