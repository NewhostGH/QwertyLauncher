package com.newhost.qwertylauncher.core

import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * The system's notification listener. Android binds only services the APK's
 * own manifest names, and modules are loaded from zips, so the shell owns
 * the service and hands its callbacks on through here to whichever module
 * [listen]s. The system calls on the main thread.
 */
object NotificationHost {
    /** The shell's listener service: what notification access is granted to. Set by the shell at start. */
    lateinit var component: ComponentName

    /** What a module does with the listener's callbacks. */
    interface Listener {
        /** Connected: [service] can read and cancel notifications until [onDisconnected]. */
        fun onConnected(service: NotificationListenerService)
        fun onDisconnected()

        /** A notification was posted or removed. */
        fun onChanged(service: NotificationListenerService)
    }

    /** The service while it's connected. */
    @Volatile var service: NotificationListenerService? = null
        private set

    @Volatile private var listener: Listener? = null

    /** Hands the callbacks to [listener]; told at once if the service is already connected. */
    fun listen(listener: Listener) {
        this.listener = listener
        service?.let(listener::onConnected)
    }

    // Called by the shell's service.

    fun connected(service: NotificationListenerService) {
        this.service = service
        listener?.onConnected(service)
    }

    fun disconnected(service: NotificationListenerService) {
        if (this.service !== service) return
        this.service = null
        listener?.onDisconnected()
    }

    fun changed(service: NotificationListenerService) {
        listener?.onChanged(service)
    }
}
