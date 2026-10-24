package com.newhost.qwertylauncher.ui.grid

import com.newhost.qwertylauncher.core.Track
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.cells
import com.newhost.qwertylauncher.core.fr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GridSolverTest {

    private fun solve(tracks: List<Track>, available: Int?, gap: Int = 0, natural: List<Int> = List(tracks.size) { 0 }) =
        GridSolver.solve(tracks, gap, available) { natural[it] }.toList()

    @Test
    fun fixedAndAutoTracksTakeTheirSize() {
        assertEquals(listOf(4, 7), solve(listOf(cells(4), auto()), available = 20, natural = listOf(0, 7)))
    }

    @Test
    fun autoStopsAtItsMax() {
        assertEquals(listOf(5), solve(listOf(auto(max = 5)), available = 20, natural = listOf(9)))
    }

    @Test
    fun frTracksShareWhatsLeft() {
        // 20 cells, less 4 fixed and 2 gaps of 1: 14 left for 1fr and 2fr, with the odd cell to the first.
        assertEquals(listOf(4, 5, 9), solve(listOf(cells(4), fr(1f), fr(2f)), available = 20, gap = 1))
        assertEquals(listOf(4, 3), solve(listOf(fr(), fr()), available = 7))
    }

    @Test
    fun frRespectsItsMinimum() {
        assertEquals(listOf(4, 2), solve(listOf(cells(4), fr(min = 2)), available = 6))
        // Too little room even then: the cell comes off the biggest track instead.
        assertEquals(listOf(7, 2), solve(listOf(cells(8), fr(min = 2)), available = 9))
    }

    @Test
    fun withoutAHeightFrIsAsBigAsItsContent() {
        assertEquals(listOf(3, 6), solve(listOf(cells(3), fr(min = 2)), available = null, natural = listOf(0, 6)))
        assertEquals(listOf(2), solve(listOf(fr(min = 2)), available = null, natural = listOf(1)))
    }

    @Test
    fun tooLittleRoomShrinksAutoTracksFirstBiggestFirst() {
        // 5 cells over: the autos give way, the bigger one first, and the fixed track keeps its size.
        assertEquals(listOf(4, 5, 5), solve(listOf(auto(), auto(), cells(5)), available = 14, natural = listOf(8, 6, 0)))
    }

    @Test
    fun shrinkingNeverGoesBelowOneCell() {
        assertEquals(listOf(1, 1), solve(listOf(cells(5), auto()), available = 1, natural = listOf(0, 5)))
    }

    @Test
    fun fitSpanGrowsTheLastTrackThatCan() {
        val tracks = listOf(auto(), cells(2), cells(2))
        val sizes = intArrayOf(1, 2, 2)
        GridSolver.fitSpan(sizes, tracks, gap = 1, from = 0, span = 3, needed = 10)
        assertArrayEquals(intArrayOf(4, 2, 2), sizes)
        // Enough room already: nothing changes.
        GridSolver.fitSpan(sizes, tracks, gap = 1, from = 0, span = 3, needed = 5)
        assertArrayEquals(intArrayOf(4, 2, 2), sizes)
    }

    @Test
    fun fitSpanLeavesFixedTracksAlone() {
        val sizes = intArrayOf(2, 2)
        GridSolver.fitSpan(sizes, listOf(cells(2), cells(2)), gap = 0, from = 0, span = 2, needed = 9)
        assertArrayEquals(intArrayOf(2, 2), sizes)
    }

    @Test
    fun startsAndTotal() {
        val sizes = intArrayOf(3, 4, 5)
        assertArrayEquals(intArrayOf(0, 5, 11), GridSolver.starts(sizes, gap = 2))
        assertEquals(16, GridSolver.total(sizes, gap = 2))
        assertEquals(0, GridSolver.total(IntArray(0), gap = 2))
    }
}
