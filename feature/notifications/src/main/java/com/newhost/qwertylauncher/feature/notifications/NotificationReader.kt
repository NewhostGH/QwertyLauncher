package com.newhost.qwertylauncher.feature.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.NotificationHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One message of a conversation; [sender] null for the user's own. */
internal data class ThreadMessage(val sender: String?, val text: String, val time: Long)

/** One of a notification's buttons; [reply] when it takes typed text (a reply). */
internal data class NotificationAction(val title: String, val intent: PendingIntent, val reply: RemoteInput?)

/** What we keep of a notification: enough to count it, list it, open it, dismiss it, read it all and answer it. */
internal data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val ongoing: Boolean,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
    /** The conversation's name (a group chat), if it has one. */
    val conversation: String? = null,
    /** Everything it says: a chat's messages, an inbox's lines, or its long text. */
    val messages: List<ThreadMessage> = emptyList(),
    val actions: List<NotificationAction> = emptyList(),
) {
    /** The button to reply with, when it has one. */
    val replyAction: NotificationAction? get() = actions.firstOrNull { it.reply != null }
}

/**
 * Bridge between the shell's notification listener service (see
 * [NotificationHost]) and the rest of the module: [NotificationReader]
 * publishes here.
 */
internal object NotificationState {
    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The module's debug log channel, for the listener to report through. */
    @Volatile var log: DebugLog? = null

    @Suppress("UNUSED_PARAMETER")
    fun component(context: Context) = NotificationHost.component

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

    fun dismiss(key: String) {
        NotificationHost.service?.cancelNotification(key)
    }

    fun dismissAll() {
        NotificationHost.service?.cancelAllNotifications()
    }

    internal fun attach(connected: Boolean) {
        _connected.value = connected
        if (!connected) _items.value = emptyList()
    }

    internal fun publish(items: List<NotificationItem>) {
        _items.value = items
    }
}

/**
 * Mirrors the active notifications into [NotificationState], from the
 * shell's listener service. Lifecycle steps go to the debug log.
 */
internal object NotificationReader : NotificationHost.Listener {

    private val labels = mutableMapOf<String, String>()

    override fun onConnected(service: NotificationListenerService) {
        NotificationState.log?.d("listener connected")
        NotificationState.attach(true)
        publish(service)
    }

    override fun onDisconnected() {
        NotificationState.log?.d("listener disconnected")
        NotificationState.attach(false)
    }

    override fun onChanged(service: NotificationListenerService) = publish(service)

    /** Never throws: a crash here would make Android stop binding the listener. */
    private fun publish(service: NotificationListenerService) {
        try {
            publishActive(service)
        } catch (e: RuntimeException) {
            NotificationState.log?.e("couldn't read notifications", e)
        }
    }

    private fun publishActive(service: NotificationListenerService) {
        val active = try {
            service.activeNotifications
        } catch (e: SecurityException) {
            null // access was revoked
        } ?: return
        NotificationState.publish(
            active
                // A group summary duplicates its children; count each message once.
                .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                .map { toItem(it, service.packageManager) }
                .sortedByDescending { it.postTime },
        )
    }

    private fun toItem(sbn: StatusBarNotification, packageManager: PackageManager): NotificationItem {
        val notification = sbn.notification
        val extras = notification.extras
        return NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName, packageManager),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            postTime = sbn.postTime,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            autoCancel = (notification.flags and Notification.FLAG_AUTO_CANCEL) != 0,
            contentIntent = notification.contentIntent,
            conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            messages = messages(extras, sbn.postTime),
            actions = notification.actions.orEmpty().mapNotNull { action ->
                val intent = action.actionIntent ?: return@mapNotNull null
                NotificationAction(action.title?.toString().orEmpty(), intent, action.remoteInputs?.firstOrNull { it.allowFreeFormInput })
            },
        )
    }

    /** A chat's messages (older ones too), else an inbox's lines, else its long text. */
    private fun messages(extras: Bundle, posted: Long): List<ThreadMessage> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, Parcelable::class.java).orEmpty().toList() +
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty().toList()
        val chat = bundles.mapNotNull { (it as? Bundle)?.let(::message) }
        if (chat.isNotEmpty()) return chat
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            // No one in particular sent them: an empty sender (null is the user).
            return lines.map { ThreadMessage("", it.toString(), 0) }
        }
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: return emptyList()
        return listOf(ThreadMessage(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), text.toString(), posted))
    }

    /** One message of a MessagingStyle notification, from its bundle. */
    private fun message(bundle: Bundle): ThreadMessage? {
        val text = bundle.getCharSequence(KEY_TEXT)?.toString() ?: return null
        val sender = bundle.getParcelable(KEY_SENDER_PERSON, Person::class.java)?.name?.toString()
            ?: bundle.getCharSequence(KEY_SENDER)?.toString()
        return ThreadMessage(sender, text, bundle.getLong(KEY_TIME))
    }

    // The keys of a MessagingStyle message's bundle.
    private const val KEY_TEXT = "text"
    private const val KEY_TIME = "time"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"

    private fun label(packageName: String, packageManager: PackageManager): String = labels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
