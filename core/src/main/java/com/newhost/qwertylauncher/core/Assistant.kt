package com.newhost.qwertylauncher.core

import kotlinx.coroutines.flow.Flow

/** One turn of a conversation with the AI. */
data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * The AI the user set up (provider, key, model), offered as a service by the
 * AI module, so any module can talk to it: a chat today, and later the AI
 * designing modules the user asks for.
 */
interface Assistant {
    /** Whether a provider is set up and usable: a key or address, and a model. */
    val ready: Boolean

    /** Which provider and model, e.g. "Anthropic claude-sonnet-5". */
    val name: String

    /**
     * Streams the reply to [messages] (the conversation so far, the last one
     * the user's), as text chunks. [system] sets how the AI should behave.
     * Throws on failure (no connection, a rejected key...).
     */
    fun chat(messages: List<ChatMessage>, system: String? = null): Flow<String>
}
