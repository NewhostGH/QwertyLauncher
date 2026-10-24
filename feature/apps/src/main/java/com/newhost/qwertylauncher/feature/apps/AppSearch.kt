package com.newhost.qwertylauncher.feature.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AppSearchProvider(
    private val catalog: LauncherAppsCatalog,
    private val settings: SettingsStore,
    private val menu: AppMenu,
) : SearchProvider {
    override val id = "apps"

    override suspend fun search(query: Query): List<SearchResult> {
        val rankByUsage = settings[AppsSettings.usageRanking]
        return catalog.apps.value.matching(query.text, limit = 20).map { (app, score) ->
            // Apps you open often float up within their match band.
            val boost = if (rankByUsage) (catalog.launchCount(app) * 5).coerceAtMost(MAX_USAGE_BOOST) else 0
            SearchResult(
                key = "app:${app.key}",
                tag = "app",
                title = app.label,
                detail = app.packageName,
                score = score + boost,
                completion = app.label,
                action = { host ->
                    host.clearQuery()
                    catalog.launch(app, host)
                },
                // Hold enter: favourites, rename, info, uninstall.
                menu = menu.items(app),
            )
        }
    }

    private companion object {
        const val MAX_USAGE_BOOST = 150
    }
}

/** Fuzzy-matches apps by label, best first. A blank query returns the first [limit] apps. */
internal fun List<AppEntry>.matching(query: String, limit: Int = 8): List<Pair<AppEntry, Int>> {
    if (query.isBlank()) return take(limit).map { it to Score.DEFAULT }
    return mapNotNull { app -> Fuzzy.score(query, app.label).takeIf { it > 0 }?.let { app to it } }
        .sortedByDescending { it.second }
        .take(limit)
}

/** App shortcuts ("New message", "Scan QR", ...). Only readable while we are the default home app. */
internal class ShortcutIndex(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    @Volatile private var cache: List<ShortcutInfo>? = null

    init {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate()
                override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate()
                override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate()
                override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = invalidate()
                override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = invalidate()
                override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = invalidate()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun invalidate() {
        cache = null
    }

    suspend fun all(): List<ShortcutInfo> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) { load() } ?: return emptyList()
        cache = loaded
        return loaded
    }

    /** Returns null (and caches nothing) while we lack access, so it's retried once we're home. */
    private fun load(): List<ShortcutInfo>? {
        if (!launcherApps.hasShortcutHostPermission()) return null
        val query = ShortcutQuery().setQueryFlags(
            ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_PINNED,
        )
        return launcherApps.profiles.flatMap { user ->
            try {
                launcherApps.getShortcuts(query, user).orEmpty()
            } catch (e: IllegalStateException) {
                emptyList() // profile locked
            } catch (e: SecurityException) {
                emptyList()
            }
        }.filter { it.isEnabled }
    }

    fun start(shortcut: ShortcutInfo, options: Bundle?) = launcherApps.startShortcut(shortcut, null, options)
}

internal class ShortcutSearchProvider(
    private val index: ShortcutIndex,
    private val catalog: LauncherAppsCatalog,
    private val settings: SettingsStore,
) : SearchProvider {
    override val id = "shortcuts"

    override suspend fun search(query: Query): List<SearchResult> {
        if (query.text.length < MIN_QUERY_LENGTH || !settings[AppsSettings.shortcuts]) return emptyList()
        val appLabels = catalog.apps.value.associate { it.packageName to it.label }
        return index.all().mapNotNull { shortcut ->
            val label = shortcut.shortLabel?.toString() ?: return@mapNotNull null
            val appLabel = appLabels[shortcut.`package`] ?: shortcut.`package`
            val score = max(Fuzzy.score(query.text, label), Fuzzy.score(query.text, "$appLabel $label"))
            if (score == 0) return@mapNotNull null
            SearchResult(
                key = "sc:${shortcut.`package`}/${shortcut.id}",
                tag = "sc",
                title = label,
                detail = appLabel,
                score = score - 50, // below the app itself
                action = { host ->
                    host.clearQuery()
                    try {
                        index.start(shortcut, host.launchOptions)
                    } catch (e: ActivityNotFoundException) {
                        host.console.println("error: shortcut is gone")
                    } catch (e: IllegalStateException) {
                        host.console.println("error: shortcut unavailable")
                    } catch (e: SecurityException) {
                        host.console.println("error: shortcut unavailable")
                    }
                },
            )
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
    }
}
