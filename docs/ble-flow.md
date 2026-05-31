# Galaxy Bridge — BLE Architecture Flow

> Mermaid swimlane diagram for iPhone ↔ Samsung Galaxy Watch BLE bridge

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    box iPhone (iOS)
        participant App as 📱 Galaxy Bridge App
        participant BLE as 🔵 CBCentralManager
        participant ANCS as 📡 ANCS (iOS)
    end

    box Galaxy Watch (Wear OS)
        participant Srv as 🟢 BleGattServerService
        participant Advertise as 📶 BLE Advertising
        participant GATT as 📥 GATT Server
        participant ANCS_C as 📡 AncsGattClient
        participant Render as 🔔 WatchNotificationRenderer
        participant Alert as 🖥️ AlertActivity
        participant Overlay as 🆘 AlertOverlayService
        participant WD as 🛡️ Watchdog
    end

    %% ═══ PHASE 1: INITIALIZATION ═══
    rect rgb(230, 245, 230)
        Note over Srv, Advertise: PHASE 1 — Service Start & Advertising
        Srv->>Srv: onCreate() + startForeground()
        Srv->>Srv: openGattServer() + addService
        Srv->>Advertise: startAdvertising()<br/>LOW_LATENCY, TX_HIGH
        Advertise-->>Srv: ✅ ADVERTISING_STARTED
        Srv->>WD: scheduleWatchdog(60s)
        Srv->>Srv: scheduleHeartbeat(15s)
    end

    %% ═══ PHASE 2: BLE CONNECTION ═══
    rect rgb(230, 230, 250)
        Note over App, Srv: PHASE 2 — iPhone Scans & Connects
        App->>BLE: start() → scanForPeripherals(serviceUUID)
        BLE->>Advertise: 📶 discovers Watch (RSSI)
        BLE->>GATT: connect(peripheral)
        GATT-->>BLE: ✅ CONNECTED
        BLE->>GATT: discoverServices + discoverCharacteristics
        BLE->>GATT: setNotifyValue(true) for callControl, health, heartbeat
        GATT-->>Srv: onConnectionStateChange(CONNECTED)
        Srv->>ANCS_C: startAncsClient(device)
    end

    %% ═══ PHASE 3: ANCS NOTIFICATION ═══
    rect rgb(255, 245, 230)
        Note over ANCS, Render: PHASE 3 — Notification Forwarding
        ANCS->>ANCS_C: 📩 notification arrives (Messenger, LINE, Phone...)
        ANCS_C->>ANCS_C: AncsParser.decode()
        ANCS_C->>Srv: showAncsNotification(event)

        alt Within 5s & count > 10
            Srv-->>Srv: ⛔ ANCS_GATED (skip — flood protection)
        else Normal
            Srv->>Render: queueLatestNotification(event, dedupeKey)
            Render->>Render: globalThrottle check (5s)
            Render->>Render: shouldSkip? (dedupe 15s)
            Render->>Alert: startActivity(AlertActivity)<br/>FLAG_DISMISS_KEYGUARD
            Render->>Overlay: startService(AlertOverlayService) [fallback]
            Render->>Render: VibrationDebug.vibrate()
            Render->>Render: SoundDebug.playBestEffortAlert()
        end
    end

    %% ═══ PHASE 3b: CUSTOM BLE NOTIFICATION ═══
    rect rgb(255, 240, 240)
        Note over App, Render: PHASE 3b — Custom BLE Notification (from iPhone app)
        App->>App: BridgeProtocol.encode(notification)
        App->>BLE: writeValue(chunks, .withResponse)
        BLE->>GATT: chunked write (160 bytes each)
        GATT->>Srv: onCharacteristicWriteRequest()
        Srv->>Srv: ChunkReassembler.accept()
        Srv->>Srv: BridgeProtocol.decode()
        Srv->>Render: showBridgeNotification(event)
        Render->>Alert: startActivity(AlertActivity)
    end

    %% ═══ PHASE 4: ALERT RENDERING ═══
    rect rgb(255, 235, 235)
        Note over Alert, Overlay: PHASE 4 — Alert Rendering (3-layer strategy)
        Alert->>Alert: KeyguardManager.requestDismissKeyguard()
        Alert->>Alert: FLAG_DISMISS_KEYGUARD + turnScreenOn
        Alert->>Alert: AlertScreen (Compose UI) — source / title / body

        Note over Overlay: Fallback if Samsung blocks Activity
        Overlay->>Overlay: WindowManager.addView()<br/>TYPE_APPLICATION_OVERLAY
        Overlay->>Overlay: Full-screen LinearLayout (direct render)
        Overlay->>Overlay: WakeLock.acquire(30s)
    end

    %% ═══ PHASE 5: DISCONNECT & AUTO-RECONNECT ═══
    rect rgb(245, 245, 245)
        Note over BLE, Srv: PHASE 5 — Disconnect & Auto-Reconnect
        BLE-->>BLE: didDisconnectPeripheral()
        BLE->>BLE: ReconnectController.schedule() → scan()
        Srv-->>Srv: onConnectionStateChange(DISCONNECTED)
        Srv->>ANCS_C: close()
        WD->>Srv: runWatchdog(60s)
        Note over WD: Check: advertisingActive? connectedDevice?
        WD->>Srv: refreshBridge() if needed
    end

    %% ═══ PHASE 6: SURVIVAL ═══
    rect rgb(240, 255, 240)
        Note over Srv, WD: PHASE 6 — Service Survival
        WD->>WD: runWatchdog() every 60s

        alt advertisingActive == false
            WD->>Advertise: startAdvertising() IMMEDIATELY
            Note over WD: WATCHDOG_ADVERTISING_DEAD
        end

        alt connectedDevice == null
            WD->>Advertise: refreshBridge()
        end

        alt service destroyed (by OS)
            Srv->>Srv: onDestroy()
            Srv->>Srv: scheduleServiceRestart()<br/>setExactAndAllowWhileIdle(3s)
            Note over Srv: 🛡️ Restart in 3s even during Doze
            Srv->>Srv: onCreate() → RESTART_COUNT++
        end
    end

    %% ═══ HARDWARE BOUNDARY ═══
    Note over App,Srv: ═══════════ BLE radio ═══════════<br/>Chunk size: 160 bytes<br/>Service UUID: 8f0e7a10-4b6d-4f0b-9c2e-7f4c0a11b001
```

## Layer Architecture

```mermaid
flowchart TB
    subgraph iPhone
        App[Galaxy Bridge iOS App]
        CoreBLE[CoreBluetooth Central]
        ANCS_iOS[ANCS Provider]
        App --> CoreBLE
        ANCS_iOS -.-> CoreBLE
    end

    subgraph Watch["Samsung Galaxy Watch"]
        subgraph Service["Foreground Service"]
            BLE_SRV[BleGattServerService]
            GATT_SRV[GATT Server]
            PROTO[ChunkReassembler<br/>BridgeProtocol]
            ANCS_C[AncsGattClient]
        end

        subgraph Notif["Notification Pipeline"]
            RENDER[WatchNotificationRenderer]
            CHANNEL[NotificationChannel<br/>IMPORTANCE_HIGH]
            FULL_SCREEN[fullScreenIntent]
        end

        subgraph UI["Alert UI (3 Layer)"]
            L1[Layer 1: AlertActivity<br/>FLAG_DISMISS_KEYGUARD]
            L2[Layer 2: startActivity Direct<br/>requestDismissKeyguard]
            L3[Layer 3: AlertOverlayService<br/>WindowManager.addView]
        end

        subgraph Survival["Survival Mechanisms"]
            WD[Watchdog 60s]
            ALARM[AlarmManager<br/>setExactAndAllowWhileIdle]
            BOOT[WatchBootReceiver<br/>BOOT_COMPLETED<br/>PACKAGE_ADDED]
        end

        BLE_SRV --> GATT_SRV
        BLE_SRV --> PROTO
        BLE_SRV --> ANCS_C
        ANCS_C --> RENDER
        PROTO --> RENDER

        RENDER --> CHANNEL
        RENDER --> FULL_SCREEN
        RENDER --> L1
        RENDER --> L2
        RENDER --> L3

        L1 --> L2
        L2 --> L3

        WD --> BLE_SRV
        BOOT --> BLE_SRV
        ALARM --> BLE_SRV
    end

    CoreBLE <-->|"BLE (160 byte chunks)"| GATT_SRV
    ANCS_iOS -.->|"ANCS (passive)"| ANCS_C
```

## State Machine — Watchdog

```mermaid
stateDiagram-v2
    [*] --> Advertising
    Advertising --> Connected : iPhone connects
    Connected --> Advertising : iPhone disconnects

    state Advertising {
        [*] --> Active
        Active --> Dead : BLE advertising<br/>silently stops
        Dead --> Active : Watchdog restart<br/>(60s check)
    }

    state Connected {
        [*] --> ANCS_Ready
        ANCS_Ready --> ANCS_Stale : ANCS client closed
        ANCS_Stale --> ANCS_Ready : recoverAncsHard()
    }

    state ServiceState {
        [*] --> Alive
        Alive --> Killed : OS kill (Doze/Battery)
        Killed --> Alive : AlarmManager<br/>setExactAndAllowWhileIdle<br/>(3s restart)
    }
```

## Data Flow — NotificationEvent

```mermaid
flowchart LR
    subgraph Input
        ANCS[ANCS Notification] --> |"source: com.facebook.Messenger"| PARSE
        BLE[Custom BLE] --> |"source: iPhone app"| PARSE
    end

    PARSE[NotificationEvent<br/>title, body, source] --> CLEAN
    CLEAN[getDisplaySource<br/>getCleanedSource<br/>cleanDisplayText] --> MAP

    subgraph MAP[Source Mapping Table - 40+ apps]
        MSG[Messenger → 💬 Messenger]
        LINE[LINE → 💬 LINE]
        CALL[Phone → 📞 โทรเข้า]
        MAIL[Gmail → 📧 Gmail]
        BANK[K PLUS → 💰 K PLUS]
        UNKNOWN[Unknown → segment.capitalize]
    end

    MAP --> THROTTLE
    THROTTLE[Global Throttle 5s<br/>Dedupe 15s] --> RENDER

    RENDER[3-Layer Rendering] --> L1
    RENDER --> L2
    RENDER --> L3

    RENDER --> VIBRATE[VibrationDebug]
    RENDER --> SOUND[SoundDebug<br/>ToneGenerator<br/>STREAM_ALARM]
```

---

## Key Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `GLOBAL_THROTTLE_MS` | 5,000 | Max 1 alert every 5 seconds |
| `DEDUPE_WINDOW_MS` | 15,000 | Same-key dedupe window |
| `ANC_NEW_CONNECTION_WINDOW_MS` | 5,000 | ANCS flood gate window |
| `MAX_ANC_NEW_NOTIFICATIONS` | 10 | Max ANCS notifications in window |
| `AUTO_DISMISS_MS` | 30,000 | Alert auto-close |
| `WATCHDOG_INTERVAL_MS` | 60,000 | Health check frequency |
| `HEARTBEAT_INTERVAL_MS` | 15,000 | BLE heartbeat |
| `SERVICE_RESTART_DELAY_MS` | 3,000 | After OS kill |

## Test Commands

```bash
# Direct AlertActivity test
adb shell am start -n com.localbridge.watch/.ui.AlertActivity \
  -e alert_title "Test" -e alert_body "Hello" -e alert_source "ADB"

# Overlay fallback test (requires SYSTEM_ALERT_WINDOW permission)
adb shell am startservice -n com.localbridge.watch/.overlay.AlertOverlayService \
  -e alert_title "Overlay" -e alert_body "Direct render" -e alert_source "ADB"

# Automated 8-checkpoint test loop
bash scripts/debug_loop.sh

# Monitor service health
adb logcat -d | grep "GB_DIAG_HEARTBEAT\|WATCHDOG_ADVERTISING_DEAD\|SERVICE_RESTARTED"