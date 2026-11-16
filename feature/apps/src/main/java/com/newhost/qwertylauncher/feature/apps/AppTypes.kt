package com.newhost.qwertylauncher.feature.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import com.newhost.qwertylauncher.core.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The drawer's sections, in the order they're shown. [id] is what `drawer <type>` takes. */
internal enum class AppType(val id: String, val title: String) {
    CALLS("calls", "calls and messages"),
    SOCIAL("social", "social"),
    WEB("web", "web"),
    PHOTOS("photos", "photos and camera"),
    MUSIC("music", "music and audio"),
    VIDEO("video", "video"),
    GAMES("games", "games"),
    NEWS("news", "news and reading"),
    MAPS("maps", "maps and travel"),
    WORK("work", "work and productivity"),
    TOOLS("tools", "tools"),
    SYSTEM("system", "system and stores"),
    OTHER("other", "other"),
    ;

    companion object {
        fun byId(id: String): AppType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Works out what kind of app each one is, first by what it does (it answers
 * the dialer's intent, it's a browser, a camera...), then by the category its
 * developer declared, then whether it came with the phone. The user can move
 * an app to another type; that wins over all of it.
 */
internal class AppTypes(private val context: Context, private val catalog: LauncherAppsCatalog) {
    private val prefs = context.getSharedPreferences("app_types", Context.MODE_PRIVATE)

    private val _chosen = MutableStateFlow(loadChosen())

    /** Types the user picked, by [AppEntry.key]. */
    val chosen: StateFlow<Map<String, AppType>> = _chosen.asStateFlow()

    fun choose(app: AppEntry, type: AppType) {
        prefs.edit().putString(app.key, type.id).apply()
        _chosen.value = _chosen.value + (app.key to type)
    }

    fun forget(app: AppEntry) {
        prefs.edit().remove(app.key).apply()
        _chosen.value = _chosen.value - app.key
    }

    private fun loadChosen(): Map<String, AppType> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { AppType.byId(it) }?.let { key to it } }.toMap()

    /**
     * Each app's own type, by [AppEntry.key], before the user's choices.
     * Asks the package manager about every role once; blocking, so call it
     * off the main thread.
     */
    fun guess(apps: List<AppEntry>): Map<String, AppType> {
        // The catalog hands out a new list on every change, so the same list means nothing changed.
        guessed?.let { (forApps, types) -> if (forApps === apps) return types }
        val roles = roles()
        val types = apps.associate { app ->
            val info = catalog.appInfo(app)
            app.key to classify(
                role = roles[app.packageName],
                category = info?.category ?: ApplicationInfo.CATEGORY_UNDEFINED,
                game = info != null && (info.flags and FLAG_IS_GAME) != 0,
                system = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        guessed = apps to types
        return types
    }

    @Volatile private var guessed: Pair<List<AppEntry>, Map<String, AppType>>? = null

    /**
     * Packages that fill a role, with its type; a package with several gets
     * the first in [ROLES]. Every launchable app is already visible to us
     * (the manifest queries launcher activities), so no more queries are needed.
     */
    private fun roles(): Map<String, AppType> {
        val pm = context.packageManager
        val found = HashMap<String, AppType>()
        for ((intent, type) in ROLES) {
            val flags = if (intent.action == Intent.ACTION_MAIN) 0 else PackageManager.MATCH_DEFAULT_ONLY
            val matches = try {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } catch (e: RuntimeException) {
                emptyList() // the package manager went away mid-query; the next reload tries again
            }
            for (match in matches) found.putIfAbsent(match.activityInfo.packageName, type)
        }
        return found
    }

    companion object {
        /** ApplicationInfo.FLAG_IS_GAME: deprecated for [ApplicationInfo.CATEGORY_GAME], but older games only set this. */
        private const val FLAG_IS_GAME = 1 shl 25

        private fun main(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

        /** What an app does, most telling first. Lazy, so [classify] runs in unit tests without Android. */
        private val ROLES: List<Pair<Intent, AppType>> by lazy {
            listOf(
                Intent(Intent.ACTION_DIAL) to AppType.CALLS,
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to AppType.CALLS,
                main(Intent.CATEGORY_APP_MESSAGING) to AppType.CALLS,
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) to AppType.CALLS,
                main(Intent.CATEGORY_APP_EMAIL) to AppType.CALLS,
                main(Intent.CATEGORY_APP_CONTACTS) to AppType.CALLS,
                main(Intent.CATEGORY_APP_BROWSER) to AppType.WEB,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE) to AppType.WEB,
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to AppType.PHOTOS,
                main(Intent.CATEGORY_APP_GALLERY) to AppType.PHOTOS,
                main(Intent.CATEGORY_APP_MUSIC) to AppType.MUSIC,
                main(Intent.CATEGORY_APP_MAPS) to AppType.MAPS,
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")) to AppType.MAPS,
                main(Intent.CATEGORY_APP_CALENDAR) to AppType.WORK,
                main(Intent.CATEGORY_APP_CALCULATOR) to AppType.TOOLS,
                main(Intent.CATEGORY_APP_FILES) to AppType.TOOLS,
                main(Intent.CATEGORY_APP_WEATHER) to AppType.TOOLS,
                main(Intent.CATEGORY_APP_FITNESS) to AppType.TOOLS,
                Intent(AlarmClock.ACTION_SHOW_ALARMS) to AppType.TOOLS,
                main(Intent.CATEGORY_APP_MARKET) to AppType.SYSTEM,
            )
        }

        /** An app's type from what it does ([role], if any), its declared [category], and its flags. */
        fun classify(role: AppType?, category: Int, game: Boolean, system: Boolean): AppType =
            role ?: fromCategory(category) ?: when {
                game -> AppType.GAMES
                system -> AppType.SYSTEM
                else -> AppType.OTHER
            }

        private fun fromCategory(category: Int): AppType? = when (category) {
            ApplicationInfo.CATEGORY_GAME -> AppType.GAMES
            ApplicationInfo.CATEGORY_AUDIO -> AppType.MUSIC
            ApplicationInfo.CATEGORY_VIDEO -> AppType.VIDEO
            ApplicationInfo.CATEGORY_IMAGE -> AppType.PHOTOS
            ApplicationInfo.CATEGORY_SOCIAL -> AppType.SOCIAL
            ApplicationInfo.CATEGORY_NEWS -> AppType.NEWS
            ApplicationInfo.CATEGORY_MAPS -> AppType.MAPS
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppType.WORK
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppType.TOOLS
            else -> null
        }
    }
}
