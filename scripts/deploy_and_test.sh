#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${PROJECT_ROOT:-/Users/arm/Resume_CV_F}"
TEAM_ID="${TEAM_ID:?TEAM_ID is required, for example: TEAM_ID=86XHS5PNPM}"
IPHONE_DEVICE_ID="${IPHONE_DEVICE_ID:-00008110-0008395E3442801E}"
WATCH_ADB_IP="${WATCH_ADB_IP:-192.168.1.167}"
WATCH_ADB_PORT="${WATCH_ADB_PORT:-}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:-com.localbridge.galaxybridge}"
WATCH_PACKAGE="${WATCH_PACKAGE:-com.localbridge.watch}"

IOS_PROJECT="$PROJECT_ROOT/ios-app/GalaxyBridge.xcodeproj"
IOS_SCHEME="GalaxyBridge"
IOS_DERIVED_DATA="$PROJECT_ROOT/build/ios-derived-data"
IOS_BUILD_DIR="$IOS_DERIVED_DATA/Build/Products/Debug-iphoneos"
WATCH_PROJECT="$PROJECT_ROOT/watch-app"
WATCH_APK="$WATCH_PROJECT/app/build/outputs/apk/debug/app-debug.apk"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ANDROID_STUDIO_JAVA_HOME="${ANDROID_STUDIO_JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

if [[ -z "${JAVA_HOME:-}" && -x "$ANDROID_STUDIO_JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JAVA_HOME"
fi

if [[ -d "$ANDROID_SDK_ROOT/platform-tools" ]]; then
  export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
fi

log() {
  printf '\n==> %s\n' "$*" >&2
}

die() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

gradle_cmd() {
  if [[ -x "$WATCH_PROJECT/gradlew" ]]; then
    printf '%s\n' "$WATCH_PROJECT/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    printf '%s\n' "gradle"
  else
    die "Missing Gradle. Install Android Studio/Gradle or add a Gradle wrapper to watch-app."
  fi
}

build_ios() {
  require_cmd xcodebuild
  local log_file
  log_file="$(mktemp -t galaxybridge-xcodebuild.XXXXXX.log)"

  log "Building iOS app for device $IPHONE_DEVICE_ID"
  if ! xcodebuild \
    -project "$IOS_PROJECT" \
    -target "$IOS_SCHEME" \
    -configuration Debug \
    -sdk iphoneos \
    CONFIGURATION_BUILD_DIR="$IOS_BUILD_DIR" \
    DEVELOPMENT_TEAM="$TEAM_ID" \
    PRODUCT_BUNDLE_IDENTIFIER="$IOS_BUNDLE_ID" \
    CODE_SIGN_STYLE=Automatic \
    -allowProvisioningUpdates \
    build 2>&1 | tee "$log_file"; then
    if grep -Eq 'iOS [0-9.]+ is not installed|platform.*is not installed|Unable to find a destination matching' "$log_file"; then
      die "xcodebuild cannot use iPhone $IPHONE_DEVICE_ID. Open Xcode > Settings > Components and install the iOS platform/device support shown above, then rerun this script."
    fi
    die "iOS build failed. Full xcodebuild log: $log_file"
  fi
}

find_ios_app() {
  local app_path
  app_path="$(find "$IOS_DERIVED_DATA/Build/Products" -path "*/$IOS_SCHEME.app" -type d -print -quit 2>/dev/null || true)"
  [[ -n "$app_path" ]] || die "Could not find built iOS app under $IOS_DERIVED_DATA"
  printf '%s\n' "$app_path"
}

install_ios() {
  require_cmd xcrun
  local app_path
  app_path="$(find_ios_app)"

  log "Installing iOS app: $app_path"
  xcrun devicectl device install app --device "$IPHONE_DEVICE_ID" "$app_path"

  log "Launching iOS app: $IOS_BUNDLE_ID"
  xcrun devicectl device process launch --device "$IPHONE_DEVICE_ID" "$IOS_BUNDLE_ID" || true
}

build_watch() {
  require_cmd java
  local gradle
  gradle="$(gradle_cmd)"

  log "Building Watch APK"
  if [[ "$gradle" == "$WATCH_PROJECT/gradlew" ]]; then
    (cd "$WATCH_PROJECT" && ./gradlew :app:assembleDebug)
  else
    (cd "$WATCH_PROJECT" && gradle :app:assembleDebug)
  fi

  [[ -f "$WATCH_APK" ]] || die "Expected APK not found: $WATCH_APK"
}

adb_connected_to_watch() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | grep -q "^$WATCH_ADB_IP:"
}

candidate_adb_ports_from_mdns() {
  adb mdns services 2>/dev/null | awk -v ip="$WATCH_ADB_IP" '
    /_adb-tls-connect\._tcp/ && $0 ~ ip {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+$/) print $i
        if ($i ~ ip ":[0-9]+") {
          sub(".*:", "", $i)
          print $i
        }
      }
    }
  ' | sort -n | uniq
}

candidate_adb_ports_from_probe() {
  require_cmd nc
  local ports=(
    5555
    37099 37101 37103 37105 37107 37109
    40000 41000 42000 43000 44000 45000 46000 46127 47000 48000 49000
  )
  local port

  if [[ -n "$WATCH_ADB_PORT" ]]; then
    printf '%s\n' "$WATCH_ADB_PORT"
  fi

  for port in "${ports[@]}"; do
    if nc -G 1 -z "$WATCH_ADB_IP" "$port" >/dev/null 2>&1; then
      printf '%s\n' "$port"
    fi
  done

  # Wireless debugging commonly uses high ephemeral ports. Probe in parallel,
  # but keep the timeout low so a missing watch does not stall the whole script.
  for start in 30000 35000 40000 45000 50000 55000 60000 65000; do
    local end=$((start + 999))
    if (( end > 65535 )); then
      end=65535
    fi
    for port in $(seq "$start" "$end"); do
      (
        nc -G 1 -z "$WATCH_ADB_IP" "$port" >/dev/null 2>&1 && printf '%s\n' "$port"
        true
      ) &
      if (( $(jobs -pr | wc -l | tr -d ' ') >= 128 )); then
        wait || true
      fi
    done
    wait || true
  done | sort -n | uniq
}

detect_watch_adb_port() {
  local port

  if adb_connected_to_watch; then
    adb devices | awk -v ip="$WATCH_ADB_IP" 'NR > 1 && $2 == "device" && $1 ~ "^" ip ":" { sub(".*:", "", $1); print $1; exit }'
    return 0
  fi

  if [[ -n "$WATCH_ADB_PORT" ]]; then
    log "Trying provided ADB port $WATCH_ADB_PORT for $WATCH_ADB_IP"
    if adb connect "$WATCH_ADB_IP:$WATCH_ADB_PORT" | grep -Eqi 'connected|already connected'; then
      printf '%s\n' "$WATCH_ADB_PORT"
      return 0
    fi
  fi

  log "Trying ADB mDNS discovery for $WATCH_ADB_IP"
  while read -r port; do
    [[ -n "$port" ]] || continue
    if adb connect "$WATCH_ADB_IP:$port" | grep -Eqi 'connected|already connected'; then
      printf '%s\n' "$port"
      return 0
    fi
  done < <(candidate_adb_ports_from_mdns)

  log "mDNS did not find the watch; probing candidate TCP ports on $WATCH_ADB_IP"
  while read -r port; do
    [[ -n "$port" ]] || continue
    if adb connect "$WATCH_ADB_IP:$port" | grep -Eqi 'connected|already connected'; then
      printf '%s\n' "$port"
      return 0
    fi
  done < <(candidate_adb_ports_from_probe)

  return 1
}

install_watch() {
  require_cmd adb

  local port
  port="$(detect_watch_adb_port)" || die "Could not auto-detect ADB port for $WATCH_ADB_IP. Confirm wireless debugging is enabled and paired."

  log "Installing Watch APK on $WATCH_ADB_IP:$port"
  adb -s "$WATCH_ADB_IP:$port" install -r "$WATCH_APK"

  log "Launching Watch app package $WATCH_PACKAGE"
  adb -s "$WATCH_ADB_IP:$port" shell monkey -p "$WATCH_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
}

smoke_test_watch() {
  require_cmd adb
  local serial
  serial="$(adb devices | awk -v ip="$WATCH_ADB_IP" 'NR > 1 && $2 == "device" && $1 ~ "^" ip ":" { print $1; exit }')"
  [[ -n "$serial" ]] || die "Watch is not connected over ADB"

  log "Checking Watch package install"
  adb -s "$serial" shell pm path "$WATCH_PACKAGE" >/dev/null
}

main() {
  cd "$PROJECT_ROOT"

  build_ios
  install_ios

  build_watch
  install_watch
  smoke_test_watch

  log "Deploy and smoke test complete"
}

main "$@"
