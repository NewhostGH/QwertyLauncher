package com.newhost.qwertylauncher.feature.apps

import android.content.Context
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Favorites
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find

/** Names the user gave apps, keyed by app key. Main thread only. */
internal class AppNames(context: Context) {
    private val prefs = context.getSharedPreferences("app_names", Context.MODE_PRIVATE)

    operator fun get(key: String): String? = prefs.getString(key, null)

    fun isRenamed(key: String) = prefs.contains(key)

    fun set(key: String, name: String) = prefs.edit().putString(key, name.trim()).apply()

    fun reset(key: String) = prefs.edit().remove(key).apply()
}

/**
 * The actions for one app, shown when the user holds enter on it (in search
 * results or the favourites grid): favourites (with that module on), rename,
 * info, uninstall.
 */
internal class AppMenu(
    private val catalog: LauncherAppsCatalog,
    private val services: Services,
    private val names: AppNames,
) {
    fun items(app: AppEntry, extra: List<MenuItem> = emptyList()): List<MenuItem> = buildList {
        add(MenuItem("open") { host -> catalog.launch(app, host) })
        addAll(extra)
        // Favourites, while that module is on.
        val favorites = services.find<Favorites>()
        if (favorites != null && app.key in favorites) {
            add(MenuItem("remove from favourites") { host -> changed(host, "Removed ${app.label} from favourites.") { favorites.remove(app.key) } })
        } else if (favorites != null) {
            add(MenuItem("add to favourites") { host -> changed(host, "Added ${app.label} to favourites.") { favorites.add(app.key) } })
        }
        add(MenuItem("rename") { host -> host.setQuery("rename ${app.label} to ") })
        if (names.isRenamed(app.key)) {
            add(
                MenuItem("restore original name") { host ->
                    names.reset(app.key)
                    catalog.reload()
                    host.console.println("Restored the original name of ${app.label}.")
                },
            )
        }
        add(MenuItem("app info") { host -> AppActions.details(catalog, app, host) })
        add(MenuItem("uninstall") { host -> AppActions.uninstall(app, host) })
    }

    private fun changed(host: LauncherHost, message: String, change: () -> Unit) {
        change()
        host.clearQuery()
        host.console.println(message)
    }
}

/** `rename <app> to <new name>`: gives an app your own name everywhere in the launcher. */
internal class RenameCommand(
    private val catalog: LauncherAppsCatalog,
    private val names: AppNames,
) : Command {
    override val name = "rename"
    override val usage = "rename <app> to <new name>"
    override val summary = "give an app your own name"

    override suspend fun suggest(args: String): List<SearchResult> {
        val appPart = args.substringBefore(SEPARATOR).trim()
        val newName = if (SEPARATOR in args) args.substringAfter(SEPARATOR).trim() else null
        return catalog.apps.value.matching(appPart).map { (app, score) ->
            if (newName.isNullOrEmpty()) {
                SearchResult(
                    key = "rename:${app.key}",
                    tag = "ren",
                    title = "rename ${app.label} to ...",
                    score = score,
                    completion = "rename ${app.label} to ",
                    action = { host -> host.setQuery("rename ${app.label} to ") },
                )
            } else {
                SearchResult(
                    key = "rename:${app.key}",
                    tag = "ren",
                    title = "rename ${app.label} to $newName",
                    score = score + Score.EXACT,
                    action = { host ->
                        names.set(app.key, newName)
                        catalog.reload()
                        host.clearQuery()
                        host.console.println("Renamed ${app.label} to $newName.")
                    },
                )
            }
        }
    }

    private companion object {
        const val SEPARATOR = " to "
    }
}
