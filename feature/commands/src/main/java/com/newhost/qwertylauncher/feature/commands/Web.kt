package com.newhost.qwertylauncher.feature.commands

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore

internal object Web {
    fun searchResult(text: String, score: Int, settings: SettingsStore) = SearchResult(
        key = "web:search:$text",
        tag = "web",
        title = "search \"$text\"",
        score = score,
        action = { host ->
            host.clearQuery()
            val url = settings[CommandsSettings.searchEngine] + Uri.encode(text)
            host.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
    )

    fun looksLikeUrl(text: String) = ' ' !in text && '.' in text && Patterns.WEB_URL.matcher(text).matches()

    fun openResult(text: String): SearchResult {
        val url = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        return SearchResult(
            key = "web:open",
            tag = "url",
            title = url,
            score = Score.PREFIX,
            action = { host ->
                host.clearQuery()
                host.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
        )
    }
}

/** Always offers a web search as the last resort, and opens anything that looks like a URL. */
internal class WebSearchProvider(private val settings: SettingsStore) : SearchProvider {
    override val id = "web"

    override suspend fun search(query: Query): List<SearchResult> {
        val text = query.text
        if (text.startsWith("?")) return emptyList() // that's for the AI
        return buildList {
            if (settings[CommandsSettings.webFallback]) add(Web.searchResult(text, Score.FALLBACK, settings))
            if (Web.looksLikeUrl(text)) add(Web.openResult(text))
        }
    }
}

internal class WebCommand(private val settings: SettingsStore) : Command {
    override val name = "web"
    override val aliases = listOf("g", "ddg")
    override val usage = "web <query>"
    override val summary = "search the web"

    override suspend fun suggest(args: String) =
        if (args.isBlank()) emptyList() else listOf(Web.searchResult(args, Score.EXACT, settings))
}
