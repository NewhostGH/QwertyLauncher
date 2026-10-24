package com.newhost.qwertylauncher.feature.battery

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/** The battery: level, charging and temperature. */
class BatteryModule : LauncherModule {
    override val id = "battery"
    override val title = "battery"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*BatterySettings.all)
        scope.widget(BatteryWidget(scope.appContext, scope.settings, scope.services))
    }
}
