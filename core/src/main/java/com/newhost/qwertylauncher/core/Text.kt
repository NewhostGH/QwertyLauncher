package com.newhost.qwertylauncher.core

import kotlin.math.max
import kotlin.math.roundToInt

/** Matches typed text against names, so every provider ranks the same way. */
object Fuzzy {
    /** Scores [candidate] against [query] in [Score] bands; 0 means no match. */
    fun score(query: String, candidate: String): Int {
        if (query.isEmpty() || candidate.isEmpty()) return 0
        val q = query.lowercase()
        val c = candidate.lowercase()
        // Among equal matches, prefer shorter names.
        val lengthPenalty = (c.length - q.length).coerceIn(0, 100)
        return when {
            c == q -> Score.EXACT
            c.startsWith(q) -> Score.PREFIX - lengthPenalty
            words(c).any { it.startsWith(q) } -> Score.WORD - lengthPenalty
            q.length > 1 && initials(c).startsWith(q) -> Score.WORD - lengthPenalty
            isSubsequence(q, c) -> Score.FUZZY - lengthPenalty
            else -> 0
        }
    }

    private fun words(s: String) = s.split(' ', '-', '_', '.').filter { it.isNotEmpty() }

    private fun initials(s: String) = words(s).joinToString("") { it.take(1) }

    private fun isSubsequence(q: String, c: String): Boolean {
        var i = 0
        for (ch in c) if (i < q.length && ch == q[i]) i++
        return i == q.length
    }
}

/** Small helpers for drawing with characters, so every module draws the same way. */
object Tty {
    /** `[######----]` */
    fun bar(fraction: Float, width: Int = 10): String {
        val filled = (fraction.coerceIn(0f, 1f) * width).roundToInt()
        return "[" + "#".repeat(filled) + "-".repeat(width - filled) + "]"
    }

    /** `▕██████░░░░▏`, a solid meter; eighth-blocks make the last cell partial. */
    fun meter(fraction: Float, width: Int = 10): String {
        val eighths = (fraction.coerceIn(0f, 1f) * width * 8).roundToInt()
        val full = eighths / 8
        val partial = eighths % 8
        val cells = buildString {
            append("█".repeat(full))
            if (partial > 0 && full < width) append(EIGHTHS[partial])
            append("░".repeat((width - length).coerceAtLeast(0)))
        }
        return "▕$cells▏"
    }

    /** Signal strength as rising bars: `▂▄▆█` with the missing ones as spaces, [level] 0 to 4. */
    fun signal(level: Int): String = "▂▄▆█".mapIndexed { i, c -> if (i < level) c else ' ' }.joinToString("")

    /** Frames [lines] in a box, like a tmux pane: `┌─ title ─┐ │ ... │ └──┘`. */
    fun box(lines: List<String>, title: String? = null): List<String> {
        val inner = maxOf(lines.maxOfOrNull { it.length } ?: 0, (title?.length ?: 0) + 3)
        val top = if (title == null) "┌" + "─".repeat(inner + 2) + "┐" else "┌─ $title ".padEnd(inner + 3, '─') + "┐"
        return listOf(top) + lines.map { "│ " + it.padEnd(inner) + " │" } + ("└" + "─".repeat(inner + 2) + "┘")
    }

    private const val EIGHTHS = " ▏▎▍▌▋▊▉"

    /** Joins two blocks of lines side by side, padding the left block to equal width. */
    fun sideBySide(left: List<String>, right: List<String>, gap: Int = 2): String {
        val width = left.maxOfOrNull { it.length } ?: 0
        return (0 until max(left.size, right.size)).joinToString("\n") { i ->
            left.getOrElse(i) { "" }.padEnd(width + gap) + right.getOrElse(i) { "" }
        }.trimEnd()
    }
}
