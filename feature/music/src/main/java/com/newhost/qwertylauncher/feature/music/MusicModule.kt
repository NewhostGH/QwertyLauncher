package com.newhost.qwertylauncher.feature.music

import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope

/**
 * What's playing, from any music app: the cover drawn as art, the song, its
 * progress, and the controls, which the home cursor steps through. Reads
 * media sessions, which Android shares with the launcher's notification
 * listener, so it needs notification access like the notifications module.
 */
class MusicModule : LauncherModule {
    override val id = "music"

    override fun install(scope: ModuleScope) {
        // The music apps installed now: the choices for the tile's app (see MusicApps.setting).
        val apps = MusicApps.find(scope.appContext)
        val app = MusicApps.setting(apps)
        scope.declareSettings(app)
        scope.widget(MusicWidget(scope.appContext, scope.settings, app, apps, scope.log))
    }
}
