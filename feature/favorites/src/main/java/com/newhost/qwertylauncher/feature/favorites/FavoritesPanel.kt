package com.newhost.qwertylauncher.feature.favorites

import com.newhost.qwertylauncher.core.AppGridPanel
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Services
import kotlinx.coroutines.flow.combine

/**
 * The favourites: the shared app grid (it fits itself to its tile) of the
 * apps you picked, in your order; holding enter on one can also move it along.
 */
internal fun favoritesPanel(favorites: FavoritesStore, services: Services) = AppGridPanel(
    id = "favorites",
    title = "favourites",
    // Near the bottom by default, like a dock; it can be moved like any tile.
    order = 90,
    services = services,
    apps = { catalog -> combine(favorites.keys, catalog.apps) { keys, _ -> keys.mapNotNull(catalog::find) } },
    menu = { app ->
        listOf(
            MenuItem("move left") { favorites.move(app.key, -1) },
            MenuItem("move right") { favorites.move(app.key, +1) },
        )
    },
    emptyText = "No favourites yet. Add one with: fav add <app>",
    // Which are on screen, so suggestions don't offer them again.
    onShown = { apps -> favorites.showing(apps.map { it.key }) },
)
