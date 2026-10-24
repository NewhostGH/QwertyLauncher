package com.newhost.qwertylauncher.feature.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneInput
import com.newhost.qwertylauncher.core.PaneRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * What the user sent from the launcher, by notification, until the app's
 * own notification shows it (apps update theirs after a reply, some don't).
 */
internal object Sent {
    val messages = MutableStateFlow<Map<String, List<ThreadMessage>>>(emptyMap())

    fun add(key: String, text: String) = messages.update {
        it + (key to (it[key].orEmpty() + ThreadMessage(null, text, System.currentTimeMillis())))
    }

    /** The ones the notification doesn't show yet. */
    fun pending(item: NotificationItem): List<ThreadMessage> =
        messages.value[item.key].orEmpty().filter { sent -> item.messages.none { it.sender == null && it.text == sent.text } }
}

/** Answering notifications: their buttons, and replies typed in the launcher. */
internal object Answer {
    /** Presses [action], as if in the notification shade. */
    fun press(context: Context, action: NotificationAction): Boolean = send(context, action.intent, null)

    /** Replies [text] through [action]'s typed answer, as if in the shade. */
    fun reply(context: Context, action: NotificationAction, text: String): Boolean {
        val input = action.reply ?: return false
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return send(context, action.intent, fill)
    }

    private fun send(context: Context, intent: PendingIntent, fill: Intent?): Boolean = try {
        // The launcher is in the foreground; let the app start what it needs (Android 14+ rule).
        @Suppress("DEPRECATION") // replaced by ALLOW_IF_VISIBLE on API 36; ALLOWED still works
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        intent.send(context, 0, fill, null, null, null, options)
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }
}

/**
 * One notification in full: the whole conversation (or all it says),
 * newest at the bottom, then its buttons; and, when it takes replies, a
 * line to type one into. Esc goes back to the list.
 * ```
 * ── mom ───────────────────────
 *   mom  are you coming tonight?   19:02
 *   you  yes, at 8                 19:05
 *   mom  see you at 8              19:06
 * ── do ────────────────────────
 *   > Mark as read
 *   > open in WhatsApp
 * reply to mom> _
 * ```
 */
internal class ThreadPane(
    private val context: Context,
    private val key: String,
    first: NotificationItem,
    private val list: NotificationsPane,
) : ListPane {
    override val title = "notif: ${first.appLabel.lowercase()}"
    override val keysHelp = "type ⏎ reply  ⏎ on a button: press  esc back"
    override val followsEnd = true

    /** What happened last (sent, pressed, failed), until the next thing. */
    private val note = MutableStateFlow<String?>(null)

    private fun item(): NotificationItem? = NotificationState.items.value.firstOrNull { it.key == key }

    override fun rows(): Flow<List<PaneRow>> = combine(NotificationState.items, Sent.messages, note) { items, _, note ->
        val item = items.firstOrNull { it.key == key }
        buildList {
            if (item == null) {
                add(PaneRow.Header("notif"))
                add(PaneRow.Item(GONE, "(gone: dismissed, or answered)", status = "Esc goes back to the list."))
                note?.let { add(PaneRow.Item(NOTE, it, status = it)) }
                return@buildList
            }
            add(PaneRow.Header(item.conversation ?: item.title.ifEmpty { item.appLabel }))
            val messages = item.messages + Sent.pending(item)
            if (messages.isEmpty()) add(PaneRow.Item("msg:none", item.text.ifEmpty { "(nothing more to read)" }))
            messages.forEachIndexed { i, message -> addAll(messageRows(i, message, item)) }
            note?.let { add(PaneRow.Item(NOTE, it, status = it)) }
            add(PaneRow.Header("do"))
            item.actions.forEachIndexed { i, action ->
                if (action.reply != null) {
                    add(PaneRow.Item(ACTION + i, "> ${action.title} (type below)", status = "type below ⏎"))
                } else {
                    add(PaneRow.Item(ACTION + i, "> ${action.title}", status = "⏎ ${action.title}"))
                }
            }
            if (item.contentIntent != null) add(PaneRow.Item(OPEN, "> open in ${item.appLabel}", status = "⏎ open in the app"))
            if (item.clearable) add(PaneRow.Item(DISMISS, "> dismiss", status = "⏎ dismiss"))
        }
    }

    /**
     * A message as lines: its sender (or "you") and the first part, then the
     * rest wrapped under it, so a long one is all there to read.
     */
    private fun messageRows(index: Int, message: ThreadMessage, item: NotificationItem): List<PaneRow.Item> {
        val who = when (message.sender) {
            null -> "you"
            "" -> ""
            else -> message.sender
        }
        val time = message.time.takeIf { it > 0 }?.let(::clock)
        val parts = wrap(message.text, WRAP)
        return parts.mapIndexed { line, part ->
            PaneRow.Item(
                "msg:$index:$line",
                if (line == 0) (if (who.isEmpty()) part else "%-10s %s".format(who.take(NAME), part)) else " ".repeat(if (who.isEmpty()) 0 else NAME + 1) + part,
                detail = time.takeIf { line == 0 },
                status = listOfNotNull(who.ifEmpty { null }, time).joinToString(", ") + "\n" + message.text,
            )
        }
    }

    /** Each message's words in lines of at most [width]. */
    private fun wrap(text: String, width: Int): List<String> = text.lines().flatMap { paragraph ->
        val out = mutableListOf<String>()
        var line = ""
        for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= width -> "$line $word"
                else -> {
                    out += line
                    word
                }
            }
        }
        out += line
        out
    }.ifEmpty { listOf("") }

    private fun clock(millis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mma"
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, Locale.US)).lowercase()
    }

    /** A line to reply in, while the notification takes replies. */
    override fun input(): Flow<PaneInput?> = NotificationState.items
        .map { items -> items.firstOrNull { it.key == key }?.replyAction != null }
        .distinctUntilChanged()
        .map { canReply ->
            if (!canReply) {
                null
            } else {
                val to = item()?.let { it.conversation ?: it.title }?.ifEmpty { null }
                PaneInput(if (to != null) "reply to $to" else "reply") { text, _ -> reply(text) }
            }
        }

    private fun reply(text: String) {
        val action = item()?.replyAction ?: return run { note.value = "It can't be answered any more." }
        if (Answer.reply(context, action, text)) {
            Sent.add(key, text)
            note.value = null
        } else {
            note.value = "Couldn't send: the app took the reply back."
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        val notification = item() ?: return false
        when {
            item.id.startsWith(ACTION) -> {
                val action = notification.actions.getOrNull(item.id.removePrefix(ACTION).toInt()) ?: return false
                if (action.reply != null) {
                    note.value = "type the reply below ⏎"
                } else {
                    note.value = if (Answer.press(context, action)) "Pressed ${action.title}." else "${action.title}: expired."
                }
            }
            item.id == OPEN -> openNotification(notification, host)
            item.id == DISMISS -> {
                NotificationState.dismiss(notification.key)
                host.showPane(list)
            }
        }
        return false
    }

    override fun onBack(host: LauncherHost): Boolean {
        host.showPane(list)
        return true
    }

    private companion object {
        const val GONE = "thread:gone"
        const val NOTE = "thread:note"
        const val ACTION = "thread:action:"
        const val OPEN = "thread:open"
        const val DISMISS = "thread:dismiss"
        const val WRAP = 44
        const val NAME = 10
    }
}
