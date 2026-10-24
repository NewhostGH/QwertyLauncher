package com.newhost.qwertylauncher.feature.ai

import android.content.Context
import com.newhost.qwertylauncher.core.Assistant
import com.newhost.qwertylauncher.core.ChatMessage
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.LauncherActions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A conversation: its [messages], and a [title] from its first question. */
internal data class Conversation(val id: Long, val title: String, val updated: Long, val messages: List<ChatMessage>)

/**
 * Every conversation, newest first, kept in a file in the app's storage.
 * Replies stream into the last message and are saved when they're done.
 * Main thread only.
 */
internal class ChatStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "ai/conversations.json")

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    init {
        scope.launch { _conversations.value = withContext(Dispatchers.IO) { read() } }
    }

    fun get(id: Long): Conversation? = _conversations.value.firstOrNull { it.id == id }

    fun create(): Conversation {
        val conversation = Conversation(System.currentTimeMillis(), "new conversation", System.currentTimeMillis(), emptyList())
        _conversations.update { listOf(conversation) + it }
        return conversation
    }

    fun delete(id: Long) {
        _conversations.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Adds [message]; the first question names the conversation. */
    fun add(id: Long, message: ChatMessage) = change(id) { c ->
        val title = if (c.messages.none { it.role == ChatMessage.Role.USER } && message.role == ChatMessage.Role.USER) {
            message.text.lineSequence().first().take(TITLE_LENGTH)
        } else {
            c.title
        }
        c.copy(title = title, messages = c.messages + message)
    }

    /** Replaces the last message's text, as a reply streams in. */
    fun setLast(id: Long, text: String) = change(id) { c ->
        if (c.messages.isEmpty()) c else c.copy(messages = c.messages.dropLast(1) + c.messages.last().copy(text = text))
    }

    private fun change(id: Long, edit: (Conversation) -> Conversation) {
        _conversations.update { list ->
            list.map { if (it.id == id) edit(it).copy(updated = System.currentTimeMillis()) else it }.sortedByDescending { it.updated }
        }
    }

    fun save() {
        val snapshot = _conversations.value
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    private fun read(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return try {
            val list = JSONArray(file.readText())
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val messages = o.getJSONArray("messages")
                Conversation(
                    o.getLong("id"),
                    o.getString("title"),
                    o.getLong("updated"),
                    (0 until messages.length()).map { j ->
                        val m = messages.getJSONObject(j)
                        ChatMessage(if (m.getString("role") == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT, m.getString("text"))
                    },
                )
            }.sortedByDescending { it.updated }
        } catch (e: JSONException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun write(list: List<Conversation>) {
        val json = JSONArray()
        list.filter { it.messages.isNotEmpty() }.forEach { c ->
            val messages = JSONArray()
            c.messages.forEach { m ->
                messages.put(JSONObject().put("role", if (m.role == ChatMessage.Role.USER) "user" else "assistant").put("text", m.text))
            }
            json.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated).put("messages", messages))
        }
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    private companion object {
        const val TITLE_LENGTH = 40
    }
}

/** The configured provider, as the [Assistant] other modules use. */
internal class ProviderAssistant(private val config: AiConfig) : Assistant {
    override val ready: Boolean get() = config.connection() != null

    override val name: String
        get() = config.provider.let { p -> "${p.label} ${config.model(p).orEmpty()}".trim() }

    override fun chat(messages: List<ChatMessage>, system: String?): Flow<String> {
        val connection = config.connection() ?: throw IllegalStateException("the AI isn't set up: ${config.missing()} is missing")
        return ProviderClient.chat(connection, messages, system)
    }
}

/**
 * Sends a message in a conversation and streams the reply into it; keeps a
 * question asked before the AI was set up, to send once it is.
 */
internal class ChatSession(
    private val store: ChatStore,
    private val assistant: Assistant,
    private val scope: CoroutineScope,
    private val log: DebugLog,
    /** What the launcher can do, to tell the AI; null without the shell's actions. */
    private val actions: () -> LauncherActions?,
) {
    /** Conversations with a reply on its way. */
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy: StateFlow<Set<Long>> = _busy.asStateFlow()
    private val jobs = mutableMapOf<Long, Job>()

    /** A question waiting for the AI to be set up: its conversation and text. */
    var pending: Pair<Long, String>? = null

    fun send(id: Long, text: String) {
        store.add(id, ChatMessage(ChatMessage.Role.USER, text))
        reply(id)
    }

    /** Streams the AI's reply to the conversation so far into a new last message. */
    private fun reply(id: Long) {
        val history = store.get(id)?.messages ?: return
        store.add(id, ChatMessage(ChatMessage.Role.ASSISTANT, ""))
        _busy.update { it + id }
        jobs[id] = scope.launch {
            val reply = StringBuilder()
            try {
                assistant.chat(history, system()).collect { chunk ->
                    reply.append(chunk)
                    store.setLast(id, reply.toString())
                }
                if (reply.isEmpty()) store.setLast(id, "(no answer)")
            } catch (e: CancellationException) {
                store.setLast(id, reply.append(" (stopped)").toString())
                throw e
            } catch (e: Exception) {
                log.e("AI reply failed", e)
                store.setLast(id, reply.toString() + (if (reply.isEmpty()) "" else "\n") + "error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.update { it - id }
                jobs.remove(id)
                store.save()
            }
        }
    }

    fun stop(id: Long) {
        jobs[id]?.cancel()
    }

    /** How the AI should answer, and what it can do: every command, and how to ask for one. */
    private fun system(): String {
        val commands = actions()?.commands?.filterNot { (usage, _) -> usage.startsWith("ai") }.orEmpty()
        if (commands.isEmpty()) return SYSTEM
        return SYSTEM + "\n\n" + ACTING + "\n" + commands.joinToString("\n") { (usage, summary) -> "- $usage: $summary" }
    }

    companion object {
        /** A line asking the launcher to do something: `DO: timer 5m`. */
        const val DO = "DO:"

        /** How the AI should answer: short, plain text, as it shows in a terminal. */
        private const val SYSTEM = "You are the assistant in a terminal-style Android launcher on a small phone screen. " +
            "Answer briefly, in plain text: no markdown, no tables, no emoji."

        private val ACTING = """
            You can also do things on the phone through the launcher. To do one, write a line of its own:
            DO: <what the user would type at the launcher's prompt>
            For example "DO: timer 5m", "DO: alarm 7:30 wake up", "DO: todo buy milk", "DO: torch on",
            "DO: clock tokyo", or an app's name to open it ("DO: spotify"). Each is shown to the user, who
            runs it or not: say briefly what it will do. Only when they ask for something to be done;
            use these commands (or an app's or a contact's name):
        """.trimIndent()
    }
}
