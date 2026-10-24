package com.newhost.qwertylauncher.feature.system

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.NumberSetting

/** The system tile (memory, storage, uptime) and a neofetch-style `fetch`. */
class SystemModule : LauncherModule {
    override val id = "system"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(SystemSettings.refreshSeconds)
        val stats = SystemStats(scope.appContext)
        scope.widget(SysWidget(stats, scope.settings))
        scope.command(FetchCommand(scope.appContext, stats, scope.services))
    }
}

internal object SystemSettings {
    val refreshSeconds = NumberSetting(
        "refresh_seconds",
        "refresh system tile every",
        default = 30,
        min = 10,
        max = 120,
        step = 10,
        unit = "s",
        summary = "How often the memory and storage meters update.",
    )
}
