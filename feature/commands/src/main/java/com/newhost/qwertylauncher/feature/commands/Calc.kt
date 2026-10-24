package com.newhost.qwertylauncher.feature.commands

import android.content.ClipData
import android.content.ClipboardManager
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.pow

/** A worked-out sum: what was typed and the answer, as shown (`3.10686 mi`, `0xff`). */
internal class Answer(val input: String, val text: String, val value: Double?)

/**
 * The calculator: sums with + - * / % ^ ! and brackets, functions like
 * `sqrt` and `sin`, `pi`, `e` and `ans` (the last answer), numbers in hex,
 * binary or octal (`0xff`, `0b101`, `0o17`), and conversions:
 * `5 km in mi`, `100 f to c`, `255 in hex`.
 */
internal class Calc(private val settings: SettingsStore, private val tape: CalcTape) {

    /**
     * The answer to [text], or null if it isn't a sum. When [live] (worked
     * out while typing), only input that is clearly maths counts, so a plain
     * number, a phone number or a word never gets an answer in the way.
     */
    fun answer(text: String, live: Boolean): Answer? =
        solve(text, live, ans = tape.lastValue(), degrees = settings[CommandsSettings.degrees])

    fun result(answer: Answer, score: Int, openTape: (LauncherHost) -> Unit) = SearchResult(
        key = "calc",
        tag = "calc",
        title = "= ${answer.text}",
        detail = "enter copies",
        score = score,
        action = { host -> keep(answer, host) },
        menu = listOf(
            MenuItem("copy ${answer.text}") { host -> keep(answer, host) },
            MenuItem("calc tape") { host ->
                host.clearQuery()
                openTape(host)
            },
        ),
    )

    /** Copies the answer and puts it on the tape. */
    fun keep(answer: Answer, host: LauncherHost) {
        host.clearQuery()
        host.console.println("${answer.input} = ${answer.text}")
        copy(answer.text, host)
        tape.add(answer)
    }

    fun copy(text: String, host: LauncherHost) {
        host.context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("result", text))
    }

    companion object {
        private val CONVERT = Regex("""^(.+?)\s+(?:in|to|as|into)\s+([\p{L}°/0-9]+)$""", RegexOption.IGNORE_CASE)
        private const val SIGNIFICANT_DIGITS = 12

        /** [answer], given the last answer ([ans], for `ans`) and the angle setting. */
        fun solve(text: String, live: Boolean, ans: Double?, degrees: Boolean): Answer? {
            val input = text.trim()
            if (input.isEmpty()) return null
            convert(input, ans, degrees)?.let { return it }
            val parsed = parse(input, ans, degrees) ?: return null
            if (live && !parsed.clearlyMaths) return null
            return Answer(input, format(parsed.value), parsed.value)
        }

        private fun parse(input: String, ans: Double?, degrees: Boolean): Parser.Result? = try {
            Parser(input, ans, degrees).parse().takeIf { it.value.isFinite() }
        } catch (e: IllegalArgumentException) {
            null
        }

        /** `<sum> [unit] in <unit or base>`. */
        private fun convert(input: String, ans: Double?, degrees: Boolean): Answer? {
            val match = CONVERT.find(input) ?: return null
            val left = match.groupValues[1].trim()
            val target = match.groupValues[2].lowercase()
            Base.of(target)?.let { base ->
                val value = parse(left, ans, degrees)?.value ?: return null
                return base.show(value)?.let { Answer(input, it, value) }
            }
            val to = Units.find(target) ?: return null
            // The longest unit name the left side ends with, whose rest is a sum.
            for ((name, from) in Units.names) {
                if (from.kind != to.kind || !left.lowercase().endsWith(name)) continue
                val amount = left.dropLast(name.length).trim()
                if (amount.isEmpty()) continue
                val value = parse(amount, ans, degrees)?.value ?: continue
                val converted = to.fromBase(from.toBase(value))
                return Answer(input, "${format(converted)} ${to.name}", converted)
            }
            return null
        }

        /** Plain digits, switching to `1.5e-9` style only when that's very long. */
        fun format(value: Double): String {
            if (value == 0.0) return "0"
            val rounded = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros()
            val size = abs(value)
            return if (size >= 1e15 || size < 1e-6) {
                rounded.toString().lowercase().replace("e+", "e")
            } else {
                rounded.toPlainString()
            }
        }
    }
}

/** Number bases a sum can be shown in: `255 in hex`. */
internal enum class Base(val names: List<String>, val radix: Int, val prefix: String) {
    HEX(listOf("hex", "hexadecimal"), 16, "0x"),
    BIN(listOf("bin", "binary"), 2, "0b"),
    OCT(listOf("oct", "octal"), 8, "0o"),
    DEC(listOf("dec", "decimal"), 10, "");

    /** Whole numbers only; null for fractions or numbers too big to be exact. */
    fun show(value: Double): String? {
        if (value != Math.rint(value) || abs(value) > MAX_EXACT) return null
        val whole = value.toLong()
        val sign = if (whole < 0) "-" else ""
        return sign + prefix + abs(whole).toString(radix)
    }

    companion object {
        private const val MAX_EXACT = 9.007199254740992e15 // 2^53

        fun of(name: String) = entries.firstOrNull { name in it.names }
    }
}

/**
 * Recursive descent:
 * expression = term (('+'|'-') term)*,
 * term = factor (('*'|'/'|'%') factor)*,
 * factor = '-' factor | power, power = postfix ('^' factor)?,
 * postfix = primary '!'*, primary = number | '(' expression ')' | constant | function factor.
 */
private class Parser(text: String, private val ans: Double?, private val degrees: Boolean) {
    class Result(val value: Double, val clearlyMaths: Boolean)

    private val s = text.replace('×', '*').replace('÷', '/').replace(',', '.').lowercase()
    private var pos = 0

    /** An operator between two values, a function, `!` or a hex/binary number: clearly maths, not a word or a phone number. */
    private var clearlyMaths = false

    fun parse(): Result {
        val value = expression()
        skipSpaces()
        require(pos == s.length) { "unexpected '${s[pos]}'" }
        return Result(value, clearlyMaths)
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            value = when {
                eat('+') -> value + term().also { clearlyMaths = true }
                eat('-') -> value - term().also { clearlyMaths = true }
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            value = when {
                eat('*') -> value * factor().also { clearlyMaths = true }
                eat('/') -> value / factor().also { clearlyMaths = true }
                eat('%') -> value % factor().also { clearlyMaths = true }
                else -> return value
            }
        }
    }

    private fun factor(): Double {
        if (eat('-')) return -factor()
        if (eat('+')) return factor()
        val base = postfix()
        if (!eat('^')) return base
        clearlyMaths = true
        return base.pow(factor())
    }

    private fun postfix(): Double {
        var value = primary()
        while (eat('!')) {
            clearlyMaths = true
            value = factorial(value)
        }
        return value
    }

    private fun primary(): Double {
        skipSpaces()
        if (eat('(')) {
            val value = expression()
            require(eat(')')) { "missing )" }
            return value
        }
        require(pos < s.length) { "number expected" }
        return if (s[pos].isLetter()) named() else number()
    }

    private fun named(): Double {
        val start = pos
        while (pos < s.length && s[pos].isLetter()) pos++
        // `log2` is a name, but in `sin30` the digits are what sin is of.
        var end = pos
        while (end < s.length && s[end].isDigit()) end++
        if (end > pos && s.substring(start, end) in FUNCTIONS) pos = end
        val name = s.substring(start, pos)
        CONSTANTS[name]?.let { return it }
        if (name == "ans") return requireNotNull(ans) { "no answer yet" }
        val function = requireNotNull(FUNCTIONS[name]) { "unknown $name" }
        clearlyMaths = true
        return function(this, factor())
    }

    private fun number(): Double {
        skipSpaces()
        if (s.startsWith("0x", pos)) return based(16)
        if (s.startsWith("0b", pos)) return based(2)
        if (s.startsWith("0o", pos)) return based(8)
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        require(pos > start) { "number expected" }
        // An exponent: 1e3, 2.5e-4. A lone `e` after a number isn't one.
        if (pos < s.length && s[pos] == 'e') {
            val after = pos + 1 + if (pos + 1 < s.length && s[pos + 1] in "+-") 1 else 0
            if (after < s.length && s[after].isDigit()) {
                pos = after
                while (pos < s.length && s[pos].isDigit()) pos++
            }
        }
        return requireNotNull(s.substring(start, pos).toDoubleOrNull()) { "bad number" }
    }

    private fun based(radix: Int): Double {
        pos += 2
        val start = pos
        while (pos < s.length && Character.digit(s[pos], radix) >= 0) pos++
        require(pos > start) { "digits expected" }
        clearlyMaths = true
        return requireNotNull(s.substring(start, pos).toLongOrNull(radix)) { "too big" }.toDouble()
    }

    private fun factorial(n: Double): Double {
        require(n >= 0 && n == Math.rint(n) && n <= 170) { "factorial of whole numbers up to 170" }
        var result = 1.0
        for (i in 2..n.toInt()) result *= i
        return result
    }

    private fun angle(x: Double) = if (degrees) Math.toRadians(x) else x
    private fun unangle(x: Double) = if (degrees) Math.toDegrees(x) else x

    private fun skipSpaces() {
        while (pos < s.length && s[pos] == ' ') pos++
    }

    private fun eat(c: Char): Boolean {
        skipSpaces()
        if (pos < s.length && s[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private companion object {
        val CONSTANTS = mapOf("pi" to Math.PI, "tau" to 2 * Math.PI, "e" to Math.E)

        fun fn(f: Parser.(Double) -> Double) = f

        val FUNCTIONS: Map<String, Parser.(Double) -> Double> = mapOf(
            "sqrt" to fn { x -> Math.sqrt(x) },
            "cbrt" to fn { x -> Math.cbrt(x) },
            "abs" to fn { x -> abs(x) },
            "round" to fn { x -> Math.rint(x) },
            "floor" to fn { x -> Math.floor(x) },
            "ceil" to fn { x -> Math.ceil(x) },
            "ln" to fn { x -> Math.log(x) },
            "log" to fn { x -> Math.log10(x) },
            "log2" to fn { x -> Math.log(x) / Math.log(2.0) },
            "exp" to fn { x -> Math.exp(x) },
            "sin" to fn { x -> Math.sin(angle(x)) },
            "cos" to fn { x -> Math.cos(angle(x)) },
            "tan" to fn { x -> Math.tan(angle(x)) },
            "asin" to fn { x -> unangle(Math.asin(x)) },
            "acos" to fn { x -> unangle(Math.acos(x)) },
            "atan" to fn { x -> unangle(Math.atan(x)) },
        )
    }
}

/** Shows the answer as you type a sum or a conversion. */
internal class CalcSearchProvider(
    private val settings: SettingsStore,
    private val calc: Calc,
    private val openTape: (LauncherHost) -> Unit,
) : SearchProvider {
    override val id = "calc"

    override suspend fun search(query: Query): List<SearchResult> {
        if (!settings[CommandsSettings.liveCalc]) return emptyList()
        val answer = calc.answer(query.text, live = true) ?: return emptyList()
        return listOf(calc.result(answer, Score.EXACT, openTape))
    }
}

internal class CalcCommand(private val calc: Calc, private val openTape: (LauncherHost) -> Unit) : Command {
    override val name = "calc"
    override val aliases = listOf("=")
    override val usage = "calc [sum]"
    override val summary = "work out a sum or convert, e.g. calc sqrt(2)*3 or 5 km in mi; alone opens the tape"

    override suspend fun suggest(args: String): List<SearchResult> {
        if (args.isBlank()) {
            return listOf(
                SearchResult(
                    key = "calc:tape",
                    tag = "calc",
                    title = "calc tape",
                    detail = "past answers",
                    score = Score.EXACT,
                    action = { host ->
                        host.clearQuery()
                        openTape(host)
                    },
                ),
            )
        }
        return listOfNotNull(calc.answer(args, live = false)?.let { calc.result(it, Score.EXACT, openTape) })
    }
}
