package com.newhost.qwertylauncher.feature.ai

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.Assistant
import com.newhost.qwertylauncher.core.ChatMessage
import com.newhost.qwertylauncher.core.LauncherActions
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.PaneInput
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.cells
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.grid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The AI's panes and the moves between them:
 *
 *     conversations ── enter ──> a conversation (type below, enter sends)
 *          │ setup
 *          v
 *     setup (provider, key, address, test) ── model ──> models (fetched, or type one)
 *
 * Esc goes back up. A question asked before the AI is set up waits in
 * setup, and is sent (and its conversation opened) once it's ready.
 */
internal class AiPanes(
    private val context: Context,
    private val config: AiConfig,
    private val assistant: Assistant,
    private val store: ChatStore,
    private val session: ChatSession,
    private val scope: CoroutineScope,
    private val services: Services,
) {
    /**
     * What each `DO:` line the AI wrote would run: looked up once (null
     * while looking, or when nothing matches), by the line.
     */
    private val previews = MutableStateFlow<Map<String, Preview>>(emptyMap())

    /** A proposed action: what it would run, if anything, and whether it has run. */
    private class Preview(val result: SearchResult?, val done: Boolean = false, val looking: Boolean = false)

    /** Looks up what [line] would run, once. */
    private fun preview(line: String) {
        if (line in previews.value) return
        previews.update { it + (line to Preview(null, looking = true)) }
        scope.launch {
            val result = services.find<LauncherActions>()?.preview(line)
            previews.update { it + (line to Preview(result)) }
        }
    }
    val conversations = ConversationsPane()
    val setup = SetupPane()
    val models = ModelPane()

    fun chat(id: Long) = ChatPane(id)

    /** Asks [question] in a new conversation; first sets up the AI if it isn't. */
    fun ask(question: String, host: LauncherHost) {
        val conversation = store.create()
        if (assistant.ready) {
            session.send(conversation.id, question)
            host.showPane(chat(conversation.id))
        } else {
            session.pending = conversation.id to question
            setup.note("Set up the AI first: your question is kept, and asked once it's ready.")
            host.showPane(setup)
        }
    }

    private fun color(id: Int) = context.getColor(id)

    /** `sk-ant-...4f2a`: enough to recognise a key, not enough to use it. */
    private fun mask(key: String): String = if (key.length <= MASK_KEEP * 2) "..." else key.take(MASK_KEEP) + "..." + key.takeLast(MASK_KEEP)

    // --- The conversations ---

    inner class ConversationsPane : ListPane {
        override val title = "ai"
        override val keysHelp = "⏎ open  n new  d delete  s setup  q back"

        override fun rows(): Flow<List<PaneRow>> = combine(store.conversations, config.changes, session.busy) { list, _, busy ->
            buildList {
                add(PaneRow.Header("assistant"))
                val missing = config.missing()
                add(
                    if (missing == null) {
                        PaneRow.Item(SETUP, "setup: ${assistant.name}", status = "⏎ provider, key, model")
                    } else {
                        PaneRow.Item(SETUP, "set up an AI provider", detail = "needs $missing", status = "⏎ set up: provider, key, model")
                    },
                )
                add(PaneRow.Item(NEW, "new conversation", status = "⏎ or n: new conversation"))
                val shown = list.filter { it.messages.isNotEmpty() }
                add(PaneRow.Header("conversations (${shown.size})"))
                if (shown.isEmpty()) add(PaneRow.Item(NONE, "no conversations yet", status = "Start one above, or ask from the prompt: ? your question"))
                shown.forEach { c ->
                    val date = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault())
                        .format(Instant.ofEpochMilli(c.updated).atZone(ZoneId.systemDefault())).lowercase()
                    PaneRow.Item(
                        CONVERSATION + c.id,
                        c.title,
                        detail = if (c.id in busy) "$date  answering" else date,
                        status = "${c.messages.size} messages\n⏎ open  d delete",
                    ).let(::add)
                }
            }
        }

        override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
            when {
                item.id == SETUP -> host.showPane(setup)
                item.id == NEW -> host.showPane(chat(store.create().id))
                item.id.startsWith(CONVERSATION) -> host.showPane(chat(item.id.removePrefix(CONVERSATION).toLong()))
            }
            return false
        }

        override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
            when (event.keyCode) {
                KeyEvent.KEYCODE_N -> host.showPane(chat(store.create().id))
                KeyEvent.KEYCODE_S -> host.showPane(setup)
                KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    val id = item?.id?.takeIf { it.startsWith(CONVERSATION) }?.removePrefix(CONVERSATION)?.toLong() ?: return true
                    session.stop(id)
                    store.delete(id)
                }
                else -> return false
            }
            return true
        }
    }

    // --- One conversation ---

    /** A conversation: its messages, the newest last, and a line to type the next one. */
    inner class ChatPane(private val id: Long) : ListPane {
        override val title = "ai: ${store.get(id)?.title ?: "new conversation"}"
        override val keysHelp = "type ⏎ send  ↑↓ scroll  ^c stop  ^n new  esc back"
        override val followsEnd = true

        override fun rows(): Flow<List<PaneRow>> = combine(store.conversations, session.busy, previews) { list, busy, looked ->
            val conversation = list.firstOrNull { it.id == id }
            val messages = conversation?.messages.orEmpty()
            buildList {
                add(PaneRow.Header(conversation?.title ?: "new conversation"))
                if (messages.isEmpty()) {
                    add(PaneRow.Item(HINT, "type below ⏎", status = "Answers come from ${assistant.name}."))
                }
                messages.forEachIndexed { i, message ->
                    val last = i == messages.lastIndex
                    val answering = last && id in busy
                    val thinking = answering && message.text.isEmpty()
                    // What the AI asks to do: taken out of its words, and offered below them, once it's done answering.
                    val (said, asks) = split(message)
                    add(block(message, if (thinking) "..." else said))
                    if (!answering) asks.forEachIndexed { k, line -> add(actionRow(i, k, line, looked[line])) }
                }
            }
        }

        /** An AI message's words, and its `DO:` lines apart. */
        private fun split(message: ChatMessage): Pair<String, List<String>> {
            if (message.role != ChatMessage.Role.ASSISTANT) return message.text to emptyList()
            val lines = message.text.lines()
            val asks = lines.filter { it.trim().startsWith(ChatSession.DO, ignoreCase = true) }
                .map { it.trim().drop(ChatSession.DO.length).trim().trim('`', '"') }
                .filter { it.isNotEmpty() }
            val said = lines.filterNot { it.trim().startsWith(ChatSession.DO, ignoreCase = true) }.joinToString("\n").trim()
            return said to asks
        }

        /** A proposed action: what it would run, and enter to run it; nothing runs until then. */
        private fun actionRow(message: Int, index: Int, line: String, preview: Preview?): PaneRow.Item {
            if (preview == null) preview(line)
            val result = preview?.result
            val id = "$ACTION$message:$index"
            return when {
                preview == null || preview.looking -> PaneRow.Item(id, "> do: $line", detail = "...", status = "Looking up what that does.")
                preview.done -> PaneRow.Item(id, "> done: $line", detail = result?.title?.toString(), status = "Done.")
                result == null -> PaneRow.Item(id, "> can't: $line", status = "Nothing in the launcher does \"$line\".")
                else -> PaneRow.Item(
                    id,
                    "> do: $line",
                    detail = listOfNotNull(result.title, result.detail).joinToString("  "),
                    status = "⏎ run: ${result.title}\nsuggested by the AI; runs only on ⏎",
                )
            }
        }

        /** One message: who says it, then what, wrapped to the pane. */
        private fun block(message: ChatMessage, text: String): PaneRow.Block {
            val user = message.role == ChatMessage.Role.USER
            val who = SpannableStringBuilder().apply {
                append(if (user) "you" else "ai", ForegroundColorSpan(color(if (user) CoreR.color.tty_accent else CoreR.color.tty_cyan)), SPAN)
            }
            return PaneRow.Block(
                grid {
                    columns(cells(WHO_COLUMNS), fr())
                    text(who)
                    text(text, column = 1)
                },
            )
        }

        override fun input(): Flow<PaneInput?> = config.changes.map {
            PaneInput(if (assistant.ready) "message ${assistant.name}" else "message (set up the AI first)") { text, host -> send(text, host) }
        }

        private fun send(text: String, host: LauncherHost) {
            if (!assistant.ready) {
                session.pending = id to text
                setup.note("Set up the AI first: your message is kept, and sent once it's ready.")
                host.showPane(setup)
                return
            }
            session.send(id, text)
        }

        override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
            if (!item.id.startsWith(ACTION)) return false
            val (message, index) = item.id.removePrefix(ACTION).split(':').map { it.toInt() }
            val text = store.get(id)?.messages?.getOrNull(message) ?: return false
            val line = split(text).second.getOrNull(index) ?: return false
            val preview = previews.value[line] ?: return false
            val result = preview.result ?: return false
            if (preview.done) return false
            previews.update { it + (line to Preview(result, done = true)) }
            result.action.run(host)
            return false
        }

        override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
            if (!event.isCtrlPressed) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> session.stop(id)
                KeyEvent.KEYCODE_N -> host.showPane(chat(store.create().id))
                else -> return false
            }
            return true
        }

        override fun onBack(host: LauncherHost): Boolean {
            host.showPane(conversations)
            return true
        }
    }

    // --- Setting up a provider ---

    /** What the setup pane's input line is for. */
    private enum class Field { KEY, ADDRESS }

    /**
     * Setting up: pick a provider; get a key on its site and paste it when you
     * come back (it's spotted on the clipboard), or type it; an address for
     * Ollama or other servers; a model; and a test.
     */
    inner class SetupPane : ListPane {
        override val title = "ai setup"
        override val keysHelp = "⏎ pick  type keys below  esc back"

        private val status = MutableStateFlow<String?>(null)
        private val field = MutableStateFlow(Field.KEY)

        /** A key spotted on the clipboard after visiting the provider's site. */
        private val found = MutableStateFlow<String?>(null)
        private var awaitingKey = false

        fun note(text: String) {
            status.value = text
        }

        override fun rows(): Flow<List<PaneRow>> = combine(config.changes, status, found, field) { _, note, clip, _ ->
            val p = config.provider
            buildList {
                if (note != null) {
                    add(PaneRow.Header("setup"))
                    add(PaneRow.Item(STATUS, note, status = note))
                }
                session.pending?.let { (_, question) ->
                    if (config.missing() == null) {
                        add(PaneRow.Item(CONTINUE, "ask: $question", status = "Everything's ready: enter asks it."))
                    }
                }
                add(PaneRow.Header("provider"))
                Provider.entries.forEach { provider ->
                    add(
                        PaneRow.Item(
                            PROVIDER + provider.id,
                            "${if (provider == p) "[x]" else "[ ]"} ${provider.label}",
                            status = "⏎ use ${provider.label}",
                        ),
                    )
                }
                val missing = config.missing(p)
                add(PaneRow.Header(if (missing == null) "${p.label}: ready" else "${p.label}: needs $missing"))
                if (p.needsAddress) {
                    val address = config.address(p)
                    add(
                        PaneRow.Item(
                            ADDRESS,
                            "address: ${address.ifBlank { "not set" }}",
                            status = "⏎ then type the address below\ne.g. http://192.168.1.20:11434 (Ollama)",
                        ),
                    )
                }
                if (p.needsKey || p == Provider.GENERIC) {
                    val key = config.key(p)
                    if (clip != null && clip != key) {
                        add(PaneRow.Item(PASTE, "paste the key you copied: ${mask(clip)}", status = "⏎ save and check with ${p.label}"))
                    } else {
                        add(PaneRow.Item(PASTE, "paste a key from the clipboard", status = "Copy your key, then enter here saves it."))
                    }
                    p.keyPage?.let { page ->
                        add(
                            PaneRow.Item(
                                SITE,
                                "get a key on ${Uri.parse(page).host}",
                                status = "Opens ${p.label}'s key page in the browser. Create a key, copy it, come back: it's offered to paste here.",
                            ),
                        )
                    }
                    add(PaneRow.Item(TYPE_KEY, "or type the key below", status = "Type or paste it on the line below, then enter."))
                    if (key != null) add(PaneRow.Item(KEY, "key: ${mask(key)}", status = "saved encrypted on this phone\n⏎ remove"))
                } else {
                    p.keyPage?.let { page ->
                        add(PaneRow.Item(SITE, "get ${p.label}", status = "Opens $page: run it on your computer or phone, then set its address above."))
                    }
                }
                add(PaneRow.Header("model"))
                add(
                    PaneRow.Item(
                        MODEL,
                        "model: ${config.model(p) ?: "not chosen"}",
                        status = "⏎ list ${p.label} models (or type one)",
                    ),
                )
                add(PaneRow.Item(TEST, "test it", status = "Sends a tiny message and shows what comes back."))
            }
        }.onStart {
            // Back from the provider's site: a key copied there is offered right away.
            // Android only lets the focused app read the clipboard, so wait for focus to come back.
            if (awaitingKey) {
                awaitingKey = false
                scope.launch {
                    delay(CLIP_DELAY_MS)
                    val clip = clipboard()
                    if (clip != null && config.provider.looksLikeKey(clip)) {
                        found.value = clip.trim()
                        status.value = "Found a ${config.provider.label} key on the clipboard: enter on paste saves it."
                    }
                }
            }
        }

        override fun input(): Flow<PaneInput?> = combine(field, config.changes) { current, _ ->
            val p = config.provider
            val typing = if (!p.needsKey && p != Provider.GENERIC) Field.ADDRESS else current
            when (typing) {
                Field.ADDRESS -> PaneInput("server address, like ${p.defaultAddress.ifBlank { "https://host/v1" }}") { text, _ -> saveAddress(text) }
                Field.KEY -> PaneInput("paste or type your ${p.label} key", secret = true) { text, _ -> saveKey(text) }
            }
        }

        override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
            val p = config.provider
            when {
                item.id.startsWith(PROVIDER) -> {
                    val chosen = Provider.of(item.id.removePrefix(PROVIDER))
                    config.setProvider(chosen)
                    found.value = null
                    field.value = if (chosen.needsKey) Field.KEY else Field.ADDRESS
                    status.value = config.missing(chosen)?.let { "${chosen.label} needs $it." } ?: "${chosen.label} is ready."
                }
                item.id == ADDRESS -> {
                    field.value = Field.ADDRESS
                    status.value = "Type the address below, then enter."
                }
                item.id == TYPE_KEY -> {
                    field.value = Field.KEY
                    status.value = "Type or paste the key below, then enter."
                }
                item.id == PASTE -> {
                    val clip = found.value ?: clipboard()
                    when {
                        clip.isNullOrBlank() -> status.value = "The clipboard is empty: copy your key first."
                        p.keyPrefix != null && !p.looksLikeKey(clip) -> status.value = "That doesn't look like a ${p.label} key (they start ${p.keyPrefix})."
                        else -> saveKey(clip)
                    }
                }
                item.id == SITE -> p.keyPage?.let { page ->
                    awaitingKey = true
                    host.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
                }
                item.id == KEY -> host.showMenu(
                    "${p.label} key",
                    listOf(
                        MenuItem("remove the key") {
                            config.setKey(p, null)
                            status.value = "Removed the ${p.label} key."
                        },
                    ),
                )
                item.id == MODEL -> host.showPane(models)
                item.id == TEST -> test()
                item.id == CONTINUE -> session.pending?.let { (id, question) ->
                    session.pending = null
                    status.value = null
                    session.send(id, question)
                    host.showPane(chat(id))
                }
            }
            return false
        }

        override fun onBack(host: LauncherHost): Boolean {
            host.showPane(conversations)
            return true
        }

        private fun saveKey(key: String) {
            val p = config.provider
            config.setKey(p, key)
            found.value = null
            check(p, "Checking the key with ${p.label}...")
        }

        private fun saveAddress(address: String) {
            val p = config.provider
            config.setAddress(p, address)
            // Keys come after the address for servers that want one.
            if (p == Provider.GENERIC) field.value = Field.KEY
            check(p, "Checking $address...")
        }

        /** Fetches the models to see the key or address works, and picks one if none is chosen yet. */
        private fun check(p: Provider, checking: String) {
            status.value = checking
            scope.launch {
                status.value = try {
                    val list = ProviderClient.models(p, config.address(p), config.key(p))
                    val current = config.model(p)
                    if (current.isNullOrBlank() || (list.isNotEmpty() && current !in list && p.suggestedModel == null)) {
                        preferred(list)?.let { config.setModel(p, it) }
                    }
                    models.loaded(p, list)
                    "It works: ${list.size} models. " + if (session.pending != null && config.missing() == null) "Your question is ready to ask above." else ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "That didn't work: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        private fun test() {
            status.value = "Testing..."
            scope.launch {
                status.value = try {
                    val reply = StringBuilder()
                    assistant.chat(listOf(ChatMessage(ChatMessage.Role.USER, "Reply with just the word: ready"))).collect { reply.append(it) }
                    "It answered: ${reply.toString().trim().take(TEST_REPLY)}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "The test failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        private fun clipboard(): String? = runCatching {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
            if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
        }.getOrNull()
    }

    /** The model to start with from a fresh list: a well-known everyday one if it's there, else the first. */
    private fun preferred(list: List<String>): String? =
        PREFERRED.firstNotNullOfOrNull { hint -> list.firstOrNull { hint in it } } ?: list.firstOrNull()

    // --- Choosing a model ---

    private sealed interface Models {
        data object Loading : Models
        data class Failed(val message: String) : Models
        data class Ready(val provider: Provider, val list: List<String>) : Models
    }

    /** The provider's models, fetched; or type one in (a new model not listed yet, a local one). */
    inner class ModelPane : ListPane {
        override val title = "ai models"
        override val keysHelp = "⏎ pick  or type an id ⏎  esc back"

        private val state = MutableStateFlow<Models>(Models.Loading)

        /** A list fetched while checking a key: shown without fetching again. */
        fun loaded(provider: Provider, list: List<String>) {
            state.value = Models.Ready(provider, list)
        }

        override fun rows(): Flow<List<PaneRow>> = combine(state, config.changes) { models, _ ->
            val p = config.provider
            val current = config.model(p)
            buildList {
                add(PaneRow.Header("models from ${p.label}"))
                when (models) {
                    Models.Loading -> add(PaneRow.Item(LOADING, "fetching the list..."))
                    is Models.Failed -> add(PaneRow.Item(FAILED, models.message, status = "Type a model id below instead."))
                    is Models.Ready -> {
                        if (models.list.isEmpty()) add(PaneRow.Item(FAILED, "none listed", status = "Type a model id below instead."))
                        models.list.forEach { model ->
                            add(PaneRow.Item(MODEL + model, "${if (model == current) "[x]" else "[ ]"} $model", status = "⏎ use $model"))
                        }
                    }
                }
                // A model typed in by hand, not in the list: shown so it's clear it's the one in use.
                if (current != null && (models !is Models.Ready || current !in models.list)) {
                    add(PaneRow.Header("typed in"))
                    add(PaneRow.Item(MODEL + current, "[x] $current", status = "In use."))
                }
            }
        }.onStart { refresh() }

        private fun refresh() {
            val p = config.provider
            val have = state.value
            if (have is Models.Ready && have.provider == p) return
            state.value = Models.Loading
            scope.launch {
                state.value = try {
                    Models.Ready(p, ProviderClient.models(p, config.address(p), config.key(p)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Models.Failed("Couldn't fetch the list: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        override fun input(): Flow<PaneInput?> = config.changes.map {
            val p = config.provider
            PaneInput("or type a model id${p.suggestedModel?.let { ", like $it" } ?: ""}") { text, host -> choose(text, host) }
        }

        override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
            if (item.id.startsWith(MODEL)) choose(item.id.removePrefix(MODEL), host)
            return false
        }

        private fun choose(model: String, host: LauncherHost) {
            config.setModel(config.provider, model)
            setup.note("Using $model.")
            host.showPane(setup)
        }

        override fun onBack(host: LauncherHost): Boolean {
            host.showPane(setup)
            return true
        }
    }

    private companion object {
        const val SETUP = "ai:setup"
        const val NEW = "ai:new"
        const val NONE = "ai:none"
        const val HINT = "ai:hint"
        const val ACTION = "ai:do:"
        const val CONVERSATION = "ai:conversation:"
        const val STATUS = "ai:status"
        const val CONTINUE = "ai:continue"
        const val PROVIDER = "ai:provider:"
        const val ADDRESS = "ai:address"
        const val PASTE = "ai:paste"
        const val SITE = "ai:site"
        const val TYPE_KEY = "ai:typekey"
        const val KEY = "ai:key"
        const val MODEL = "ai:model:"
        const val TEST = "ai:test"
        const val LOADING = "ai:loading"
        const val FAILED = "ai:failed"
        const val MASK_KEEP = 4
        const val CLIP_DELAY_MS = 400L
        const val TEST_REPLY = 60
        const val WHO_COLUMNS = 4
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Everyday models worth starting with, most wanted first, matched by part of their id. */
        val PREFERRED = listOf("claude-sonnet", "gpt-5-mini", "gpt-4o-mini", "gemini-2.5-flash", "gemini-flash", "flash", "llama", "qwen")
    }
}
