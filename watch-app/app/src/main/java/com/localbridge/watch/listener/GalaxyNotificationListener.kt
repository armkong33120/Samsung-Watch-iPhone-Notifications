package com.localbridge.watch.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Samsung One UI Watch 6 may whitelist apps that register as NotificationListenerService,
 * giving them higher priority in the notification pipeline.
 *
 * This listener does not read or forward any notification content — it simply exists
 * to improve our standing with Samsung's proprietary notification gatekeeping
 * (allowedMobileAppHashMap / AlertingPipeline).
 *
 * User must enable: Settings → Apps → Special Access → Notification Access → Galaxy Bridge
 */
class GalaxyNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NOTIF_LISTENER_CREATED")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NOTIF_LISTENER_CONNECTED")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "NOTIF_LISTENER_DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op: we don't read notifications, just need listener status for Samsung standing
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NOTIF_LISTENER_DESTROYED")
    }

    companion object {
        private const val TAG = "GalaxyNotifListener"
    }
}