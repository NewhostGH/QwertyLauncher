package com.newhost.qwertylauncher.ui.grid

import com.newhost.qwertylauncher.core.Track
import kotlin.math.floor
import kotlin.math.max

/**
 * Sizes the tracks along one axis of a grid, in cells, much like CSS grid:
 * fixed tracks take their size, `auto` ones their content's, and `fr` ones
 * share what's left by weight. Plain arithmetic, no views, so it's easy to
 * reason about.
 */
internal object GridSolver {

    /**
     * [natural] gives each track's content size. With [available] cells, `fr`
     * tracks share the room left over, and when there's too little room the
     * `auto` tracks give way first (the biggest first), then the rest, down to
     * one cell. Without [available] (a height that's free to grow), `fr`
     * tracks are as big as their content.
     */
    fun solve(tracks: List<Track>, gap: Int, available: Int?, natural: (Int) -> Int): IntArray {
        val sizes = IntArray(tracks.size) { i ->
            when (val track = tracks[i]) {
                is Track.Cells -> track.count
                is Track.Auto -> natural(i).coerceAtMost(track.max)
                is Track.Fr -> if (available == null) max(track.min, natural(i)) else track.min
            }.coerceAtLeast(0)
        }
        if (available == null) return sizes
        val free = available - gaps(tracks.size, gap) - sizes.sum()
        val fractions = tracks.indices.filter { tracks[it] is Track.Fr }
        when {
            free > 0 && fractions.isNotEmpty() -> share(sizes, tracks, fractions, free)
            free < 0 -> shrink(sizes, tracks, -free)
        }
        return sizes
    }

    /**
     * An item spanning several tracks that needs more than they add up to
     * gets the difference on the last of them that can grow (`auto` or `fr`),
     * when the room is free to grow.
     */
    fun fitSpan(sizes: IntArray, tracks: List<Track>, gap: Int, from: Int, span: Int, needed: Int) {
        val last = (from + span - 1).coerceAtMost(sizes.lastIndex)
        if (from > last) return
        val have = (from..last).sumOf { sizes[it] } + gaps(last - from + 1, gap)
        if (have >= needed) return
        val grow = (last downTo from).firstOrNull { tracks[it] !is Track.Cells } ?: return
        sizes[grow] += needed - have
    }

    /** Where each track starts, in cells. */
    fun starts(sizes: IntArray, gap: Int): IntArray {
        val starts = IntArray(sizes.size)
        var at = 0
        for (i in sizes.indices) {
            starts[i] = at
            at += sizes[i] + gap
        }
        return starts
    }

    /** The whole length of [sizes] with the gaps between them. */
    fun total(sizes: IntArray, gap: Int) = sizes.sum() + gaps(sizes.size, gap)

    private fun gaps(count: Int, gap: Int) = gap * (count - 1).coerceAtLeast(0)

    /** Splits [free] cells between the `fr` tracks by weight; leftover cells go to the first ones. */
    private fun share(sizes: IntArray, tracks: List<Track>, fractions: List<Int>, free: Int) {
        val total = fractions.sumOf { (tracks[it] as Track.Fr).weight.toDouble() }.takeIf { it > 0 } ?: return
        var given = 0
        for (i in fractions) {
            val part = floor(free * (tracks[i] as Track.Fr).weight / total).toInt()
            sizes[i] += part
            given += part
        }
        var k = 0
        while (given < free) {
            sizes[fractions[k % fractions.size]]++
            given++
            k++
        }
    }

    /** Takes [over] cells back: from `auto` tracks first, biggest first, then from any; none below one cell. */
    private fun shrink(sizes: IntArray, tracks: List<Track>, over: Int) {
        var left = over
        for (autoFirst in listOf(true, false)) {
            while (left > 0) {
                val biggest = sizes.indices
                    .filter { (!autoFirst || tracks[it] is Track.Auto) && sizes[it] > 1 }
                    .maxByOrNull { sizes[it] } ?: break
                sizes[biggest]--
                left--
            }
        }
    }
}
