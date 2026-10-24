package com.newhost.qwertylauncher.feature.favorites

import android.content.Context
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Favorites
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.ResultAction
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ordered favourite app keys. Mutate on the main thread only. */
internal class FavoritesStore(context: Context) : Favorites {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(read())
    override val keys: StateFlow<List<String>> = _keys.asStateFlow()

    private val _visible = MutableStateFlow<List<String>>(emptyList())
    override val visible: StateFlow<List<String>> = _visible.asStateFlow()

    /** The grid shows these now. */
    fun showing(keys: List<String>) {
        _visible.value = keys
    }

    override operator fun contains(key: String) = key in _keys.value

    override fun add(key: String) = write(_keys.value.filterNot { it == key } + key)

    override fun remove(key: String) = write(_keys.value - key)

    fun move(key: String, delta: Int) {
        val list = _keys.value.toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return
        list.removeAt(from)
        list.add((from + delta).coerceIn(0, list.size), key)
        write(list)
    }

    private fun read(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun write(list: List<String>) {
        _keys.value = list
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "keys"
    }
}

/** `fav add|rm|ls <app>` */
internal class FavCommand(
    private val services: Services,
    private val favorites: FavoritesStore,
) : Command {
    override val name = "fav"
    override val usage = "fav add|rm|ls <app>"
    override val summary = "manage the favourites grid"

    override suspend fun suggest(args: String): List<SearchResult> {
        // The apps come from the apps module; with it off there's nothing to add.
        val catalog = services.find<AppCatalog>() ?: return emptyList()
        val sub = args.substringBefore(' ').lowercase()
        val rest = args.substringAfter(' ', "").trim()
        return when (sub) {
            "add" -> catalog.apps.value.filterNot { it.key in favorites }.matching(rest).map { (app, score) ->
                result("add", app, score) { host ->
                    favorites.add(app.key)
                    host.console.println("+ ${app.label}")
                    host.clearQuery()
                }
            }
            "rm" -> favoriteApps(catalog).matching(rest).map { (app, score) ->
                result("rm", app, score) { host ->
                    favorites.remove(app.key)
                    host.console.println("- ${app.label}")
                    host.clearQuery()
                }
            }
            "ls" -> listOf(
                SearchResult(
                    key = "fav:ls",
                    tag = "fav",
                    title = "fav ls",
                    detail = "${favorites.keys.value.size} apps",
                    action = { host ->
                        host.clearQuery()
                        host.openPrompt() // keeps the list in view
                        favoriteApps(catalog).forEachIndexed { i, app -> host.console.println("${i + 1}. ${app.label}") }
                    },
                ),
            )
            else -> SUBCOMMANDS.filter { it.startsWith(sub) }.map { verb ->
                SearchResult(
                    key = "fav:$verb",
                    tag = "fav",
                    title = "fav $verb",
                    completion = "fav $verb ",
                    action = { host -> host.setQuery("fav $verb ") },
                )
            }
        }
    }

    private fun favoriteApps(catalog: AppCatalog) = favorites.keys.value.mapNotNull(catalog::find)

    private fun result(verb: String, app: AppEntry, score: Int, action: ResultAction) = SearchResult(
        key = "fav:$verb:${app.key}",
        tag = "fav",
        title = "fav $verb ${app.label}",
        score = score,
        completion = "fav $verb ${app.label}",
        action = action,
    )

    private companion object {
        val SUBCOMMANDS = listOf("add", "rm", "ls")
    }
}

/** Fuzzy-matches apps by label, best first. A blank query returns the first [limit] apps. */
private fun List<AppEntry>.matching(query: String, limit: Int = 8): List<Pair<AppEntry, Int>> {
    if (query.isBlank()) return take(limit).map { it to Score.DEFAULT }
    return mapNotNull { app -> Fuzzy.score(query, app.label).takeIf { it > 0 }?.let { app to it } }
        .sortedByDescending { it.second }
        .take(limit)
}
