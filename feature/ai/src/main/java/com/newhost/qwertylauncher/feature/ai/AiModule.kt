package com.newhost.qwertylauncher.feature.ai

import com.newhost.qwertylauncher.core.Assistant
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherActions
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.service

/**
 * An AI chat over the provider of your choice (Anthropic, OpenAI, Google,
 * OpenRouter, Ollama, or any OpenAI-compatible server): `ai` opens the
 * conversations, `? question` or `ai question` asks in a new one. Offers the
 * configured provider to other modules as an [Assistant].
 */
class AiModule : LauncherModule {
    override val id = "ai"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*AiSettings.all)
        val config = AiConfig(scope.appContext, scope.settings, scope.appScope)
        val assistant = ProviderAssistant(config)
        val store = ChatStore(scope.appContext, scope.appScope)
        val session = ChatSession(store, assistant, scope.appScope, scope.log) { scope.services.find<LauncherActions>() }
        val panes = AiPanes(scope.appContext, config, assistant, store, session, scope.appScope, scope.services)
        scope.service<Assistant>(assistant)
        scope.searchProvider(AiSearchProvider(panes, assistant, scope.settings))
        scope.command(AiCommand(panes, assistant))
    }
}

private fun askResult(panes: AiPanes, assistant: Assistant, question: String, score: Int) = SearchResult(
    key = "ai:ask",
    tag = "ai",
    title = "ask: $question",
    detail = if (assistant.ready) assistant.name else "set up first",
    score = score,
    action = { host ->
        host.clearQuery()
        panes.ask(question, host)
    },
)

private fun openResult(panes: AiPanes, assistant: Assistant) = SearchResult(
    key = "ai:open",
    tag = "ai",
    title = "ai conversations",
    detail = if (assistant.ready) assistant.name else "set up first",
    score = Score.EXACT,
    action = { host ->
        host.clearQuery()
        host.showPane(if (assistant.ready) panes.conversations else panes.setup)
    },
)

internal class AiSearchProvider(
    private val panes: AiPanes,
    private val assistant: Assistant,
    private val settings: SettingsStore,
) : SearchProvider {
    override val id = "ai"

    override suspend fun search(query: Query): List<SearchResult> {
        val text = query.text
        return when {
            text.startsWith("?") -> {
                val question = text.removePrefix("?").trim()
                if (question.isEmpty()) listOf(openResult(panes, assistant)) else listOf(askResult(panes, assistant, question, Score.EXACT))
            }
            // Multi-word input may be a question; offer it just above the web-search fallback.
            ' ' in text && settings[AiSettings.offerForQuestions] -> listOf(askResult(panes, assistant, text, Score.FALLBACK + 1))
            else -> emptyList()
        }
    }
}

internal class AiCommand(private val panes: AiPanes, private val assistant: Assistant) : Command {
    override val name = "ai"
    override val usage = "ai [question]"
    override val summary = "chat with the AI: conversations, or ask right away"

    override suspend fun suggest(args: String) =
        if (args.isBlank()) listOf(openResult(panes, assistant)) else listOf(askResult(panes, assistant, args.trim(), Score.EXACT))
}
