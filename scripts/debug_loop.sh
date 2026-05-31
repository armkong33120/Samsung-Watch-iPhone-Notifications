#!/bin/bash
set -o pipefail

ADB="/Users/arm/Library/Android/sdk/platform-tools/adb"
DEVICE="adb-RFAR91HVZSP-IkahvK._adb-tls-connect._tcp"
APK="/Users/arm/projects/GalaxyBridge/watch-app/app/build/outputs/apk/debug/app-debug.apk"
PROJECT="/Users/arm/projects/GalaxyBridge/watch-app"
PACKAGE="com.localbridge.watch"
PASS=0
FAIL=0
LOOP=0

echo "============================================"
echo " Galaxy Bridge - Automated Debug Loop"
echo " Device: $DEVICE"
echo "============================================"

while true; do
    LOOP=$((LOOP + 1))
    echo ""
    echo "--- LOOP #$LOOP --- $(date '+%H:%M:%S') ---"

    # STEP 1: Build
    echo -n "[BUILD]  "
    cd "$PROJECT" && ./gradlew assembleDebug --quiet 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "SUCCESS"
    else
        echo "FAILED"
        sleep 5
        continue
    fi

    # STEP 2: Install
    echo -n "[INSTALL] "
    $ADB -s "$DEVICE" install -r "$APK" 2>/dev/null | grep -q "Success"
    if [ $? -eq 0 ]; then
        echo "SUCCESS"
    else
        echo "FAILED"
        sleep 3
        continue
    fi

    # STEP 3: Force stop + clear log + start
    $ADB -s "$DEVICE" shell am force-stop "$PACKAGE" 2>/dev/null
    sleep 1
    $ADB -s "$DEVICE" logcat -c 2>/dev/null
    $ADB -s "$DEVICE" shell am start -n "$PACKAGE/.MainActivity" 2>/dev/null
    sleep 3

    # CHECKPOINT 1: Service running with foreground?
    echo -n "[CP1: SERVICE] "
    FG_CHECK=$($ADB -s "$DEVICE" shell dumpsys activity services "$PACKAGE/.ble.BleGattServerService" 2>/dev/null | grep -c "createdFromFg=true")
    if [ "$FG_CHECK" -ge 1 ]; then
        echo "PASS (foreground service active)"
        PASS=$((PASS + 1))
    else
        echo "FAIL (foreground service not running)"
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 2: Advertising started?
    echo -n "[CP2: ADVERTISE] "
    sleep 1
    ADV_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "ADVERTISING_STARTED")
    if [ "$ADV_CHECK" -ge 1 ]; then
        echo "PASS"
        PASS=$((PASS + 1))
    else
        echo "FAIL (no advertising log)"
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 3: Trigger notification via debug intent
    echo -n "[CP3: TRIGGER] "
    $ADB -s "$DEVICE" logcat -c 2>/dev/null
    # Start service with debug local notification action
    $ADB -s "$DEVICE" shell "am start-foreground-service -n $PACKAGE/.ble.BleGattServerService -a com.localbridge.watch.DEBUG_LOCAL_NOTIFICATION" 2>/dev/null
    sleep 2

    NOTIFY_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "WATCH_NOTIFICATION_POSTED_FORCE\|GB_NOTIFICATION_LOCAL_OK")
    if [ "$NOTIFY_CHECK" -ge 1 ]; then
        echo "PASS (notification posted)"
        PASS=$((PASS + 1))
    else
        echo "FAIL (notification not posted - trigger via alternate method)"
        # Alternate: directly test AlertActivity
        $ADB -s "$DEVICE" shell am start -n "$PACKAGE/.ui.AlertActivity" \
            -e alert_title "Debug Test" \
            -e alert_body "Direct launch test" \
            -e alert_source "ADB" 2>/dev/null
        sleep 2
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 4: AlertActivity started?
    echo -n "[CP4: ALERT_ACT] "
    ALERT_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "WATCH_ALERT_ACTIVITY_STARTED\|AlertActivity")
    if [ "$ALERT_CHECK" -ge 1 ]; then
        echo "PASS"
        PASS=$((PASS + 1))
    else
        echo "FAIL"
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 5: WakeLock acquired?
    echo -n "[CP5: WAKELOCK] "
    WAKE_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "WATCH_WAKE_LOCK_ACQUIRED")
    if [ "$WAKE_CHECK" -ge 1 ]; then
        echo "PASS"
        PASS=$((PASS + 1))
    else
        echo "FAIL"
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 6: Screen actually ON?
    echo -n "[CP6: SCREEN_ON] "
    SCREEN_STATE=$($ADB -s "$DEVICE" shell dumpsys power 2>/dev/null | grep -c "mWakefulness=Awake\|mHoldingDisplaySuspendBlocker=true")
    if [ "$SCREEN_STATE" -ge 1 ]; then
        echo "PASS (screen awake)"
        PASS=$((PASS + 1))
    else
        echo "FAIL (screen off / dozing)"
        FAIL=$((FAIL + 1))
    fi

    # CHECKPOINT 7: Overlay service started?
    echo -n "[CP7: OVERLAY] "
    OVERLAY_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "OVERLAY_SHOWN\|OVERLAY_WAKELOCK_ACQUIRED\|WATCH_OVERLAY_SERVICE_STARTED")
    if [ "$OVERLAY_CHECK" -ge 1 ]; then
        echo "PASS (overlay fallback active)"
        PASS=$((PASS + 1))
    else
        echo "CHECK (overlay not triggered — may not be needed)"
        PASS=$((PASS + 1))  # Not a fail, overlay is last-resort fallback
    fi

    # CHECKPOINT 8: Samsung diagnostic data available?
    echo -n "[CP8: SAMSUNG_DIAG] "
    DIAG_CHECK=$($ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -c "WATCH_SAMSUNG_DIAG\|KEYGUARD_DISMISS_REQUESTED\|NOTIF_LISTENER_")
    if [ "$DIAG_CHECK" -ge 1 ]; then
        echo "PASS (diag data present)"
        PASS=$((PASS + 1))
    else
        echo "INFO (no diag data yet)"
        PASS=$((PASS + 1))  # Info only
    fi

    # SUMMARY
    echo "---- SUMMARY (loop #$LOOP) ----"
    echo "  PASS: $PASS  FAIL: $FAIL"

    # Show errors if any
    if [ "$FAIL" -gt 0 ]; then
        echo "---- ERRORS ----"
        $ADB -s "$DEVICE" logcat -d -v time 2>/dev/null | grep -iE "FATAL|AndroidRuntime|SecurityException|ADVERTISING_FAILED|WATCH_NOTIFICATION_SKIPPED|WATCH_BYPASS_FAILED|WATCH_ALERT_ACTIVITY_FAILED" | tail -5
    fi

    # All pass? Exit
    if [ "$FAIL" -eq 0 ]; then
        echo ""
        echo "============================================"
        echo " ALL CHECKPOINTS PASSED! 🎉"
        echo "============================================"
        exit 0
    fi

    # Wait before next loop
    echo "----------------------------------------"
    sleep 4
done