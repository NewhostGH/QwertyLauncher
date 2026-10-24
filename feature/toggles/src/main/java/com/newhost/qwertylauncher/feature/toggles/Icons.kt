package com.newhost.qwertylauncher.feature.toggles

import com.newhost.qwertylauncher.core.ShapeIcon
import com.newhost.qwertylauncher.core.icon

/**
 * The toggles' pictures, bold shapes on the 100 × 100 icon canvas, drawn as
 * ASCII art by the shell like the wrench. Each takes a colour and whether
 * it's on (for the few that look different then, like the lit flashlight).
 */
internal object Icons {
    val airplane: Draw = { c, _ ->
        icon {
            fill(c)
            round(44f, 4f, 56f, 92f, 6f)
            polygon(50f, 28f, 96f, 54f, 96f, 64f, 50f, 50f, 4f, 64f, 4f, 54f)
            polygon(50f, 72f, 72f, 86f, 72f, 94f, 50f, 88f, 28f, 94f, 28f, 86f)
        }
    }

    val bluetooth: Draw = { c, _ ->
        icon {
            stroke(c, 10f)
            line(26f, 30f, 72f, 70f)
            line(72f, 70f, 50f, 92f)
            line(50f, 92f, 50f, 8f)
            line(50f, 8f, 72f, 30f)
            line(72f, 30f, 26f, 70f)
        }
    }

    private fun ShapeIcon.Pen.bellShape(c: Int) {
        fill(c)
        circle(50f, 14f, 6f)
        circle(50f, 42f, 26f)
        polygon(24f, 42f, 76f, 42f, 86f, 74f, 14f, 74f)
        round(8f, 70f, 92f, 80f, 4f)
        circle(50f, 88f, 9f)
    }

    val bell: Draw = { c, _ -> icon { bellShape(c) } }

    val silent: Draw = { c, _ ->
        icon {
            bellShape(c)
            // A slash with a gap each side, so it reads over the bell.
            erase(24f)
            line(10f, 8f, 92f, 92f)
            stroke(c, 9f)
            line(10f, 8f, 92f, 92f)
        }
    }

    val vibrate: Draw = { c, _ ->
        icon {
            fill(c)
            round(32f, 12f, 68f, 88f, 8f)
            erase()
            round(38f, 22f, 62f, 72f, 3f)
            stroke(c, 7f)
            line(20f, 26f, 10f, 38f)
            line(10f, 38f, 20f, 50f)
            line(20f, 50f, 10f, 62f)
            line(10f, 62f, 20f, 74f)
            line(80f, 26f, 90f, 38f)
            line(90f, 38f, 80f, 50f)
            line(80f, 50f, 90f, 62f)
            line(90f, 62f, 80f, 74f)
        }
    }

    val torch: Draw = { c, lit ->
        icon {
            fill(c)
            polygon(24f, 34f, 76f, 34f, 64f, 56f, 36f, 56f)
            round(38f, 54f, 62f, 96f, 5f)
            if (lit) {
                stroke(c, 7f)
                line(50f, 24f, 50f, 4f)
                line(32f, 26f, 18f, 10f)
                line(68f, 26f, 82f, 10f)
            }
        }
    }

    val wifi: Draw = { c, _ ->
        icon {
            stroke(c, 10f)
            arc(6f, 20f, 94f, 108f, 225f, 90f)
            arc(24f, 38f, 76f, 90f, 225f, 90f)
            fill(c)
            circle(50f, 78f, 9f)
        }
    }

    /** Up and down arrows: data going both ways. */
    val data: Draw = { c, _ ->
        icon {
            fill(c)
            polygon(30f, 6f, 52f, 34f, 38f, 34f, 38f, 94f, 22f, 94f, 22f, 34f, 8f, 34f)
            polygon(70f, 94f, 48f, 66f, 62f, 66f, 62f, 6f, 78f, 6f, 78f, 66f, 92f, 66f)
        }
    }

    /** A point sending out rings on both sides. */
    val hotspot: Draw = { c, _ ->
        icon {
            fill(c)
            circle(50f, 50f, 10f)
            stroke(c, 8f)
            arc(26f, 26f, 74f, 74f, 135f, 90f)
            arc(26f, 26f, 74f, 74f, -45f, 90f)
            arc(6f, 6f, 94f, 94f, 135f, 90f)
            arc(6f, 6f, 94f, 94f, -45f, 90f)
        }
    }

    val nfc: Draw = { c, _ ->
        icon {
            stroke(c, 8f)
            arc(4f, 34f, 36f, 66f, -50f, 100f)
            arc(-12f, 18f, 52f, 82f, -50f, 100f)
            arc(-28f, 2f, 68f, 98f, -50f, 100f)
            fill(c)
            circle(18f, 50f, 7f)
        }
    }

    val pin: Draw = { c, _ ->
        icon {
            fill(c)
            circle(50f, 36f, 28f)
            polygon(25f, 48f, 75f, 48f, 50f, 96f)
            erase()
            circle(50f, 36f, 11f)
        }
    }

    /** A padlock, shut when connected. */
    val lock: Draw = { c, on ->
        icon {
            stroke(c, 10f)
            if (on) arc(28f, 8f, 72f, 56f, 180f, 180f) else arc(48f, 4f, 92f, 52f, 180f, 180f)
            if (on) {
                line(28f, 32f, 28f, 48f)
                line(72f, 32f, 72f, 48f)
            } else {
                line(92f, 28f, 92f, 40f)
            }
            fill(c)
            round(18f, 46f, 82f, 94f, 6f)
            erase()
            circle(50f, 66f, 7f)
        }
    }

    /** A screen with waves from its corner. */
    val cast: Draw = { c, _ ->
        icon {
            stroke(c, 8f)
            polygon(28f, 16f, 92f, 16f, 92f, 80f, 60f, 80f)
            arc(-16f, 56f, 32f, 104f, 270f, 90f)
            arc(-34f, 38f, 50f, 122f, 270f, 90f)
            fill(c)
            circle(8f, 82f, 7f)
        }
    }

    val moon: Draw = { c, _ ->
        icon {
            fill(c)
            circle(46f, 54f, 38f)
            erase()
            circle(70f, 36f, 30f)
        }
    }

    /** A speaker: with waves when sound plays, crossed out when muted. */
    val speaker: Draw = { c, muted ->
        icon {
            fill(c)
            rect(10f, 36f, 30f, 64f)
            polygon(30f, 36f, 56f, 12f, 56f, 88f, 30f, 64f)
            stroke(c, 8f)
            if (muted) {
                line(66f, 34f, 92f, 66f)
                line(92f, 34f, 66f, 66f)
            } else {
                arc(46f, 30f, 86f, 70f, -50f, 100f)
                arc(40f, 14f, 100f, 86f, -50f, 100f)
            }
        }
    }

    val rotate: Draw = { c, _ ->
        icon {
            stroke(c, 9f)
            arc(16f, 16f, 84f, 84f, -60f, 290f)
            fill(c)
            polygon(10f, 24f, 42f, 12f, 36f, 42f)
        }
    }

    /** A sun with an A in it: brightness that sets itself. */
    val autoSun: Draw = { c, _ ->
        icon {
            rays(c, 1f)
            fill(c)
            circle(50f, 50f, 26f)
            erase()
            text("A", 50f, 51f, 34f)
        }
    }

    /** A sun as bright as [level] (0 to 1): its rays grow with it. */
    fun sun(c: Int, level: Float) = icon {
        rays(c, level.coerceIn(0.25f, 1f))
        fill(c)
        circle(50f, 50f, 22f)
    }

    private fun ShapeIcon.Pen.rays(c: Int, length: Float) {
        stroke(c, 8f)
        val reach = 30f + 18f * length
        for (i in 0 until 8) {
            turned(i * 45f) { line(50f, 50f - 32f, 50f, 50f - reach) }
        }
    }

    val hourglass: Draw = { c, _ ->
        icon {
            fill(c)
            rect(18f, 4f, 82f, 14f)
            rect(18f, 86f, 82f, 96f)
            polygon(24f, 14f, 76f, 14f, 54f, 50f, 76f, 86f, 24f, 86f, 46f, 50f)
            erase()
            polygon(34f, 20f, 66f, 20f, 50f, 44f)
        }
    }

    /** Half a circle filled: dark and light. */
    val halfMoon: Draw = { c, _ ->
        icon {
            stroke(c, 8f)
            circle(50f, 50f, 40f)
            fill(c)
            arc(10f, 10f, 90f, 90f, 90f, 180f, pie = true)
        }
    }

    /** A battery with a leaf-like plus: saving power. */
    val battery: Draw = { c, _ ->
        icon {
            fill(c)
            rect(38f, 4f, 62f, 14f)
            round(22f, 12f, 78f, 96f, 8f)
            erase()
            rect(44f, 34f, 56f, 76f)
            rect(29f, 49f, 71f, 61f)
        }
    }

    val camera: Draw = { c, _ ->
        icon {
            fill(c)
            polygon(32f, 18f, 68f, 18f, 76f, 30f, 24f, 30f)
            round(6f, 28f, 94f, 86f, 10f)
            erase()
            circle(50f, 57f, 21f)
            fill(c)
            circle(50f, 57f, 12f)
        }
    }
}
