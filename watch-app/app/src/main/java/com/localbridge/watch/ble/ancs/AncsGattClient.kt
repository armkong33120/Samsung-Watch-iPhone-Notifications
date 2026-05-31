package com.localbridge.watch.ble.ancs

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import com.localbridge.watch.ble.BridgeDiagnostics
import com.localbridge.watch.ui.NotificationEvent

class AncsGattClient(
    private val context: Context,
    private val onNotification: (NotificationEvent) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val parser = AncsParser()
    private val operationQueue = AncsOperationQueue(handler, TAG) { phase ->
        timeoutCount += 1
        if (timeoutCount >= MAX_TIMEOUTS) {
            failAndClose("too many timeouts after $phase", AncsFailureReason.OperationTimeout)
        }
    }
    private val bondAttemptedAddresses = mutableSetOf<String>()
    private var bondReceiverRegistered = false
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var notificationSource: BluetoothGattCharacteristic? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var dataSource: BluetoothGattCharacteristic? = null
    private var state = AncsStatus.Idle
    private var discoveryRetry = 0
    private var timeoutCount = 0
    private var closed = false
    private var lastReadyAt = 0L
    private var lastEventAt = 0L
    private var lastDataAt = 0L
    private var consecutiveFailures = 0

    data class StatusSnapshot(
        val status: AncsStatus,
        val ready: Boolean,
        val closed: Boolean,
        val lastReadyAt: Long,
        val lastEventAt: Long,
        val lastDataAt: Long,
        val consecutiveFailures: Int
    ) {
        override fun toString(): String {
            return "status=$status ready=$ready closed=$closed failures=$consecutiveFailures " +
                "lastReadyAt=$lastReadyAt lastEventAt=$lastEventAt lastDataAt=$lastDataAt"
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val changedDevice = changed ?: return
            if (changedDevice.address != device?.address) return
            when (changedDevice.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    Log.i(TAG, "ANCS_BOND_OK")
                    Log.i(TAG, "ANCS_PROBE_BOND_OK")
                    unregisterBondReceiver()
                    connectGatt()
                }
                BluetoothDevice.BOND_NONE -> {
                    Log.w(TAG, "ANCS_BOND_FAILED reason=bond_none")
                    Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=bond_none")
                    unregisterBondReceiver()
                    failAndClose("bond failed", AncsFailureReason.BondFailed)
                }
                BluetoothDevice.BOND_BONDING -> logProbeBondState(changedDevice.bondState)
            }
        }
    }

    fun start(device: BluetoothDevice) {
        if (closed) closed = false
        if (!hasConnectPermission()) {
            Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
            Log.w(TAG, "ANCS_PROBE_CLOSE reason=permission missing")
            return
        }
        this.device = device
        discoveryRetry = 0
        timeoutCount = 0
        consecutiveFailures = 0
        state = AncsStatus.WaitingForBond
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_PROBE_START device=${masked(device)}")
        Log.i(TAG, "ANCS_START device=${masked(device)}")
        Log.i(TAG, "ANCS_PERMISSION_OK")
        logProbeBondState(device.bondState)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "ANCS_BOND_OK")
            Log.i(TAG, "ANCS_PROBE_BOND_OK")
            connectGatt()
            return
        }
        val address = runCatching { device.address }.getOrNull().orEmpty()
        if (!bondAttemptedAddresses.add(address)) {
            Log.w(TAG, "ANCS_BOND_FAILED reason=already_attempted")
            Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=already_attempted")
            failAndClose("bond already attempted", AncsFailureReason.BondFailed)
            return
        }
        Log.i(TAG, "ANCS_BOND_START state=${bondStateName(device.bondState)}")
        Log.i(TAG, "ANCS_PROBE_BOND_START")
        registerBondReceiver()
        handler.postDelayed({
            if (state == AncsStatus.WaitingForBond) {
                Log.w(TAG, "ANCS_BOND_FAILED reason=timeout")
                Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=timeout")
                failAndClose("bond timeout", AncsFailureReason.BondFailed)
            }
        }, AncsProfile.BOND_TIMEOUT_MS)
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            Log.w(TAG, "ANCS_BOND_FAILED reason=createBond_false")
            Log.w(TAG, "ANCS_PROBE_BOND_FAILED reason=createBond_false")
            failAndClose("createBond false", AncsFailureReason.BondFailed)
        }
    }

    fun close(reason: String) {
        if (closed) return
        closed = true
        state = AncsStatus.Closed
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_CLOSE reason=$reason")
        Log.i(TAG, "ANCS_PROBE_CLOSE reason=$reason")
        unregisterBondReceiver()
        operationQueue.clear()
        handler.removeCallbacksAndMessages(null)
        parser.resetDataSourceBuffer()
        runCatching { gatt?.close() }
        gatt = null
        device = null
        notificationSource = null
        controlPoint = null
        dataSource = null
    }

    private fun connectGatt() {
        val target = device ?: return
        if (!hasConnectPermission()) {
            Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
            failAndClose("permission missing", AncsFailureReason.PermissionMissing)
            return
        }
        state = AncsStatus.ConnectingGatt
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_PROBE_CONNECT_GATT_START")
        Log.i(TAG, "ANCS_GATT_CONNECT_START")
        val timeoutRunnable = Runnable {
            if (state == AncsStatus.ConnectingGatt) {
                Log.w(TAG, "ANCS_CLIENT_CONNECT_FAILED status=timeout")
                failAndClose("connect timeout", AncsFailureReason.ConnectFailed)
            }
        }
        handler.postDelayed(timeoutRunnable, AncsProfile.CONNECT_TIMEOUT_MS)
        gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "ANCS_PROBE_GATT_CONNECTED status=$status")
                Log.i(TAG, "ANCS_GATT_CONNECTED status=$status")
                discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "ANCS_PROBE_GATT_DISCONNECTED status=$status")
                Log.i(TAG, "ANCS_GATT_DISCONNECTED status=$status")
                if (!closed && status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "ANCS_CLIENT_CONNECT_FAILED status=$status")
                }
                close("gatt disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            operationQueue.completeActive(status == BluetoothGatt.GATT_SUCCESS, "discover status=$status")
            val services = gatt.services.orEmpty()
            Log.i(TAG, "ANCS_PROBE_DISCOVER_DONE status=$status serviceCount=${services.size}")
            Log.i(TAG, "ANCS_DISCOVER_DONE status=$status serviceCount=${services.size}")
            services.forEach { Log.i(TAG, "ANCS_PROBE_SERVICE uuid=${it.uuid}") }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "ANCS_CLIENT_CONNECT_FAILED status=$status")
                retryOrUnavailable()
                return
            }
            val service = gatt.getService(AncsProfile.SERVICE_UUID)
            if (service == null) {
                retryOrUnavailable()
                return
            }
            Log.i(TAG, "ANCS_PROBE_ANCS_FOUND")
            Log.i(TAG, "ANCS_SERVICE_FOUND")
            notificationSource = service.getCharacteristic(AncsProfile.NOTIFICATION_SOURCE_UUID)
            controlPoint = service.getCharacteristic(AncsProfile.CONTROL_POINT_UUID)
            dataSource = service.getCharacteristic(AncsProfile.DATA_SOURCE_UUID)
            subscribeAfterDiscovery()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val phase = operationQueue.activePhase().orEmpty()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                operationQueue.completeActive(true)
                if (descriptor.characteristic?.uuid == AncsProfile.NOTIFICATION_SOURCE_UUID) {
                    Log.i(TAG, "ANCS_SUBSCRIBE_NOTIFICATION_SOURCE_OK")
                    subscribeDataSourceOrReady()
                } else if (descriptor.characteristic?.uuid == AncsProfile.DATA_SOURCE_UUID) {
                    Log.i(TAG, "ANCS_SUBSCRIBE_DATA_SOURCE_OK")
                    markReady()
                }
            } else {
                logAuthOrGattFailure(phase, status)
                operationQueue.completeActive(false, "descriptor status=$status")
                if (descriptor.characteristic?.uuid == AncsProfile.NOTIFICATION_SOURCE_UUID) {
                    failAndClose("notification source subscribe failed", AncsFailureReason.AuthFailed)
                } else {
                    Log.w(TAG, "ANCS_DETAILS_UNAVAILABLE: missing Control Point or Data Source")
                    markReady()
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val phase = operationQueue.activePhase().orEmpty()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                operationQueue.completeActive(true)
            } else {
                logAuthOrGattFailure(phase, status)
                operationQueue.completeActive(false, "write status=$status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicValue(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onCharacteristicValue(characteristic.uuid, value)
        }
    }

    private fun discoverServices() {
        val client = gatt ?: return
        state = AncsStatus.DiscoveringServices
        BridgeDiagnostics.ancsStatus(state)
        operationQueue.enqueue("discoverServices", AncsProfile.DISCOVERY_TIMEOUT_MS) {
            if (!hasConnectPermission()) {
                Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
                false
            } else {
                Log.i(TAG, "ANCS_PROBE_DISCOVER_START")
                Log.i(TAG, "ANCS_DISCOVER_START")
                client.discoverServices()
            }
        }
    }

    private fun retryOrUnavailable() {
        if (discoveryRetry < AncsProfile.DISCOVERY_RETRY_DELAYS_MS.size) {
            val retry = discoveryRetry + 1
            val delay = AncsProfile.DISCOVERY_RETRY_DELAYS_MS[discoveryRetry++]
            Log.w(TAG, "ANCS_PROBE_ANCS_NOT_FOUND retry=$retry")
            Log.w(TAG, "ANCS_SERVICE_MISSING retry=$retry")
            handler.postDelayed({ discoverServices() }, delay)
        } else {
            Log.w(TAG, "ANCS_UNAVAILABLE: service not published after bonding")
            failAndClose("service unavailable", AncsFailureReason.ServiceUnavailable)
        }
    }

    private fun subscribeAfterDiscovery() {
        val source = notificationSource
        if (source == null) {
            Log.w(TAG, "ANCS_UNAVAILABLE_MISSING_NOTIFICATION_SOURCE")
            failAndClose("notification source missing", AncsFailureReason.CharacteristicMissing)
            return
        }
        state = AncsStatus.SubscribingNotificationSource
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_SUBSCRIBE_NOTIFICATION_SOURCE_START")
        enqueueNotificationSubscription(
            phase = "subscribeNotificationSource",
            characteristic = source,
            enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
    }

    private fun subscribeDataSourceOrReady() {
        if (controlPoint == null || dataSource == null) {
            Log.w(TAG, "ANCS_DETAILS_UNAVAILABLE: missing Control Point or Data Source")
            markReady()
            return
        }
        state = AncsStatus.SubscribingDataSource
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_SUBSCRIBE_DATA_SOURCE_START")
        enqueueNotificationSubscription(
            phase = "subscribeDataSource",
            characteristic = dataSource ?: return,
            enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
    }

    @Suppress("DEPRECATION")
    private fun enqueueNotificationSubscription(
        phase: String,
        characteristic: BluetoothGattCharacteristic,
        enableValue: ByteArray
    ) {
        val client = gatt ?: return
        operationQueue.enqueue(phase, AncsProfile.CCCD_WRITE_TIMEOUT_MS) {
            if (!hasConnectPermission()) {
                Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
                false
            } else {
                val descriptor = characteristic.getDescriptor(AncsProfile.CCCD_UUID)
                if (descriptor == null) {
                    Log.w(TAG, "ANCS_DETAILS_UNAVAILABLE: missing CCCD")
                    false
                } else {
                    client.setCharacteristicNotification(characteristic, true)
                    descriptor.value = enableValue
                    client.writeDescriptor(descriptor)
                }
            }
        }
    }

    private fun markReady() {
        state = AncsStatus.Ready
        lastReadyAt = System.currentTimeMillis()
        consecutiveFailures = 0
        BridgeDiagnostics.ancsStatus(state)
        Log.i(TAG, "ANCS_READY")
    }

    fun statusSnapshot(): StatusSnapshot = StatusSnapshot(
        status = state,
        ready = state == AncsStatus.Ready && !closed,
        closed = closed,
        lastReadyAt = lastReadyAt,
        lastEventAt = lastEventAt,
        lastDataAt = lastDataAt,
        consecutiveFailures = consecutiveFailures
    )

    fun recoverSoft(reason: String) {
        Log.w(TAG, "ANCS_RECOVER_SOFT reason=$reason state=$state")
        if (closed) {
            recoverHard("soft requested while closed: $reason")
            return
        }
        operationQueue.clear()
        parser.resetDataSourceBuffer()
        if (notificationSource != null) {
            subscribeAfterDiscovery()
        } else {
            discoverServices()
        }
    }

    fun recoverHard(reason: String) {
        Log.w(TAG, "ANCS_RECOVER_HARD reason=$reason state=$state")
        val target = device
        close("hard recover: $reason")
        if (target != null) {
            handler.postDelayed({ start(target) }, HARD_RECOVER_DELAY_MS)
        }
    }

    private fun onCharacteristicValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            AncsProfile.NOTIFICATION_SOURCE_UUID -> handleNotificationSource(value)
            AncsProfile.DATA_SOURCE_UUID -> handleDataSource(value)
        }
    }

    private fun handleNotificationSource(value: ByteArray) {
        val event = parser.parseNotificationSource(value)
        if (event == null) {
            Log.w(TAG, "ANCS_PARSE_ERROR reason=notification_source_short")
            return
        }
        Log.i(
            TAG,
            "ANCS notification received uid=${event.uid} event=${event.eventId.logName} category=${event.categoryId.displayName}"
        )
        lastEventAt = System.currentTimeMillis()
        BridgeDiagnostics.ancsNotificationEvent()
        if (event.eventId != AncsEventId.Added) {
            Log.i(TAG, "ANCS notification ignored uid=${event.uid} event=${event.eventId.logName}")
            return
        }
        if (controlPoint != null && dataSource != null) {
            requestAttributes(event.uid)
        } else {
            onNotification(
                NotificationEvent(
                    title = "ANCS notification",
                    body = event.categoryId.displayName,
                    source = "ANCS"
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAttributes(uid: UInt) {
        val client = gatt ?: return
        val characteristic = controlPoint ?: return
        val request = parser.buildGetNotificationAttributesRequest(uid)
        operationQueue.enqueue("writeControlPoint", AncsProfile.CONTROL_POINT_WRITE_TIMEOUT_MS) {
            if (!hasConnectPermission()) {
                Log.w(TAG, "ANCS_PERMISSION_MISSING permission=BLUETOOTH_CONNECT")
                false
            } else {
                characteristic.value = request
                val started = client.writeCharacteristic(characteristic)
                if (started) Log.i(TAG, "ANCS_ATTR_REQUEST_SENT uid=$uid")
                started
            }
        }
    }

    private fun handleDataSource(value: ByteArray) {
        Log.i(TAG, "ANCS_DATA_CHUNK_RECEIVED bytes=${value.size}")
        lastDataAt = System.currentTimeMillis()
        BridgeDiagnostics.ancsDataReceived()
        val parsed = parser.parseDataSourceIncremental(value) ?: return
        val error = parsed.error
        if (error != null) {
            Log.w(TAG, "ANCS_PARSE_ERROR reason=$error")
            return
        }
        val attributes = parsed.attributes ?: return
        Log.i(
            TAG,
            "ANCS attributes received uid=${attributes.uid} app=${attributes.appIdentifier} " +
                "titleLen=${attributes.title.length} messageLen=${attributes.message.length}"
        )
        val title = attributes.title.ifBlank { attributes.appIdentifier.ifBlank { "ANCS notification" } }
        onNotification(
            NotificationEvent(
                title = title,
                body = attributes.message,
                source = "ANCS: ${attributes.appIdentifier.ifBlank { "Unknown app" }}"
            )
        )
    }

    private fun logAuthOrGattFailure(phase: String, status: Int) {
        Log.w(TAG, "ANCS_AUTH_FAILED phase=$phase status=$status")
        if (status == 5 || status == 8 || status == 15) {
            Log.w(TAG, "ANCS_REJECTED: entitlement required or authorization unavailable; phase=$phase status=$status")
        }
    }

    private fun failAndClose(reason: String, failure: AncsFailureReason) {
        state = AncsStatus.Failed
        consecutiveFailures += 1
        BridgeDiagnostics.ancsStatus(state)
        Log.w(TAG, "ANCS_FAIL reason=$failure detail=$reason")
        close(reason)
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

    private fun logProbeBondState(state: Int) {
        Log.i(TAG, "ANCS_PROBE_BOND_STATE state=${bondStateName(state)}")
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN"
    }

    private fun masked(device: BluetoothDevice): String {
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val suffix = address.takeLast(5).ifBlank { "unknown" }
        return "**:**:**:**:$suffix"
    }

    companion object {
        private const val TAG = "GalaxyBridgeANCS"
        private const val MAX_TIMEOUTS = 3
        private const val HARD_RECOVER_DELAY_MS = 1_500L
    }
}
