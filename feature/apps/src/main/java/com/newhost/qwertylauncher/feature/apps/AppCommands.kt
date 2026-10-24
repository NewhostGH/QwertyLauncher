package com.newhost.qwertylauncher.feature.apps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.SearchResult

internal object AppActions {
    fun uninstall(app: AppEntry, host: LauncherHost) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
        host.startActivitySafely(intent)
    }

    fun details(catalog: LauncherAppsCatalog, app: AppEntry, host: LauncherHost) {
        try {
            catalog.openDetails(app)
        } catch (e: ActivityNotFoundException) {
            host.console.println("error: no settings screen for ${app.label}")
        } catch (e: SecurityException) {
            host.console.println("error: no settings screen for ${app.label}")
        }
    }
}

/** `<name> <app>`: runs [act] on the chosen app. Used for `uninstall` and `info`. */
internal class AppActionCommand(
    override val name: String,
    override val summary: String,
    private val catalog: LauncherAppsCatalog,
    private val act: (AppEntry, LauncherHost) -> Unit,
) : Command {
    override val usage = "$name <app>"

    override suspend fun suggest(args: String): List<SearchResult> =
        catalog.apps.value.matching(args).map { (app, score) ->
            SearchResult(
                key = "$name:${app.key}",
                tag = name.take(4),
                title = "$name ${app.label}",
                detail = app.packageName,
                score = score,
                completion = "$name ${app.label}",
                action = { host ->
                    host.clearQuery()
                    act(app, host)
                },
            )
        }
}
