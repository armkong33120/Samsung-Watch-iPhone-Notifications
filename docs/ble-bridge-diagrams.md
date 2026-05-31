# Galaxy Bridge — BLE Architecture (L0–L5)

> ทุnode อ้างอิง file:line จริงจากโค้ด — ไม่มีการ invent

---

## L0 — System Context

```mermaid
flowchart LR
    iPhone["📱 iPhone\nBleCentralManager.swift"]
    Watch["⌚ Galaxy Watch 4 Classic\nSM-R895F / One UI Watch 8"]
    ANCS["📡 ANCS Provider\niOS System"]

    iPhone <-->|"BLE (Custom Bridge)\nSERVICE_UUID: 8F0E7A10\nChunk: 160 bytes [BridgeProtocol.kt:28]"| Watch
    ANCS -.->|"ANCS (Passive)\nSERVICE_UUID: 7905F431 [AncsProfile.kt:6]"| Watch
```

| Actor | Platform | Source File |
|-------|----------|-------------|
| `BleCentralManager` | iOS | `ios-app/GalaxyBridge/Bridge/BleCentralManager.swift` |
| `BleGattServerService` | Watch | `watch-app/.../ble/BleGattServerService.kt:40` |
| `AncsGattClient` | Watch | `watch-app/.../ble/ancs/AncsGattClient.kt:22` |
| ANCS Provider | iOS System | Built-in Apple Notification Center Service |

---

## L1 — Component Architecture

```mermaid
flowchart TB
    subgraph iPhone["iPhone (iOS)"]
        App["Galaxy Bridge App\n(BleCentralManager)"]
        CoreBLE["CBCentralManager\n(CoreBluetooth)"]
        Reconnect["ReconnectController\n.swift:schedule()→scan()"]
        App --> CoreBLE
        App --> Reconnect
    end

    subgraph Watch["Galaxy Watch (Wear OS / One UI 8)"]
        subgraph Service["Foreground Service"]
            Srv["BleGattServerService\n.kt:40"]
            GATT["BluetoothGattServer"]
            Proto["ChunkReassembler\nBridgeProtocol.kt:65"]
        end

        subgraph ANCS_Sub["ANCS Client"]
            ANCS_C["AncsGattClient\n.kt:22"]
            Parser["AncsParser"]
        end

        subgraph Pipeline["Notification Pipeline"]
            Render["WatchNotificationRenderer\n.kt:28"]
            Notif["NotificationManager"]
        end

        subgraph UI["Alert UI"]
            L1["AlertActivity\nFLAG_DISMISS_KEYGUARD"]
            L2["AlertOverlayService\nWindowManager.addView()"]
        end

        subgraph Survival["Survival"]
            WD["Watchdog 60s\n.kt:395"]
            Alarm["AlarmManager\nAllowWhileIdle 3s\n.kt:128"]
            Boot["WatchBootReceiver\n.kt:10"]
        end

        Srv --> GATT
        Srv --> Proto
        Srv --> ANCS_C
        ANCS_C --> Parser
        ANCS_C --> Render
        Proto --> Render
        Render --> Notif
        Render --> L1
        Render --> L2
        L1 --> L2
        WD --> Srv
        Alarm --> Srv
        Boot --> Srv
    end

    CoreBLE <-->|"BLE"| GATT
```

---

## L2 — Connection Flow

```mermaid
sequenceDiagram
    autonumber
    participant Boot as ⚡ WatchBootReceiver
    participant Srv as 🟢 BleGattServerService
    participant Adv as 📶 BLE Advertiser
    participant GATT as 📥 BluetoothGattServer
    participant iOS as 🔵 CBCentralManager (iPhone)
    participant ANCS as 📡 AncsGattClient

    rect rgb(230, 245, 230)
        Note over Boot, Adv: Startup [BleGattServerService.kt:61-82]
        Boot->>Srv: startForegroundService(intent) [WatchBootReceiver.kt:17]
        Srv->>Srv: startForeground(1001) [.kt:69]
        Srv->>Srv: openGattServer() [.kt:139]
        Srv->>Adv: startAdvertising(LOW_LATENCY, TX_HIGH) [.kt:156-174]
        Adv-->>Srv: onStartSuccess() [.kt:548-551]
    end

    rect rgb(230, 230, 250)
        Note over iOS, GATT: iPhone Scan & Connect [BleCentralManager.swift:81-280]
        iOS->>Adv: scanForPeripherals(serviceUUID) [.swift:85]
        Adv-->>iOS: didDiscover(peripheral, RSSI) [.swift:204]
        iOS->>GATT: connect(peripheral) [.swift:211]
        GATT-->>iOS: didConnect [.swift:214]
        GATT-->>Srv: onConnectionStateChange(CONNECTED) [.kt:184-189]
        Srv->>ANCS: startAncsClient(device) [.kt:281]
        iOS->>GATT: discoverServices + discoverCharacteristics [.swift:219,249-255]
        iOS->>GATT: setNotifyValue(true) ×3 [.swift:271-275]
    end

    rect rgb(245, 240, 255)
        Note over ANCS: ANCS Subscribe [AncsGattClient.kt:89-357]
        ANCS->>ANCS: start(device) [.kt:89]
        alt BOND_BONDED
            ANCS->>ANCS: connectGatt() directly [.kt:106-110]
        else
            ANCS->>ANCS: createBond() → BondReceiver → connectGatt() [.kt:119-135]
        end
        ANCS->>ANCS: discoverServices() → getService(ANCS) [.kt:264-215]
        ANCS->>ANCS: writeDescriptor(ENABLE_NOTIFICATION) [.kt:338-348]
        ANCS->>ANCS: markReady() [.kt:351-357]
    end
```

---

## L3 — ANCS Notification → Alert

```mermaid
sequenceDiagram
    autonumber
    participant ANCS_iOS as 📡 ANCS Provider (iOS)
    participant ANCS_C as 📡 AncsGattClient
    participant Parser as 🔍 AncsParser
    participant Srv as 🟢 BleGattServerService
    participant Render as 🔔 WatchNotificationRenderer
    participant Alert as 🖥️ AlertActivity
    participant Vib as 📳 VibrationDebug
    participant Snd as 🔊 SoundDebug

    rect rgb(255, 245, 230)
        Note over ANCS_iOS, Snd: ANCS Notification → 3-Layer Alert
        ANCS_iOS->>ANCS_C: onCharacteristicChanged(NOTIFICATION_SOURCE) [AncsGattClient.kt:250-253]
        ANCS_C->>Parser: parseNotificationSource(value) [.kt:401]
        ANCS_C->>ANCS_C: handleNotificationSource(event) [.kt:400-427]

        alt eventId != Added
            Note over ANCS_C: ignored (Modified/Removed) [.kt:412-415]
        else Added
            ANCS_C->>ANCS_C: requestAttributes(uid) [.kt:430-445]
            ANCS_C->>ANCS_C: writeCharacteristic(controlPoint) [.kt:439-443]
            ANCS_iOS-->>ANCS_C: onCharacteristicChanged(DATA_SOURCE) [.kt:250-253]
            ANCS_C->>Parser: parseDataSourceIncremental(value) [.kt:451]
            ANCS_C->>Srv: onNotification(NotificationEvent) [.kt:464-470]
            Note over ANCS_C: title=attrs.title<br/>body=attrs.message<br/>source="ANCS: {appIdentifier}"
        end
    end

    rect rgb(255, 235, 235)
        Note over Srv, Snd: Notification Pipeline [WatchNotificationRenderer.kt:62-156]
        Srv->>Render: showAncsNotification(event) [BleGattServerService.kt:290-306]

        alt ANCS gate: within 5s & count > 10
            Note over Render: ⛔ ANCS_GATED [.kt:302-305]
        else pass gate
            alt globalThrottle: within 5s [WatchNotificationRenderer.kt:72-76]
                Note over Render: ⛔ WATCH_NOTIFICATION_SKIPPED reason=global_throttle
            else
                alt dedupe: within 15s same-key [.kt:78-81]
                    Note over Render: ⛔ WATCH_NOTIFICATION_SKIPPED reason=duplicate
                else
                    Render->>Render: getDisplaySource() → map bundleID → emoji [NotificationEvent.kt:28-43]
                    Render->>Alert: startActivity(AlertActivity) [.kt:127-133]
                    Alert->>Alert: FLAG_DISMISS_KEYGUARD [AlertActivity.kt:26]
                    Alert->>Alert: requestDismissKeyguard() [.kt:46-50]
                    Render->>Vib: vibrate([0,500,250,500]) [.kt:148]
                    Render->>Snd: playBestEffortAlert() [.kt:151]
                    Snd->>Snd: check ringerMode [SoundDebug.kt:158-164]
                end
            end
        end
    end
```

---

## L4 — Custom BLE Notification (Chunked Write)

```mermaid
sequenceDiagram
    autonumber
    participant App as 📱 BleCentralManager
    participant iOS_BLE as 🔵 CBPeripheral
    participant Srv as 🟢 BleGattServerService
    participant Proto as 📦 ChunkReassembler
    participant Render as 🔔 WatchNotificationRenderer

    rect rgb(255, 240, 240)
        Note over App, Render: Custom BLE Notification [BridgeProtocol.kt:28-62, BleGattServerService.kt:207-278]
        App->>App: BridgeProtocol.chunk(envelope)<br/>max 160 bytes each [BridgeProtocol.kt:46-62]
        App->>iOS_BLE: writeValue(chunk, .withResponse) [BleCentralManager.swift:139]
        iOS_BLE->>Srv: onCharacteristicWriteRequest(NOTIFY_EVENTS) [BleGattServerService.kt:207-228]

        loop until all chunks received
            Srv->>Proto: ChunkReassembler.accept(bytes) [BridgeProtocol.kt:81-119]
            alt !json.optBoolean("chunk")
                Note over Proto: plain (non-chunked) payload
            else chunk
                Proto->>Proto: store seq in chunks[id]
                alt map.size != total
                    Note over Proto: waiting for more [.kt:109]
                else all chunks arrived
                    Proto->>Proto: Base64 fold → merged bytes [.kt:111-118]
                    Proto-->>Srv: merged complete envelope
                end
            end
        end

        Srv->>Srv: BridgeProtocol.decode(merged) → BridgeEnvelope [.kt:30-39]
        Srv->>Srv: handleEnvelope(envelope) [.kt:261-278]

        alt type == "notification"
            Srv->>Render: showBridgeNotification(event) [.kt:265-270]
        else type == "call"
            Note over Srv: BridgeUiState.latestEvent [.kt:272-274]
        else type == "heartbeat"
            Note over Srv: BridgeUiState.connectionLabel [.kt:276]
        end
    end
```

---

## L5 — Survival & Error Recovery

```mermaid
sequenceDiagram
    autonumber
    participant OS as 🤖 Android OS
    participant Srv as 🟢 BleGattServerService
    participant WD as 🛡️ Watchdog (60s)
    participant Adv as 📶 BLE Advertiser
    participant Alarm as ⏰ AlarmManager
    participant Boot as ⚡ WatchBootReceiver
    participant ANCS as 📡 AncsGattClient

    rect rgb(240, 255, 240)
        Note over WD, Adv: Watchdog Loop [BleGattServerService.kt:395-397]

        loop every 60 seconds
            WD->>WD: runWatchdog() [.kt:395]

            alt advertisingActive == false
                WD->>Adv: startAdvertising() IMMEDIATELY [.kt:400-403]
                Note over WD: WATCHDOG_ADVERTISING_DEAD — restarting immediately
            end

            alt connectedDevice == null && cooldown passed
                WD->>Adv: refreshBridge() [.kt:406-413]
            end

            alt ANCS snapshot.closed or null
                WD->>ANCS: startAncsClient(device) [.kt:416-421]
            end
        end
    end

    rect rgb(255, 245, 235)
        Note over OS, Alarm: Service Killed [BleGattServerService.kt:109-139]

        OS->>Srv: onDestroy() [.kt:109-120]
        Srv->>ANCS: close("service destroy") [.kt:115]
        Srv->>Adv: stopAdvertising() [.kt:117]
        Srv->>GATT: gattServer?.close() [.kt:118]

        Srv->>Alarm: scheduleServiceRestart() [.kt:128-139]
        Note over Alarm: setExactAndAllowWhileIdle(3s)<br/>🛡️ Restart even during Doze [.kt:136]

        Alarm->>Srv: onCreate() → RESTART_COUNT++ [.kt:67-68]
        Srv->>Srv: startForeground + openGattServer + startAdvertising
    end

    rect rgb(245, 250, 255)
        Note over Boot: Device Boot [WatchBootReceiver.kt:10-36]
        Boot->>Srv: startForegroundService(intent) [.kt:17]
        Note over Boot: Actions: BOOT_COMPLETED, PACKAGE_ADDED,<br/>MY_PACKAGE_REPLACED, TRIGGER_ALERT [.kt:30-35]
    end

    rect rgb(255, 240, 240)
        Note over ANCS: ANCS Recovery [AncsGattClient.kt:369-391]
        alt soft recovery
            ANCS->>ANCS: recoverSoft(reason) [.kt:369-381]
            ANCS->>ANCS: resubscribe notification source
        else hard recovery
            ANCS->>ANCS: recoverHard(reason) [.kt:384-391]
            ANCS->>ANCS: close() → postDelayed(1500ms) → start() [.kt:389]
        end
    end
```

---

## Key Constants (All Levels)

| Constant | Value | Source |
|----------|-------|--------|
| `CHUNK_SIZE` | 160 bytes | `BridgeProtocol.kt:28` |
| `GLOBAL_THROTTLE_MS` | 5,000 | `WatchNotificationRenderer.kt:271` |
| `DEDUPE_WINDOW_MS` | 15,000 | `WatchNotificationRenderer.kt:272` |
| `ANC_NEW_CONNECTION_WINDOW_MS` | 5,000 | `BleGattServerService.kt:597` |
| `MAX_ANC_NEW_NOTIFICATIONS` | 10 | `BleGattServerService.kt:598` |
| `WATCHDOG_INTERVAL_MS` | 60,000 | `BleGattServerService.kt:581` |
| `HEARTBEAT_INTERVAL_MS` | 15,000 | `BleGattServerService.kt:579` |
| `AUTO_DISMISS_MS` | 30,000 | `AlertActivity.kt:94` |
| `SERVICE_RESTART_DELAY_MS` | 3,000 | `BleGattServerService.kt:136` |
| `BOND_TIMEOUT_MS` | 30,000 | `AncsProfile.kt:29` |
| `CONNECT_TIMEOUT_MS` | 15,000 | `AncsProfile.kt:28` |
| `VIBRATION_PATTERN` | `[0,500,250,500]` | `WatchNotificationRenderer.kt:276` |

## BLE UUIDs

| Name | UUID | Source |
|------|------|--------|
| `SERVICE_UUID` | `8F0E7A10-4B6D-4F0B-9C2E-7F4C0A11B001` | `BridgeGattProfile.kt:6` |
| `NOTIFY_EVENTS_UUID` | `8F0E7A11...` | `BridgeGattProfile.kt:7` |
| `CALL_CONTROL_UUID` | `8F0E7A12...` | `BridgeGattProfile.kt:8` |
| `HEALTH_SAMPLES_UUID` | `8F0E7A13...` | `BridgeGattProfile.kt:9` |
| `HEARTBEAT_UUID` | `8F0E7A14...` | `BridgeGattProfile.kt:10` |
| ANCS `SERVICE_UUID` | `7905F431...` | `AncsProfile.kt:6` |
| ANCS `NOTIFICATION_SOURCE_UUID` | `9FBF120D...` | `AncsProfile.kt:7` |
| ANCS `CONTROL_POINT_UUID` | `69D1D8F3...` | `AncsProfile.kt:8` |
| ANCS `DATA_SOURCE_UUID` | `22EAC6E9...` | `AncsProfile.kt:9` |

## Test Commands

```bash
# Render L0-L5 diagrams
# Option 1: Open in browser
open https://mermaid.live

# Option 2: Use mermaid CLI (install: npm i -g @mermaid-js/mermaid-cli)
cd docs
for lvl in L0 L1 L2 L3 L4 L5; do
  mmdc -i ble-bridge-diagrams.md -o ble-bridge-${lvl}.png
done

# Option 3: Use mermaid.ink API
# Extract each diagram block and POST to https://mermaid.ink/generate