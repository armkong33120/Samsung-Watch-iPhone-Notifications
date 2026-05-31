# Galaxy Bridge 🌉

**iPhone notifications → Samsung Galaxy Watch — ใน 1 วิ**

ระบบเชื่อมต่อ iPhone ↔ Samsung Galaxy Watch ผ่าน BLE (Bluetooth Low Energy) แสดงการแจ้งเตือนจาก iOS แบบเต็มจอบนหน้าปัดนาฬิกา พร้อมเสียง + สั่น

> 🧪 **Beta** — ทดสอบบน Samsung Galaxy Watch 4 Classic (SM-R895F) เท่านั้น

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