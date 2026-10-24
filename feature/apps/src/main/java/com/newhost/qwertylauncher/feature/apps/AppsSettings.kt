package com.newhost.qwertylauncher.feature.apps

import com.newhost.qwertylauncher.core.ToggleSetting

internal object AppsSettings {
    val shortcuts = ToggleSetting("search_shortcuts", "app shortcuts in search results", default = true)
    val usageRanking = ToggleSetting("usage_ranking", "rank apps you open often higher", default = true)

    val all = arrayOf(shortcuts, usageRanking)
}
