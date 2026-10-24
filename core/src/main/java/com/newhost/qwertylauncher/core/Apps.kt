package com.newhost.qwertylauncher.core

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A launchable activity, possibly in a work profile. */
data class AppEntry(
    /** Stable across restarts; safe to persist. */
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
) {
    val packageName: String get() = component.packageName
}

/** Installed apps. Offered as a service by the apps module. */
interface AppCatalog {
    /** All launchable apps, sorted by label. */
    val apps: StateFlow<List<AppEntry>>

    fun find(key: String): AppEntry?
    fun launch(app: AppEntry, host: LauncherHost)

    /** Every app started through [launch], as it's started: for modules that learn what's used when. */
    val launches: Flow<AppEntry> get() = emptyFlow()

    /** Loads the app's icon. May block; call it off the main thread. */
    fun loadIcon(app: AppEntry): Drawable?

    /**
     * The icon of any installed package, launchable or not (a notification's
     * sender may be a system part or a service): the one place app icons come
     * from, for every module. Null if there's none; show [placeholderIcon]
     * then. May block; call it off the main thread.
     */
    fun loadIcon(packageName: String): Drawable? =
        apps.value.firstOrNull { it.packageName == packageName }?.let { loadIcon(it) }

    /**
     * What holding enter on [app] offers, wherever it's shown: open, rename,
     * app info, uninstall... [extra] (e.g. a grid's own "move") goes after "open".
     */
    fun menu(app: AppEntry, extra: List<MenuItem> = emptyList()): List<MenuItem> =
        listOf(MenuItem("open") { host -> launch(app, host) }) + extra
}

/** The user's favourite apps, by [AppEntry.key], in order. Offered as a service by the favourites module. Main thread only. */
interface Favorites {
    val keys: StateFlow<List<String>>

    /** The favourites on screen now: the grid's page that shows (none while it isn't on home). */
    val visible: StateFlow<List<String>>

    operator fun contains(key: String): Boolean

    fun add(key: String)

    fun remove(key: String)
}

/**
 * Turns a drawable (an app icon, or any art a module draws itself) into
 * terminal text, coloured with spans. Offered as a service by an icon module.
 */
interface IconRenderer {
    /**
     * Renders [icon] into [rows] lines of [cols] characters. Characters are
     * assumed to be twice as tall as wide; drawables with an intrinsic size
     * keep their aspect ratio.
     */
    suspend fun render(icon: Drawable, cols: Int, rows: Int): CharSequence

    /**
     * With [fine], draws with half blocks instead: each character is two
     * stacked pixels in their own colours (`▀` over its background), which
     * keeps small icons crisp where a character ramp can't show their shape.
     */
    suspend fun render(icon: Drawable, cols: Int, rows: Int, fine: Boolean): CharSequence = render(icon, cols, rows)
}
