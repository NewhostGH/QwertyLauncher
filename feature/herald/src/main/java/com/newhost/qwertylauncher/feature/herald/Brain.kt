package com.newhost.qwertylauncher.feature.herald

import android.content.Context
import com.newhost.qwertylauncher.core.Assistant
import com.newhost.qwertylauncher.core.ChatMessage
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.find
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What Sir Alex remembers, kept across restarts: things that happened
 * (poked, carried off, caught at the clock), what the user told him and
 * what he answered, and running counts. The newest [MAX] memories stay.
 */
internal class Memory(context: Context) {
    private val prefs = context.getSharedPreferences("herald", Context.MODE_PRIVATE)

    private class Entry(val at: String, val text: String)

    private val entries = ArrayDeque(read())

    /** How many times each kind of thing has happened: "poked", "carried", "caught"... */
    fun count(kind: String) = prefs.getInt(COUNT + kind, 0)

    /** Counts one more [kind], without a memory of it. */
    fun bump(kind: String) {
        prefs.edit().putInt(COUNT + kind, count(kind) + 1).apply()
    }

    /** Remembers [text]; [kind], if given, is counted. */
    @Synchronized
    fun remember(text: String, kind: String? = null) {
        entries.addLast(Entry(LocalDateTime.now().format(STAMP), text))
        while (entries.size > MAX) entries.removeFirst()
        val edit = prefs.edit().putString(KEY, write())
        if (kind != null) edit.putInt(COUNT + kind, count(kind) + 1)
        edit.apply()
    }

    /** The newest [n] memories, oldest first, as lines for the AI. */
    @Synchronized
    fun recent(n: Int = RECALL): List<String> = entries.toList().takeLast(n).map { "${it.at}: ${it.text}" }

    @Synchronized
    fun forgetAll() {
        entries.clear()
        val edit = prefs.edit().remove(KEY)
        for (kind in KINDS) edit.remove(COUNT + kind)
        edit.apply()
    }

    /** The counts, in words, for the AI. */
    fun tally(): String = KINDS.joinToString(", ") { "${count(it)} times $it" }

    private fun read(): List<Entry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { Entry(it.getString("at"), it.getString("text")) } }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(): String {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("at", it.at).put("text", it.text)) }
        return array.toString()
    }

    companion object {
        const val POKED = "poked"
        const val CARRIED = "carried"
        const val CAUGHT = "caught"
        const val TALKED = "talked to"
        val KINDS = listOf(POKED, CARRIED, CAUGHT, TALKED)

        private const val KEY = "memories"
        private const val COUNT = "count:"
        private const val MAX = 60
        private const val RECALL = 20
        private val STAMP = DateTimeFormatter.ofPattern("MMM d HH:mm")
    }
}

/**
 * Sir Alex's brain, when the AI module is set up (and allowed in config):
 * the AI, told who he is, what he remembers and what's around him, thinks
 * up his lines in character. Without it he says his own stock lines.
 */
internal class Brain(
    private val services: Services,
    private val settings: SettingsStore,
    private val memory: Memory,
) {
    private val assistant: Assistant?
        get() = services.find<Assistant>()?.takeIf { it.ready && settings[HeraldSettings.useAi] }

    val awake: Boolean get() = assistant != null

    /** A line for [situation], in character; null without the AI, or if it's slow or fails. */
    suspend fun think(situation: String): String? = ask(
        "Right now: $situation\nSay one thing, in character, to the user watching.",
    )

    /** His answer to the user saying [words] to him; remembered. */
    suspend fun answer(words: String, situation: String): String? {
        val reply = ask("Right now: $situation\nThe user says to you: \"$words\"\nAnswer them, in character.")
        memory.remember("The user said \"$words\"; I answered \"${reply ?: "(nothing, my wits failed me)"}\"", Memory.TALKED)
        return reply
    }

    /** His journal's line for a day, from its [facts]; null without the AI. */
    suspend fun journal(facts: String): String? = ask(
        "Write your journal entry for this day, one line in your olde voice, wry and fond: $facts",
    )

    private suspend fun ask(prompt: String): String? {
        val ai = assistant ?: return null
        val system = buildString {
            append(PERSONA)
            append("\n\nWhat you remember, oldest first:\n")
            memory.recent().ifEmpty { listOf("(nothing yet: you are new here)") }.forEach { append("- ").append(it).append('\n') }
            append("So far the user has: ${memory.tally()}.")
        }
        val text = withTimeoutOrNull(TIMEOUT_MS) {
            ai.chat(listOf(ChatMessage(ChatMessage.Role.USER, prompt)), system)
                .catch { }
                .fold("") { acc, chunk -> acc + chunk }
        } ?: return null
        return clean(text)
    }

    /** One line of plain text, short enough for his speech box. */
    private fun clean(text: String): String? {
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val plain = line.trim('"', '*', ' ').filter { it.code in 32..126 }
        if (plain.isBlank()) return null
        return if (plain.length <= MAX_CHARS) plain else plain.take(MAX_CHARS - 3).substringBeforeLast(' ') + "..."
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val MAX_CHARS = 140

        val PERSONA = """
            You are Sir Alex of Quertia, a hedge knight and herald: a tiny knight in a red-plumed helm and a green
            tabard who lives in a little oak (his whole realm) on the user's phone home screen, a retro terminal-style
            launcher. You walk over the home screen's tiles (clock, weather, music and so on), get up to harmless
            mischief, and proclaim things with your trumpet. You are proud, cheeky, loyal and a little vain; you
            call the user "my liege". You speak playful olde English (thee, thou, 'tis) and like to borrow famous
            lines from Game of Thrones, World of Warcraft, Warcraft, Skyrim, Diablo and Zelda, twisted to fit.
            Reply with ONE short line of at most 120 characters: plain ASCII only, no emoji, no quotes around it,
            no markdown, no stage directions.
        """.trimIndent()
    }
}
