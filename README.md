# 📱→⌚ Samsung Watch + iPhone Notifications

[![Platform](https://img.shields.io/badge/Watch-Samsung%20Galaxy%20Watch%204%20Classic-blue)](https://github.com/armkong33120/Samsung-Watch-iPhone-Notifications)
[![iOS](https://img.shields.io/badge/iOS-26.5+-lightgrey)](https://github.com/armkong33120/Samsung-Watch-iPhone-Notifications)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Forward ALL iPhone notifications to your Samsung Galaxy Watch — LINE, Messenger, WhatsApp, โทรศัพท์, SMS, อีเมล, แจ้งเตือนธนาคาร — แสดงผลเต็มจอ พร้อมเสียง + สั่น**

> กดติดตั้ง → เปิด Bluetooth → รับแจ้งเตือน iPhone บน Galaxy Watch ได้ทันที  
> 🧪 **Beta** — ทดสอบบน Samsung Galaxy Watch 4 Classic (SM-R895F) / One UI Watch 8 / Android 16 / iOS 26.5

### 🔍 Keywords
`iphone notifications on samsung watch` · `connect galaxy watch to iphone` · `samsung watch ios notification bridge` · `galaxy watch receive iphone messages` · `ใช้ samsung watch กับ iphone แจ้งเตือน`

## 📦 ติดตั้ง

### Watch (Wear OS 6 / Android 16)
1. เปิด **Settings → Developer Options → Wireless debugging**
2. เสียบ ADB: `adb connect <watch-ip>:37773`
3. ติดตั้ง APK: `adb install watch-app/app/build/outputs/apk/debug/app-debug.apk`
4. เปิดแอพ Galaxy Bridge บนนาฬิกา

### iPhone
1. เปิด Xcode → `ios-app/GalaxyBridge.xcodeproj`
2. Build & Run บน iPhone
3. เปิดแอพ Galaxy Bridge → รอให้เชื่อมต่ออัตโนมัติ

## ⚙️ การตั้งค่าที่จำเป็น

| Setting | วิธีเปิด |
|---------|----------|
| **Display over other apps** | Settings → Apps → Galaxy Bridge → Allow |
| **Notification Access** | Settings → Apps → Special Access → Notification Access → Galaxy Bridge |
| **Battery Optimization** | Settings → Apps → Galaxy Bridge → Battery → Unrestricted |

## 🛠️ Development

```bash
# Build Watch APK
cd watch-app && ./gradlew assembleDebug

# Auto-test (Watch only)
bash scripts/debug_loop.sh

# ทดสอบ AlertActivity โดยตรง
adb shell am start -n com.localbridge.watch/.ui.AlertActivity \
  -e alert_title "Test" -e alert_body "Hello" -e alert_source "ADB"

# ทดสอบ Overlay fallback
adb shell am startservice -n com.localbridge.watch/.overlay.AlertOverlayService \
  -e alert_title "Overlay" -e alert_body "Direct render" -e alert_source "ADB"
```

## 🏗️ Architecture

```
iPhone (Swift/CoreBluetooth)          Watch (Kotlin/Wear OS 6)
┌──────────────────────────┐          ┌──────────────────────────────────┐
│ BleCentralManager        │  BLE     │ BleGattServerService (GATT Srv)  │
│ → chunks 160 bytes       │ ───────→ │ → ChunkReassembler              │
│ → BridgeProtocol.encode  │          │ → BridgeProtocol.decode          │
│                          │          │ → NotificationEvent               │
│ ANCS (Messenger/โทร)     │  ANCS    │ → WatchNotificationRenderer.post()│
│                          │ ───────→ │   → Notification + fullScreenIntent│
└──────────────────────────┘          │   → AlertActivity (full-screen)  │
                                      │   → WakeLock (หน้าจอสว่าง)        │
                                      └──────────────────────────────────┘
```

## ⚠️ Known Issues

- **Samsung One UI Watch เท่านั้น** — ทดสอบบน SM-R895F
- **iPhone ต้องเปิดแอพครั้งแรก** — หลังจากนั้น auto-reconnect
- **Samsung thermal throttle** — ถ้านาฬิการ้อน (ชาร์จ) → BLE advertising หลุด → Watchdog restart อัตโนมัติ

## 📝 License

MIT — ใช้ฟรี, แก้ฟรี, แจกฟรี