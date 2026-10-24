package com.newhost.qwertylauncher.feature.music

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.Align
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.GridBuilder
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Responsive
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.control
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.meter
import com.newhost.qwertylauncher.core.responsive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * The music tile: the cover drawn as art beside the song, the artist and
 * album, an art progress bar with the time, and the controls (previous, back
 * ten seconds, play or pause, forward ten seconds, next, stop), which the
 * home cursor steps through. Follows whichever app is playing, else the most
 * recent one. Tapping the tile opens that app.
 */
internal class MusicWidget(
    private val context: Context,
    private val settings: SettingsStore,
    private val musicApp: ChoiceSetting,
    private val apps: List<MusicApp>,
    private val log: DebugLog,
) : HomeWidget {
    override val id = "music"
    override val order = 25
    override val size = TileSize.WIDE

    /** Room for the song and a row of controls. */
    override val minColumns = 20
    override val minLines = 4

    /** The session shown, for [onClick]. */
    @Volatile private var current: MediaController? = null

    /** Whether media sessions could be read: without notification access they can't. */
    @Volatile private var hasAccess = true

    override fun render(): Flow<WidgetContent> = callbackFlow {
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val listener = listenerComponent()
        if (listener == null) {
            trySend(message("No notification listener found."))
            awaitClose { }
            return@callbackFlow
        }
        val main = Handler(Looper.getMainLooper())
        var controller: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(content(controller))
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                trySend(content(controller))
            }

            override fun onSessionDestroyed() {
                controller?.unregisterCallback(this)
                controller = null
                follow(runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()))
            }

            /**
             * Watches the best of [list]: one that's playing, else the most
             * recent. Many players make their session inactive on pause or
             * stop, dropping it from the list: it's kept until it's destroyed,
             * so its controls stay to play again.
             */
            fun follow(list: List<MediaController>) {
                val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: controller
                if (best?.sessionToken != controller?.sessionToken) {
                    controller?.unregisterCallback(this)
                    controller = best
                    best?.registerCallback(this, main)
                }
                current = controller
                trySend(content(controller))
            }
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> callback.follow(list.orEmpty()) }
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listener, main)
            hasAccess = true
            callback.follow(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            hasAccess = false
            // No notification access: media sessions are shared with the notification listener only.
            log.d("media sessions need notification access")
            trySend(message("Tap to allow notification access, to see what's playing."))
        }
        // The position moves on its own while playing.
        val ticker = launch {
            while (true) {
                delay(TICK_MS)
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) trySend(content(controller))
            }
        }
        awaitClose {
            ticker.cancel()
            runCatching { sessions.removeOnActiveSessionsChangedListener(changed) }
            controller?.unregisterCallback(callback)
            current = null
        }
    }

    /**
     * Selecting the tile (its title line) or tapping it: opens the app that's
     * playing, at its now-playing screen; with nothing playing, the chosen
     * music app, asking which (and keeping the answer) the first time.
     * Without notification access, its setting.
     */
    override fun onClick(host: LauncherHost) {
        val controller = current
        when {
            !hasAccess -> host.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            controller != null -> if (!openNowPlaying(controller, host)) open(controller.packageName, host)
            else -> openChosen(host)
        }
    }

    private fun openChosen(host: LauncherHost) {
        val chosen = settings[musicApp]
        if (chosen != MusicApps.ASK && open(chosen, host)) return
        if (apps.isEmpty()) {
            host.console.println("No music app found.")
            return
        }
        host.showMenu(
            "play music with",
            apps.map { app ->
                MenuItem(app.label.lowercase()) { menuHost ->
                    // Kept: next time this app opens straight away. Reset it in config, under music.
                    settings.set(musicApp, app.packageName)
                    open(app.packageName, menuHost)
                }
            },
        )
    }

    /**
     * The playing app's own "now playing" screen. Sent as the foreground app
     * allowing it to start (Android 14 on blocks it otherwise, silently), and
     * with no animation, like every app the launcher opens.
     */
    private fun openNowPlaying(controller: MediaController, host: LauncherHost): Boolean {
        val activity = controller.sessionActivity ?: return false
        @Suppress("DEPRECATION") // replaced by ALLOW_IF_VISIBLE on API 36; ALLOWED still works
        val options = ActivityOptions.makeCustomAnimation(host.context, 0, 0)
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        return runCatching { activity.send(host.context, 0, null, null, null, null, options) }.isSuccess
    }

    /** Opens [pkg]'s main screen; false if it can't be opened (it was uninstalled). */
    private fun open(pkg: String, host: LauncherHost): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return host.startActivitySafely(intent)
    }

    /** This app's notification listener, which media sessions are shared with; found, not named. */
    private fun listenerComponent(): ComponentName? =
        context.packageManager
            .queryIntentServices(Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
            .firstOrNull()
            ?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }

    private fun message(text: String): WidgetContent {
        val dim = SpannableStringBuilder().apply { append(text, ForegroundColorSpan(context.getColor(CoreR.color.tty_dim)), SPAN) }
        return WidgetContent(dim, layout = layout(NOTE, "music:note", dim, null, null, emptyList()))
    }

    private fun content(controller: MediaController?): WidgetContent {
        if (controller == null) {
            // Says what a tap does: open the chosen app, or ask for one.
            val chosen = apps.firstOrNull { it.packageName == settings[musicApp] }
            return message(if (chosen != null) "Nothing playing. Tap to open ${chosen.label}." else "Nothing playing. Tap to choose a music app.")
        }
        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appName(controller.packageName)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val picture: () -> Drawable = if (cover != null) ({ BitmapDrawable(context.resources, cover) }) else NOTE
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = position(state)
        val details = SpannableStringBuilder().apply {
            listOfNotNull(artist, album).filter { it.isNotBlank() }.forEachIndexed { i, part ->
                if (i > 0) append("  ")
                append(part, ForegroundColorSpan(context.getColor(if (i == 0) CoreR.color.tty_cyan else CoreR.color.tty_dim)), SPAN)
            }
        }
        val progress = if (duration > 0) {
            meter(
                "music:progress",
                "${time(position)} ${time(duration)}",
                position.toFloat() / duration,
                colors = listOf(IconColors.CYAN, IconColors.BLUE),
            )
        } else {
            null
        }
        val controls = controls(controller, playing)
        // A new song rolls the cover over.
        val key = "music:cover:${title.hashCode()}:${album.hashCode()}:${cover != null}"
        return WidgetContent(title, layout = layout(picture, key, title, details.takeIf { it.isNotEmpty() }, progress, controls))
    }

    /** Previous, back ten seconds, play or pause, forward ten seconds, next, stop. */
    private fun controls(controller: MediaController, playing: Boolean) = listOf(
        control("music:previous", "previous", PREVIOUS) { controller.transportControls.skipToPrevious() },
        control("music:back", "back ten seconds", BACK) { seek(controller, -SEEK_MS) },
        control(
            "music:play",
            if (playing) "pause" else "play",
            if (playing) PAUSE else PLAY,
        ) { if (playing) controller.transportControls.pause() else controller.transportControls.play() },
        control("music:forward", "forward ten seconds", FORWARD) { seek(controller, SEEK_MS) },
        control("music:next", "next", NEXT) { controller.transportControls.skipToNext() },
        control("music:stop", "stop", STOP) { controller.transportControls.stop() },
    )

    /**
     * Beside the cover when there's width, the controls under the song; above
     * it, stacked, when narrow. The controls always show, every one of them:
     * short on height, the song's lines give way first (details, then the
     * progress, then the title), and the cover before them when narrow.
     */
    private fun layout(
        picture: () -> Drawable,
        key: String,
        title: CharSequence,
        details: CharSequence?,
        progress: Cell?,
        controls: List<Cell>,
    ): Responsive {
        val cover = Cell.Art(key, Size(COVER_COLUMNS, COVER_LINES), drawable = picture)
        // In reading order, each with how much it's wanted (0 most): the title, then the progress, then the details.
        val song = listOfNotNull(
            Cell.Text(title, wrap = false) to 0,
            details?.let { Cell.Text(it, wrap = false) to 2 },
            progress?.let { it to 1 },
        )
        val all = song.size
        /** The [count] most wanted lines, in reading order. */
        fun shown(count: Int) = song.filter { (_, rank) -> song.count { it.second < rank } < count }.map { it.first }
        return responsive {
            // The first variant the room meets is drawn: the fullest first.
            for (count in all downTo 0) {
                for (controlLines in 1..CONTROL_ROWS) {
                    atLeast(columns = WIDE_COVER + controlColumns(controlLines), lines = count + controlLines) {
                        wide(cover, shown(count), controls, controlLines)
                    }
                }
            }
            for (controlLines in 1..CONTROL_ROWS) {
                atLeast(columns = controlColumns(controlLines), lines = COVER_MIN_LINES + all + controlLines) {
                    narrow(cover, shown(all), controls, controlLines)
                }
            }
            for (count in all downTo 0) {
                for (controlLines in 1..CONTROL_ROWS) {
                    atLeast(columns = controlColumns(controlLines), lines = count + controlLines) {
                        narrow(null, shown(count), controls, controlLines)
                    }
                }
            }
            otherwise { narrow(null, emptyList(), controls, CONTROL_ROWS) }
        }
    }

    /** The cover on the left, the song's [lines] and the controls beside it. */
    private fun GridBuilder.wide(cover: Cell, lines: List<Cell>, controls: List<Cell>, controlLines: Int) {
        columns(auto(), fr())
        rows(*(List(lines.size) { auto() } + fr(min = controlLines)).toTypedArray())
        gap(columns = 2)
        add(cover, rowSpan = lines.size + 1, alignX = Align.CENTER, alignY = Align.CENTER)
        lines.forEachIndexed { i, cell -> add(cell, row = i, column = 1) }
        add(controlRow(controls), row = lines.size, column = 1)
    }

    /** Stacked: the cover (if any), the song's [lines], the controls. */
    private fun GridBuilder.narrow(cover: Cell?, lines: List<Cell>, controls: List<Cell>, controlLines: Int) {
        val top = listOfNotNull(cover) + lines
        columns(fr())
        rows(*(List(top.size) { auto() } + fr(min = controlLines)).toTypedArray())
        top.forEachIndexed { i, cell ->
            if (cell === cover) add(cell, row = i, alignX = Align.CENTER, alignY = Align.CENTER) else add(cell, row = i)
        }
        add(controlRow(controls), row = top.size)
    }

    /** Columns the six controls need on [lines] rows: three cells each, a column apart. */
    private fun controlColumns(lines: Int): Int {
        val perRow = (CONTROL_COUNT + lines - 1) / lines
        return perRow * CONTROL_COLUMNS + (perRow - 1)
    }

    /** The controls side by side, a column apart, wrapping to a second row rather than leaving any out. */
    private fun controlRow(controls: List<Cell>) =
        Cell.Flow(controls, CONTROL_COLUMNS, rows = CONTROL_ROWS, gap = 1, maxItemLines = MAX_CONTROL_LINES, pack = true)

    /** Where playback is now: its last report, moved on by the time since while playing. */
    private fun position(state: PlaybackState?): Long {
        state ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (since * state.playbackSpeed).toLong()
    }

    private fun seek(controller: MediaController, delta: Long) {
        val state = controller.playbackState
        val canSeek = state != null && state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        when {
            canSeek -> controller.transportControls.seekTo((position(state) + delta).coerceAtLeast(0L))
            delta < 0 -> controller.transportControls.rewind()
            else -> controller.transportControls.fastForward()
        }
    }

    private fun appName(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** `3:07`, or `1:02:45` past an hour. */
    private fun time(ms: Long): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val TICK_MS = 1000L
        const val SEEK_MS = 10_000L
        const val COVER_MIN_LINES = 2
        const val CONTROL_COUNT = 6
        const val COVER_COLUMNS = 8
        const val COVER_LINES = 4
        /** The cover and the gap beside it, in the wide layout. */
        const val WIDE_COVER = COVER_COLUMNS + 2

        /** A control's cells: a small icon, room around it. */
        const val CONTROL_COLUMNS = 3
        const val MAX_CONTROL_LINES = 2
        const val CONTROL_ROWS = 2
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        val NOTE: () -> Drawable = icon {
            fill(IconColors.VIOLET); circle(30f, 76f, 16f); circle(74f, 66f, 16f)
            rect(40f, 18f, 48f, 76f); rect(84f, 10f, 92f, 66f)
            polygon(40f, 18f, 92f, 6f, 92f, 22f, 40f, 34f)
        }
        val PLAY = icon { fill(IconColors.GREEN); polygon(24f, 12f, 84f, 50f, 24f, 88f) }
        val PAUSE = icon { fill(IconColors.YELLOW); rect(22f, 14f, 42f, 86f); rect(58f, 14f, 78f, 86f) }
        val STOP = icon { fill(IconColors.RED); rect(18f, 18f, 82f, 82f) }
        val NEXT = icon { fill(IconColors.CYAN); polygon(12f, 16f, 60f, 50f, 12f, 84f); rect(66f, 16f, 82f, 84f) }
        val PREVIOUS = icon { fill(IconColors.CYAN); polygon(88f, 16f, 40f, 50f, 88f, 84f); rect(18f, 16f, 34f, 84f) }
        val FORWARD = icon { fill(IconColors.BLUE); polygon(8f, 20f, 48f, 50f, 8f, 80f); polygon(48f, 20f, 88f, 50f, 48f, 80f) }
        val BACK = icon { fill(IconColors.BLUE); polygon(92f, 20f, 52f, 50f, 92f, 80f); polygon(52f, 20f, 12f, 50f, 52f, 80f) }
    }
}
