package com.newhost.qwertylauncher.feature.herald

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.sin

/**
 * Sir Alex's realm drawn in bold shapes on a 200 x 120 canvas, for the ascii
 * art module to turn into coloured characters: an oak with a door and a
 * round window in its trunk, his pennant flying from the crown, hedges at
 * its foot (he's a hedge knight), a signpost, a lantern, a path; the sun and
 * a drifting cloud by day, the moon, stars and fireflies by [night].
 *
 * [home]: the window glows and snores drift out of it; else the door stands
 * open. [frame] moves it (the pennant waves, the stars twinkle); 0 is still.
 * [level] is how grand the realm is: the oak; with a treehouse in its crown
 * (2); and a little stone tower beside it (3). The [season] colours the
 * leaves (blossom, green, orange, snow), a [holiday] decorates it (a
 * pumpkin, lights, bunting for his name day), and today's [chores] show:
 * a woodpile, a banner, a swept path, a mended door.
 */
internal class RealmDrawable(
    private val level: Int,
    private val home: Boolean,
    private val night: Boolean,
    private val frame: Int,
    private val season: Season = Season.SUMMER,
    private val holiday: Holiday = Holiday.NONE,
    private val chores: Set<String> = emptySet(),
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // The leaves, by the season: blossom in spring, orange in autumn, snow-capped in winter.
    private val leaf = when (season) {
        Season.AUTUMN -> 0xFFE8892B.toInt()
        Season.WINTER -> 0xFF2F8A4A.toInt()
        else -> LEAF
    }
    private val leafDark = when (season) {
        Season.AUTUMN -> 0xFFB5501F.toInt()
        Season.WINTER -> 0xFF1E6536.toInt()
        else -> LEAF_DARK
    }
    private val leafLight = when (season) {
        Season.AUTUMN -> 0xFFFFC04D.toInt()
        Season.WINTER -> SNOW
        Season.SPRING -> 0xFFFFB3D9.toInt()
        else -> LEAF_LIGHT
    }

    /** A gentle back and forth, -1 to 1, for things that sway. */
    private val sway = if (frame == 0) 0f else sin(frame * 0.6).toFloat()

    override fun getIntrinsicWidth() = W.toInt()
    override fun getIntrinsicHeight() = H.toInt()

    /**
     * Fitted to whatever shape it's given: the oak stays in the middle, on
     * the ground; a wider realm grows more hedges and saplings to the sides,
     * a taller one more sky.
     */
    override fun draw(canvas: Canvas) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val aspect = bounds.width().toFloat() / bounds.height()
        val w = maxOf(W, H * aspect)
        val h = maxOf(H, W / aspect)
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / w, bounds.height() / h)
        // The middle 200 x 120, on the ground, where the oak stands.
        val left = (w - W) / 2
        val top = h - H
        if (night) nightSky(canvas, w, top) else daySky(canvas, w)
        countryside(canvas, w, left, top)
        canvas.save()
        canvas.translate(left, top)
        if (level >= 3) tower(canvas)
        hedges(canvas)
        signpost(canvas)
        tree(canvas)
        if (level >= 2) treehouse(canvas)
        pennant(canvas)
        door(canvas)
        window(canvas)
        lantern(canvas)
        if ("banner" in chores) banner(canvas)
        if ("woodpile" in chores) woodpile(canvas)
        when (holiday) {
            Holiday.HALLOWEEN -> pumpkin(canvas)
            Holiday.CHRISTMAS -> lights(canvas)
            Holiday.NAME_DAY -> bunting(canvas)
            Holiday.NONE -> Unit
        }
        canvas.restore()
        ground(canvas, w, h, left, top)
        canvas.save()
        canvas.translate(left, top)
        mushrooms(canvas)
        if (season == Season.AUTUMN && "swept" !in chores) fallenLeaves(canvas)
        if (home) snores(canvas)
        canvas.restore()
        if (season == Season.AUTUMN) falling(canvas, w, top)
        if (season == Season.WINTER && !night) snowfall(canvas, w, h)
        if (night) fireflies(canvas, w, top)
        canvas.restore()
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
    }

    private fun daySky(c: Canvas, w: Float) {
        fill(SUN)
        c.drawCircle(24f, 20f, 10f, paint)
        stroke(SUN, 2.5f)
        for (i in 0 until 8) {
            c.save()
            c.rotate(i * 45f + frame * 5f, 24f, 20f)
            c.drawLine(24f, 5f, 24f, 1f, paint)
            c.restore()
        }
        // A cloud drifting across, near the right edge.
        val x = w - 50f + sway * 8f
        fill(CLOUD)
        c.drawCircle(x, 18f, 8f, paint)
        c.drawCircle(x + 9f, 14f, 9f, paint)
        c.drawCircle(x + 19f, 18f, 7f, paint)
        c.drawRoundRect(RectF(x - 6f, 16f, x + 25f, 26f), 5f, 5f, paint)
    }

    private fun nightSky(c: Canvas, w: Float, top: Float) {
        // A crescent moon, and stars across all the sky there is, twinkling out in turn.
        fill(MOON)
        c.drawCircle(24f, 20f, 11f, paint)
        paint.xfermode = CLEAR
        c.drawCircle(30f, 16f, 10f, paint)
        paint.xfermode = null
        fill(STAR)
        val sky = top + SKY
        val count = (w * sky / STAR_AREA).toInt().coerceAtLeast(STARS_MIN)
        for (i in 0 until count) {
            // Scattered, but the same every frame.
            val x = (i * 67f + 45f) % w
            val y = 4f + (i * 29f + 11f) % (sky - 4f)
            if (x in 12f..40f && y < 34f) continue
            if ((frame + i) % 5 != 0) c.drawCircle(x, y, 1.5f, paint)
        }
    }

    /** Past the realm's middle, to each side: more hedges, with saplings between. */
    private fun countryside(c: Canvas, w: Float, left: Float, top: Float) {
        var i = 1
        while (left + 44f - i * SPREAD > -SPREAD || left + 156f + i * SPREAD < w + SPREAD) {
            for (x in listOf(left + 44f - i * SPREAD, left + 156f + i * SPREAD)) {
                if (x < -SPREAD || x > w + SPREAD) continue
                if (i % 2 == 1) sapling(c, x, top) else hedge(c, x, top, 30f)
            }
            i++
        }
    }

    private fun sapling(c: Canvas, x: Float, top: Float) {
        fill(BARK)
        c.drawRect(x - 2.5f, top + 88f, x + 2.5f, top + 114f, paint)
        fill(leafDark)
        c.drawCircle(x, top + 82f, 14f, paint)
        fill(leaf)
        c.drawCircle(x - 1f, top + 79f, 11f, paint)
        fill(leafLight)
        c.drawCircle(x - 5f, top + 75f, 4f, paint)
    }

    private fun hedges(c: Canvas) {
        hedge(c, 44f, 0f, 34f)
        hedge(c, 156f, 0f, 34f)
    }

    private fun hedge(c: Canvas, x: Float, top: Float, w: Float) {
        fill(HEDGE_DARK)
        c.drawRoundRect(RectF(x - w / 2, top + 92f, x + w / 2, top + 114f), 9f, 9f, paint)
        fill(HEDGE)
        for (i in 0 until 4) c.drawCircle(x - w / 2 + 5f + i * (w - 10f) / 3, top + 95f, 6f, paint)
        fill(if (season == Season.WINTER) SNOW else leafLight)
        if (season == Season.WINTER) c.drawRoundRect(RectF(x - w / 2 + 2f, top + 89f, x + w / 2 - 2f, top + 94f), 3f, 3f, paint)
        c.drawCircle(x - w / 4, top + 93f, 2.5f, paint)
        c.drawCircle(x + w / 4, top + 94f, 2.5f, paint)
    }

    private fun signpost(c: Canvas) {
        fill(WOOD_DARK)
        c.drawRect(22f, 92f, 25f, 114f, paint)
        fill(WOOD)
        c.drawPath(Path().apply {
            moveTo(10f, 88f)
            lineTo(34f, 88f)
            lineTo(39f, 92.5f)
            lineTo(34f, 97f)
            lineTo(10f, 97f)
            close()
        }, paint)
        // The realm's name, too small to read: just its marks.
        stroke(WOOD_DARK, 1.5f)
        c.drawLine(13f, 92.5f, 31f, 92.5f, paint)
    }

    private fun tree(c: Canvas) {
        // The trunk, flaring into roots.
        fill(BARK)
        c.drawPath(Path().apply {
            moveTo(80f, 114f)
            quadTo(90f, 108f, 90f, 92f)
            lineTo(92f, 56f)
            lineTo(108f, 56f)
            lineTo(110f, 92f)
            quadTo(110f, 108f, 120f, 114f)
            close()
        }, paint)
        stroke(BARK_DARK, 1.5f)
        c.drawLine(95f, 60f, 94f, 86f, paint)
        c.drawLine(106f, 62f, 107f, 84f, paint)
        // The crown: darker underneath, lit on top.
        fill(leafDark)
        for ((x, y, r) in CROWN) c.drawCircle(x, y + 4f, r, paint)
        fill(leaf)
        for ((x, y, r) in CROWN) c.drawCircle(x - 1f, y, r - 2f, paint)
        fill(leafLight)
        for ((x, y, r) in CROWN) c.drawCircle(x - r / 3, y - r / 3, r / 3, paint)
        if (season == Season.WINTER) {
            // Snow lying on top of each clump.
            fill(SNOW)
            for ((x, y, r) in CROWN) c.drawArc(RectF(x - r, y - r - 1f, x + r, y + r / 2), 200f, 140f, true, paint)
        }
        // Apples in summer and autumn, blossom in spring.
        if (season == Season.SUMMER || season == Season.AUTUMN) {
            fill(APPLE)
            for ((x, y) in listOf(78f to 52f, 118f to 44f, 96f to 34f, 128f to 58f)) c.drawCircle(x, y, 2.4f, paint)
        } else if (season == Season.SPRING) {
            fill(BLOSSOM)
            for ((x, y) in listOf(78f to 48f, 118f to 40f, 96f to 30f, 128f to 54f, 86f to 22f, 110f to 18f, 70f to 58f)) c.drawCircle(x, y, 2.2f, paint)
        }
    }

    private fun pennant(c: Canvas) {
        // A pole from the crown, and his red pennant with a gold stripe, waving.
        stroke(WOOD_DARK, 2f)
        c.drawLine(118f, 22f, 124f, 0f, paint)
        val tip = 146f + sway * 3f
        val dip = 7f + sway * 2.5f
        fill(PENNANT)
        c.drawPath(Path().apply {
            moveTo(124f, 1f)
            quadTo(136f, 3f + sway * 2f, tip, dip)
            quadTo(136f, 10f + sway * 2f, 123f, 12f)
            close()
        }, paint)
        stroke(GOLD, 1.5f)
        c.drawLine(125f, 6.5f, tip - 6f, dip, paint)
    }

    /** His door: 30 units tall (84 to 114), 16 wide, big enough for him (he's drawn to fit it). */
    private fun door(c: Canvas) {
        val arch = Path().apply {
            moveTo(92f, 114f)
            lineTo(92f, 94f)
            quadTo(100f, 78f, 108f, 94f)
            lineTo(108f, 114f)
            close()
        }
        if (home) {
            fill(DOOR)
            c.drawPath(arch, paint)
            stroke(BARK_DARK, 1f)
            c.drawLine(100f, 88f, 100f, 114f, paint)
            fill(GOLD)
            c.drawCircle(104.5f, 104f, 1.4f, paint)
            if ("mended" in chores) mend(c)
        } else {
            // Open: dark inside, the door swung out.
            fill(DARK)
            c.drawPath(arch, paint)
            fill(DOOR)
            c.drawPath(Path().apply {
                moveTo(108f, 114f)
                lineTo(108f, 94f)
                lineTo(114f, 97f)
                lineTo(114f, 115f)
                close()
            }, paint)
            if ("mended" in chores) {
                fill(WOOD)
                c.drawRect(109f, 101f, 113.5f, 104f, paint)
            }
        }
    }

    private fun window(c: Canvas) {
        // Lit while he's home, flickering like a candle.
        fill(BARK_DARK)
        c.drawCircle(100f, 68f, 6.5f, paint)
        fill(if (!home) DARK else if (frame % 4 == 3) CANDLE_LOW else CANDLE)
        c.drawCircle(100f, 68f, 5f, paint)
        stroke(BARK_DARK, 1.2f)
        c.drawLine(95f, 68f, 105f, 68f, paint)
        c.drawLine(100f, 63f, 100f, 73f, paint)
    }

    private fun lantern(c: Canvas) {
        fill(WOOD_DARK)
        c.drawRect(131f, 94f, 133f, 114f, paint)
        c.drawRect(127f, 86f, 137f, 88f, paint)
        fill(if (night) CANDLE else CANDLE_LOW)
        c.drawRoundRect(RectF(128f, 88f, 136f, 96f), 2f, 2f, paint)
        if (night) {
            // Its glow.
            fill(GLOW)
            c.drawCircle(132f, 92f, 9f, paint)
            fill(CANDLE)
            c.drawRoundRect(RectF(128f, 88f, 136f, 96f), 2f, 2f, paint)
        }
    }

    /** Grass all the way across, tufts and flowers in it, and the path to his door. */
    private fun ground(c: Canvas, w: Float, h: Float, left: Float, top: Float) {
        val grass = top + 112f
        fill(if (season == Season.WINTER) SNOW else GRASS)
        c.drawRect(0f, grass, w, h, paint)
        fill(if ("swept" in chores) PATH_SWEPT else PATH)
        c.drawPath(Path().apply {
            moveTo(left + 94f, grass)
            lineTo(left + 106f, grass)
            lineTo(left + 116f, h)
            lineTo(left + 84f, h)
            close()
        }, paint)
        var x = 6f
        var i = 0
        while (x < w) {
            // Not on the path.
            if (x !in left + 82f..left + 118f && season != Season.WINTER) {
                stroke(LEAF_LIGHT, 1.5f)
                c.drawLine(x, grass + 1f, x - 2f, grass - 3f, paint)
                c.drawLine(x, grass + 1f, x + 2f, grass - 3f, paint)
                if (i % 3 == 1) {
                    fill(FLOWERS[i / 3 % FLOWERS.size])
                    c.drawCircle(x + 5f, grass - 2f, 1.8f, paint)
                }
            }
            x += TUFT_STEP
            i++
        }
    }

    private fun mushrooms(c: Canvas) {
        for (x in listOf(76f, 124f)) {
            fill(CLOUD)
            c.drawRect(x - 1.2f, 108f, x + 1.2f, 113f, paint)
            fill(APPLE)
            c.drawArc(RectF(x - 4.5f, 104f, x + 4.5f, 112f), 180f, 180f, true, paint)
            fill(CLOUD)
            c.drawCircle(x - 1.5f, 106f, 0.9f, paint)
            c.drawCircle(x + 2f, 107f, 0.8f, paint)
        }
    }

    /** Z's drifting up out of the window. */
    private fun snores(c: Canvas) {
        stroke(SNORE, 1.6f)
        for (i in 0 until 3) {
            val step = (frame + i * 2) % 6
            val x = 108f + step * 4f + i * 2f
            val y = 62f - step * 7f
            val s = 2.5f + step * 0.6f
            c.drawPath(Path().apply {
                moveTo(x, y)
                lineTo(x + s, y)
                lineTo(x, y + s)
                lineTo(x + s, y + s)
            }, paint)
        }
    }

    private fun fireflies(c: Canvas, w: Float, top: Float) {
        fill(FIREFLY)
        val count = (w / FIREFLY_SPACING).toInt().coerceAtLeast(3)
        for (i in 0 until count) {
            val t = frame * 0.5 + i * 1.7
            val x = 30f + i * FIREFLY_SPACING + (sin(t) * 6).toFloat()
            val y = top + 72f + (sin(t * 1.3 + i) * 10).toFloat()
            if ((frame + i) % 3 != 0) c.drawCircle(x, y, 1.5f, paint)
        }
    }

    /** A new plank nailed across the door, bright nails. */
    private fun mend(c: Canvas) {
        fill(WOOD)
        c.drawRect(93f, 100f, 107f, 104f, paint)
        fill(STEEL)
        c.drawCircle(95f, 102f, 0.9f, paint)
        c.drawCircle(105f, 102f, 0.9f, paint)
    }

    /** His banner hung from the lowest bough: red with the gold tree. */
    private fun banner(c: Canvas) {
        stroke(WOOD_DARK, 1.2f)
        c.drawLine(64f, 60f, 76f, 60f, paint)
        fill(PENNANT)
        c.drawPath(Path().apply {
            moveTo(65f, 60f)
            lineTo(75f, 60f)
            lineTo(75f, 80f)
            lineTo(70f, 76f + sway)
            lineTo(65f, 80f)
            close()
        }, paint)
        fill(GOLD)
        c.drawCircle(70f, 66f, 2.4f, paint)
        c.drawRect(69.4f, 67f, 70.6f, 71f, paint)
    }

    /** Logs he chopped, stacked by the hedge. */
    private fun woodpile(c: Canvas) {
        for ((i, row) in listOf(3, 2, 1).withIndex()) {
            for (j in 0 until row) {
                val x = 140f + j * 6f + i * 3f
                val y = 110f - i * 5f
                fill(BARK)
                c.drawCircle(x, y, 2.8f, paint)
                fill(WOOD)
                c.drawCircle(x, y, 1.6f, paint)
            }
        }
    }

    /** A treehouse up in the crown, with a rope ladder down. */
    private fun treehouse(c: Canvas) {
        fill(WOOD_DARK)
        c.drawRect(108f, 36f, 142f, 39f, paint)
        fill(WOOD)
        c.drawRect(112f, 22f, 138f, 36f, paint)
        fill(PENNANT)
        c.drawPath(Path().apply {
            moveTo(109f, 23f)
            lineTo(125f, 12f)
            lineTo(141f, 23f)
            close()
        }, paint)
        fill(if (home) CANDLE else DARK)
        c.drawRect(121f, 26f, 128f, 32f, paint)
        stroke(WOOD_DARK, 1f)
        c.drawLine(138f, 39f, 138f, 86f, paint)
        c.drawLine(142f, 39f, 142f, 86f, paint)
        for (y in 44 until 86 step 6) c.drawLine(138f, y.toFloat(), 142f, y.toFloat(), paint)
    }

    /** A little stone tower to the left, a flag on top. */
    private fun tower(c: Canvas) {
        fill(STONE_DARK)
        c.drawRect(52f, 52f, 72f, 114f, paint)
        fill(STONE)
        for (row in 0 until 8) for (col in 0 until 2) {
            val x = 53f + col * 10f + (row % 2) * 4f
            c.drawRect(x, 54f + row * 7.5f, (x + 8f).coerceAtMost(71f), 59.5f + row * 7.5f, paint)
        }
        fill(STONE_DARK)
        for (i in 0 until 3) c.drawRect(50f + i * 9f, 45f, 56f + i * 9f, 53f, paint)
        fill(DARK)
        c.drawRoundRect(RectF(59f, 64f, 65f, 74f), 3f, 3f, paint)
        stroke(WOOD_DARK, 1.5f)
        c.drawLine(62f, 45f, 62f, 30f, paint)
        fill(0xFF4B8BFF.toInt())
        c.drawRect(62f, 30f, 72f + sway * 2f, 36f, paint)
    }

    private fun pumpkin(c: Canvas) {
        fill(0xFFFF8A1F.toInt())
        c.drawOval(RectF(78f, 104f, 90f, 114f), paint)
        fill(0xFF3D7A2A.toInt())
        c.drawRect(83.5f, 101f, 85f, 104.5f, paint)
        fill(if (night) CANDLE else DARK)
        c.drawCircle(81.5f, 108f, 1f, paint)
        c.drawCircle(86.5f, 108f, 1f, paint)
        c.drawRect(81f, 110.5f, 87f, 111.5f, paint)
    }

    /** Coloured lights strung through the crown, twinkling. */
    private fun lights(c: Canvas) {
        val colors = listOf(APPLE, GOLD, 0xFF4B8BFF.toInt(), 0xFF46C95A.toInt())
        val spots = listOf(76f to 56f, 86f to 60f, 98f to 62f, 110f to 60f, 122f to 56f, 84f to 38f, 100f to 42f, 116f to 38f)
        spots.forEachIndexed { i, (x, y) ->
            if ((frame + i) % 3 != 0) {
                fill(colors[i % colors.size])
                c.drawCircle(x, y, 1.8f, paint)
            }
        }
    }

    /** Bunting from the pennant pole to the lantern: his name day. */
    private fun bunting(c: Canvas) {
        stroke(WOOD_DARK, 0.8f)
        c.drawLine(122f, 20f, 134f, 86f, paint)
        val colors = listOf(APPLE, GOLD, 0xFF4B8BFF.toInt(), 0xFF46C95A.toInt())
        for (i in 0 until 7) {
            val t = (i + 0.5f) / 7
            val x = 122f + 12f * t
            val y = 20f + 66f * t
            fill(colors[i % colors.size])
            c.drawPath(Path().apply {
                moveTo(x - 3f, y)
                lineTo(x + 3f, y + 1f)
                lineTo(x - 1f, y + 6f)
                close()
            }, paint)
        }
    }

    /** Leaves lying on the grass, until he sweeps. */
    private fun fallenLeaves(c: Canvas) {
        for (i in 0 until 9) {
            fill(if (i % 2 == 0) leaf else leafDark)
            c.drawOval(RectF(40f + i * 14f, 110f + (i % 3), 44f + i * 14f, 112.5f + (i % 3)), paint)
        }
    }

    /** Leaves drifting down across the scene. */
    private fun falling(c: Canvas, w: Float, top: Float) {
        for (i in 0 until (w / 40f).toInt().coerceAtLeast(3)) {
            val step = (frame + i * 3) % 12
            val x = 20f + i * 40f + (sin((frame + i) * 0.8) * 5).toFloat()
            val y = top + 30f + step * 7f
            fill(if (i % 2 == 0) leaf else leafLight)
            c.drawOval(RectF(x, y, x + 3.5f, y + 2f), paint)
        }
    }

    private fun snowfall(c: Canvas, w: Float, h: Float) {
        fill(SNOW)
        val count = (w * h / SNOW_AREA).toInt().coerceAtLeast(8)
        for (i in 0 until count) {
            val x = (i * 53f + 17f + frame * 1.5f) % w
            val y = (i * 31f + frame * 4f) % h
            c.drawCircle(x, y, 1.2f, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val W = 200f
        const val H = 120f

        /** The crown's leafy clumps: x, y, radius. */
        val CROWN = listOf(
            Triple(100f, 40f, 28f), Triple(72f, 50f, 18f), Triple(128f, 50f, 18f),
            Triple(84f, 26f, 18f), Triple(116f, 26f, 18f), Triple(100f, 16f, 15f),
        )
        /** How much sky there is above the realm's middle, before any extra; stars, one per this much of it. */
        const val SKY = 60f
        const val STAR_AREA = 1400f
        const val STARS_MIN = 6

        /** Hedges and saplings to the sides, this far apart; grass tufts; fireflies. */
        const val SPREAD = 56f
        const val TUFT_STEP = 13f
        const val FIREFLY_SPACING = 36f
        val CLEAR = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)

        val SUN = 0xFFFFD23F.toInt()
        val CLOUD = 0xFFF2F5F8.toInt()
        val MOON = 0xFFFFF1B5.toInt()
        val STAR = 0xFFFFFFFF.toInt()
        val LEAF = 0xFF3FB950.toInt()
        val LEAF_DARK = 0xFF23803A.toInt()
        val LEAF_LIGHT = 0xFF8FE07A.toInt()
        val HEDGE = 0xFF32A852.toInt()
        val HEDGE_DARK = 0xFF1F7A3A.toInt()
        val BARK = 0xFFA8703F.toInt()
        val BARK_DARK = 0xFF6B4423.toInt()
        val WOOD = 0xFFC99A5E.toInt()
        val WOOD_DARK = 0xFF7A5230.toInt()
        val DOOR = 0xFF8A5A2E.toInt()
        val DARK = 0xFF1E1A16.toInt()
        val GOLD = 0xFFFFD23F.toInt()
        val PENNANT = 0xFFFF4B4B.toInt()
        val CANDLE = 0xFFFFE066.toInt()
        val CANDLE_LOW = 0xFFFFA83A.toInt()
        val GLOW = 0x66FFD23F
        val GRASS = 0xFF46C95A.toInt()
        val PATH = 0xFFD9B27C.toInt()
        val FLOWERS = listOf(0xFFFF79C6.toInt(), 0xFFFFD23F.toInt(), 0xFF9AD0FF.toInt(), 0xFFFFFFFF.toInt())
        val APPLE = 0xFFFF5555.toInt()
        val SNORE = 0xFFB8D8FF.toInt()
        val FIREFLY = 0xFFE8FF6A.toInt()
        val SNOW = 0xFFF4F8FF.toInt()
        val BLOSSOM = 0xFFFFB3D9.toInt()
        val PATH_SWEPT = 0xFFEACB98.toInt()
        val STEEL = 0xFFD2DBE3.toInt()
        val STONE = 0xFFA7A9AE.toInt()
        val STONE_DARK = 0xFF6E7178.toInt()
        const val SNOW_AREA = 700f
    }
}
