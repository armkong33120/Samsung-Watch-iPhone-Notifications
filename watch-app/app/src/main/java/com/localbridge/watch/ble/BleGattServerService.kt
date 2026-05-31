package com.localbridge.watch.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localbridge.watch.R
import com.localbridge.watch.ble.ancs.AncsGattClient
import com.localbridge.watch.health.HealthRepository
import com.localbridge.watch.notifications.WatchNotificationRenderer
import com.localbridge.watch.ui.BridgeUiState
import com.localbridge.watch.ui.NotificationEvent
import org.json.JSONObject

class BleGattServerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reassembler = ChunkReassembler()
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var callControlCharacteristic: BluetoothGattCharacteristic? = null
    private var healthCharacteristic: BluetoothGattCharacteristic? = null
    private var heartbeatCharacteristic: BluetoothGattCharacteristic? = null
    private var ancsGattClient: AncsGattClient? = null
    private lateinit var notificationRenderer: WatchNotificationRenderer
    private lateinit var healthRepository: HealthRepository
    private var lastSoftAncsRecoverAt = 0L
    private var lastHardAncsRecoverAt = 0L
    private var lastBridgeRefreshAt = 0L
    private var lastServiceRestartAt = 0L
    private var advertisingActive = false
    private var ancsConnectedAt = 0L
    private var ancsNotificationCount = 0
    private var serviceCreatedAt = 0L
    private var restartCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nowMs = now()
        restartCount++
        if (serviceCreatedAt > 0L) {
            val downtime = nowMs - serviceCreatedAt
            val battery = batteryPercent()
            Log.w(TAG, "SERVICE_RESTARTED downtime_ms=$downtime battery=$battery% restart_count=$restartCount")
        }
        serviceCreatedAt = nowMs
        BridgeDiagnostics.serviceStarted()
        notificationRenderer = WatchNotificationRenderer(this)
        createNotificationChannel()
        notificationRenderer.createChannel()
        runCatching {
            startForeground(NOTIFICATION_ID, buildPersistentNotification("Waiting for iPhone"))
        }.onFailure {
            BridgeUiState.connectionLabel = "Foreground service limited"
        }
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        healthRepository = HealthRepository(this)
        healthRepository.start()
        openGattServer()
        startAdvertising()
        scheduleHeartbeat()
        scheduleHealthPublish()
        scheduleWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEBUG_LOCAL_NOTIFICATION -> {
                postDebugLocalNotification()
                return START_STICKY
            }
            ACTION_DEBUG_RECONNECT_ANCS -> {
                recoverAncsHard("debug intent")
                return START_STICKY
            }
            ACTION_DEBUG_RESTART_BRIDGE -> {
                refreshBridge("debug intent")
                return START_STICKY
            }
            ACTION_INTERNAL_SERVICE_RESTART -> {
                BridgeDiagnostics.recovery(4, "service_restarted", "internal restart intent")
            }
        }
        if (gattServer == null) openGattServer()
        startAdvertising()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        advertisingActive = false
        mainHandler.removeCallbacksAndMessages(null)
        healthRepository.stop()
        ancsGattClient?.close("service destroy")
        ancsGattClient = null
        runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        // Schedule restart via AlarmManager (avoids Samsung death-loop detection)
        scheduleServiceRestart()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleServiceRestart()
    }

    private fun scheduleServiceRestart() {
        runCatching {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, BleGattServerService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
            }
            Log.i(TAG, "Service restart scheduled via AlarmManager in 3s (allowWhileIdle=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.M})")
        }
    }

    private fun openGattServer() {
        if (!hasBluetoothPermission()) return
        gattServer = bluetoothManager.openGattServer(this, callback)
        val service = BluetoothGattService(
            BridgeGattProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(writeCharacteristic(BridgeGattProfile.NOTIFY_EVENTS_UUID))
        callControlCharacteristic = notifyCharacteristic(BridgeGattProfile.CALL_CONTROL_UUID)
        healthCharacteristic = notifyCharacteristic(BridgeGattProfile.HEALTH_SAMPLES_UUID)
        heartbeatCharacteristic = heartbeatCharacteristic()
        callControlCharacteristic?.let(service::addCharacteristic)
        healthCharacteristic?.let(service::addCharacteristic)
        heartbeatCharacteristic?.let(service::addCharacteristic)
        gattServer?.addService(service)
    }

    private fun startAdvertising() {
        if (advertisingActive) {
            Log.d(TAG, "Advertising already active, skipping duplicate call")
            return
        }
        if (!hasBluetoothPermission()) return
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            BridgeUiState.connectionLabel = "BLE advertiser unavailable"
            Log.w(TAG, "Bluetooth LE advertiser is unavailable on this device/state")
            return
        }
        advertisingActive = true
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BridgeGattProfile.SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
        BridgeUiState.connectionLabel = "Advertising"
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            connectedDevice = if (newState == BluetoothGatt.STATE_CONNECTED) device else null
            BridgeUiState.connectionLabel = if (connectedDevice != null) "Connected" else "Advertising"
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                BridgeDiagnostics.customBridgeConnected()
                startAncsClient(device)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                BridgeDiagnostics.customBridgeDisconnected()
                ancsGattClient?.close("custom bridge disconnected")
                ancsGattClient = null
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = heartbeatEnvelope().toBytes()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val text = String(value, Charsets.UTF_8)
            Log.i(TAG, "WRITE uuid=${characteristic.uuid} bytes=${value.size} text=${text.take(80)}")
            val merged = runCatching { reassembler.accept(value) }.getOrNull()
            if (merged != null) {
                Log.i(TAG, "WRITE_ASSEMBLED bytes=${merged.size}")
                handleEnvelope(merged)
            } else {
                Log.d(TAG, "WRITE_CHUNK waiting for more")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
        }
    }

    private fun handleEnvelope(bytes: ByteArray) {
        val envelope = runCatching { BridgeProtocol.decode(bytes) }.getOrNull() ?: return
        if (!reassembler.remember(envelope.id)) return
        when (envelope.type) {
            "notification" -> {
                val title = envelope.payload.optString("title", "Notification")
                val body = envelope.payload.optString("body", "")
                val source = envelope.payload.optString("source", "iPhone")
                val event = NotificationEvent(title = title, body = body, source = source)
                showBridgeNotification(event, "custom:${envelope.id}")
            }
            "call" -> {
                val caller = envelope.payload.optString("caller", "Unknown caller")
                BridgeUiState.latestEvent = "Incoming call\n$caller"
            }
            "heartbeat" -> BridgeUiState.connectionLabel = "Connected"
        }
        callControlCharacteristic?.let { notify(it, BridgeProtocol.ack(envelope.id)) }
    }

    private fun startAncsClient(device: BluetoothDevice) {
        if (!ANCS_PASSIVE_ENABLED) return
        mainHandler.post {
            ancsGattClient?.close("custom bridge reconnected")
            // Reset gate: allow max 10 ANCS notifications within 5 seconds of first connection
            ancsConnectedAt = now()
            ancsNotificationCount = 0
            ancsGattClient = AncsGattClient(this) { event -> showAncsNotification(event) }
            ancsGattClient?.start(device)
        }
    }

    private fun showAncsNotification(event: NotificationEvent) {
        mainHandler.post {
            ancsNotificationCount++
            val nowMs = now()
            val connectedMs = nowMs - ancsConnectedAt
            // Gate: within first 5 seconds of ANCS connection, cap at 10 notifications
            // This prevents iOS dumping all historical notifications on reconnect
            if (connectedMs < ANC_NEW_CONNECTION_WINDOW_MS && ancsNotificationCount > MAX_ANC_NEW_NOTIFICATIONS) {
                Log.i(TAG, "ANCS_GATED skipped count=$ancsNotificationCount connectedMs=$connectedMs source=${event.source}")
                return@post
            }
            queueLatestNotification(
                event,
                "ancs:${event.source}:${event.title}:${event.body}".hashCode().toString()
            )
        }
    }

    private fun showBridgeNotification(event: NotificationEvent, dedupeKey: String) {
        queueLatestNotification(event, dedupeKey)
    }

    private fun queueLatestNotification(event: NotificationEvent, dedupeKey: String) {
        BridgeUiState.latestEvent = event.cleanDisplayText()
        notificationRenderer.post(event, dedupeKey)
        Log.i(TAG, "Notification posted immediately source=${event.source} title=${event.title}")
    }

    private fun postDebugLocalNotification() {
        val event = NotificationEvent(
            title = "Debug local notification",
            body = "Posted from watch service ${System.currentTimeMillis()}",
            source = "Galaxy Bridge"
        )
        notificationRenderer.post(event, "debug:${System.currentTimeMillis()}")
        BridgeUiState.latestEvent = event.cleanDisplayText()
        Log.i(TAG, "GB_NOTIFICATION_LOCAL_OK")
    }

    fun sendCallAction(action: String) {
        val envelope = BridgeEnvelope(
            type = "call_control",
            payload = JSONObject().put("action", action)
        )
        BridgeUiState.lastAction = "Sending $action"
        Log.i(TAG, "Call control action tapped: $action")
        val characteristic = callControlCharacteristic
        if (characteristic == null) {
            BridgeUiState.lastAction = "Call control unavailable"
            Log.w(TAG, "Call control action dropped: characteristic unavailable")
            return
        }
        val sent = notify(characteristic, envelope)
        BridgeUiState.lastAction = if (sent) "Sent $action" else "Send failed: not connected"
    }

    private fun scheduleHeartbeat() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                heartbeatCharacteristic?.let { notify(it, heartbeatEnvelope()) }
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun scheduleHealthPublish() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                healthCharacteristic?.let {
                    notify(it, BridgeEnvelope(type = "health", payload = healthRepository.currentPayload()))
                }
                mainHandler.postDelayed(this, HEALTH_INTERVAL_MS)
            }
        }, HEALTH_INTERVAL_MS)
    }

    private fun scheduleWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                runWatchdog()
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }, WATCHDOG_INTERVAL_MS)
    }

    private fun runWatchdog() {
        val snapshot = ancsGattClient?.statusSnapshot()
        BridgeDiagnostics.logHeartbeat(snapshot?.toString() ?: "none")

        // CRITICAL: restart advertising immediately if it died silently
        if (!advertisingActive) {
            Log.w(TAG, "WATCHDOG_ADVERTISING_DEAD — restarting immediately")
            startAdvertising()
        }

        val device = connectedDevice
        if (device == null) {
            // Force re-advertising if not connected and not currently advertising
            if (shouldRun(lastBridgeRefreshAt, BRIDGE_REFRESH_COOLDOWN_MS)) {
                Log.i(TAG, "Watchdog: forcing advertising refresh")
                refreshBridge("watchdog: connection lost or not advertising")
            }
            return
        }

        if (!ANCS_PASSIVE_ENABLED) return
        if (snapshot == null || snapshot.closed) {
            if (shouldRun(lastHardAncsRecoverAt, ANCS_HARD_RECOVER_COOLDOWN_MS)) {
                BridgeDiagnostics.recovery(2, "hard_ancs", "watchdog: missing or closed ANCS client")
                lastHardAncsRecoverAt = now()
                startAncsClient(device)
            }
            return
        }

        if (!snapshot.ready && shouldRun(lastHardAncsRecoverAt, ANCS_HARD_RECOVER_COOLDOWN_MS)) {
            recoverAncsHard("watchdog: ANCS not ready (${snapshot.status})")
            return
        }

        // Recovery build: do not periodically resubscribe ANCS while it is ready.
        // iOS may replay a backlog and cause alert storms; only recover explicit failures.
    }

    private fun recoverAncsSoft(reason: String) {
        val client = ancsGattClient ?: return
        if (!shouldRun(lastSoftAncsRecoverAt, ANCS_SOFT_RECOVER_COOLDOWN_MS)) return
        lastSoftAncsRecoverAt = now()
        BridgeDiagnostics.recovery(1, "soft_ancs", reason)
        client.recoverSoft(reason)
    }

    private fun recoverAncsHard(reason: String) {
        val device = connectedDevice
        if (!shouldRun(lastHardAncsRecoverAt, ANCS_HARD_RECOVER_COOLDOWN_MS)) return
        lastHardAncsRecoverAt = now()
        BridgeDiagnostics.recovery(2, "hard_ancs", reason)
        val client = ancsGattClient
        if (client != null) {
            client.recoverHard(reason)
        } else if (device != null) {
            startAncsClient(device)
        }
    }

    private fun refreshBridge(reason: String) {
        if (!shouldRun(lastBridgeRefreshAt, BRIDGE_REFRESH_COOLDOWN_MS)) return
        lastBridgeRefreshAt = now()
        BridgeDiagnostics.recovery(3, "refresh_bridge", reason)
        advertisingActive = false
        runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        openGattServer()
        startAdvertising()
        if (connectedDevice == null && shouldRun(lastServiceRestartAt, SERVICE_RESTART_COOLDOWN_MS)) {
            restartService("watchdog: bridge refresh without connected device")
        }
    }

    private fun restartService(reason: String) {
        lastServiceRestartAt = now()
        BridgeDiagnostics.recovery(4, "restart_service", reason)
        val restartIntent = Intent(this, BleGattServerService::class.java).setAction(ACTION_INTERNAL_SERVICE_RESTART)
        ContextCompat.startForegroundService(this, restartIntent)
    }

    private fun heartbeatEnvelope() = BridgeEnvelope(
        type = "heartbeat",
        payload = JSONObject()
            .put("battery", batteryPercent())
            .put("protocol", BridgeGattProfile.PROTOCOL_VERSION)
    )

    private fun notify(characteristic: BluetoothGattCharacteristic, envelope: BridgeEnvelope): Boolean {
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "Notify skipped type=${envelope.type} uuid=${characteristic.uuid}: no connected device")
            return false
        }
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Notify skipped type=${envelope.type} uuid=${characteristic.uuid}: missing bluetooth permission")
            return false
        }
        var sentAny = false
        BridgeProtocol.chunk(envelope).forEach { chunk ->
            characteristic.value = chunk
            if (Build.VERSION.SDK_INT >= 33) {
                val result = gattServer?.notifyCharacteristicChanged(device, characteristic, false, chunk)
                Log.i(TAG, "Notify sent type=${envelope.type} uuid=${characteristic.uuid} bytes=${chunk.size} result=$result")
                sentAny = result == BluetoothGatt.GATT_SUCCESS || sentAny
            } else {
                @Suppress("DEPRECATION")
                val result = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                Log.i(TAG, "Notify sent type=${envelope.type} uuid=${characteristic.uuid} bytes=${chunk.size} result=$result")
                sentAny = result || sentAny
            }
        }
        return sentAny
    }

    private fun writeCharacteristic(uuid: java.util.UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private fun notifyCharacteristic(uuid: java.util.UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply { addDescriptor(clientConfigDescriptor()) }

    private fun heartbeatCharacteristic() = BluetoothGattCharacteristic(
        BridgeGattProfile.HEARTBEAT_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply { addDescriptor(clientConfigDescriptor()) }

    private fun clientConfigDescriptor() = BluetoothGattDescriptor(
        BridgeGattProfile.CLIENT_CONFIG_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
    }

    private fun batteryPercent(): Int {
        val manager = getSystemService(BatteryManager::class.java)
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // Delete old LOW-importance channel — wrapped in runCatching
            // Android 14+ may throw SecurityException if a foreground service is running
            runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
            val existing = runCatching { nm.getNotificationChannel(CHANNEL_ID) }.getOrNull()
            if (existing == null || existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Galaxy Bridge",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Persistent foreground service for iPhone BLE bridge"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
        // Also ensure the alert channel for forwarded notifications uses HIGH importance
        notificationRenderer.createChannel()
    }

    private fun buildPersistentNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_bridge)
        .setContentTitle("Galaxy Bridge")
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setGroup("gb_alerts_group")
        .setGroupSummary(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertisingActive = true
            BridgeUiState.connectionLabel = "Advertising"
            BridgeDiagnostics.advertisingStarted()
            Log.i(TAG, "BLE advertising started for ${BridgeGattProfile.SERVICE_UUID}")
        }

        override fun onStartFailure(errorCode: Int) {
            advertisingActive = false
            BridgeUiState.connectionLabel = "Advertise failed: $errorCode"
            BridgeDiagnostics.advertisingFailed(errorCode)
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private fun shouldRun(lastRunAt: Long, cooldownMs: Long): Boolean =
        lastRunAt == 0L || now() - lastRunAt >= cooldownMs

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val ACTION_DEBUG_LOCAL_NOTIFICATION = "com.localbridge.watch.DEBUG_LOCAL_NOTIFICATION"
        const val ACTION_DEBUG_RECONNECT_ANCS = "com.localbridge.watch.DEBUG_RECONNECT_ANCS"
        const val ACTION_DEBUG_RESTART_BRIDGE = "com.localbridge.watch.DEBUG_RESTART_BRIDGE"
        private const val ACTION_INTERNAL_SERVICE_RESTART = "com.localbridge.watch.INTERNAL_SERVICE_RESTART"
        private const val TAG = "GalaxyBridgeBle"
        private const val CHANNEL_ID = "galaxy_bridge_ble"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val ANCS_SOFT_RECOVER_COOLDOWN_MS = 5 * 60_000L
        private const val ANCS_HARD_RECOVER_COOLDOWN_MS = 2 * 60_000L
        private const val BRIDGE_REFRESH_COOLDOWN_MS = 3 * 60_000L
        private const val SERVICE_RESTART_COOLDOWN_MS = 10 * 60_000L
        private const val ANCS_PASSIVE_ENABLED = true
        private const val ANC_NEW_CONNECTION_WINDOW_MS = 5_000L  // 5 seconds after ANCS connection
        private const val MAX_ANC_NEW_NOTIFICATIONS = 10          // cap at 10 during window

        var instance: BleGattServerService? = null
            private set
    }
}
