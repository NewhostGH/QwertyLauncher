package com.newhost.qwertylauncher.feature.herald

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.Align
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.grid
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/**
 * Sir Alex's realm: for now an oak with his pennant on top, where he
 * lives between visits (drawn by [RealmDrawable]; [art] is its small text
 * version). The realm has a [level], which quest gold raises: the oak, then
 * a treehouse in it, then a little stone tower beside it.
 */
internal class Realm(context: Context, private val chronicle: Chronicle) {
    private val prefs = context.getSharedPreferences("herald", Context.MODE_PRIVATE)

    /**
     * How grand the realm is, 1 to [LEVELS]: as the gold earned from quests
     * affords ([LEVEL_GOLD]), or as set (for donations, one day), whichever is more.
     */
    val level: Int
        get() {
            val earned = 1 + LEVEL_GOLD.count { chronicle.gold >= it }
            return maxOf(prefs.getInt(LEVEL, 1), earned).coerceIn(1, LEVELS)
        }

    /** How `alex debug` has the realm drawn, over how it really is. */
    val preview = MutableStateFlow(RealmPreview())

    /** The level as shown: previewed, or real. */
    val shownLevel: Int get() = preview.value.level ?: level

    /**
     * The realm drawn with each character's colour key (see
     * [Sprites.PALETTE] and [TREE_PALETTE]), [home] or not, [frame] for
     * the snores drifting up.
     */
    fun art(home: Boolean, frame: Int): List<Pair<String, String>> {
        // Only the first level so far; later ones will draw bigger.
        val snore = SNORES[frame % SNORES.size]
        val window = if (home) "o" else "."
        val door = if (home) "n" else " "
        val lines = listOf(
            "        |>        " to "        br        ",
            "      _,|,_       " to "      llbll       ",
            "   ,&&&%&&&&,     " to "   lgGgdgGgGl     ",
            "  &&%&&&&&%&&&  " + (if (home) snore else "  ") to "  gGdgGgGgdgGg  dd",
            " &&&&&&%&&&&&&&   " to " GgGgGgdgGgGgGgG  ",
            "  &&%&&&&&&&&&    " to "  gGdgGgGgGgGgG   ",
            "   '&&&&&%&&&'    " to "   lGgGgGdgGgl    ",
            "      \\\\ | //     " to "      bb b bb     ",
            "       |$window|        " to "       b${if (home) "y" else "k"}b        ",
            "  ,,,__|$door|__,,,   " to "  lll__bbb__lll   ",
        )
        return lines.map { (art, paint) -> art.padEnd(WIDTH) to paint.padEnd(WIDTH).replace('_', 'b') }
    }

    companion object {
        const val WIDTH = 18
        private const val LEVEL = "realm_level"
        private const val LEVELS = 3

        /** Gold earned for the treehouse, and for the tower. */
        private val LEVEL_GOLD = listOf(30, 80)
        private val SNORES = listOf("z ", " z", "Z ", " Z")

        /** The tree's own colours, beside the knight's. */
        val TREE_PALETTE = Sprites.PALETTE + mapOf(
            'g' to 0xFF3FAF3F.toInt(),
            'G' to 0xFF2E8B3A.toInt(),
            'd' to 0xFF1F6B2A.toInt(),
            'l' to 0xFF8FD46A.toInt(),
            'k' to 0xFF3A2A1A.toInt(),
        )
    }
}

/** A look for the realm from `alex debug`; each null is as it really is. */
internal data class RealmPreview(
    val level: Int? = null,
    val season: Season? = null,
    val holiday: Holiday? = null,
    val night: Boolean? = null,
    /** Every chore done (true), none (false). */
    val chores: Boolean? = null,
) {
    companion object {
        /** The chores the realm can show done. */
        val CHORES = setOf("swept", "woodpile", "banner", "mended")
    }
}

/**
 * The realm's tile: his oak drawn as ASCII art, filling the tile, lit while
 * he's home; by day or by night after the clock. Without the ascii art
 * module, a small tree in text. Tapping it knocks on his door.
 */
internal class RealmWidget(
    private val realm: Realm,
    private val alex: Alex,
    private val services: Services,
    private val life: Life,
    private val chronicle: Chronicle,
) : HomeWidget {
    override val id = "realm"
    override val title = "realm of Quertia"
    override val order = 90
    override val size = TileSize.WIDE
    override val minColumns = Realm.WIDTH + 2
    override val minLines = 6

    /** Frames for the moving art (pennant, stars, snores), or a slow tick without the ascii art module. */
    private fun frames(): Flow<Int> = services.find<ArtAnimator>()?.frames(FRAME_MS) ?: flow {
        var frame = 0
        while (true) {
            emit(frame++)
            delay(TEXT_FRAME_MS)
        }
    }

    override fun render(): Flow<WidgetContent> = combine(alex.out, frames(), life.changes, realm.preview) { out, frame, _, preview ->
        val home = !out
        if (services.find<IconRenderer>() == null) return@combine textTree(home, frame)
        val hour = LocalTime.now().hour
        val night = preview.night ?: (hour < DAWN || hour >= DUSK)
        val level = preview.level ?: realm.level
        val season = preview.season ?: life.season()
        val holiday = preview.holiday ?: life.holiday()
        val chores = when (preview.chores) {
            true -> RealmPreview.CHORES
            false -> emptySet()
            null -> life.choresToday()
        }
        // Filling the tile whatever its shape: the scene widens or gains sky to fit.
        val key = "realm:$level:$home:$night:$season:$holiday:${chores.sorted().joinToString(",")}"
        val picture = Cell.Art(key, Size(ART_COLUMNS, ART_LINES), frame = frame, fill = true) {
            RealmDrawable(level, home, night, frame, season, holiday, chores)
        }
        WidgetContent(
            if (home) "Sir Alex is home" else "Sir Alex is out",
            layout = grid {
                columns(fr())
                rows(fr())
                // His door is in the middle of the tile, at the bottom.
                add(picture)
            },
        )
    }

    private fun textTree(home: Boolean, frame: Int): WidgetContent {
        val text = SpannableStringBuilder()
        realm.art(home = home, frame = frame).forEachIndexed { row, (art, paint) ->
            if (row > 0) text.append('\n')
            art.forEachIndexed { col, char ->
                val start = text.length
                text.append(char)
                val color = Realm.TREE_PALETTE[paint.getOrElse(col) { ' ' }]
                if (char != ' ' && color != null) text.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return WidgetContent(
            text,
            layout = grid {
                columns(fr())
                rows(fr())
                add(Cell.Text(text, wrap = false), alignX = Align.CENTER, alignY = Align.END)
            },
        )
    }

    /** Knocks on his door: out he comes (or, if he's out, he's poked). */
    override fun onClick(host: LauncherHost) {
        chronicle.knocked()
        if (alex.out.value) alex.pokes.trySend(Unit) else alex.knock()
    }

    private companion object {
        // Each frame draws the whole scene again: slow enough to be cheap, quick enough for a flag and snores.
        const val FRAME_MS = 800L
        const val TEXT_FRAME_MS = 900L

        /** The size the picture would like, in cells, when the tile's height is left to it. */
        const val ART_COLUMNS = 30
        const val ART_LINES = 9

        /** Day from 7 until 19; night otherwise. */
        const val DAWN = 7
        const val DUSK = 19
    }
}
