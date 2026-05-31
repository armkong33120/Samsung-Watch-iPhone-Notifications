package com.localbridge.watch.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

object VibrationDebug {
    private const val TAG = "GalaxyBridgeVibrateDebug"

    val shortPattern = longArrayOf(0L, 180L)
    val doublePattern = longArrayOf(0L, 180L, 120L, 180L)
    val longPattern = longArrayOf(0L, 650L)

    fun vibrate(context: Context, label: String, pattern: LongArray): Boolean {
        if (!hasVibratePermission(context)) {
            Log.w(TAG, "DIRECT_VIBRATE_SKIPPED label=$label reason=permission_missing")
            return false
        }

        val vibrator = vibrator(context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "DIRECT_VIBRATE_SKIPPED label=$label reason=vibrator_unavailable")
            return false
        }

        return runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            Log.i(TAG, "DIRECT_VIBRATE_SENT label=$label pattern=${pattern.joinToString()}")
            true
        }.getOrElse { error ->
            Log.w(TAG, "DIRECT_VIBRATE_FAILED label=$label error=${error.message}")
            false
        }
    }

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    private fun hasVibratePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
    }
}