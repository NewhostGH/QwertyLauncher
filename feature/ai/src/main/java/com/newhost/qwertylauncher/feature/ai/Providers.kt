package com.newhost.qwertylauncher.feature.ai

import com.newhost.qwertylauncher.core.ChatMessage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The wire format a provider speaks. */
internal enum class Protocol { ANTHROPIC, OPENAI, GEMINI, OLLAMA }

/**
 * An AI provider: how to reach it, where its users get a key ([keyPage]),
 * what its keys look like ([keyPrefix], to spot one on the clipboard), and a
 * model to start with before the list is fetched.
 */
internal enum class Provider(
    val id: String,
    val label: String,
    val protocol: Protocol,
    val defaultAddress: String,
    val keyPage: String?,
    val keyPrefix: String?,
    val needsKey: Boolean,
    val needsAddress: Boolean,
    val suggestedModel: String?,
) {
    ANTHROPIC(
        "anthropic", "Anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com",
        "https://console.anthropic.com/settings/keys", "sk-ant-", needsKey = true, needsAddress = false, suggestedModel = "claude-sonnet-5",
    ),
    OPENAI(
        "openai", "OpenAI", Protocol.OPENAI, "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys", "sk-", needsKey = true, needsAddress = false, suggestedModel = null,
    ),
    GOOGLE(
        "google", "Google Gemini", Protocol.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
        "https://aistudio.google.com/apikey", "AIza", needsKey = true, needsAddress = false, suggestedModel = null,
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", Protocol.OPENAI, "https://openrouter.ai/api/v1",
        "https://openrouter.ai/keys", "sk-or-", needsKey = true, needsAddress = false, suggestedModel = null,
    ),
    OLLAMA(
        "ollama", "Ollama", Protocol.OLLAMA, "http://localhost:11434",
        "https://ollama.com/download", null, needsKey = false, needsAddress = true, suggestedModel = null,
    ),
    GENERIC(
        "generic", "other (OpenAI-compatible)", Protocol.OPENAI, "",
        null, null, needsKey = false, needsAddress = true, suggestedModel = null,
    ),
    ;

    /** Whether [text] looks like one of this provider's keys: no spaces, long enough, the right start. */
    fun looksLikeKey(text: String): Boolean {
        val t = text.trim()
        if (t.length < MIN_KEY_LENGTH || t.any { it.isWhitespace() }) return false
        val prefix = keyPrefix ?: return true
        // OpenAI's "sk-" also starts Anthropic's and OpenRouter's keys: those aren't OpenAI's.
        if (this == OPENAI && (t.startsWith(ANTHROPIC.keyPrefix!!) || t.startsWith(OPENROUTER.keyPrefix!!))) return false
        return t.startsWith(prefix)
    }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: ANTHROPIC
        const val MIN_KEY_LENGTH = 20
    }
}

/** Everything needed to talk to a provider: where, with which key, which model. */
internal class Connection(val provider: Provider, val address: String, val key: String?, val model: String)

/** Talks to providers in their own formats: lists models, streams chat replies. */
internal object ProviderClient {

    /** The models the provider offers, by id, sorted. */
    suspend fun models(provider: Provider, address: String, key: String?): List<String> = withContext(Dispatchers.IO) {
        val base = address.trimEnd('/')
        when (provider.protocol) {
            Protocol.ANTHROPIC -> ids(Http.get("$base/v1/models?limit=100", anthropicHeaders(key)), "data", "id")
            Protocol.OPENAI -> ids(Http.get("$base/models", bearer(key)), "data", "id")
            Protocol.GEMINI -> {
                val root = JSONObject(Http.get("$base/models?pageSize=200&key=${enc(key.orEmpty())}", emptyMap()))
                val models = root.optJSONArray("models") ?: JSONArray()
                (0 until models.length()).map { models.getJSONObject(it) }
                    .filter { m ->
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        methods == null || (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                    }
                    .map { it.getString("name").removePrefix("models/") }
            }
            Protocol.OLLAMA -> ids(Http.get("$base/api/tags", emptyMap()), "models", "name")
        }.distinct().sorted()
    }

    /** Streams the reply to [messages] as text chunks. */
    fun chat(c: Connection, messages: List<ChatMessage>, system: String?): Flow<String> = flow {
        val base = c.address.trimEnd('/')
        when (c.provider.protocol) {
            Protocol.ANTHROPIC -> {
                val body = JSONObject()
                    .put("model", c.model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("stream", true)
                    .put("messages", roles(messages, assistant = "assistant"))
                if (system != null) body.put("system", system)
                Http.stream("$base/v1/messages", anthropicHeaders(c.key), body.toString()) { line ->
                    val data = sse(line) ?: return@stream
                    val event = JSONObject(data)
                    when (event.optString("type")) {
                        "content_block_delta" -> event.optJSONObject("delta")?.optString("text")?.let { if (it.isNotEmpty()) emit(it) }
                        "error" -> throw IOException(event.optJSONObject("error")?.optString("message") ?: "error")
                    }
                }
            }
            Protocol.OPENAI -> {
                val all = JSONArray()
                if (system != null) all.put(JSONObject().put("role", "system").put("content", system))
                val turns = roles(messages, assistant = "assistant")
                for (i in 0 until turns.length()) all.put(turns.get(i))
                val body = JSONObject().put("model", c.model).put("stream", true).put("messages", all)
                Http.stream("$base/chat/completions", bearer(c.key), body.toString()) { line ->
                    val data = sse(line) ?: return@stream
                    if (data == "[DONE]") return@stream
                    val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return@stream
                    val text = choice.optJSONObject("delta")?.optString("content").orEmpty()
                    if (text.isNotEmpty() && text != "null") emit(text)
                }
            }
            Protocol.GEMINI -> {
                val contents = JSONArray()
                messages.forEach { m ->
                    contents.put(
                        JSONObject()
                            .put("role", if (m.role == ChatMessage.Role.USER) "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", m.text))),
                    )
                }
                val body = JSONObject().put("contents", contents)
                if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                val url = "$base/models/${c.model}:streamGenerateContent?alt=sse&key=${enc(c.key.orEmpty())}"
                Http.stream(url, emptyMap(), body.toString()) { line ->
                    val data = sse(line) ?: return@stream
                    val parts = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@stream
                    for (i in 0 until parts.length()) parts.optJSONObject(i)?.optString("text")?.let { if (it.isNotEmpty()) emit(it) }
                }
            }
            Protocol.OLLAMA -> {
                val all = JSONArray()
                if (system != null) all.put(JSONObject().put("role", "system").put("content", system))
                val turns = roles(messages, assistant = "assistant")
                for (i in 0 until turns.length()) all.put(turns.get(i))
                val body = JSONObject().put("model", c.model).put("stream", true).put("messages", all)
                // Not server-sent events: one JSON object a line.
                Http.stream("$base/api/chat", emptyMap(), body.toString()) { line ->
                    if (line.isBlank()) return@stream
                    val obj = JSONObject(line)
                    obj.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
                    obj.optJSONObject("message")?.optString("content")?.let { if (it.isNotEmpty()) emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun roles(messages: List<ChatMessage>, assistant: String): JSONArray {
        val out = JSONArray()
        messages.forEach { m ->
            out.put(JSONObject().put("role", if (m.role == ChatMessage.Role.USER) "user" else assistant).put("content", m.text))
        }
        return out
    }

    /** The data of a server-sent event line, or null for other lines. */
    private fun sse(line: String): String? = if (line.startsWith("data:")) line.removePrefix("data:").trim() else null

    private fun anthropicHeaders(key: String?) = mapOf("x-api-key" to key.orEmpty(), "anthropic-version" to ANTHROPIC_VERSION)

    private fun bearer(key: String?) = if (key.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun ids(body: String, array: String, field: String): List<String> {
        val list = JSONObject(body).optJSONArray(array) ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString(field)?.takeIf { id -> id.isNotEmpty() } }
    }

    private const val MAX_TOKENS = 4096
    private const val ANTHROPIC_VERSION = "2023-06-01"
}

/** HTTP for the providers: GET a body, or POST and read the reply line by line as it streams. Blocking. */
internal object Http {
    fun get(url: String, headers: Map<String, String>): String {
        val connection = open(url, headers)
        try {
            check(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** POSTs [body] as JSON and hands each line of the reply to [line] as it arrives. */
    suspend fun stream(url: String, headers: Map<String, String>, body: String, line: suspend (String) -> Unit) {
        val connection = open(url, headers)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection)
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val next = reader.readLine() ?: break
                    line(next)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /** Throws with the provider's own message on an error reply (a rejected key says so). */
    private fun check(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val message = runCatching {
            val root = JSONObject(detail)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: root.optString("error").takeIf { it.isNotEmpty() }
        }.getOrNull() ?: detail.take(MAX_DETAIL)
        throw IOException("HTTP $code${if (message.isNotBlank()) ": $message" else ""}")
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_DETAIL = 200
}
