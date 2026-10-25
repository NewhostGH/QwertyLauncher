package com.newhost.qwertylauncher.ui

import kotlin.math.roundToInt

/**
 * What a screen reader should say for terminal text. Meters, bars and
 * signal bars are block characters that would be read out one by one ("full
 * block, full block, light shade"), so they're said as what they mean, and
 * other box and block characters, which only draw, are left out. Short
 * words a speech engine says wrong ("todo" as "toad-oh", "ai" as "eye") are
 * spelled the way they're meant: engines have no dictionary to teach them.
 * Key symbols (⏎, ↑↓, ^c) are said as the keys' names.
 */
internal object Speech {

    /** [text] for a screen reader: graphics as words, drawing characters left out, spaces collapsed. */
    fun speakable(text: CharSequence): String {
        var s = text.toString()
        // Tty.meter: ▕█████▌░░░▏, eighths of a cell each.
        s = METER.replace(s) { m -> "${percent(m.groupValues[1].sumOf { EIGHTHS.indexOf(it).coerceAtLeast(0) }, m.groupValues[1].length * 8)} percent" }
        // Tty.bar: [####------].
        s = BAR.replace(s) { m -> "${percent(m.groupValues[1].count { it == '#' }, m.groupValues[1].length)} percent" }
        // Tty.signal: ▂▄▆█, the missing bars as spaces.
        s = SIGNAL.replace(s) { m -> "signal ${m.value.length} of 4" }
        for ((key, name) in KEY_NAMES) s = s.replace(key, " $name ")
        s = CONTROL.replace(s) { m -> "control ${m.groupValues[1]}" }
        return words(DRAWING.replace(s, " ").replace(SPACES, " ").trim())
    }

    /** [text] with the [SAID] words spelled as they're said; the rest as it is. */
    fun words(text: String): String = WORD.replace(text) { m -> SAID[m.value.lowercase()] ?: m.value }

    /** A list row for a screen reader: [speakable], without the cursor marker ("> ") it starts with. */
    fun row(text: CharSequence): String = speakable(text.toString().trimStart().removePrefix(">").trimStart())

    /** A search result: its title and detail, after what kind of thing it is. */
    fun result(tag: String, title: CharSequence, detail: CharSequence?): String =
        listOfNotNull(KINDS[tag] ?: tag, speakable(title), detail?.let(::speakable)?.takeIf { it.isNotEmpty() })
            .joinToString(", ")

    /** True when [text] has something [speakable] would say differently. */
    fun needed(text: CharSequence): Boolean =
        text.any { it in '\u2500'..'\u25FF' || it in '\u2800'..'\u28FF' } || BAR.containsMatchIn(text) ||
            WORD.findAll(text).any { it.value.lowercase() in SAID }

    /** A chart's values in words: "chart from 3 to 9, latest 7". */
    fun chart(values: List<Double>): String {
        if (values.isEmpty()) return "empty chart"
        return "chart from ${number(values.min())} to ${number(values.max())}, latest ${number(values.last())}"
    }

    private fun percent(part: Int, whole: Int) = if (whole == 0) 0 else (part * 100f / whole).roundToInt()

    private fun number(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.1f".format(value)

    /** Empty, then one to eight eighths of a cell: the light shade is an empty cell. */
    private const val EIGHTHS = "░▏▎▍▌▋▊▉█"
    private val METER = Regex("▕([░▏▎▍▌▋▊▉█]+)▏")
    private val BAR = Regex("""\[([#-]{3,})]""")
    private val SIGNAL = Regex("▂(?:▄(?:▆█?)?)?")
    /** Box drawing, block elements, geometric shapes (sliders' knobs) and braille (sparklines): pure drawing. */
    private val DRAWING = Regex("[\u2500-\u25FF\u2800-\u28FF]+")

    /** Result tags in words; others are said as they are. */
    private val KINDS = mapOf(
        "sc" to "shortcut",
        "cmd" to "command",
        "ctc" to "contact",
        "sms" to "message",
        "web" to "web search",
        "url" to "link",
        "calc" to "calculator",
        "wx" to "weather",
        "unin" to "uninstall",
    )
    private val SPACES = Regex("\\s+")

    /** Key symbols as their names; the longer ones first, so ←↑↓→ isn't read as ← and ↑↓. */
    private val KEY_NAMES = listOf(
        "\u2190\u2191\u2193\u2192" to "arrows",
        "\u2191\u2193" to "up down",
        "\u2190\u2192" to "left right",
        "\u2190" to "left",
        "\u2191" to "up",
        "\u2193" to "down",
        "\u2192" to "right",
        "\u23CE" to "enter",
    )
    /** ^c, ^n: control and a letter. */
    private val CONTROL = Regex("\\^([a-z])")

    /**
     * Words as the speech engine should say them: abbreviations the launcher
     * uses, and words it reads wrong. Matched whole and in any case.
     */
    private val SAID = mapOf(
        "ai" to "A I",
        "todo" to "to do",
        "todos" to "to dos",
        "bt" to "bluetooth",
        "wx" to "weather",
        "dnd" to "do not disturb",
        "uv" to "U V",
        "cfg" to "config",
        "notif" to "notifications",
        "calc" to "calculator",
        "sys" to "system",
        "nfc" to "N F C",
        "vpn" to "V P N",
        "gps" to "G P S",
        "usb" to "U S B",
        "cpu" to "C P U",
        "wifi" to "wi-fi",
        "ascii" to "askey",
        "hpa" to "hectopascals",
    )
    private val WORD = Regex("[A-Za-z]+")
}
