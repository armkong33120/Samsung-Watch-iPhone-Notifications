package com.localbridge.watch.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AlertActivity : ComponentActivity() {
    private val dismissHandler = Handler(Looper.getMainLooper())
    private var dismissed = false
    private var currentTitle by mutableStateOf("")
    private var currentBody by mutableStateOf("")
    private var currentSource by mutableStateOf("")
    private var lastDismissResetAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Aggressive Keyguard bypass for Samsung Wear OS 6 ---
        // Samsung blocks full-screen activities when isWearing=false or keyguard is locked.
        // FLAG_DISMISS_KEYGUARD + requestDismissKeyguard() are the strongest bypasses available.
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        // Wake screen, show when locked, keep screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Force dismiss keyguard on Samsung — must come after setShowWhenLocked
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    km.requestDismissKeyguard(this, null)
                } else {
                    @Suppress("DEPRECATION")
                    km.newKeyguardLock("GalaxyBridge")?.disableKeyguard()
                }
                android.util.Log.i("AlertActivity", "KEYGUARD_DISMISS_REQUESTED")
            } catch (e: Exception) {
                android.util.Log.w("AlertActivity", "KEYGUARD_DISMISS_FAILED error=${e.message}")
            }
        }

        // Hide system bars (full-screen) — wrap in post to avoid NPE on Wear OS 5+
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                @Suppress("DEPRECATION")
                window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
        }

        val title = intent.getStringExtra("alert_title") ?: "Alert"
        val body = intent.getStringExtra("alert_body") ?: ""
        val source = intent.getStringExtra("alert_source") ?: "Galaxy Bridge"

        setContent {
            AlertScreen(
                title = title,
                body = body,
                source = source,
                onDismiss = { dismissSelf() }
            )
        }

        // Auto-dismiss after 30 seconds to preserve battery
        dismissHandler.postDelayed({ dismissSelf() }, AUTO_DISMISS_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissHandler.removeCallbacksAndMessages(null)
        dismissed = true
    }

    private fun dismissSelf() {
        if (dismissed) return
        dismissed = true
        finish()
        // Fade out animation
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        private const val AUTO_DISMISS_MS = 30_000L
    }
}