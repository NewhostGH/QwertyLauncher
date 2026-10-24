package com.newhost.qwertylauncher.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A home screen tile as a character sees it, in terminal cells from the top
 * left of the tiles' area: [line] is its top line (its title rule, when it
 * has a frame), and it's [columns] wide and [lines] tall.
 */
data class StageTile(
    val id: String,
    val title: String,
    val column: Int,
    val line: Int,
    val columns: Int,
    val lines: Int,
)

/**
 * The home screen's tiles, for [Actor]s: [columns] across and [lines] down
 * (all of them, whether scrolled into view or not), where each tile is, and
 * which lines are on screen now ([visibleTop], [visibleLines] of them).
 */
data class Stage(
    val columns: Int,
    val lines: Int,
    val tiles: List<StageTile>,
    val visibleTop: Int = 0,
    val visibleLines: Int = lines,
) {
    fun tile(id: String): StageTile? = tiles.firstOrNull { it.id == id }

    companion object {
        val EMPTY = Stage(0, 0, emptyList())
    }
}

/**
 * One moment of a character: its picture, [art], as lines of plain text
 * (spaces are see-through), its top left at [column], [line] on the
 * [Stage]; and what it [says], in a box by it. It may stand partly off the
 * stage, e.g. walking in.
 *
 * Each character is coloured by its key in [paint] (the same shape as
 * [art]) looked up in [palette]; without one, [color], else the terminal's.
 *
 * Or, finer: a [picture] already drawn as text (by an [IconRenderer],
 * coloured with spans), shrunk to fit [columns] by [lines] cells, like
 * other ASCII art, in place of [art].
 */
data class Pose(
    val column: Int,
    val line: Int,
    val art: List<String> = emptyList(),
    val color: Int? = null,
    val says: String? = null,
    val paint: List<String> = emptyList(),
    val palette: Map<Char, Int> = emptyMap(),
    val picture: CharSequence? = null,
    val columns: Int = 0,
    val lines: Int = 0,
    /** Things of his drawn behind him, like a ladder he's climbing. */
    val props: List<Prop> = emptyList(),
) {
    /** How wide and tall it is on the stage, in cells. */
    val width: Int get() = if (picture != null) columns else art.maxOfOrNull { it.length } ?: 0
    val height: Int get() = if (picture != null) lines else art.size
}

/**
 * Something a character has set down on the stage, like a ladder: plain
 * text [art] (spaces see-through) with its top left at [column], [line],
 * in [color]. Drawn behind the character.
 */
data class Prop(val column: Int, val line: Int, val art: List<String>, val color: Int)

/**
 * A character living on the home screen, on top of the tiles: it walks
 * over them, and the shell draws it on the text grid, like everything else.
 */
interface Actor {
    val id: String

    /**
     * Its poses, one after another, on the latest [stage]; null while it's
     * away. Collected while the home screen is started.
     */
    fun perform(stage: StateFlow<Stage>): Flow<Pose?>

    /** The user tapped the character or what it's saying. Main thread. */
    fun onPoke(host: LauncherHost) {}

    /**
     * The user is dragging the character: its art's top left is under their
     * finger at [column], [line]. [dropped] when they let go there. Main thread.
     */
    fun onDrag(column: Int, line: Int, dropped: Boolean) {}
}
