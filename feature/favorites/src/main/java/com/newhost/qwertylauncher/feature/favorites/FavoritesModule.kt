package com.newhost.qwertylauncher.feature.favorites

import com.newhost.qwertylauncher.core.Favorites
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.service

/** The favourites grid and the `fav` command. Apps come from the apps module. */
class FavoritesModule : LauncherModule {
    override val id = "favorites"
    override val title = "favourites"

    override fun install(scope: ModuleScope) {
        val favorites = FavoritesStore(scope.appContext)
        scope.service<Favorites>(favorites)
        scope.command(FavCommand(scope.services, favorites))
        scope.panel(favoritesPanel(favorites, scope.services))
    }
}
