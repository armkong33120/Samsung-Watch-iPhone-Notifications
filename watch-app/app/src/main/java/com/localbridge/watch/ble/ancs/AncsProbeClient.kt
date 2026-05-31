package com.localbridge.watch.ble.ancs

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class AncsProbeClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var bondReceiverRegistered = false
    private val bondAttemptedAddresses = mutableSetOf<String>()
    private var discoveryRetry = 0

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val changedDevice = changed ?: return
            if (changedDevice.address != device?.address) return
            when (changedDevice.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "ANCS_PROBE_BOND_OK")
                    unregisterBondReceiver()
                    connectGatt()
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=bond_none")
                    unregisterBondReceiver()
                    close("bond failed")
                }
                BluetoothDevice.BOND_BONDING -> logBondState(changedDevice.bondState)
            }
        }
    }

    fun start(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
            return
        }
        this.device = device
        discoveryRetry = 0
        Log.i(TAG, "ANCS_PROBE_START device=${masked(device)}")
        logBondState(device.bondState)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "ANCS_PROBE_BOND_OK")
            connectGatt()
            return
        }
        val address = device.address ?: ""
        if (!bondAttemptedAddresses.add(address)) {
            Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=already_attempted")
            close("bond already attempted")
            return
        }
        registerBondReceiver()
        Log.i(TAG, "ANCS_PROBE_BOND_START")
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=createBond_false")
            unregisterBondReceiver()
            close("bond start failed")
        }
    }

    fun close(reason: String) {
        Log.i(TAG, "ANCS_PROBE_CLOSE reason=$reason")
        unregisterBondReceiver()
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        device = null
    }

    private fun connectGatt() {
        val target = device ?: return
        if (!hasConnectPermission()) {
            Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
            close("permission missing")
            return
        }
        Log.i(TAG, "ANCS_PROBE_CONNECT_GATT_START")
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "ANCS_PROBE_GATT_CONNECTED status=$status")
                discover(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "ANCS_PROBE_GATT_DISCONNECTED status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "ANCS_CLIENT_CONNECT_FAILED status=$status")
                }
                close("gatt disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services = gatt.services.orEmpty()
            Log.i(TAG, "ANCS_PROBE_DISCOVER_DONE status=$status serviceCount=${services.size}")
            services.forEach { Log.i(TAG, "ANCS_PROBE_SERVICE uuid=${it.uuid}") }
            subscribeServiceChangedIfPresent(gatt)
            if (status == BluetoothGatt.GATT_SUCCESS && gatt.getService(AncsProfile.SERVICE_UUID) != null) {
                Log.i(TAG, "ANCS_PROBE_ANCS_FOUND")
                return
            }
            if (discoveryRetry < AncsProfile.DISCOVERY_RETRY_DELAYS_MS.size) {
                val retry = discoveryRetry + 1
                val delay = AncsProfile.DISCOVERY_RETRY_DELAYS_MS[discoveryRetry++]
                Log.w(TAG, "ANCS_PROBE_ANCS_NOT_FOUND retry=$retry")
                handler.postDelayed({ discover(gatt) }, delay)
            } else {
                Log.w(TAG, "ANCS_UNAVAILABLE: service not published after bonding")
                close("ancs unavailable")
            }
        }
    }

    private fun discover(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
            close("permission missing")
            return
        }
        Log.i(TAG, "ANCS_PROBE_DISCOVER_START")
        if (!gatt.discoverServices()) {
            Log.w(TAG, "ANCS_CLIENT_CONNECT_FAILED status=discoverServices_false")
            close("discoverServices false")
        }
    }

    @Suppress("DEPRECATION")
    private fun subscribeServiceChangedIfPresent(gatt: BluetoothGatt) {
        val serviceChanged = gatt.getService(AncsProfile.GENERIC_ATTRIBUTE_SERVICE_UUID)
            ?.getCharacteristic(AncsProfile.SERVICE_CHANGED_UUID)
        if (serviceChanged == null) {
            Log.i(TAG, "ANCS_PROBE_SERVICE_CHANGED unavailable")
            return
        }
        if (!hasConnectPermission()) return
        val descriptor = serviceChanged.getDescriptor(AncsProfile.CCCD_UUID) ?: return
        gatt.setCharacteristicNotification(serviceChanged, true)
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        bondReceiverRegistered = false
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun logBondState(state: Int) {
        val label = when (state) {
            BluetoothDevice.BOND_NONE -> "NONE"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "UNKNOWN"
        }
        Log.i(TAG, "ANCS_PROBE_BOND_STATE state=$label")
    }

    private fun masked(device: BluetoothDevice): String {
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val suffix = address.takeLast(5).ifBlank { "unknown" }
        return "**:**:**:**:$suffix"
    }

    companion object {
        private const val TAG = "GalaxyBridgeANCS"
    }
}
