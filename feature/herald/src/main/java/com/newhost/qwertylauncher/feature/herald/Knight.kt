package com.newhost.qwertylauncher.feature.herald

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** What he wears on his head. */
internal enum class Hat { HELM, FEATHER_CAP, HOOD, NIGHTCAP, CROWN }

/**
 * How Sir Alex looks: his colours, what's on his head, and what he wears
 * over his armour (a cape, a fur mantle, a scarf). The green of a hedge
 * knight most days; see [Outfit.ALL] for the rest, and [Wardrobe] for when
 * he wears which.
 */
internal data class Outfit(
    val id: String,
    /** What it's called, for `alex wear`. */
    val title: String,
    val steel: Int,
    val shadow: Int,
    val tabard: Int,
    val trim: Int,
    val plume: Int,
    val leather: Int,
    val wood: Int,
    val skin: Int,
    val blade: Int,
    val hat: Hat = Hat.HELM,
    /** A cape behind him, in this colour; with [scales], a dragon's. */
    val cape: Int? = null,
    val scales: Boolean = false,
    /** A fur mantle over his shoulders, and a scarf, in these colours. */
    val mantle: Int? = null,
    val scarf: Int? = null,
    /** A tourney knight's great plume. */
    val bigPlume: Boolean = false,
    /** Stripes across his tabard (pyjamas), in this colour. */
    val stripes: Int? = null,
    /** Gold earned from quests before he can wear it; 0 for always. */
    val cost: Int = 0,
) {
    companion object {
        val HEDGE_KNIGHT = Outfit(
            id = "hedge",
            title = "hedge knight",
            steel = 0xFFD2DBE3.toInt(),
            shadow = 0xFF8C99A6.toInt(),
            tabard = 0xFF46C95A.toInt(),
            trim = 0xFFFFD23F.toInt(),
            plume = 0xFFFF4B4B.toInt(),
            leather = 0xFFC08A52.toInt(),
            wood = 0xFFB9814A.toInt(),
            skin = 0xFFFFCFA3.toInt(),
            blade = 0xFFF7FAFD.toInt(),
        )

        /** Red and gold, a feathered cap: for proclaiming. */
        val HERALD = HEDGE_KNIGHT.copy(
            id = "herald", title = "herald", tabard = 0xFFE0413A.toInt(), trim = 0xFFFFD23F.toInt(),
            plume = 0xFFFFFFFF.toInt(), hat = Hat.FEATHER_CAP,
        )

        /** A dark cloak and hood, for walking the walls at night. */
        val NIGHT_WATCH = HEDGE_KNIGHT.copy(
            id = "night", title = "night watch", tabard = 0xFF2F3B4F.toInt(), trim = 0xFF9AA9C0.toInt(),
            steel = 0xFFA9B4C0.toInt(), hat = Hat.HOOD, cape = 0xFF222A38.toInt(),
        )

        /** A fur mantle and a red scarf, for the cold. */
        val WINTER = HEDGE_KNIGHT.copy(
            id = "winter", title = "winter furs", mantle = 0xFFF4EFE6.toInt(), scarf = 0xFFE0413A.toInt(),
            cape = 0xFF7A4A2A.toInt(),
        )

        /** Full plate and a great plume, for the lists. */
        val TOURNEY = HEDGE_KNIGHT.copy(
            id = "tourney", title = "tourney plate", tabard = 0xFFD2DBE3.toInt(), trim = 0xFF4B8BFF.toInt(),
            plume = 0xFF4B8BFF.toInt(), bigPlume = true,
        )

        /** Striped pyjamas and a nightcap. */
        val PYJAMAS = HEDGE_KNIGHT.copy(
            id = "pyjamas", title = "pyjamas", steel = 0xFFBFD7F2.toInt(), shadow = 0xFF8FB0D6.toInt(),
            tabard = 0xFFBFD7F2.toInt(), trim = 0xFFFFFFFF.toInt(), plume = 0xFFFFFFFF.toInt(), hat = Hat.NIGHTCAP,
            stripes = 0xFF6F95C9.toInt(),
        )

        // Earned with quest gold.

        val GOLD_TRIM = HEDGE_KNIGHT.copy(
            id = "gold", title = "gilded armour", steel = 0xFFFFE08A.toInt(), shadow = 0xFFC9A23A.toInt(), cost = 10,
        )
        val CROWNED = HEDGE_KNIGHT.copy(id = "crown", title = "crown", hat = Hat.CROWN, cost = 25)
        val DRAGON = HEDGE_KNIGHT.copy(
            id = "dragon", title = "dragon-scale cape", cape = 0xFFB3262B.toInt(), scales = true, cost = 50,
        )

        val ALL = listOf(HEDGE_KNIGHT, HERALD, NIGHT_WATCH, WINTER, TOURNEY, PYJAMAS, GOLD_TRIM, CROWNED, DRAGON)
    }
}

/** What his forward hand holds. */
internal enum class Hold { NOTHING, SWORD, TRUMPET, SCROLL, CAN, LANTERN, TURKEY, BROOM, AXE, HAMMER, LETTER, BALLS }

/**
 * How he stands: every angle in degrees, 0 straight down (for legs and
 * arms) or straight up (for the blade), more turning forward (the way he
 * faces). Sizes are in his own units: he's 200 tall, 100 wide.
 */
internal data class Posture(
    val id: String,
    /** The whole body tipped forward from the hips. */
    val lean: Float = 0f,
    /** Off the ground (a jump), or below it (a crouch, negative). */
    val lift: Float = 0f,
    val frontLeg: Float = 8f,
    val backLeg: Float = -8f,
    /** The sword arm (far side) and its forearm, bent further forward. */
    val frontArm: Float = 25f,
    val frontElbow: Float = 35f,
    /** The shield arm (near side). */
    val backArm: Float = 20f,
    val backElbow: Float = 70f,
    val hold: Hold = Hold.SWORD,
    val blade: Float = 35f,
    /** The shield held up over his head, in both hands. */
    val shieldUp: Boolean = false,
    /** Visor lifted: his face shows (eyes, moustache), and his [mouth] when he shouts. */
    val visor: Boolean = false,
    val mouth: Boolean = false,
    val eyesShut: Boolean = false,
    /** The plume blown back (or bouncing). */
    val plume: Float = 0f,
    /** Flat on the ground, face up. */
    val lying: Boolean = false,
    /** Seeing stars. */
    val dizzy: Boolean = false,
)

/**
 * Sir Alex drawn in bold shapes on a 100 x 200 canvas, in [outfit]'s
 * colours, [facingRight] or not: the ascii art module turns this into
 * coloured characters. 40 x 80 is his natural size.
 */
internal class KnightDrawable(
    private val posture: Posture,
    private val outfit: Outfit,
    private val facingRight: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = NATURAL_WIDTH
    override fun getIntrinsicHeight() = NATURAL_HEIGHT

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / UNITS_W, bounds.height() / UNITS_H)
        if (!facingRight) canvas.scale(-1f, 1f, UNITS_W / 2, 0f)
        val p = posture
        // Smaller than the canvas, feet on its floor, so a raised sword, the
        // trumpet's bell or a streaming plume stay inside it.
        canvas.scale(FIT, FIT, UNITS_W / 2, GROUND)
        if (p.lying) {
            // Laid down along the ground, smaller, head to the front.
            canvas.translate(0f, 82f)
            canvas.scale(0.5f, 0.5f, 50f, 104f)
            canvas.rotate(90f, 50f, 104f)
        }
        canvas.translate(0f, -p.lift)
        figure(canvas, p)
        canvas.restore()
    }

    private fun figure(c: Canvas, p: Posture) {
        val o = outfit
        // A cape hangs behind everything, from the shoulders.
        o.cape?.let { cape(c, p, it) }
        // The legs, from the hips; the body tips over them.
        leg(c, 45f, p.backLeg, o.shadow)
        leg(c, 55f, p.frontLeg, o.steel)
        c.save()
        c.rotate(p.lean, 50f, HIP)
        // The sword arm is on his far side: behind the body.
        val swordHand = arm(c, 60f, p.frontArm, p.frontElbow, o.shadow)
        if (!p.shieldUp) held(c, p, swordHand)
        body(c)
        o.mantle?.let { mantle(c, it) }
        o.scarf?.let { scarf(c, p, it) }
        head(c, p)
        if (p.shieldUp) {
            shieldOverhead(c)
        } else {
            val shieldHand = arm(c, 40f, p.backArm, p.backElbow, o.steel)
            shield(c, shieldHand.first, shieldHand.second)
        }
        if (p.dizzy) stars(c)
        c.restore()
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

    private fun leg(c: Canvas, hipX: Float, angle: Float, color: Int) {
        val a = Math.toRadians(angle.toDouble())
        val footX = hipX + (sin(a) * LEG).toFloat()
        val footY = HIP + (cos(a) * LEG).toFloat()
        stroke(color, 11f)
        c.drawLine(hipX, HIP, footX, footY - 4, paint)
        // A knee guard halfway, and a boot pointing forward.
        fill(outfit.shadow)
        c.drawCircle((hipX + footX) / 2, (HIP + footY) / 2, 6f, paint)
        fill(outfit.leather)
        c.drawRoundRect(RectF(footX - 7, footY - 8, footX + 11, footY + 2), 4f, 4f, paint)
    }

    /** An arm from the shoulder at [x]; returns where the hand is. */
    private fun arm(c: Canvas, x: Float, shoulder: Float, elbow: Float, color: Int): Pair<Float, Float> {
        val a = Math.toRadians(shoulder.toDouble())
        val b = Math.toRadians((shoulder + elbow).toDouble())
        val ex = x + (sin(a) * UPPER_ARM).toFloat()
        val ey = SHOULDER + (cos(a) * UPPER_ARM).toFloat()
        val hx = ex + (sin(b) * FOREARM).toFloat()
        val hy = ey + (cos(b) * FOREARM).toFloat()
        stroke(color, 9f)
        c.drawLine(x, SHOULDER, ex, ey, paint)
        c.drawLine(ex, ey, hx, hy, paint)
        // A gauntlet, with its cuff.
        fill(outfit.shadow)
        c.drawCircle(hx, hy, 5.5f, paint)
        stroke(outfit.trim, 2f)
        c.drawCircle(ex + (hx - ex) * 0.7f, ey + (hy - ey) * 0.7f, 4.5f, paint)
        return hx to hy
    }

    private fun body(c: Canvas) {
        val o = outfit
        // Mail, then the tabard over it, pointed at the hem.
        fill(o.steel)
        c.drawRoundRect(RectF(33f, 80f, 67f, HIP + 2), 9f, 9f, paint)
        fill(o.tabard)
        c.drawPath(Path().apply {
            moveTo(37f, 84f)
            lineTo(63f, 84f)
            lineTo(63f, 146f)
            lineTo(50f, 154f)
            lineTo(37f, 146f)
            close()
        }, paint)
        stroke(o.trim, 2f)
        c.drawPath(Path().apply {
            moveTo(37f, 84f)
            lineTo(63f, 84f)
            lineTo(63f, 146f)
            lineTo(50f, 154f)
            lineTo(37f, 146f)
            close()
        }, paint)
        // Mail rings showing at his sides.
        fill(o.shadow)
        for (y in 90 until 136 step 7) {
            c.drawCircle(34.5f, y.toFloat(), 1.4f, paint)
            c.drawCircle(65.5f, y.toFloat() + 3, 1.4f, paint)
        }
        // Pyjama stripes across it.
        o.stripes?.let { stripe ->
            stroke(stripe, 3f)
            for (y in 90 until 146 step 9) c.drawLine(38f, y.toFloat(), 62f, y.toFloat(), paint)
        }
        // His sigil: a little gold tree, for the hedge knight.
        fill(o.trim)
        c.drawCircle(50f, 101f, 7.5f, paint)
        c.drawRect(48.5f, 105f, 51.5f, 116f, paint)
        // Belt and buckle.
        fill(o.leather)
        c.drawRect(35f, 124f, 65f, 131f, paint)
        fill(o.trim)
        c.drawRect(53f, 123f, 59f, 132f, paint)
        // Pauldrons.
        fill(o.steel)
        c.drawCircle(38f, 85f, 9f, paint)
        c.drawCircle(62f, 85f, 9f, paint)
    }

    private fun head(c: Canvas, p: Posture) {
        when (outfit.hat) {
            Hat.HELM, Hat.CROWN -> helm(c, p)
            Hat.FEATHER_CAP -> cap(c, p)
            Hat.HOOD -> hood(c, p)
            Hat.NIGHTCAP -> nightcap(c, p)
        }
    }

    /** His face, bare: eyes (or shut), a proud moustache, a shout. */
    private fun face(c: Canvas, p: Posture, top: Float = 49f) {
        val o = outfit
        fill(o.skin)
        c.drawRoundRect(RectF(40f, top - 9f, 62f, 76f), 9f, 9f, paint)
        fill(DARK)
        if (p.eyesShut) c.drawRect(52f, top + 6f, 59f, top + 8f, paint) else c.drawCircle(55f, top + 7f, 2.8f, paint)
        fill(o.leather)
        c.drawRoundRect(RectF(48f, top + 14f, 63f, top + 18f), 2f, 2f, paint)
        if (p.mouth) {
            fill(DARK)
            c.drawOval(RectF(52f, top + 18f, 59f, top + 23f), paint)
        }
        // A cold red nose, in furs.
        if (outfit.scarf != null) {
            fill(0xFFFF6B6B.toInt())
            c.drawCircle(62f, top + 11f, 3f, paint)
        }
    }

    /** The herald's feathered cap over a bare face. */
    private fun cap(c: Canvas, p: Posture) {
        val o = outfit
        face(c, p)
        fill(o.tabard)
        c.drawRoundRect(RectF(34f, 30f, 66f, 44f), 7f, 7f, paint)
        fill(o.trim)
        c.drawRect(34f, 40f, 66f, 44f, paint)
        // The feather, sweeping back.
        stroke(o.plume, 5f)
        c.drawPath(Path().apply {
            moveTo(42f, 32f)
            quadTo(26f, 14f - p.plume / 2, 12f, 22f + p.plume)
        }, paint)
    }

    /** The night watch's hood, the face in its shadow. */
    private fun hood(c: Canvas, p: Posture) {
        val o = outfit
        fill(o.cape ?: o.tabard)
        c.drawRoundRect(RectF(33f, 30f, 67f, 82f), 16f, 16f, paint)
        face(c, p, top = 52f)
        stroke(o.cape ?: o.tabard, 5f)
        c.drawArc(RectF(37f, 36f, 67f, 80f), 180f, 180f, false, paint)
    }

    /** A floppy nightcap with a bobble, over a sleepy face. */
    private fun nightcap(c: Canvas, p: Posture) {
        val o = outfit
        face(c, p)
        fill(o.stripes ?: o.tabard)
        c.drawPath(Path().apply {
            moveTo(36f, 42f)
            lineTo(64f, 42f)
            quadTo(56f, 18f, 30f, 22f + p.plume / 2)
            close()
        }, paint)
        fill(o.trim)
        c.drawCircle(28f, 23f + p.plume / 2, 5f, paint)
        c.drawRect(36f, 39f, 64f, 44f, paint)
    }

    private fun helm(c: Canvas, p: Posture) {
        val o = outfit
        // The plume, streaming back from the crown (a crown instead, when he's earned one).
        if (o.hat == Hat.CROWN) {
            crown(c)
        } else {
            plume(c, p)
        }
        helmet(c, p)
    }

    private fun crown(c: Canvas) {
        fill(0xFFFFD23F.toInt())
        c.drawPath(Path().apply {
            moveTo(38f, 36f)
            lineTo(38f, 22f)
            lineTo(44f, 29f)
            lineTo(50f, 18f)
            lineTo(56f, 29f)
            lineTo(62f, 22f)
            lineTo(62f, 36f)
            close()
        }, paint)
        fill(0xFFFF4B4B.toInt())
        c.drawCircle(50f, 31f, 2.5f, paint)
    }

    private fun plume(c: Canvas, p: Posture) {
        val o = outfit
        stroke(o.plume, if (o.bigPlume) 15f else 10f)
        val sway = p.plume
        c.drawPath(Path().apply {
            moveTo(50f, 36f)
            quadTo(38f, 14f - sway / 2, 24f, 18f + sway)
            quadTo(14f, 22f + sway, 12f, 34f + sway)
            if (o.bigPlume) quadTo(8f, 46f + sway, 14f, 56f + sway)
        }, paint)
    }

    private fun helmet(c: Canvas, p: Posture) {
        val o = outfit
        // The great helm, its gold band, and rivets.
        fill(o.steel)
        c.drawRoundRect(RectF(36f, 34f, 64f, 78f), 11f, 11f, paint)
        fill(o.trim)
        c.drawRect(36f, 44f, 64f, 47f, paint)
        c.drawRect(48.5f, 34f, 51.5f, 44f, paint)
        fill(o.shadow)
        for (x in listOf(39f, 44f, 56f, 61f)) c.drawCircle(x, 75f, 1.3f, paint)
        if (p.visor) {
            // Visor up: his face, eyes and a proud moustache.
            fill(o.steel)
            c.drawRoundRect(RectF(38f, 28f, 66f, 40f), 5f, 5f, paint)
            fill(o.skin)
            c.drawRoundRect(RectF(45f, 49f, 63f, 73f), 5f, 5f, paint)
            fill(DARK)
            if (p.eyesShut) c.drawRect(53f, 56f, 60f, 58f, paint) else c.drawCircle(56f, 57f, 2.8f, paint)
            fill(o.leather)
            c.drawRoundRect(RectF(49f, 63f, 64f, 67f), 2f, 2f, paint)
            if (p.mouth) {
                fill(DARK)
                c.drawOval(RectF(53f, 67f, 60f, 72f), paint)
            }
        } else {
            // Visor down: the eye slit and breaths.
            fill(DARK)
            c.drawRect(47f, 54f, 64f, 58f, paint)
            c.drawCircle(56f, 66f, 1.6f, paint)
            c.drawCircle(60f, 66f, 1.6f, paint)
            c.drawCircle(56f, 70f, 1.6f, paint)
            c.drawCircle(60f, 70f, 1.6f, paint)
        }
    }

    /** What the sword hand holds, at ([hand]). */
    private fun held(c: Canvas, p: Posture, hand: Pair<Float, Float>) {
        val o = outfit
        val (x, y) = hand
        when (p.hold) {
            Hold.NOTHING -> Unit
            Hold.SWORD -> {
                c.save()
                c.rotate(p.blade, x, y)
                fill(o.leather)
                c.drawRect(x - 2.5f, y - 2f, x + 2.5f, y + 9f, paint)
                fill(o.trim)
                c.drawRoundRect(RectF(x - 9f, y - 5f, x + 9f, y - 1f), 2f, 2f, paint)
                c.drawCircle(x, y + 10f, 3f, paint)
                fill(o.blade)
                c.drawPath(Path().apply {
                    moveTo(x - 3.5f, y - 5f)
                    lineTo(x + 3.5f, y - 5f)
                    lineTo(x + 3.5f, y - 46f)
                    lineTo(x, y - 54f)
                    lineTo(x - 3.5f, y - 46f)
                    close()
                }, paint)
                // The fuller down the middle of the blade.
                stroke(o.shadow, 1.2f)
                c.drawLine(x, y - 8f, x, y - 42f, paint)
                c.restore()
            }
            Hold.TRUMPET -> {
                // From his helm, forward and a little up, with a banner hanging off it.
                stroke(o.trim, 4.5f)
                c.drawLine(62f, 64f, 94f, 56f, paint)
                fill(o.trim)
                c.drawPath(Path().apply {
                    moveTo(88f, 57f)
                    lineTo(100f, 46f)
                    lineTo(100f, 66f)
                    close()
                }, paint)
                fill(o.plume)
                c.drawRect(70f, 63f, 88f, 80f, paint)
                fill(o.trim)
                c.drawCircle(79f, 71f, 3.5f, paint)
            }
            Hold.SCROLL -> {
                fill(o.blade)
                c.drawRect(x - 3f, y - 20f, x + 15f, y + 6f, paint)
                fill(o.leather)
                c.drawRoundRect(RectF(x - 5f, y - 23f, x + 17f, y - 18f), 2f, 2f, paint)
                c.drawRoundRect(RectF(x - 5f, y + 4f, x + 17f, y + 9f), 2f, 2f, paint)
                stroke(o.shadow, 1.5f)
                for (i in 0 until 4) c.drawLine(x, y - 14f + i * 5, x + 12f, y - 14f + i * 5, paint)
            }
            Hold.LANTERN -> {
                // Held out on its bail, lit.
                stroke(o.shadow, 2f)
                c.drawLine(x, y, x, y + 8f, paint)
                fill(o.shadow)
                c.drawRect(x - 6f, y + 7f, x + 6f, y + 10f, paint)
                fill(0x66FFD23F)
                c.drawCircle(x, y + 17f, 13f, paint)
                fill(0xFFFFE066.toInt())
                c.drawRoundRect(RectF(x - 5f, y + 10f, x + 5f, y + 24f), 3f, 3f, paint)
                fill(o.shadow)
                c.drawRect(x - 6f, y + 24f, x + 6f, y + 27f, paint)
            }
            Hold.TURKEY -> {
                // A roast leg: the bone, and the meat.
                fill(0xFFF4EFE6.toInt())
                c.drawCircle(x - 3f, y + 3f, 3f, paint)
                c.drawRect(x - 3f, y - 4f, x + 3f, y + 3f, paint)
                fill(0xFFB5652B.toInt())
                c.drawOval(RectF(x - 8f, y - 22f, x + 9f, y - 3f), paint)
                fill(0xFFD9894A.toInt())
                c.drawCircle(x - 2f, y - 16f, 3f, paint)
            }
            Hold.BROOM -> {
                c.save()
                c.rotate(p.blade, x, y)
                stroke(o.wood, 3.5f)
                c.drawLine(x, y - 30f, x, y + 36f, paint)
                fill(0xFFD9B27C.toInt())
                c.drawPath(Path().apply {
                    moveTo(x - 3f, y + 34f)
                    lineTo(x + 3f, y + 34f)
                    lineTo(x + 10f, y + 52f)
                    lineTo(x - 10f, y + 52f)
                    close()
                }, paint)
                c.restore()
            }
            Hold.AXE, Hold.HAMMER -> {
                c.save()
                c.rotate(p.blade, x, y)
                stroke(o.wood, 3.5f)
                c.drawLine(x, y + 6f, x, y - 34f, paint)
                fill(o.shadow)
                if (p.hold == Hold.AXE) {
                    c.drawPath(Path().apply {
                        moveTo(x, y - 34f)
                        quadTo(x + 16f, y - 38f, x + 16f, y - 22f)
                        lineTo(x, y - 24f)
                        close()
                    }, paint)
                } else {
                    c.drawRect(x - 7f, y - 38f, x + 7f, y - 30f, paint)
                }
                c.restore()
            }
            Hold.LETTER -> {
                fill(0xFFF4EFE6.toInt())
                c.drawRect(x - 2f, y - 16f, x + 16f, y - 2f, paint)
                stroke(o.shadow, 1f)
                c.drawLine(x - 2f, y - 16f, x + 7f, y - 9f, paint)
                c.drawLine(x + 16f, y - 16f, x + 7f, y - 9f, paint)
                fill(0xFFE0413A.toInt())
                c.drawCircle(x + 7f, y - 8f, 2.5f, paint)
            }
            Hold.BALLS -> {
                // Three balls in the air above his hands, where they are this frame.
                val colors = listOf(0xFFFF4B4B.toInt(), 0xFFFFD23F.toInt(), 0xFF4B8BFF.toInt())
                val up = p.plume > 0
                for (i in 0 until 3) {
                    fill(colors[i])
                    val bx = 36f + i * 14f
                    val by = if ((i % 2 == 0) == up) 22f else 40f
                    c.drawCircle(bx, by, 4.5f, paint)
                }
            }
            Hold.CAN -> {
                // A watering can, tipped, pouring.
                c.save()
                c.rotate(25f, x, y)
                fill(o.shadow)
                c.drawRoundRect(RectF(x - 2f, y - 4f, x + 18f, y + 14f), 3f, 3f, paint)
                stroke(o.shadow, 3f)
                c.drawLine(x + 18f, y + 2f, x + 30f, y - 6f, paint)
                c.restore()
                fill(0xFF6FC3FF.toInt())
                for (i in 0 until 4) c.drawCircle(x + 30f + i * 2, y + 8f + i * 7, 1.8f, paint)
            }
        }
    }

    /** A cape from his shoulders, streaming back as he moves; a dragon's scales on it. */
    private fun cape(c: Canvas, p: Posture, color: Int) {
        c.save()
        c.rotate(p.lean, 50f, HIP)
        val flow = p.plume
        val shape = Path().apply {
            moveTo(36f, 84f)
            lineTo(60f, 84f)
            quadTo(50f, 130f, 44f - flow / 2, 168f)
            lineTo(18f - flow, 164f)
            quadTo(22f, 120f, 36f, 84f)
            close()
        }
        fill(color)
        c.drawPath(shape, paint)
        if (outfit.scales) {
            stroke(0xFFFFD23F.toInt(), 1.4f)
            for (row in 0 until 5) for (col in 0 until 3) {
                val sx = 26f + col * 8f + row * 1.5f - flow / 4
                val sy = 104f + row * 12f
                c.drawArc(RectF(sx - 4f, sy - 4f, sx + 4f, sy + 4f), 0f, 180f, false, paint)
            }
        }
        c.restore()
    }

    /** A thick fur mantle over the shoulders. */
    private fun mantle(c: Canvas, color: Int) {
        fill(color)
        for (x in listOf(34f, 42f, 50f, 58f, 66f)) c.drawCircle(x, 84f, 8f, paint)
        fill(0xFFD9D2C4.toInt())
        for (x in listOf(38f, 54f, 62f)) c.drawCircle(x, 88f, 2f, paint)
    }

    /** A scarf round the neck, its end blowing back. */
    private fun scarf(c: Canvas, p: Posture, color: Int) {
        fill(color)
        c.drawRoundRect(RectF(38f, 74f, 64f, 82f), 4f, 4f, paint)
        stroke(color, 6f)
        c.drawLine(40f, 80f, 26f - p.plume / 2, 96f + p.plume / 2, paint)
    }

    /** The kite shield on his near arm, at the hand. */
    private fun shield(c: Canvas, x: Float, y: Float) {
        val o = outfit
        val kite = Path().apply {
            moveTo(x - 15f, y - 20f)
            lineTo(x + 15f, y - 20f)
            lineTo(x + 15f, y + 4f)
            lineTo(x, y + 26f)
            lineTo(x - 15f, y + 4f)
            close()
        }
        fill(o.wood)
        c.drawPath(kite, paint)
        stroke(o.trim, 3f)
        c.drawPath(kite, paint)
        // The hedge knight's tree on it, and the boss.
        fill(o.tabard)
        c.drawCircle(x, y - 6f, 7f, paint)
        c.drawRect(x - 1.5f, y - 1f, x + 1.5f, y + 9f, paint)
    }

    private fun shieldOverhead(c: Canvas) {
        val o = outfit
        stroke(o.steel, 9f)
        c.drawLine(38f, SHOULDER, 30f, 22f, paint)
        c.drawLine(62f, SHOULDER, 70f, 22f, paint)
        fill(o.wood)
        c.drawRoundRect(RectF(8f, 4f, 92f, 22f), 6f, 6f, paint)
        stroke(o.trim, 3f)
        c.drawRoundRect(RectF(8f, 4f, 92f, 22f), 6f, 6f, paint)
        fill(o.trim)
        c.drawCircle(50f, 13f, 5f, paint)
    }

    private fun stars(c: Canvas) {
        fill(outfit.trim)
        for ((x, y) in listOf(28f to 26f, 50f to 18f, 72f to 26f)) {
            c.drawPath(Path().apply {
                moveTo(x, y - 6f)
                lineTo(x + 2f, y - 2f)
                lineTo(x + 6f, y)
                lineTo(x + 2f, y + 2f)
                lineTo(x, y + 6f)
                lineTo(x - 2f, y + 2f)
                lineTo(x - 6f, y)
                lineTo(x - 2f, y - 2f)
                close()
            }, paint)
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
        const val UNITS_W = 100f
        const val UNITS_H = 200f
        const val NATURAL_WIDTH = 40
        const val NATURAL_HEIGHT = 80

        const val FIT = 0.78f
        const val GROUND = 198f
        const val HIP = 138f
        const val LEG = 56f
        const val SHOULDER = 86f
        const val UPPER_ARM = 26f
        const val FOREARM = 24f
        val DARK = 0xFF2A2F36.toInt()
    }
}
