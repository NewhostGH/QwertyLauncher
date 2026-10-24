package com.newhost.qwertylauncher.feature.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.TrimmedDrawable
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [AppCatalog] backed by [LauncherApps], so work-profile apps are included.
 * Reloads whenever a package is installed, removed or changed.
 */
internal class LauncherAppsCatalog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val names: AppNames,
) : AppCatalog {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val usage = context.getSharedPreferences("apps_usage", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    override val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    @Volatile private var activities: Map<String, LauncherActivityInfo> = emptyMap()
    @Volatile private var byKey: Map<String, AppEntry> = emptyMap()
    private var refreshJob: Job? = null

    init {
        launcherApps.registerCallback(PackageCallback(), Handler(Looper.getMainLooper()))
        refresh()
    }

    /** Main thread only. */
    /** Loads the app list again, e.g. after an app was renamed. Main thread only. */
    fun reload() = refresh()

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            activities = loaded.associate { (entry, info) -> entry.key to info }
            byKey = loaded.associate { (entry, _) -> entry.key to entry }
            _apps.value = loaded.map { it.first }.sortedBy { it.label.lowercase() }
        }
    }

    private fun load(): List<Pair<AppEntry, LauncherActivityInfo>> =
        launcherApps.profiles.flatMap { user ->
            // The serial number, unlike the user id, is stable across reboots.
            val serial = userManager.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map { info ->
                val key = "${info.componentName.flattenToShortString()}#$serial"
                val entry = AppEntry(
                    key = key,
                    component = info.componentName,
                    user = user,
                    // A name the user gave the app wins over the app's own label.
                    label = names[key] ?: info.label.toString(),
                )
                entry to info
            }
        }.filter { (entry, _) -> entry.packageName != context.packageName }

    override fun find(key: String): AppEntry? = byKey[key]

    /** Set by the module once the menu exists (it needs this catalog). */
    lateinit var appMenu: AppMenu

    override fun menu(app: AppEntry, extra: List<MenuItem>): List<MenuItem> = appMenu.items(app, extra)

    // Launches, for whoever listens (suggestions learn from them); a few may queue up.
    private val launched = kotlinx.coroutines.flow.MutableSharedFlow<AppEntry>(extraBufferCapacity = 8)
    override val launches: kotlinx.coroutines.flow.Flow<AppEntry> get() = launched

    override fun launch(app: AppEntry, host: LauncherHost) {
        try {
            launcherApps.startMainActivity(app.component, app.user, null, host.launchOptions)
            usage.edit().putInt(app.key, launchCount(app) + 1).apply()
            launched.tryEmit(app)
        } catch (e: ActivityNotFoundException) {
            host.console.println("error: ${app.label} is no longer installed")
        } catch (e: SecurityException) {
            host.console.println("error: not allowed to start ${app.label}")
        }
    }

    override fun loadIcon(app: AppEntry): Drawable? = activities[app.key]?.getIcon(0)?.let(::evened)

    /** A launchable app's own icon first; else the package manager's, which knows every package. */
    override fun loadIcon(packageName: String): Drawable? {
        val launchable = apps.value.firstOrNull { it.packageName == packageName }?.let { runCatching { loadIcon(it) }.getOrNull() }
        if (launchable != null) return launchable
        return try {
            evened(context.packageManager.getApplicationIcon(packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Every icon the same size: an adaptive icon already fills its shape (and
     * is drawn best from its layers), but an older one's transparent margin
     * would leave it smaller; that's trimmed off.
     */
    private fun evened(icon: Drawable): Drawable = if (icon is AdaptiveIconDrawable) icon else TrimmedDrawable(icon)

    fun launchCount(app: AppEntry): Int = usage.getInt(app.key, 0)

    fun openDetails(app: AppEntry) = launcherApps.startAppDetailsActivity(app.component, app.user, null, null)

    private inner class PackageCallback : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = refresh()
    }
}
