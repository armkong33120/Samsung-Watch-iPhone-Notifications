package com.localbridge.watch.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SYSTEM_ALERT_WINDOW overlay fallback for Samsung Wear OS 6.
 *
 * Samsung blocks full-screen Activity launches for non-whitelisted third-party apps
 * when isWearing=false or the notification pipeline classifies the app as silent.
 *
 * This service renders the alert content directly via WindowManager.addView(),
 * bypassing the Activity stack and Samsung's proprietary notification gatekeeping.
 *
 * Requires: Settings → Apps → Galaxy Bridge → Allow display over other apps
 */
class AlertOverlayService : Service() {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val dismissHandler = Handler(Looper.getMainLooper())
    private var dismissed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("alert_title") ?: "Alert"
        val body = intent?.getStringExtra("alert_body") ?: ""
        val source = intent?.getStringExtra("alert_source") ?: "Galaxy Bridge"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "OVERLAY_PERMISSION_MISSING cannot show AlertOverlay")
            stopSelf()
            return START_NOT_STICKY
        }

        dismissOverlay() // remove any existing overlay first
        showOverlay(title, body, source)

        // Auto-dismiss after 30 seconds
        dismissHandler.postDelayed({ dismissSelf() }, AUTO_DISMISS_MS)

        return START_NOT_STICKY
    }

    private fun showOverlay(title: String, body: String, source: String) {
        val wm = windowManager ?: return

        // Inflate simple full-screen layout
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setOnClickListener { dismissSelf() }
        }

        // Source label
        view.addView(TextView(this).apply {
            text = source
            textSize = 12f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            maxLines = 1
        })

        // Spacer 1
        view.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 16
        ))

        // Title
        view.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        // Spacer 2
        view.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 20
        ))

        // Body
        view.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(Color.parseColor("#E6FFFFFF"))
            gravity = Gravity.CENTER
            maxLines = 5
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        params.screenBrightness = 1.0f // Full brightness

        overlayView = view
        wm.addView(view, params)

        // Wake screen
        acquireWakeLock()

        Log.i(TAG, "OVERLAY_SHOWN title=$title source=$source")
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GalaxyBridge:overlay"
            )
            wakeLock?.acquire(AUTO_DISMISS_MS)
            Log.i(TAG, "OVERLAY_WAKELOCK_ACQUIRED")
        }.onFailure { e ->
            Log.w(TAG, "OVERLAY_WAKELOCK_FAILED error=${e.message}")
        }
    }

    private fun dismissOverlay() {
        val view = overlayView
        val wm = windowManager
        if (view != null && wm != null) {
            runCatching {
                wm.removeView(view)
                Log.i(TAG, "OVERLAY_REMOVED")
            }
        }
        overlayView = null
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun dismissSelf() {
        if (dismissed) return
        dismissed = true
        dismissOverlay()
        dismissHandler.removeCallbacksAndMessages(null)
        stopSelf()
    }

    override fun onDestroy() {
        dismissSelf()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlertOverlayService"
        private const val AUTO_DISMISS_MS = 30_000L
        private const val OVERLAY_FG_ID = 2001
    }
}