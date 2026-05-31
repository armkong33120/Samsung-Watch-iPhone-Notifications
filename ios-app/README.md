# Galaxy Bridge iOS App

SwiftUI iOS 17+ app that scans for the custom Galaxy Bridge BLE GATT service and acts as the BLE central.

## Build

Open `ios-app/GalaxyBridge.xcodeproj` in Xcode, select your iPhone 13 Pro Max, set your personal development team, and run.

Capabilities and plist entries are included for:

- CoreBluetooth central background mode: `bluetooth-central`
- HealthKit write entitlement
- Bluetooth and HealthKit usage descriptions

## Runtime

The app scans for service `8F0E7A10-4B6D-4F0B-9C2E-7F4C0A11B001`, connects to the watch, subscribes to call-control, health, and heartbeat notifications, and can send simulated notification/call events.

## Verification Notes

`swiftc -typecheck` passes for the app sources. `xcodebuild` is currently blocked on this machine because Xcode reports missing first-launch/CoreSimulator content and recommends `xcodebuild -runFirstLaunch`.
