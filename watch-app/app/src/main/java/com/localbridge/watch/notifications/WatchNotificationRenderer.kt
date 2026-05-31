package com.localbridge.watch.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.localbridge.watch.MainActivity
import com.localbridge.watch.R
import com.localbridge.watch.ble.BridgeDiagnostics
import com.localbridge.watch.overlay.AlertOverlayService
import com.localbridge.watch.ui.AlertActivity
import com.localbridge.watch.ui.NotificationEvent
import com.localbridge.watch.util.SoundDebug
import com.localbridge.watch.util.VibrationDebug
import kotlin.math.absoluteValue

class WatchNotificationRenderer(private val context: Context) {
    private val recentlyPosted = LinkedHashMap<String, Long>()
    private var lastPostTime = 0L

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Galaxy Bridge Alerts v2",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerting notifications forwarded from iPhone and ANCS"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableLights(true)
                setBypassDnd(true)

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(DEFAULT_NOTIFICATION_SOUND, audioAttributes)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            // Delete old channel if exists to reset system-level silent flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.deleteNotificationChannel("galaxy_bridge_restore_alerts_v1")
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun post(event: NotificationEvent, dedupeKey: String) {
        // Global throttle: max 1 alert every 5 seconds (prevents ANCS flood on reconnect)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPostTime < GLOBAL_THROTTLE_MS) {
            Log.i(TAG, "WATCH_NOTIFICATION_SKIPPED reason=global_throttle")
            return
        }
        lastPostTime = nowMs

        if (shouldSkip(dedupeKey)) {
            Log.i(TAG, "WATCH_NOTIFICATION_SKIPPED reason=duplicate")
            return
        }
        if (!hasNotificationPermission()) {
            Log.w(TAG, "WATCH_NOTIFICATION_SKIPPED reason=permission_missing")
            return
        }
        logChannelState()

        val title = event.title.ifBlank { event.getCleanedSource() }
        val notificationId = nextNotificationId()
        val contentText = event.cleanDisplayText()
        // Use unique tag to prevent system from grouping/silencing based on tag
        val uniqueTag = "gb_alert_${System.currentTimeMillis()}"

        val displaySource = event.getDisplaySource()
        val alertIntent = alertActivityIntent(title, contentText, displaySource)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVibrate(VIBRATION_PATTERN)
            .setSound(DEFAULT_NOTIFICATION_SOUND)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setFullScreenIntent(alertIntent, true)
            .setContentIntent(contentIntent())
            .setGroup("gb_alerts_group") // Explicit group
            .setGroupSummary(false)     // Ensure this is NOT a summary
            .extend(NotificationCompat.WearableExtender()
                .setHintScreenTimeout(NotificationCompat.WearableExtender.SCREEN_TIMEOUT_LONG)
            )
            .build()

        NotificationManagerCompat.from(context).notify(uniqueTag, notificationId, notification)
        BridgeDiagnostics.notificationPosted(event.source, title)
        Log.i(
            TAG,
            "WATCH_NOTIFICATION_POSTED_FORCE source=${event.source} title=$title"
        )

        // Fallback: directly start AlertActivity in case fullScreenIntent is blocked by OEM
        // Samsung diagnostics: log keyguard + power state before launch
        runCatching {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isKeyguardLocked = km?.isKeyguardLocked ?: "unknown"
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm?.isInteractive ?: "unknown"
            } else {
                @Suppress("DEPRECATION") pm?.isScreenOn ?: "unknown"
            }
            Log.i(TAG, "WATCH_SAMSUNG_DIAG keyguard=$isKeyguardLocked screenOn=$isScreenOn isDeviceLocked=${km?.isDeviceLocked ?: "unknown"}")

            val directIntent = Intent(context, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alert_title", title)
                putExtra("alert_body", contentText)
                putExtra("alert_source", displaySource)
            }
            context.startActivity(directIntent)
            Log.i(TAG, "WATCH_ALERT_ACTIVITY_STARTED direct fallback")
        }.onFailure { e ->
            Log.w(TAG, "WATCH_ALERT_ACTIVITY_FAILED error=${e.message}")
        }

        // Final fallback: use SYSTEM_ALERT_WINDOW overlay to bypass Samsung's Activity block
        runCatching {
            val overlayIntent = Intent(context, AlertOverlayService::class.java).apply {
                putExtra("alert_title", title)
                putExtra("alert_body", contentText)
                putExtra("alert_source", displaySource)
            }
            context.startService(overlayIntent)
            Log.i(TAG, "WATCH_OVERLAY_SERVICE_STARTED fallback")
        }.onFailure { e ->
            Log.w(TAG, "WATCH_OVERLAY_SERVICE_FAILED error=${e.message}")
        }

        // Direct vibration (bypass Samsung notification pipeline)
        VibrationDebug.vibrate(context, event.source, VIBRATION_PATTERN)

        // Direct sound (bypass Samsung notification pipeline)
        SoundDebug.playBestEffortAlert(context, event.source)

        // Wake screen directly
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GalaxyBridge:alert"
            )
            wakeLock.acquire(8_000L) // 8 seconds
            Log.i(TAG, "WATCH_WAKE_LOCK_ACQUIRED")
        }.onFailure { e ->
            Log.w(TAG, "WATCH_WAKE_LOCK_FAILED error=${e.message}")
        }
    }

    fun postDebugVibration(label: String, pattern: LongArray) {
        if (!hasNotificationPermission()) {
            Log.w(DEBUG_TAG, "DEBUG_NOTIFICATION_SKIPPED label=$label reason=permission_missing")
            return
        }

        val channelId = debugChannelId(label)
        createDebugChannel(channelId, label, pattern)

        val notificationId = DEBUG_NOTIFICATION_ID_BASE + label.hashCode().absoluteValue.mod(1_000)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Vibration debug: $label")
            .setContentText("Notification vibration test (${pattern.joinToString()})")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(pattern)
            .setSound(null)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setTimeoutAfter(12_000L)
            .build()

        NotificationManagerCompat.from(context).notify("gb_vibration_debug_$label", notificationId, notification)
        Log.i(DEBUG_TAG, "DEBUG_NOTIFICATION_POSTED label=$label channel=$channelId pattern=${pattern.joinToString()}")
    }

    private fun shouldSkip(dedupeKey: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = recentlyPosted[dedupeKey]
        recentlyPosted[dedupeKey] = now
        val iterator = recentlyPosted.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > DEDUPE_WINDOW_MS) iterator.remove()
        }
        return previous != null && now - previous < DEDUPE_WINDOW_MS
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alertActivityIntent(eventTitle: String, eventBody: String, eventSource: String): PendingIntent {
        val intent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alert_title", eventTitle)
            putExtra("alert_body", eventBody)
            putExtra("alert_source", eventSource)
        }
        val requestCode = (System.nanoTime() % Int.MAX_VALUE).toInt()
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            // FLAG_MUTABLE required for fullScreenIntent on Android 14+ / Wear OS 5+
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun createDebugChannel(channelId: String, label: String, pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            val channel = NotificationChannel(
                channelId,
                "GB vibration debug $label",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Debug channel for testing Wear OS notification vibration: $label"
                enableVibration(true)
                vibrationPattern = pattern
                setSound(null, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun debugChannelId(label: String): String {
        val safeLabel = label.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        return "${DEBUG_CHANNEL_PREFIX}_${safeLabel.ifBlank { "test" }}"
    }

    private fun nextNotificationId(): Int {
        // Return a more varied ID to avoid collisions or system-level collapsing
        return (System.nanoTime() % Int.MAX_VALUE).toInt()
    }

    companion object {
        const val CHANNEL_ID = "galaxy_bridge_restore_alerts_v2"
        private const val TAG = "GalaxyBridgeNotify"
        private const val DEBUG_TAG = "GalaxyBridgeVibrateDebug"
        private const val GLOBAL_THROTTLE_MS = 5_000L   // Global: max 1 alert every 5 seconds
        private const val DEDUPE_WINDOW_MS = 15_000L // Prevent flood: 15s between same-key alerts
        private const val DEBUG_NOTIFICATION_ID_BASE = 91_000
        private const val DEBUG_CHANNEL_PREFIX = "galaxy_bridge_vibration_debug_v1"
        private val DEFAULT_NOTIFICATION_SOUND = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        private val VIBRATION_PATTERN = longArrayOf(0L, 500L, 250L, 500L)
    }

    private fun logChannelState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        val importance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
        Log.i(TAG, "WATCH_NOTIFICATION_CHANNEL_STATE id=$CHANNEL_ID importance=$importance")
        if (importance < NotificationManager.IMPORTANCE_DEFAULT) {
            Log.w(TAG, "WATCH_NOTIFICATION_CHANNEL_BLOCKED id=$CHANNEL_ID importance=$importance")
        }
    }
}
