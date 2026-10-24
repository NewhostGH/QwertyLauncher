package com.newhost.qwertylauncher.feature.apps

import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.service

/** Installed apps: the catalog service, app and shortcut search, rename, info and uninstall. */
class AppsModule : LauncherModule {
    override val id = "apps"
    override val title = "apps"

    override fun install(scope: ModuleScope) {
        val names = AppNames(scope.appContext)
        val catalog = LauncherAppsCatalog(scope.appContext, scope.appScope, names)
        val menu = AppMenu(catalog, scope.services, names)
        // Everywhere an app is shown (the favourites grid too), holding enter offers this menu.
        catalog.appMenu = menu
        val settings = scope.settings
        scope.declareSettings(*AppsSettings.all)

        scope.service<AppCatalog>(catalog)
        scope.searchProvider(AppSearchProvider(catalog, settings, menu))
        scope.searchProvider(ShortcutSearchProvider(ShortcutIndex(scope.appContext), catalog, settings))
        scope.command(RenameCommand(catalog, names))
        scope.command(
            AppActionCommand("uninstall", "remove an app", catalog) { app, host -> AppActions.uninstall(app, host) },
        )
        scope.command(
            AppActionCommand("info", "open an app's system settings", catalog) { app, host ->
                AppActions.details(catalog, app, host)
            },
        )
    }
}
