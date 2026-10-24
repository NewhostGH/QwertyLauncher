package com.newhost.qwertylauncher

import android.app.Application
import android.content.ComponentName
import com.newhost.qwertylauncher.core.NotificationHost
import com.newhost.qwertylauncher.debug.LauncherLog
import com.newhost.qwertylauncher.settings.DataStoreSettings
import com.newhost.qwertylauncher.shell.CrashGuard
import com.newhost.qwertylauncher.shell.HostNotificationListener
import com.newhost.qwertylauncher.shell.ModuleLoader
import com.newhost.qwertylauncher.shell.ModuleRegistry
import java.io.File

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
        log = LauncherLog(this)
        // Every feature is a module zip: the bundled ones, then any the user imported. A module
        // that keeps crashing the launcher is held back (CrashGuard), so home always opens.
        val guard = CrashGuard(File(filesDir, "crashguard"))
        modules = ModuleLoader(this, log.tagged("modules"), guard)
        log.recordCrashes { error -> modules.blame(error).also { guard.record(it) } }
        settings = DataStoreSettings(this)
        NotificationHost.component = ComponentName(this, HostNotificationListener::class.java)
        registry = ModuleRegistry(this, modules.load(), settings, log, modules)
    }
}
