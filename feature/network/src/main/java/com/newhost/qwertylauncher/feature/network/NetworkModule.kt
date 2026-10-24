package com.newhost.qwertylauncher.feature.network

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/** The network tile: connection type, signal bars, and whether the internet is reachable. */
class NetworkModule : LauncherModule {
    override val id = "network"

    override fun install(scope: ModuleScope) {
        scope.widget(NetWidget(scope.appContext))
    }
}
