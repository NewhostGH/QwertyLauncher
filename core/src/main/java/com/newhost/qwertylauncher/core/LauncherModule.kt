package com.newhost.qwertylauncher.core

import android.content.Context
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope

/**
 * A feature plugged into the launcher shell.
 *
 * A module ships as a zip: a `module.json` naming its entry class (this
 * interface, with a no-argument constructor) and the core [ModuleApi]
 * version it was built against, beside its compiled `classes.dex`. It's
 * loaded against the launcher's own core, Kotlin, coroutines and
 * androidx.core, so it brings none of those. It can't bring Android
 * resources or manifest entries either: draw icons with [ShapeIcon], and
 * use only permissions and components the launcher declares.
 *
 * Modules are installed once at process start: the bundled ones in their
 * set order, then the imported ones.
 * They contribute behaviour only through [ModuleScope] and must keep working
 * when any other module is absent: look optional collaborators up in
 * [ModuleScope.services] at use time and degrade gracefully when they are missing.
 */
interface LauncherModule {
    val id: String

    /** Shown as the module's heading in the launcher settings. */
    val title: String get() = id

    fun install(scope: ModuleScope)
}

/** The module contract's version: a zip built for a newer one isn't loaded. Bump it when [LauncherModule]'s world changes incompatibly. */
object ModuleApi {
    const val VERSION = 1
}

interface ModuleScope {
    val appContext: Context

    /** Lives as long as the process; use it for caches and background refreshes. */
    val appScope: CoroutineScope

    val services: Services

    val settings: SettingsStore

    /** This module's channel of the launcher's debug log (the `debug` command shows it). */
    val log: DebugLog

    /**
     * Lists [settings] in the launcher settings under this module. The shell
     * adds an on/off switch for every module by itself: while a module is off,
     * its search providers, commands, widgets, panels and services are
     * hidden (so [Services.find] returns null for them).
     */
    fun declareSettings(vararg settings: Setting<*>)

    fun searchProvider(provider: SearchProvider)
    fun command(command: Command)
    fun widget(widget: HomeWidget)
    fun panel(panel: HomePanel)

    /** Quick buttons in the top bar, beside help and config; see [BarStrip]. */
    fun barStrip(strip: BarStrip)

    /** A character that lives on the home screen, over the tiles; see [Actor]. */
    fun actor(actor: Actor)

    /** Offers [impl] to other modules under [type]. */
    fun <T : Any> service(type: KClass<T>, impl: T)
}

inline fun <reified T : Any> ModuleScope.service(impl: T) = service(T::class, impl)

/** Capabilities modules offer each other, such as [AppCatalog] or [IconRenderer]. */
interface Services {
    fun <T : Any> find(type: KClass<T>): T?
}

inline fun <reified T : Any> Services.find(): T? = find(T::class)

/**
 * The launcher's shared debug log: one place for every module's diagnostics,
 * persisted across restarts and mirrored to logcat. Cheap enough to leave in.
 */
interface DebugLog {
    fun d(message: String)
    fun e(message: String, error: Throwable? = null)
}
