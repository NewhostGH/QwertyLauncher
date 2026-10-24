package com.newhost.qwertylauncher.feature.commands

import android.content.Context
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneInput
import com.newhost.qwertylauncher.core.PaneRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class TapeLine(val id: Long, val input: String, val answer: String, val value: Double?)

/**
 * Past answers, like a calculator's paper tape, oldest first, saved in
 * private preferences. Written on the main thread; read from anywhere.
 */
internal class CalcTape(context: Context) {
    private val prefs = context.getSharedPreferences("calc", Context.MODE_PRIVATE)
    private val _lines = MutableStateFlow(read())
    val lines: StateFlow<List<TapeLine>> = _lines.asStateFlow()

    /** The last answer that was a number, for `ans`. */
    fun lastValue(): Double? = _lines.value.lastOrNull { it.value != null }?.value

    fun add(answer: Answer) {
        val line = TapeLine(System.currentTimeMillis(), answer.input, answer.text, answer.value)
        write((_lines.value + line).takeLast(MAX_LINES))
    }

    fun clear() = write(emptyList())

    private fun read(): List<TapeLine> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TapeLine(
                o.getLong("id"),
                o.getString("input"),
                o.getString("answer"),
                if (o.has("value")) o.getDouble("value") else null,
            )
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(lines: List<TapeLine>) {
        _lines.value = lines
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject()
                    .put("id", line.id)
                    .put("input", line.input)
                    .put("answer", line.answer)
                    .apply { line.value?.takeIf { it.isFinite() }?.let { put("value", it) } },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "tape"
        const val MAX_LINES = 100
    }
}

/**
 * The tape as a pane that is also a calculator: type a sum at the bottom,
 * enter adds its answer. Enter on a line copies its answer.
 */
internal class CalcPane(private val calc: Calc, private val tape: CalcTape) : ListPane {
    override val title = "calc"
    override val keysHelp = "type a sum ⏎  ⏎ on a line: copy  esc close"
    override val followsEnd = true

    /** What happened last (copied, or couldn't work it out), until the next thing. */
    private val note = MutableStateFlow<String?>(null)

    override fun rows(): Flow<List<PaneRow>> = combine(tape.lines, note) { lines, note ->
        buildList {
            add(PaneRow.Header("tape"))
            if (lines.isEmpty()) {
                add(PaneRow.Item(EMPTY, "nothing worked out yet", status = HINT))
            } else {
                add(PaneRow.Item(CLEAR, "clear the tape", status = "⏎ clear ${lines.size} lines"))
            }
            lines.forEach { line ->
                add(
                    PaneRow.Item(
                        line.id.toString(),
                        line.input,
                        detail = "= ${line.answer}",
                        status = "${line.input} = ${line.answer}\nEnter copies ${line.answer}.",
                    ),
                )
            }
            if (note != null) add(PaneRow.Item(NOTE, note, status = note))
        }
    }

    override fun input(): Flow<PaneInput?> = flowOf(
        PaneInput("sum, e.g. 2^10 or 5 km in mi or 255 in hex") { text, host ->
            val answer = calc.answer(text, live = false)
            if (answer == null) {
                note.value = "Can't work out $text"
            } else {
                note.value = null
                tape.add(answer)
            }
        },
    )

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        when (item.id) {
            EMPTY, NOTE -> Unit
            CLEAR -> {
                note.value = null
                tape.clear()
            }
            else -> tape.lines.value.firstOrNull { it.id.toString() == item.id }?.let { line ->
                calc.copy(line.answer, host)
                note.value = "Copied ${line.answer}"
            }
        }
        return false
    }

    private companion object {
        const val EMPTY = "calc:empty"
        const val CLEAR = "calc:clear"
        const val NOTE = "calc:note"
        const val HINT = "Type a sum below: + - * / % ^ ! and brackets, sqrt sin log, pi and ans, " +
            "0xff and 0b101, or a conversion like 100 f to c."
    }
}
