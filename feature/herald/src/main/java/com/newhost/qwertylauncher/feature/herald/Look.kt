package com.newhost.qwertylauncher.feature.herald

import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.Pose
import com.newhost.qwertylauncher.core.Prop
import java.util.concurrent.ConcurrentHashMap

/**
 * How Sir Alex is drawn: [columns] by [lines] cells, his feet on the last
 * line. As a picture turned into coloured ASCII art when the ascii art
 * module is on ([PictureLook]); as plain text when it isn't ([TextLook]).
 */
internal interface Look {
    val columns: Int
    val lines: Int

    /**
     * [move], facing [right], his box's left edge at [x] and his feet on
     * line [feet], saying [says]. [size], when not [lines], draws him that
     * many lines tall instead, centred on his box (by his tree, to its
     * scale); [props] go behind him.
     */
    suspend fun pose(
        move: Move,
        right: Boolean,
        x: Int,
        feet: Int,
        says: String?,
        size: Int = lines,
        props: List<Prop> = emptyList(),
    ): Pose
}

/**
 * His picture in [outfit] (changed as he changes clothes), drawn as ASCII
 * art by [renderer], each pose in each outfit once and then remembered.
 */
internal class PictureLook(private val renderer: IconRenderer, @Volatile var outfit: Outfit) : Look {
    override val columns = COLUMNS
    override val lines = LINES

    private val drawn = ConcurrentHashMap<String, CharSequence>()

    override suspend fun pose(move: Move, right: Boolean, x: Int, feet: Int, says: String?, size: Int, props: List<Prop>): Pose {
        val outfit = outfit
        val key = "${outfit.id}:${move.id}:$right"
        val picture = drawn[key] ?: renderer.render(KnightDrawable(move.posture, outfit, right), ART_COLUMNS, ART_ROWS)
            .also { drawn[key] = it }
        // The same picture in a smaller (or bigger) box, if he's to be: it's drawn to fit, centred on his box.
        val side = size.coerceAtLeast(1)
        return Pose(
            x + (columns - side) / 2,
            feet - side + 1,
            says = says,
            picture = picture,
            columns = side,
            lines = side,
            props = props,
        )
    }

    private companion object {
        /** On the home screen: 6 cells square, small beside a tile. */
        const val COLUMNS = 6
        const val LINES = 6

        /**
         * In art characters: 48 across by 48 down (characters are twice as
         * tall as wide, so a 48 x 96 picture), about 8 to a cell: fine detail.
         */
        const val ART_COLUMNS = 48
        const val ART_ROWS = 48
    }
}

/** His plain text frames, in the palette of their parts. */
internal object TextLook : Look {
    override val columns = Sprites.WIDTH
    override val lines = Sprites.TALL

    override suspend fun pose(move: Move, right: Boolean, x: Int, feet: Int, says: String?, size: Int, props: List<Prop>): Pose {
        val frame = if (right) move.frame else Sprites.mirror(move.frame)
        return Pose(x, feet - frame.height + 1, frame.art, says = says, paint = frame.paint, palette = Sprites.PALETTE, props = props)
    }
}
