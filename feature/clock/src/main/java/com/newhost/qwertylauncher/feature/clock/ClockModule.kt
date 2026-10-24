package com.newhost.qwertylauncher.feature.clock

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/** The clock and date, and world clocks: `clock` and its pane. */
class ClockModule : LauncherModule {
    override val id = "clock"
    override val title = "clock"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*ClockSettings.all)
        val clocks = WorldClocks(scope.appContext)
        val pane = WorldClockPane(scope.appContext, clocks, scope.settings, scope.services)
        scope.widget(ClockWidget(scope.appContext, scope.services, scope.settings, clocks))
        scope.command(ClockCommand(scope.appContext, clocks, scope.settings, pane))
    }
}
