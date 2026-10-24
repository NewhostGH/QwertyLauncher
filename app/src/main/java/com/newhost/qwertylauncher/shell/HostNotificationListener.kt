package com.newhost.qwertylauncher.shell

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.newhost.qwertylauncher.core.NotificationHost

/**
 * The notification listener the system binds: the shell's, since modules
 * load from zips and can't bring services. Passes everything on to
 * [NotificationHost], where a module listens.
 */
class HostNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = NotificationHost.connected(this)

    override fun onListenerDisconnected() = NotificationHost.disconnected(this)

    override fun onDestroy() {
        NotificationHost.disconnected(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotificationHost.changed(this)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotificationHost.changed(this)
}
