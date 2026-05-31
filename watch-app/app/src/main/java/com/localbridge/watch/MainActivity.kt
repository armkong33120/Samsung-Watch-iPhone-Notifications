package com.localbridge.watch

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.localbridge.watch.ble.BleGattServerService
import com.localbridge.watch.ui.WatchScreen
import com.localbridge.watch.util.Permissions

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startBleService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate action=${intent.action}")
        if (Permissions.required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleService(intent.action)
        } else {
            permissionLauncher.launch(Permissions.required)
        }
        setContent { WatchScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent action=${intent.action}")
        setIntent(intent)
        startBleService(intent.action)
    }

    private fun startBleService(action: String? = null) {
        val serviceIntent = Intent(this, BleGattServerService::class.java)
        if (action in DEBUG_ACTIONS) serviceIntent.action = action
        Log.i(TAG, "startBleService requestedAction=$action forwardedAction=${serviceIntent.action}")
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    companion object {
        private const val TAG = "GalaxyBridgeMain"

        private val DEBUG_ACTIONS = setOf(
            BleGattServerService.ACTION_DEBUG_LOCAL_NOTIFICATION,
            BleGattServerService.ACTION_DEBUG_RECONNECT_ANCS,
            BleGattServerService.ACTION_DEBUG_RESTART_BRIDGE
        )
    }
}
