package com.localbridge.watch.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")
        if (action in SUPPORTED_ACTIONS) {
            val serviceIntent = Intent(context, BleGattServerService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "BleGattServerService started automatically via $action")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start BleGattServerService: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WatchBootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_ADDED,
            "com.localbridge.watch.TRIGGER_ALERT"
        )
    }
}
