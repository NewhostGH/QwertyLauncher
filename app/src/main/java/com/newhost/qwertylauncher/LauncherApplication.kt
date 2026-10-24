package com.newhost.qwertylauncher

import android.app.Application
import android.content.ComponentName
import com.newhost.qwertylauncher.core.NotificationHost
import com.newhost.qwertylauncher.debug.LauncherLog
import com.newhost.qwertylauncher.settings.DataStoreSettings
import com.newhost.qwertylauncher.shell.HostNotificationListener
import com.newhost.qwertylauncher.shell.ModuleLoader
import com.newhost.qwertylauncher.shell.ModuleRegistry

class LauncherApplication : Application() {

    // Manual DI root: everything the screens need comes from here.
    lateinit var log: LauncherLog
        private set
    lateinit var settings: DataStoreSettings
        private set
    lateinit var registry: ModuleRegistry
        private set
    lateinit var modules: ModuleLoader
        private set

    override fun onCreate() {
        super.onCreate()
        log = LauncherLog(this).apply { recordCrashes() }
        settings = DataStoreSettings(this)
        NotificationHost.component = ComponentName(this, HostNotificationListener::class.java)
        // Every feature is a module zip: the bundled ones, then any the user imported.
        modules = ModuleLoader(this, log.tagged("modules"))
        registry = ModuleRegistry(this, modules.load(), settings, log, modules)
    }
}
