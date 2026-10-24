package com.newhost.qwertylauncher.feature.herald

import com.newhost.qwertylauncher.core.Stage
import com.newhost.qwertylauncher.core.StageTile
import kotlin.math.abs

/**
 * Somewhere he can stand: his feet on line [feet], his left edge anywhere
 * from [left] to [right]; on a tile ([tile]) or the ground (null).
 */
internal data class Platform(val left: Int, val right: Int, val feet: Int, val tile: String?) {
    fun holds(x: Int) = x in left..right

    fun nearest(x: Int) = x.coerceIn(left, right)
}

/**
 * What Sir Alex sees of the home screen, [wide] by [tall] himself: the
 * ceiling (the top of what's shown), the floor (the bottom of it, or of the
 * tiles if they end sooner), the walls either side, and every surface he
 * can stand on: each tile's title rule (with room for him above it), each
 * tile's bottom line, and the ground. Everywhere he goes is checked against
 * it, so he's never left somewhere he can't be.
 */
internal class World(stage: Stage, val wide: Int, val tall: Int, private val realm: String? = null) {
    /** The highest line his head may reach, and the lowest his feet may. */
    val ceiling = stage.visibleTop
    val floor = maxOf(minOf(stage.visibleTop + stage.visibleLines, stage.lines) - 1, ceiling + tall - 1)

    /** The leftmost and rightmost his left edge may be. */
    val leftWall = 0
    val rightWall = maxOf(stage.columns - wide, 0)
    val columns = stage.columns

    val tiles: List<StageTile> = stage.tiles

    /** Every surface in view, highest first, ending with the ground. */
    val platforms: List<Platform> = buildList {
        for (t in stage.tiles) {
            if (t.columns < wide) continue
            val left = t.column
            val right = t.column + t.columns - wide
            // Standing on its title rule, with room for him above it; not his realm's: that he walks
            // into, down to the ground in front of his tree.
            val rule = t.line - 1
            if (t.id != realm && rule - tall + 1 >= ceiling && rule <= floor) add(Platform(left, right, rule, t.id))
            // Standing on its bottom line.
            val bottom = t.line + t.lines - 1
            if (bottom - tall + 1 >= ceiling && bottom <= floor && bottom != rule) add(Platform(left, right, bottom, t.id))
        }
        add(Platform(leftWall, rightWall, floor, null))
    }.distinct().sortedBy { it.feet }

    val ground: Platform get() = platforms.last()

    /** Whether he'd be in view, feet at [feet]. */
    fun inView(feet: Int) = feet - tall + 1 >= ceiling && feet <= floor

    /** The surface he's standing on at [x], [feet], if any. */
    fun standingOn(x: Int, feet: Int): Platform? = platforms.firstOrNull { it.feet == feet && it.holds(x) }

    /** Where he lands falling from [x], [feet]: the first surface at or below, under him. The ground at worst. */
    fun below(x: Int, feet: Int): Platform =
        platforms.firstOrNull { it.feet >= feet && it.holds(x) } ?: ground

    /** The nearest surface to [x], [feet], counting a line up or down as two columns across. */
    fun nearest(x: Int, feet: Int): Platform =
        platforms.minByOrNull { abs(it.feet - feet) * 2 + abs(it.nearest(x) - x) } ?: ground

    /** Surfaces on tiles (not the ground), for going somewhere. */
    fun onTiles(except: String? = null) = platforms.filter { it.tile != null && it.tile != except }

    fun tile(id: String): StageTile? = tiles.firstOrNull { it.id == id }

    fun clampX(x: Int) = x.coerceIn(leftWall, rightWall)
}
