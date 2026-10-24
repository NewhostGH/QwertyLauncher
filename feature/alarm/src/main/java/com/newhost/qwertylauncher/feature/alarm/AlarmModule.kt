package com.newhost.qwertylauncher.feature.alarm

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/** The next alarm tile: an ASCII alarm clock, when it rings, and how long until then. */
class AlarmModule : LauncherModule {
    override val id = "alarm"
    override val title = "next alarm"

    override fun install(scope: ModuleScope) {
        scope.widget(NextAlarmWidget(scope.appContext))
    }
}
