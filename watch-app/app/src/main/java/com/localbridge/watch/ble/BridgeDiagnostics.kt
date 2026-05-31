package com.localbridge.watch.ble

import android.util.Log
import com.localbridge.watch.ble.ancs.AncsStatus

object BridgeDiagnostics {
    private const val TAG = "GalaxyBridgeDiag"
    private const val MAX_EVENTS = 80

    @Volatile var serviceStartedAt: Long = 0L
        private set
    @Volatile var lastServiceHeartbeatAt: Long = 0L
        private set
    @Volatile var lastCustomBridgeConnectAt: Long = 0L
        private set
    @Volatile var lastCustomBridgeDisconnectAt: Long = 0L
        private set
    @Volatile var lastAncsReadyAt: Long = 0L
        private set
    @Volatile var lastAncsEventAt: Long = 0L
        private set
    @Volatile var lastAncsDataAt: Long = 0L
        private set
    @Volatile var lastNotificationPostedAt: Long = 0L
        private set
    @Volatile var lastRecoveryAction: String = "none"
        private set
    @Volatile var customBridgeConnected: Boolean = false
        private set
    @Volatile var advertisingStarted: Boolean = false
        private set
    @Volatile var advertisingFailedCode: Int? = null
        private set
    @Volatile var ancsStatus: AncsStatus = AncsStatus.Idle
        private set

    private val events = ArrayDeque<String>()

    fun serviceStarted() {
        serviceStartedAt = now()
        record("SERVICE_STARTED")
    }

    fun serviceHeartbeat() {
        lastServiceHeartbeatAt = now()
    }

    fun customBridgeConnected() {
        customBridgeConnected = true
        lastCustomBridgeConnectAt = now()
        record("CUSTOM_BRIDGE_CONNECTED")
    }

    fun customBridgeDisconnected() {
        customBridgeConnected = false
        lastCustomBridgeDisconnectAt = now()
        record("CUSTOM_BRIDGE_DISCONNECTED")
    }

    fun advertisingStarted() {
        advertisingStarted = true
        advertisingFailedCode = null
        record("ADVERTISING_STARTED")
    }

    fun advertisingFailed(errorCode: Int) {
        advertisingStarted = false
        advertisingFailedCode = errorCode
        record("ADVERTISING_FAILED code=$errorCode")
    }

    fun ancsStatus(status: AncsStatus) {
        ancsStatus = status
        if (status == AncsStatus.Ready) lastAncsReadyAt = now()
        record("ANCS_STATUS status=$status")
    }

    fun ancsNotificationEvent() {
        lastAncsEventAt = now()
        record("ANCS_EVENT")
    }

    fun ancsDataReceived() {
        lastAncsDataAt = now()
        record("ANCS_DATA")
    }

    fun notificationPosted(source: String, title: String) {
        lastNotificationPostedAt = now()
        record("NOTIFICATION_POSTED source=$source title=$title")
    }

    fun recovery(level: Int, action: String, reason: String) {
        lastRecoveryAction = "L$level:$action"
        record("RECOVERY level=$level action=$action reason=$reason")
        Log.w(TAG, "GB_RECOVERY_LEVEL_${level}_${action.uppercase()} reason=$reason")
    }

    fun logHeartbeat(ancsSnapshot: String) {
        serviceHeartbeat()
        Log.i(
            TAG,
            "GB_DIAG_HEARTBEAT uptimeMs=${ageOf(serviceStartedAt)} " +
                "bridgeConnected=$customBridgeConnected advertising=$advertisingStarted " +
                "advertiseFail=$advertisingFailedCode ancs=$ancsStatus " +
                "lastAncsEventAgeMs=${ageOf(lastAncsEventAt)} " +
                "lastAncsDataAgeMs=${ageOf(lastAncsDataAt)} " +
                "lastNotificationAgeMs=${ageOf(lastNotificationPostedAt)} " +
                "lastRecovery=$lastRecoveryAction snapshot=$ancsSnapshot"
        )
    }

    fun recentEvents(): List<String> = synchronized(events) { events.toList() }

    private fun record(message: String) {
        val entry = "${now()} $message"
        synchronized(events) {
            events.addLast(entry)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        Log.i(TAG, "GB_DIAG_EVENT $message")
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun ageOf(timestamp: Long): Long = if (timestamp == 0L) -1L else now() - timestamp
}
