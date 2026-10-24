package com.newhost.qwertylauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun bands() {
        assertEquals(Score.EXACT, Fuzzy.score("Camera", "camera"))
        // Less a point for every letter the name is longer than what was typed.
        assertEquals(Score.PREFIX - 4, Fuzzy.score("spo", "Spotify"))
        assertEquals(Score.WORD - 12, Fuzzy.score("pre", "Spotify Premium"))
        assertEquals(Score.WORD - 9, Fuzzy.score("gm", "Google Maps"))
        assertEquals(Score.FUZZY - 2, Fuzzy.score("sptfy", "Spotify"))
    }

    @Test
    fun noMatch() {
        assertEquals(0, Fuzzy.score("xyz", "Spotify"))
        assertEquals(0, Fuzzy.score("", "Spotify"))
        assertEquals(0, Fuzzy.score("s", ""))
        // One letter is never taken for initials.
        assertEquals(0, Fuzzy.score("m", "Google"))
    }

    @Test
    fun wordsSplitOnPunctuation() {
        assertEquals(Score.WORD - 7, Fuzzy.score("lens", "google-lens"))
        assertEquals(Score.WORD - 8, Fuzzy.score("bar", "foo_bar.baz"))
    }

    @Test
    fun shorterNamesRankFirst() {
        assertTrue(Fuzzy.score("ca", "Camera") > Fuzzy.score("ca", "Calculator"))
    }

    @Test
    fun aBetterKindOfMatchAlwaysWins() {
        // A prefix of a very long name still beats a word match in a short one.
        assertTrue(Fuzzy.score("ma", "Ma" + "x".repeat(300)) > Fuzzy.score("ma", "Go Maps"))
    }
}
