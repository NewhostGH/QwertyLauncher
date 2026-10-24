package com.newhost.qwertylauncher.feature.asciiicons

import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.SettingsStore
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * [ArtAnimator] by redrawing characters: every frame is the art's own text
 * with some cells swapped, its colours copied over, so it's something a
 * terminal could show. Each kind follows its switch in [AsciiSettings].
 */
internal class TextArtAnimator(private val settings: SettingsStore) : ArtAnimator {

    override fun drawIn(art: CharSequence): Flow<CharSequence> {
        if (!settings[AsciiSettings.animateDrawIn]) return flowOf(art)
        val lines = art.split('\n')
        if (lines.size <= 1) return typeIn(art)
        return flow {
            // Top to bottom, a few rows a frame, so small and large art take about as long.
            val perFrame = (lines.size + DRAW_IN_FRAMES - 1) / DRAW_IN_FRAMES
            var shown = 0
            while (shown < lines.size) {
                shown = (shown + perFrame).coerceAtMost(lines.size)
                val visible = lineStart(art, shown)
                emit(frame(art) { i, c -> if (i < visible || c == '\n') c else ' ' })
                if (shown < lines.size) delay(FRAME_MS)
            }
        }
    }

    /** One line: it types itself in, a few characters a frame. */
    private fun typeIn(text: CharSequence): Flow<CharSequence> = flow {
        val perFrame = (text.length + DRAW_IN_FRAMES - 1) / DRAW_IN_FRAMES
        var shown = 0
        while (shown < text.length) {
            shown = (shown + perFrame.coerceAtLeast(1)).coerceAtMost(text.length)
            emit(frame(text) { i, c -> if (i < shown) c else ' ' })
            if (shown < text.length) delay(FRAME_MS)
        }
        if (text.isEmpty()) emit(text)
    }

    override fun transition(old: CharSequence, new: CharSequence): Flow<CharSequence> {
        if (!settings[AsciiSettings.animateRoll]) return flowOf(new)
        if (old.length != new.length || shape(old) != shape(new)) return drawIn(new)
        val changed = new.indices.filter { old[it] != new[it] }
        if (changed.isEmpty()) return flowOf(new)
        return flow {
            // Each changed cell settles on its own frame; until then it shows a random character.
            val settle = changed.associateWith { Random.nextInt(1, ROLL_FRAMES + 1) }
            val noise = ramp()
            for (f in 1..ROLL_FRAMES) {
                emit(
                    frame(new) { i, c ->
                        val at = settle[i]
                        when {
                            at == null || f >= at -> c
                            c == ' ' && old[i] == ' ' -> ' '
                            else -> noise[Random.nextInt(1, noise.length)]
                        }
                    },
                )
                if (f < ROLL_FRAMES) delay(FRAME_MS)
            }
        }
    }

    override fun shimmer(art: CharSequence): Flow<CharSequence> {
        if (!settings[AsciiSettings.animateShimmer]) return flowOf(art)
        val ramp = ramp()
        // Only cells drawn with the density ramp flicker; lines and letters stay put.
        val cells = art.indices.filter { ramp.indexOf(art[it]) > 0 }
        if (cells.isEmpty()) return flowOf(art)
        return flow {
            while (true) {
                val flicker = cells.filter { Random.nextFloat() < SHIMMER_SHARE }.toSet()
                emit(
                    frame(art) { i, c ->
                        if (i !in flicker) {
                            c
                        } else {
                            val level = ramp.indexOf(c) + if (Random.nextBoolean()) 1 else -1
                            ramp[level.coerceIn(1, ramp.lastIndex)]
                        }
                    },
                )
                delay(SHIMMER_MS)
            }
        }
    }

    override fun decode(text: CharSequence): Flow<CharSequence> {
        if (!settings[AsciiSettings.animateDecode]) return flowOf(text)
        return flow {
            // Left to right, with some spread, like a decoder locking on.
            val settle = IntArray(text.length) { i ->
                (i * DECODE_FRAMES / text.length.coerceAtLeast(1) + Random.nextInt(0, DECODE_SPREAD)).coerceAtMost(DECODE_FRAMES)
            }
            for (f in 0..DECODE_FRAMES) {
                emit(
                    frame(text) { i, c ->
                        when {
                            c == ' ' || c == '\n' || f >= settle[i] -> c
                            else -> NOISE[Random.nextInt(NOISE.length)]
                        }
                    },
                )
                if (f < DECODE_FRAMES) delay(FRAME_MS)
            }
        }
    }

    override fun frames(periodMs: Long): Flow<Int> {
        if (!settings[AsciiSettings.animateLive]) return flowOf(0)
        return flow {
            var n = 0
            while (true) {
                emit(n++)
                delay(periodMs)
            }
        }
    }

    private fun ramp() = AsciiSettings.ramp(settings[AsciiSettings.charset])

    /** Line lengths: art of the same shape can roll from one to the other. */
    private fun shape(text: CharSequence) = text.split('\n').map { it.length }

    /** Index where line [line] starts (the text's length past the last line). */
    private fun lineStart(text: CharSequence, line: Int): Int {
        var seen = 0
        for (i in text.indices) {
            if (text[i] == '\n' && ++seen == line) return i
        }
        return text.length
    }

    /** The art's text with each character passed through [pick], and its colours kept. */
    private inline fun frame(art: CharSequence, pick: (Int, Char) -> Char): CharSequence {
        val chars = CharArray(art.length) { pick(it, art[it]) }
        val out = SpannableString(String(chars))
        if (art is Spanned) TextUtils.copySpansFrom(art, 0, art.length, Any::class.java, out, 0)
        return out
    }

    private companion object {
        const val FRAME_MS = 40L
        const val DRAW_IN_FRAMES = 10
        const val ROLL_FRAMES = 6
        const val SHIMMER_MS = 120L
        const val SHIMMER_SHARE = 0.12f
        const val DECODE_FRAMES = 14
        const val DECODE_SPREAD = 4
        const val NOISE = "!#$%&*+-/0123456789<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_{|}~"
    }
}
