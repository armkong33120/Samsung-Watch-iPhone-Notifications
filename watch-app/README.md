# Galaxy Bridge Watch App

Wear OS Kotlin app that advertises the custom Galaxy Bridge BLE GATT service and acts as the BLE peripheral.

## Build

Open `watch-app` in Android Studio, install a JDK and Android SDK 36, then sync Gradle.

Expected target:

- `compileSdk 36`
- `minSdk 30`
- Kotlin + Compose for Wear

This shell currently does not have a Java runtime or `adb`, so CLI build/install could not be verified here.

## Install

```bash
adb connect 192.168.1.167:46127
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Runtime

The app requests Bluetooth, notification, activity, and body sensor permissions. After permissions are granted, it starts `BleGattServerService` as a foreground service with `connectedDevice|health`, advertises `GalaxyBridge`, publishes heartbeat/health notifications, and accepts test notification/call envelopes from the iPhone.
