package com.newhost.qwertylauncher.feature.music

import android.content.Context
import android.content.Intent
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice

/** An app that can play music: its package and its name. */
internal class MusicApp(val packageName: String, val label: String)

internal object MusicApps {
    /**
     * Apps that say they play music (the music category, or a media browser
     * service, which players offer to cars and watches), sorted by name. None
     * found, every app that can be launched, so there's always a choice.
     */
    fun find(context: Context): List<MusicApp> {
        val pm = context.packageManager
        val players = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .map { it.activityInfo.packageName } +
            pm.queryIntentServices(Intent(MEDIA_BROWSER), 0).map { it.serviceInfo.packageName }
        val packages = players.distinct().filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .ifEmpty {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != context.packageName }
            }
        return packages
            .map { pkg -> MusicApp(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The "music app" setting: the app a tap on the tile opens when nothing is
     * playing, or [ASK] to be asked (and have the answer kept). Setting it back
     * to [ASK] resets the choice.
     */
    fun setting(apps: List<MusicApp>) = ChoiceSetting(
        "app",
        "music app for the controls",
        listOf(Choice(ASK, "ask")) + apps.map { Choice(it.packageName, it.label.lowercase()) },
        default = ASK,
        summary = "The app a tap on the tile opens when nothing is playing. Set it to ask to choose again next time.",
    )

    const val ASK = "ask"
    private const val MEDIA_BROWSER = "android.media.browse.MediaBrowserService"
}
