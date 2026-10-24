package com.newhost.qwertylauncher.feature.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.Extra
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ShapeIcon
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.placeholderIcon
import com.newhost.qwertylauncher.core.tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import com.newhost.qwertylauncher.core.NotificationHost
import com.newhost.qwertylauncher.core.R as CoreR

/** Notification count on the home screen, and a full-screen `notif` pane to open and dismiss them. */
class NotificationsModule : LauncherModule {
    override val id = "notifications"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*NotificationsSettings.all)
        val pane = NotificationsPane(scope.appContext, scope.settings, scope.services, scope.log)
        scope.widget(NotificationWidget(scope.appContext, scope.settings, scope.services, pane))
        scope.command(NotifCommand(pane))
        NotificationState.log = scope.log
        NotificationHost.listen(NotificationReader)
        scope.log.d("notification access ${if (NotificationState.hasAccess(scope.appContext)) "granted" else "not granted"}")
    }
}

internal object NotificationsSettings {
    val countOngoing = ToggleSetting(
        "count_ongoing",
        "count ongoing notifications",
        default = false,
        summary = "music players, downloads, running services…",
    )
    val showApps = ToggleSetting("show_apps", "notification tile shows which apps", default = true)

    val all = arrayOf(countOngoing, showApps)
}

private fun List<NotificationItem>.counted(settings: SettingsStore) =
    if (settings[NotificationsSettings.countOngoing]) this else filterNot { it.ongoing }

/** Opens notification access for this app, falling back to the list of all listeners. */
private fun openAccessSettings(context: Context, host: LauncherHost) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationState.component(context).flattenToString())
    if (!host.startActivitySafely(detail)) host.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

/** An ASCII bell (amber when there's something new), a red count bubble, and which apps. */
internal class NotificationWidget(
    private val context: Context,
    private val settings: SettingsStore,
    private val services: Services,
    private val pane: NotificationsPane,
) : HomeWidget {
    override val id = "notifications"
    override val order = 5 // right below the clock

    /** What the widget shows, before it's laid out. */
    private class Shown(val headline: CharSequence, val items: List<NotificationItem>, val active: Boolean, val count: Int, val showApps: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun render(): Flow<WidgetContent> {
        var lastCount = -1
        return combine(
            NotificationState.items,
            NotificationState.connected,
            settings.observe(NotificationsSettings.countOngoing),
            settings.observe(NotificationsSettings.showApps),
        ) { items, connected, _, showApps ->
            if (!connected) {
                val hint = if (NotificationState.hasAccess(context)) "connecting…" else "tap to allow notification access"
                Shown(dim(hint), emptyList(), active = false, count = -1, showApps = showApps)
            } else {
                val counted = items.counted(settings)
                // The count is drawn on the bell; nothing needs saying beside it.
                Shown("", counted, active = counted.isNotEmpty(), count = counted.size, showApps = showApps)
            }
        }.transformLatest { shown ->
            // Something new came in: the bell rings, swinging from its top, then settles.
            val ring = lastCount >= 0 && shown.count > lastCount
            lastCount = shown.count
            val animator = services.find<ArtAnimator>()
            if (ring && animator != null) {
                animator.frames(RING_FRAME_MS).take(RING_ANGLES.size).collect { frame -> emit(content(shown, frame + 1)) }
            }
            emit(content(shown, 0))
        }
    }

    override fun onClick(host: LauncherHost) = host.showPane(pane)

    /**
     * The bell (swung by ring [frame], 0 for still) beside the count, each app's
     * count with its own icon, and on a big tile the latest notifications.
     */
    private fun content(shown: Shown, frame: Int): WidgetContent {
        val angle = if (frame == 0) 0f else RING_ANGLES[(frame - 1).coerceIn(0, RING_ANGLES.lastIndex)]
        val active = shown.active
        val count = shown.count.coerceAtLeast(0)
        val bell = Cell.Art("bell:$active", Size(BELL_COLUMNS, BELL_LINES), frame = frame) { bellDrawable(active, angle) }
        val facts = if (shown.showApps) apps(shown.items) else emptyList()
        val latest = shown.items.sortedByDescending { it.postTime }.take(LATEST).map { item ->
            val line = listOf(item.appLabel.lowercase(), item.title, item.text).filter { it.isNotBlank() }.joinToString("  ")
            Cell.Text(line, wrap = false)
        }
        val extras = if (latest.isEmpty()) emptyList() else listOf(Extra(Cell.Flow(latest, LATEST_COLUMNS, rows = LATEST), LATEST_LINES))
        return WidgetContent(shown.headline, layout = tile(bell, shown.headline, facts, extras = extras, badge = badge(count)))
    }

    /** Each app's count, most first, with the app's own icon drawn in half blocks. */
    private fun apps(items: List<NotificationItem>): List<Cell> {
        val catalog = services.find<AppCatalog>()
        return items.groupBy { it.packageName }.entries
            .sortedByDescending { it.value.size }
            .map { (pkg, list) ->
                val label = list.first().appLabel.lowercase()
                val placeholder = placeholderIcon(label)
                // The app's icon if it can be found at all, else a placeholder with its initial.
                fact("app:$pkg", "$label ${list.size}") { catalog?.loadIcon(pkg) ?: placeholder() }
            }
    }

    private fun dim(text: String): CharSequence = SpannableStringBuilder().apply {
        append(text, ForegroundColorSpan(context.getColor(CoreR.color.tty_dim)), SPAN)
    }

    /**
     * The count on the bell's shoulder: real terminal text, bold black on red,
     * so it's sharp at any size (drawn into the art, it blurred). None at 0.
     */
    private fun badge(count: Int): CharSequence? {
        if (count <= 0) return null
        val label = if (count > MAX_SHOWN) "$MAX_SHOWN+" else count.toString()
        return SpannableStringBuilder(" $label ").apply {
            setSpan(BackgroundColorSpan(context.getColor(CoreR.color.tty_red)), 0, length, SPAN)
            setSpan(ForegroundColorSpan(Color.BLACK), 0, length, SPAN)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, SPAN)
        }
    }

    /** The bell, amber when there's something new, swung [angle] degrees about its top. */
    private fun bellDrawable(active: Boolean, angle: Float): Drawable {
        val color = context.getColor(if (active) CoreR.color.tty_accent else CoreR.color.tty_dim)
        return ShapeIcon {
            turned(angle, BELL_CENTER_X, BELL_TOP) {
                fill(color)
                path(BELL, BELL_LEFT.toFloat(), BELL_TOP, BELL_SIZE.toFloat())
            }
        }
    }

    private companion object {
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Material's "notifications" bell. */
        const val BELL = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4" +
            "c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"

        // 16 × 8 characters at the default 2.6sp art size is about 25 × 24sp.
        /** The bell's swing when it rings, one angle a frame: wide, then narrowing to a stop. */
        val RING_ANGLES = listOf(0f, -22f, 20f, -16f, 14f, -9f, 7f, -3f, 0f)
        const val RING_FRAME_MS = 90L
        // The bell sits a little left and low, leaving the top right for the count.
        const val BELL_LEFT = 2
        const val BELL_SIZE = 80
        const val BELL_TOP = 16f
        const val BELL_CENTER_X = 42f
        const val MAX_SHOWN = 99

        // The bell's own size, in cells; the tile scales it.
        const val BELL_COLUMNS = 6
        const val BELL_LINES = 3

        // The latest notifications, on a big tile.
        const val LATEST = 6
        const val LATEST_COLUMNS = 30
        const val LATEST_LINES = 3
    }
}

/** `notif`: opens the notifications pane. */
internal class NotifCommand(private val pane: NotificationsPane) : Command {
    override val name = "notif"
    override val aliases = listOf("notifications")
    override val usage = "notif"
    override val summary = "read, answer, open and dismiss notifications"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "notif",
            tag = "ntf",
            title = "notif",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.showPane(pane)
            },
        ),
    )
}

/**
 * Notifications full screen, grouped by app, newest first:
 * ```
 * ── whatsapp (2) ──────────
 * > mom  see you at 8
 *   dad  call me
 * ```
 * Enter opens one, d/x/Del dismisses it, c clears all.
 */
internal class NotificationsPane(
    private val context: Context,
    private val settings: SettingsStore,
    private val services: Services,
    private val log: DebugLog,
) : ListPane {
    override val title = "notif"
    override val keysHelp = "⏎ open  o in its app  d dismiss  c clear all  q back"

    /** Apps opened to show their notifications, by package; the rest show just their count. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    /** Apps' icons, drawn once. */
    private val icons = java.util.concurrent.ConcurrentHashMap<String, CharSequence>()

    override fun rows(): Flow<List<PaneRow>> = combine(
        NotificationState.items,
        NotificationState.connected,
        settings.observe(NotificationsSettings.countOngoing),
        expanded,
    ) { items, connected, _, open ->
        when {
            !NotificationState.hasAccess(context) -> single(
                PaneRow.Item(ACCESS, "allow notification access", status = "needed to count and list notifications\n⏎ open the setting"),
            )
            !connected -> single(PaneRow.Item(WAITING, "connecting…"))
            else -> {
                val shown = items.counted(settings)
                if (shown.isEmpty()) {
                    single(PaneRow.Item(NONE, "no notifications", detail = "all clear"))
                } else {
                    // One line an app, with its icon and count; opened, its notifications under it.
                    // groupBy keeps first-seen order, and items are newest first.
                    listOf(PaneRow.Header("notifications (${shown.size})")) +
                        shown.groupBy { it.packageName }.flatMap { (pkg, group) ->
                            val isOpen = pkg in open
                            listOf(appRow(pkg, group, isOpen)) + if (isOpen) group.map(::row) else emptyList()
                        }
                }
            }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean = when (item.id) {
        ACCESS -> {
            openAccessSettings(context, host)
            false
        }
        WAITING, NONE -> false
        else -> if (item.id.startsWith(APP)) {
            // Opens or closes the app's list.
            val pkg = item.id.removePrefix(APP)
            expanded.update { if (pkg in it) it - pkg else it + pkg }
            false
        } else {
            // All of it, its buttons, and a line to reply in.
            find(item.id)?.let { host.showPane(ThreadPane(context, it.key, it, this)) }
            false
        }
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
            val id = item?.id
            if (id != null && id.startsWith(APP)) {
                // On an app: all of its notifications that can be dismissed.
                val pkg = id.removePrefix(APP)
                NotificationState.items.value.filter { it.packageName == pkg && it.clearable }.forEach { NotificationState.dismiss(it.key) }
            } else {
                val notification = id?.let { find(it) }
                if (notification?.clearable == true) NotificationState.dismiss(notification.key)
            }
            true
        }
        KeyEvent.KEYCODE_C -> {
            NotificationState.dismissAll()
            true
        }
        KeyEvent.KEYCODE_O -> {
            item?.id?.let { find(it) }?.let { openNotification(it, host) }
            true
        }
        else -> false
    }

    private fun find(key: String) = NotificationState.items.value.firstOrNull { it.key == key }

    private fun single(item: PaneRow.Item) = listOf(PaneRow.Header("notifications"), item)

    /** An app's line: its icon, name and count; Enter opens or closes its notifications. */
    private suspend fun appRow(pkg: String, group: List<NotificationItem>, isOpen: Boolean): PaneRow.Item {
        val label = group.first().appLabel.lowercase()
        return PaneRow.Item(
            id = APP + pkg,
            text = label,
            detail = "${group.size}",
            status = "${group.size} from $label\n⏎ ${if (isOpen) "hide" else "show"}  d dismiss all",
            icon = appIcon(pkg, label),
        )
    }

    /**
     * The app's own icon, in half blocks, one line tall; a placeholder with its
     * initial when it has none the launcher can find (system parts, services).
     * Only real icons are kept: a placeholder is tried again next time.
     */
    private suspend fun appIcon(pkg: String, label: String): CharSequence? {
        icons[pkg]?.let { return it }
        val renderer = services.find<IconRenderer>() ?: run {
            log.d("no icon for $pkg: no renderer (the ascii art module is off)")
            return null
        }
        val catalog = services.find<AppCatalog>()
        val drawable = withContext(Dispatchers.IO) { catalog?.loadIcon(pkg) }
        if (drawable == null) {
            log.d("no icon for $pkg: ${if (catalog == null) "the apps module is off" else "the system has none"}")
            return renderer.render(placeholderIcon(label)(), APP_ICON_COLS, APP_ICON_ROWS, fine = true)
        }
        return renderer.render(drawable, APP_ICON_COLS, APP_ICON_ROWS, fine = true).also { icons[pkg] = it }
    }

    private fun row(item: NotificationItem): PaneRow.Item {
        val headline = "  " + item.title.ifEmpty { item.text }
        val ago = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val flags = listOfNotNull(
            "ongoing".takeIf { item.ongoing },
            "can't dismiss".takeIf { !item.clearable },
            "${item.messages.size} messages".takeIf { item.messages.size > 1 },
            "⏎ reply".takeIf { item.replyAction != null },
        )
        return PaneRow.Item(
            id = item.key,
            text = headline,
            detail = item.text.takeIf { item.title.isNotEmpty() },
            status = "${item.appLabel}, $ago${flags.joinToString("") { ", $it" }}\n${item.text.ifEmpty { headline }}",
        )
    }

    private companion object {
        const val ACCESS = "notif:access"
        const val WAITING = "notif:waiting"
        const val NONE = "notif:none"
        const val APP = "notif:app:"
        const val APP_ICON_COLS = 8
        const val APP_ICON_ROWS = 4
    }
}

/** Opens a notification like tapping it in the shade, and dismisses it if the app asked for that. */
internal fun openNotification(item: NotificationItem, host: LauncherHost) {
    val intent = item.contentIntent ?: return host.console.println("${item.appLabel}: nothing to open")
    try {
        // We are in the foreground; let the notification's app start its activity (Android 14+ rule).
        // No opening animation: apps start instantly from the terminal.
        @Suppress("DEPRECATION") // replaced by ALLOW_IF_VISIBLE on API 36; ALLOWED still works
        val options = ActivityOptions.makeCustomAnimation(host.context, 0, 0)
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        intent.send(host.context, 0, null, null, null, null, options)
        if (item.autoCancel) NotificationState.dismiss(item.key)
    } catch (e: PendingIntent.CanceledException) {
        host.console.println("${item.appLabel}: notification expired")
    }
}
