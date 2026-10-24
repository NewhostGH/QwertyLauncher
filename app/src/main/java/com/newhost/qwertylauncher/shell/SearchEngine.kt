package com.newhost.qwertylauncher.shell

import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Fans a query out to every provider in parallel and merges the ranked results. */
internal class SearchEngine(
    private val providers: () -> List<SearchProvider>,
    private val log: DebugLog,
) {

    suspend fun search(query: Query): List<SearchResult> = coroutineScope {
        providers()
            .map { provider -> async(Dispatchers.Default) { searchSafely(provider, query) } }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.score }
            .distinctBy { it.key } // keeps the best-scored copy
            .take(MAX_RESULTS)
    }

    private suspend fun searchSafely(provider: SearchProvider, query: Query): List<SearchResult> =
        try {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.search(query) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("search provider '${provider.id}' failed", e)
            emptyList()
        }

    private companion object {
        const val MAX_RESULTS = 50
        const val PROVIDER_TIMEOUT_MS = 1_500L
    }
}
