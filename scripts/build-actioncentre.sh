#!/usr/bin/env bash
# Build the ztmetro Metro Action Centre APK (org.ztmetro.actioncentre) with
# nothing but javac + aapt2 + d8 + apksigner. No Gradle, no AndroidX, no network.
#
#   repo/scripts/build-actioncentre.sh
#
# On a Mac without an Android SDK the script stages the sources on
# hermes-ubuntu (~/ztux/actioncentre), builds there and copies the APK and the
# static-verification evidence back into repo/actioncentre/build/out and
# repo/evidence/actioncentre. On a Linux host with android-36 present it builds
# locally. Override the remote with ACTIONCENTRE_REMOTE.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

APP="${APP_DIR:-$ROOT/actioncentre/app/src/main}"
BUILD="${BUILD_DIR:-$ROOT/actioncentre/build}"
OUT="$BUILD/out"
EVID="$BUILD/evidence"
EVIDENCE="${EVIDENCE_DIR:-$ROOT/evidence/actioncentre}"
# The WP status-strip task reports into its own evidence directory; the common
# APK facts stay in evidence/actioncentre so other tasks keep working.
STATUS_EVIDENCE="${STATUS_STRIP_EVIDENCE_DIR:-$ROOT/evidence/status-strip-overlay}"
APK_NAME="ztmetro-actioncentre.apk"
APK="$OUT/$APK_NAME"
REMOTE="${ACTIONCENTRE_REMOTE:-hermes-ubuntu}"
RWORK="${ACTIONCENTRE_REMOTE_DIR:-ztux/actioncentre}"
MIN_API=29
MAX_BYTES=2097152   # 2 MB

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

resolve_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" /opt/android-sdk; do
        if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-36/android.jar" ]; then
            SDK="$candidate"
            return 0
        fi
    done
    return 1
}

resolve_jdk() {
    local candidate
    for candidate in "${JAVA_HOME:-}" "$HOME/android-jdk" \
                     /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/default-java; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
            JDK="$candidate"
            return 0
        fi
    done
    return 1
}

resolve_tools() {
    resolve_sdk || die "Android SDK platform android-36 not found (set ANDROID_HOME)"
    resolve_jdk || die "JDK with javac not found (set JAVA_HOME)"
    ANDROID_JAR="$SDK/platforms/android-36/android.jar"
    BT="$SDK/build-tools/36.0.0"
    if [ ! -x "$BT/aapt2" ]; then
        BT=$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)
    fi
    local tool
    for tool in aapt2 d8 zipalign apksigner; do
        [ -x "$BT/$tool" ] || die "missing $BT/$tool"
    done
    command -v zip >/dev/null 2>&1 || die "zip is required"
    command -v unzip >/dev/null 2>&1 || die "unzip is required"
    export JAVA_HOME="$JDK"
    export PATH="$JDK/bin:$PATH"
}

build_apk() {
    resolve_tools
    [ -f "$APP/AndroidManifest.xml" ] || die "manifest not found under $APP"
    [ -d "$APP/assets/fonts" ] || die "bundled fonts missing under $APP/assets"
    [ -f "$APP/assets/fonts/OFL.txt" ] || die "Open Sans OFL licence missing"

    rm -rf "$BUILD/res" "$BUILD/rjava" "$BUILD/classes" "$BUILD/dex" "$BUILD/evidence"
    rm -f "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/unsigned.apk" \
          "$BUILD/aligned.apk" "$BUILD/sources.txt" "$BUILD/verify.dex"
    mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/rjava" "$BUILD/keystore" \
             "$OUT" "$EVID"

    say "SDK   $SDK"
    say "tools $BT"
    say "JDK   $JDK"

    say "aapt2 compile + link"
    "$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"
    "$BT/aapt2" link -o "$BUILD/base.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$APP/AndroidManifest.xml" \
        --java "$BUILD/rjava" \
        -A "$APP/assets" \
        --auto-add-overlay \
        "$BUILD/res.zip"

    say "javac (Java 8 bytecode against android-36)"
    find "$APP/java" "$BUILD/rjava" -name '*.java' -print > "$BUILD/sources.txt"
    "$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
        -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

    say "d8 (min-api $MIN_API)"
    CLASSES=()
    while IFS= read -r class_file; do
        CLASSES+=("$class_file")
    done < <(find "$BUILD/classes" -name '*.class' -print)
    "$BT/d8" --release --min-api "$MIN_API" --lib "$ANDROID_JAR" \
        --output "$BUILD/dex" "${CLASSES[@]}"

    say "packaging classes.dex"
    cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
    TZ=UTC touch -t 198001010000.00 "$BUILD/dex/classes.dex"
    ( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex )
    "$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

    KS="$BUILD/keystore/actioncentre.p12"
    if [ ! -f "$KS" ]; then
        say "generating throwaway signing key (build/ is gitignored)"
        "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
            -storepass android -keypass android -alias ztmetro-actioncentre \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=ztmetro action centre, O=ztmetro, C=US" >/dev/null 2>&1 \
            || die "keytool failed"
    fi

    say "apksigner"
    "$BT/apksigner" sign \
        --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias ztmetro-actioncentre \
        --min-sdk-version "$MIN_API" \
        --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
        --out "$APK" "$BUILD/aligned.apk"
    "$BT/apksigner" verify --min-sdk-version "$MIN_API" "$APK" >/dev/null

    verify_apk
}

verify_apk() {
    say "static verification"
    local badging xmltree resources shade_xml strip_xml size sha failed=0
    badging=$("$BT/aapt2" dump badging "$APK")
    xmltree=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK")
    resources=$("$BT/aapt2" dump resources "$APK")
    shade_xml=$("$BT/aapt2" dump xmltree --file res/xml/shade_bridge.xml "$APK" 2>/dev/null || true)
    strip_xml=$("$BT/aapt2" dump xmltree --file res/xml/status_strip_service.xml "$APK" 2>/dev/null || true)

    printf '%s\n' "$badging" > "$EVID/aapt2-badging.txt"
    printf '%s\n' "$xmltree" > "$EVID/aapt2-manifest.xmltree.txt"
    printf '%s\n' "$resources" > "$EVID/aapt2-resources.txt"
    printf '%s\n' "$shade_xml" > "$EVID/aapt2-xmltree-shade_bridge.txt"
    printf '%s\n' "$strip_xml" > "$EVID/aapt2-xmltree-status_strip_service.txt"
    "$BT/apksigner" verify --min-sdk-version "$MIN_API" --verbose --print-certs \
        "$APK" > "$EVID/apksigner-verify.txt" 2>&1 || failed=1

    CHECKS="$EVID/verify.txt"
    : > "$CHECKS"
    check() { # description haystack needle
        if printf '%s\n' "$2" | grep -qF -- "$3"; then
            printf 'PASS  %s\n' "$1" >> "$CHECKS"
        else
            printf 'FAIL  %s\n' "$1" >> "$CHECKS"
            failed=1
        fi
    }
    check_re() { # description haystack extended-regex
        if printf '%s\n' "$2" | grep -qE -- "$3"; then
            printf 'PASS  %s\n' "$1" >> "$CHECKS"
        else
            printf 'FAIL  %s\n' "$1" >> "$CHECKS"
            failed=1
        fi
    }
    check_absent() { # description haystack needle
        if printf '%s\n' "$2" | grep -qF -- "$3"; then
            printf 'FAIL  %s\n' "$1" >> "$CHECKS"
            failed=1
        else
            printf 'PASS  %s\n' "$1" >> "$CHECKS"
        fi
    }
    check "package is org.ztmetro.actioncentre" "$badging" \
        "package: name='org.ztmetro.actioncentre'"
    check "application label present" "$badging" "application-label:"
    check "launcher activity exported in badging" "$badging" \
        "launchable-activity: name='org.ztmetro.actioncentre.ActionCentreActivity'"
    check "minSdk 29" "$badging" "minSdkVersion:'29'"
    check "targetSdk 36" "$badging" "targetSdkVersion:'36'"
    check "notification listener bind permission" "$xmltree" \
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    check "listener service registered" "$xmltree" ".ActionCentreListener"
    check "listener intent action" "$xmltree" \
        "android.service.notification.NotificationListenerService"
    check "shade bridge service registered" "$xmltree" ".ShadeBridgeService"
    check "shade bridge bind permission" "$xmltree" \
        "android.permission.BIND_ACCESSIBILITY_SERVICE"
    check "shade bridge intent action" "$xmltree" \
        "android.accessibilityservice.AccessibilityService"
    check "shade bridge config meta-data" "$xmltree" \
        "android.accessibilityservice"
    check "shade bridge config resource in table" "$resources" "xml/shade_bridge"
    check "status strip service registered" "$xmltree" ".StatusStripService"
    check "status strip bind permission" "$xmltree" \
        "android.permission.BIND_ACCESSIBILITY_SERVICE"
    check "status strip config packaged" "$(unzip -l "$APK")" \
        "res/xml/status_strip_service.xml"
    check "status strip config resource in table" "$resources" "xml/status_strip_service"
    check "status strip config description" "$strip_xml" \
        "description(0x01010020)=@0x7f"
    check "status strip label string" "$resources" "status_strip_name"
    check "status strip READ_PHONE_STATE (optional)" "$xmltree" \
        "android.permission.READ_PHONE_STATE"
    check "gesture settings row strings" "$resources" "gesture_enable"
    check "lock overlay swipe hint string" "$resources" "lock_swipe_hint"
    check "tile activity alias" "$xmltree" ".TileAlias"
    check "CAMERA permission" "$xmltree" "android.permission.CAMERA"
    check "WRITE_SETTINGS appop permission" "$xmltree" "android.permission.WRITE_SETTINGS"
    check "slide-up animation resource" "$resources" "ac_slide_up"
    check "slide-down animation resource" "$resources" "ac_slide_down"
    check "recents activity registered" "$xmltree" ".RecentsActivity"
    check "recents intent action" "$xmltree" "org.ztmetro.actioncentre.RECENTS"
    check "recents theme in resource table" "$resources" "Theme.Recents"
    check "recents entry animation resource" "$resources" "ac_recents_in"
    check "recents exit animation resource" "$resources" "ac_recents_out"
    check "recents title string" "$resources" "recents_title"
    check "recents no-access statement" "$resources" "recents_no_access_body"
    check "recents system-change statement" "$resources" "recents_change_body"
    check "recents PACKAGE_USAGE_STATS (app-op, optional)" "$xmltree" \
        "android.permission.PACKAGE_USAGE_STATS"
    check "recents REORDER_TASKS (normal)" "$xmltree" \
        "android.permission.REORDER_TASKS"
    check "action centre window animation style" "$resources" "Animation.ActionCentre"

    # The accessibility config is binary XML; aapt2 prints packed flag values.
    # typeWindowStateChanged = 0x20, typeWindowContentChanged = 0x800
    # (lock overlay: bouncer/IME detection), feedbackGeneric = 0x10,
    # flagDefault|flagReportViewIds|flagRetrieveInteractiveWindows = 0x51.
    [ -n "$shade_xml" ] || { printf 'FAIL  accessibility config XML readable\n' >> "$CHECKS"; failed=1; }
    check "accessibility config packaged" "$(unzip -l "$APK")" \
        "res/xml/shade_bridge.xml"
    check_re "accessibility config: window-state + content-changed events" "$shade_xml" \
        "accessibilityEventTypes[^=]*=0x0*820([[:space:]]|$)"
    check_re "accessibility config: generic feedback" "$shade_xml" \
        "accessibilityFeedbackType[^=]*=0x0*10([[:space:]]|$)"
    check_re "accessibility config: retrieve windows + report view ids" "$shade_xml" \
        "accessibilityFlags[^=]*=0x0*51([[:space:]]|$)"
    check_re "accessibility config: can retrieve window content" "$shade_xml" \
        "canRetrieveWindowContent[^=]*=true([[:space:]]|$)"
    check "accessibility config: all-package events for foreground tracking" \
        "$shade_xml" "accessibilityEventTypes"
    check_absent "accessibility config: no package filter (needs previous app)" \
        "$shade_xml" "android:packageNames"
    check "accessibility config: human description reference" "$shade_xml" \
        "description(0x01010020)=@0x7f"
    check "accessibility description string" "$resources" "shade_bridge_description"

    if printf '%s\n' "$xmltree" | grep -qF "android.intent.category.HOME"; then
        printf 'FAIL  launcher is not HOME\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  launcher is not HOME\n' >> "$CHECKS"
    fi
    if printf '%s\n' "$badging" | grep -q "uses-permission: name='android.permission.BLUETOOTH_CONNECT'"; then
        printf 'FAIL  no BLUETOOTH_CONNECT (dangerous) requested\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  no BLUETOOTH_CONNECT (dangerous) requested\n' >> "$CHECKS"
    fi
    # The lock overlay must never depend on "draw over other apps": it uses the
    # accessibility window type, which is above the keyguard without this grant.
    check_absent "no SYSTEM_ALERT_WINDOW permission" "$xmltree" \
        "android.permission.SYSTEM_ALERT_WINDOW"

    unzip -p "$APK" classes.dex > "$BUILD/verify.dex"
    "$BT/dexdump" -f "$BUILD/verify.dex" > "$EVID/dexdump-classes.txt" 2>&1 || true
    if grep -qF "Lorg/ztmetro/actioncentre/ShadeBridgeService;" \
            "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists ShadeBridgeService\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists ShadeBridgeService\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "onAccessibilityEvent" "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists onAccessibilityEvent callback\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists onAccessibilityEvent callback\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "findFullscreenSystemUiWindow" "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists geometry detector\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists geometry detector\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "notifyPanelVisible" "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists panel-visible timing hook\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists panel-visible timing hook\n' >> "$CHECKS"
        failed=1
    fi
    if grep -aq "extra_instant" "$BUILD/verify.dex"; then
        printf 'PASS  instant-launch extra packaged\n' >> "$CHECKS"
    else
        printf 'FAIL  instant-launch extra packaged\n' >> "$CHECKS"
        failed=1
    fi
    # BACK policy: two mutually exclusive paths (legacy BACK-first when there is
    # no strip, and the panel-first path that may send one BACK after 120 ms).
    # `backSent` is the per-trigger cap; no path sends a second BACK.
    ACTIVITY_SRC="$APP/java/org/ztmetro/actioncentre/ActionCentreActivity.java"
    SHADE_SRC="$APP/java/org/ztmetro/actioncentre/ShadeBridgeService.java"
    back_calls=$(grep -c "performGlobalAction" "$SHADE_SRC" 2>/dev/null || true)
    if [ "${back_calls:-0}" -le 2 ]; then
        printf 'PASS  at most two BACK call sites (one per collapse path)\n' >> "$CHECKS"
    else
        printf 'FAIL  expected <= 2 BACK call sites, found %s\n' "${back_calls:-0}" >> "$CHECKS"
        failed=1
    fi
    if grep -qF "BACK_AFTER_START_MS" "$SHADE_SRC" && grep -qF "backSent" "$SHADE_SRC"; then
        printf 'PASS  panel-first shade path: BACK only after 120 ms, capped at one\n' >> "$CHECKS"
    else
        printf 'FAIL  panel-first shade timeline/BACK cap missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "panel start at" "$SHADE_SRC" && grep -qF "panel visible " "$SHADE_SRC"; then
        printf 'PASS  shade timeline logs detected/start/back/visible\n' >> "$CHECKS"
    else
        printf 'FAIL  shade timeline log lines missing\n' >> "$CHECKS"
        failed=1
    fi
    # Notification tap: the contentIntent must be sent with a NEW_TASK fill-in
    # intent, with an action[0] fallback and a logged result.
    if grep -qF "new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)" "$ACTIVITY_SRC" \
            && grep -qF "isImmutable()" "$ACTIVITY_SRC"; then
        printf 'PASS  row tap sends contentIntent with FLAG_ACTIVITY_NEW_TASK fill-in (immutability logged)\n' >> "$CHECKS"
    else
        printf 'FAIL  NEW_TASK fill-in intent / immutability log missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "actionIntent" "$APP/java/org/ztmetro/actioncentre/Notif.java" \
            && grep -qF "falling back to action[0]" "$ACTIVITY_SRC"; then
        printf 'PASS  action[0] fallback carried and used when contentIntent is null\n' >> "$CHECKS"
    else
        printf 'FAIL  action[0] fallback missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "cancelKey(notif.key)" "$ACTIVITY_SRC" \
            && grep -qF "cancelNotification ok" "$ACTIVITY_SRC"; then
        printf 'PASS  row X cancels the notification and logs the result\n' >> "$CHECKS"
    else
        printf 'FAIL  row cancel result is not logged\n' >> "$CHECKS"
        failed=1
    fi
    # Swipe-up close: 60 dp drag or an upward fling, 220 ms slide-down, and the
    # arm condition that keeps list scrolling intact.
    if grep -qF "CLOSE_DRAG_DP = 60" "$ACTIVITY_SRC" \
            && grep -qF "CLOSE_FLING_DP_PER_S" "$ACTIVITY_SRC" \
            && grep -qF "canScrollVertically(View.FOCUS_DOWN)" "$ACTIVITY_SRC"; then
        printf 'PASS  swipe-up close: 60 dp / fling, scroll-safe arming\n' >> "$CHECKS"
    else
        printf 'FAIL  swipe-up close thresholds/scroll guard missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "addStripOverlay" "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists top-edge strip overlay\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists top-edge strip overlay\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "TYPE_ACCESSIBILITY_OVERLAY" \
            "$APP/java/org/ztmetro/actioncentre/ShadeBridgeService.java"; then
        printf 'PASS  strip uses TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
    else
        printf 'FAIL  strip uses TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
        failed=1
    fi
    # Lock overlay: its own class, the accessibility overlay type, the
    # NOT_FOCUSABLE|NOT_TOUCHABLE main surface, and the only touchable strip.
    if grep -qF "Lorg/ztmetro/actioncentre/LockOverlayController;" \
            "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists LockOverlayController\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists LockOverlayController\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "Lorg/ztmetro/actioncentre/LockOverlayView;" \
            "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists LockOverlayView\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists LockOverlayView\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "TYPE_ACCESSIBILITY_OVERLAY" \
            "$APP/java/org/ztmetro/actioncentre/LockOverlayController.java"; then
        printf 'PASS  lock overlay uses TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
    else
        printf 'FAIL  lock overlay uses TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "FLAG_NOT_TOUCHABLE" \
            "$APP/java/org/ztmetro/actioncentre/LockOverlayController.java" \
            && grep -q "FLAG_NOT_FOCUSABLE" \
            "$APP/java/org/ztmetro/actioncentre/LockOverlayController.java"; then
        printf 'PASS  lock surface NOT_FOCUSABLE|NOT_TOUCHABLE\n' >> "$CHECKS"
    else
        printf 'FAIL  lock surface flags missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "performGlobalAction" \
            "$APP/java/org/ztmetro/actioncentre/LockOverlayController.java"; then
        printf 'FAIL  lock overlay must not call performGlobalAction\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  lock overlay performs no global action (never unlocks)\n' >> "$CHECKS"
    fi
    # ---- lock overlay unlock path ----
    UNLOCK_SRC="$APP/java/org/ztmetro/actioncentre/UnlockProxyActivity.java"
    UNLOCK_CTRL="$APP/java/org/ztmetro/actioncentre/LockOverlayController.java"
    LOCK_VIEW_SRC="$APP/java/org/ztmetro/actioncentre/LockOverlayView.java"
    for cls in UnlockProxyActivity UnlockTimeline LockClockPanel; do
        if grep -qF "Lorg/ztmetro/actioncentre/$cls;" "$EVID/dexdump-classes.txt"; then
            printf 'PASS  dexdump lists %s\n' "$cls" >> "$CHECKS"
        else
            printf 'FAIL  dexdump lists %s\n' "$cls" >> "$CHECKS"
            failed=1
        fi
    done
    check "unlock proxy activity registered" "$xmltree" ".UnlockProxyActivity"
    check "DISABLE_KEYGUARD permission declared" "$xmltree" \
        "android.permission.DISABLE_KEYGUARD"
    if grep -q 'android:name=".UnlockProxyActivity"' "$APP/AndroidManifest.xml" \
            && grep -q 'android:showWhenLocked="true"' "$APP/AndroidManifest.xml" \
            && grep -q 'android:excludeFromRecents="true"' "$APP/AndroidManifest.xml" \
            && grep -q 'android:exported="false"' "$APP/AndroidManifest.xml"; then
        printf 'PASS  unlock proxy: showWhenLocked, excluded from recents, not exported\n' >> "$CHECKS"
    else
        printf 'FAIL  unlock proxy manifest attributes missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "requestDismissKeyguard" "$UNLOCK_SRC"; then
        printf 'PASS  non-secure unlock calls requestDismissKeyguard\n' >> "$CHECKS"
    else
        printf 'FAIL  requestDismissKeyguard missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "createConfirmDeviceCredentialIntent" "$UNLOCK_SRC"; then
        printf 'PASS  secure unlock opens the system credential screen\n' >> "$CHECKS"
    else
        printf 'FAIL  credential intent missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "UnlockTimeline" "$UNLOCK_SRC" \
            && grep -q "UnlockTimeline" "$UNLOCK_CTRL"; then
        printf 'PASS  unlock timeline logged from proxy and controller\n' >> "$CHECKS"
    else
        printf 'FAIL  unlock timeline logging missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "aboveKeyguard" "$UNLOCK_CTRL" \
            && grep -q "getLayer" "$UNLOCK_CTRL" \
            && grep -q "keyguardIndex" "$UNLOCK_CTRL"; then
        printf 'PASS  foreign windows compared above/below the keyguard (layer/order)\n' >> "$CHECKS"
    else
        printf 'FAIL  foreign-window above/below-keyguard guard missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "setAuthPanel" "$LOCK_VIEW_SRC" \
            && grep -q "LockClockPanel" "$LOCK_VIEW_SRC"; then
        printf 'PASS  clock area is a separate panel with the PIN-pad hook\n' >> "$CHECKS"
    else
        printf 'FAIL  clock panel/PIN-pad hook missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "Theme.ActionCentre.UnlockProxy" \
            "$APP/res/values/styles.xml"; then
        printf 'PASS  unlock proxy has a no-animation transparent theme\n' >> "$CHECKS"
    else
        printf 'FAIL  unlock proxy theme missing\n' >> "$CHECKS"
        failed=1
    fi

    # ---- WP status strip (agent-37): its own accessibility service + view ----
    STRIP_SRC="$APP/java/org/ztmetro/actioncentre/StatusStripService.java"
    STRIP_VIEW="$APP/java/org/ztmetro/actioncentre/StatusStripOverlayView.java"
    if grep -qF "Lorg/ztmetro/actioncentre/StatusStripService;" \
            "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists StatusStripService\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists StatusStripService\n' >> "$CHECKS"
        failed=1
    fi
    if grep -qF "Lorg/ztmetro/actioncentre/StatusStripOverlayView;" \
            "$EVID/dexdump-classes.txt"; then
        printf 'PASS  dexdump lists StatusStripOverlayView\n' >> "$CHECKS"
    else
        printf 'FAIL  dexdump lists StatusStripOverlayView\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "TYPE_ACCESSIBILITY_OVERLAY" "$STRIP_SRC"; then
        printf 'PASS  status strip uses TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
    else
        printf 'FAIL  status strip does not use TYPE_ACCESSIBILITY_OVERLAY\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "FLAG_NOT_TOUCHABLE" "$STRIP_SRC"; then
        printf 'PASS  status strip is NOT_TOUCHABLE (passes touches through)\n' >> "$CHECKS"
    else
        printf 'FAIL  status strip is touchable or flag missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "FLAG_NOT_FOCUSABLE" "$STRIP_SRC" \
            && grep -q "FLAG_LAYOUT_IN_SCREEN" "$STRIP_SRC" \
            && grep -q "FLAG_LAYOUT_NO_LIMITS" "$STRIP_SRC"; then
        printf 'PASS  status strip flags NOT_FOCUSABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS\n' >> "$CHECKS"
    else
        printf 'FAIL  status strip window flags incomplete\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS" "$STRIP_SRC"; then
        printf 'PASS  status strip draws into the cutout at 28 dp\n' >> "$CHECKS"
    else
        printf 'FAIL  cutout handling missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "HEIGHT_DP = 28" "$STRIP_VIEW"; then
        printf 'PASS  status strip height is exactly 28 dp\n' >> "$CHECKS"
    else
        printf 'FAIL  status strip height is not 28 dp\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "ACTION_BATTERY_CHANGED" "$STRIP_SRC"; then
        printf 'PASS  battery from ACTION_BATTERY_CHANGED\n' >> "$CHECKS"
    else
        printf 'FAIL  battery source missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "isWifiEnabled" "$STRIP_SRC"; then
        printf 'PASS  Wi-Fi from WifiManager.isWifiEnabled\n' >> "$CHECKS"
    else
        printf 'FAIL  Wi-Fi source missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "getSignalStrength" "$STRIP_SRC"; then
        printf 'PASS  signal from TelephonyManager.getSignalStrength\n' >> "$CHECKS"
    else
        printf 'FAIL  signal source missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "ACTION_TIME_TICK" "$STRIP_SRC"; then
        printf 'PASS  clock from ACTION_TIME_TICK (no 1 Hz timer)\n' >> "$CHECKS"
    else
        printf 'FAIL  clock does not use ACTION_TIME_TICK\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "ACTION_SCREEN_OFF" "$STRIP_SRC" \
            && grep -q "ACTION_USER_PRESENT" "$STRIP_SRC"; then
        printf 'PASS  strip removed on lock/screen-off, re-added on USER_PRESENT\n' >> "$CHECKS"
    else
        printf 'FAIL  keyguard remove/re-add hooks missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "setPanelVisible" "$APP/java/org/ztmetro/actioncentre/ActionCentreActivity.java" \
            && grep -q "setPanelVisible" "$STRIP_SRC"; then
        printf 'PASS  strip suppressed while Action Centre panel is resumed\n' >> "$CHECKS"
    else
        printf 'FAIL  panel suppression hook missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -q "postDelayed\|new Timer\|scheduleAtFixedRate" "$STRIP_SRC" "$STRIP_VIEW"; then
        printf 'FAIL  status strip uses a timer loop\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  status strip has no timer/polling loop\n' >> "$CHECKS"
    fi
    if grep -q "WAKE_LOCK\|newWakeLock" "$STRIP_SRC" "$STRIP_VIEW"; then
        printf 'FAIL  status strip takes a wakelock\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  status strip takes no wakelock\n' >> "$CHECKS"
    fi
    if grep -qE "Color\.(GREEN|RED)|0xFF(00FF00|FF0000)" "$STRIP_VIEW"; then
        printf 'FAIL  status strip uses green/red battery colour\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  status strip is white-only (no green/red)\n' >> "$CHECKS"
    fi
    if grep -aq "androidx" "$BUILD/verify.dex"; then
        printf 'FAIL  AndroidX classes found in dex\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  no androidx in classes.dex\n' >> "$CHECKS"
    fi
    local symbol
    for symbol in ActionCentreActivity ActionCentreListener ShadeBridgeService \
                  QuickTile QuickGrid NotificationRowView StatusStripView Motion \
                  LockOverlayController LockOverlayView LockClockPanel \
                  UnlockProxyActivity UnlockTimeline \
                  StatusStripService StatusStripOverlayView RecentsActivity; do
        if grep -aq "$symbol" "$BUILD/verify.dex"; then
            printf 'PASS  class %s in dex\n' "$symbol" >> "$CHECKS"
        else
            printf 'FAIL  class %s in dex\n' "$symbol" >> "$CHECKS"
            failed=1
        fi
    done
    if grep -aq "getRunningTasks" "$BUILD/verify.dex"; then
        printf 'PASS  recents reads ActivityManager.getRunningTasks\n' >> "$CHECKS"
    else
        printf 'FAIL  recents task read API missing\n' >> "$CHECKS"
        failed=1
    fi
    if grep -aq "removeTask" "$BUILD/verify.dex"; then
        printf 'PASS  recents attempts the hidden removeTask boundary\n' >> "$CHECKS"
    else
        printf 'FAIL  recents dismissal probe missing\n' >> "$CHECKS"
        failed=1
    fi

    if unzip -l "$APK" | grep -q "assets/fonts/OpenSans-Light.ttf"; then
        printf 'PASS  bundled Open Sans Light asset in APK\n' >> "$CHECKS"
    else
        printf 'FAIL  bundled Open Sans Light asset missing\n' >> "$CHECKS"
        failed=1
    fi
    if unzip -l "$APK" | grep -q " classes.dex"; then
        printf 'PASS  classes.dex packaged\n' >> "$CHECKS"
    else
        printf 'FAIL  classes.dex missing\n' >> "$CHECKS"
        failed=1
    fi

    size=$(wc -c < "$APK" | tr -d ' ')
    if [ "$size" -lt "$MAX_BYTES" ]; then
        printf 'PASS  APK smaller than 2 MB (%s bytes)\n' "$size" >> "$CHECKS"
    else
        printf 'FAIL  APK larger than 2 MB (%s bytes)\n' "$size" >> "$CHECKS"
        failed=1
    fi
    sha=$(sha256 "$APK")

    printf '%s  %s\n' "$sha" "$APK_NAME" > "$EVID/$APK_NAME.sha256"
    {
        printf 'shade open         260 ms  ztmetro.c:138 SHADE_MS (window animation ac_slide_up)\n'
        printf 'shade close        220 ms  res/anim/ac_slide_down.xml (window animation)\n'
        printf 'drag commit        40 dp   ztmetro.c:11761 (dy > 40)\n'
        printf 'drag travel        1:1 with the finger, handle drag (both ways)\n'
        printf 'close gesture      swipe up > 60 dp or up-fling >= 1200 dp/s -> 220 ms slide-down\n'
        printf 'close arming       top strip, or list at top with nothing to scroll / empty tail\n'
        printf 'scale              Settings.Global.ANIMATOR_DURATION_SCALE applies to window animations; 0 = instant\n'
        printf 'window             translucent; open/close are window animations (Theme.ActionCentre windowAnimationStyle)\n'
        printf 'bridge launch      EXTRA_INSTANT suppresses open/close window animations\n'
        printf 'easing             (1 - pow(0.1, t)) / 0.9  core/zrnmetrocore.c:1628 (Log(10)) for drag; XML cubic for windows\n'
        printf 'frames at 60 Hz    open 15.6, close 13.2\n'
    } > "$EVID/motion-facts.txt"
    {
        printf 'activity           org.ztmetro.actioncentre/.RecentsActivity (exported, action org.ztmetro.actioncentre.RECENTS)\n'
        printf 'title              "recent" 58 sp Open Sans Light, WP lower-case page title\n'
        printf 'layout             black frame, caps RECENT APPS, horizontal flat cards 248x320 dp, icon + label + TASK #id\n'
        printf 'task source        ActivityManager.getRunningTasks(30); cross-app only when caller UID is config_recentsComponentName\n'
        printf 'ordinary app       own tasks + HOME only (AOSP 16 ATMS.isGetTasksAllowed / RecentTasks)\n'
        printf 'dismiss            swipe up; attempts hidden ActivityManager.removeTask(); needs REMOVE_TASKS (signature|recents|role)\n'
        printf 'fallback           WP frame + exact system-side change + optional Usage Access app list (PACKAGE_USAGE_STATS app-op)\n'
        printf 'wm shell           config_recentsComponentName is read by system_server RecentTasks/PMS, not by WMShell or Launcher3\n'
    } > "$EVID/recents-facts.txt"
    {
        printf 'apk:        actioncentre/build/out/%s\n' "$APK_NAME"
        printf 'evidence:   %s/%s\n' "${EVIDENCE#$ROOT/}" "$APK_NAME"
        printf 'size:       %s bytes\n' "$size"
        printf 'sha256:     %s\n' "$sha"
        printf 'package:    org.ztmetro.actioncentre\n'
        printf 'version:    1 (1.0)\n'
        printf 'min/target: %s / 36\n' "$MIN_API"
        printf 'build:      plain javac + aapt2 + d8 + apksigner (no Gradle, no AndroidX)\n'
        printf 'bridge:     ShadeBridgeService (res/xml/shade_bridge.xml), BIND_ACCESSIBILITY_SERVICE; strip path starts the panel instantly, shade fallback = panel first + one BACK after 120 ms\n'
        printf 'strip:      StatusStripService (res/xml/status_strip_service.xml), TYPE_ACCESSIBILITY_OVERLAY 28 dp, NOT_TOUCHABLE; READ_PHONE_STATE optional for live signal\n'
        printf 'lock:       LockOverlayController: TYPE_ACCESSIBILITY_OVERLAY, NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN, bottom strip starts the unlock proxy\n'
        printf 'unlock:     UnlockProxyActivity: requestDismissKeyguard (non-secure) or ConfirmDeviceCredential (secure); overlay stays until USER_PRESENT/credential on top\n'
        printf 'fonts:      Open Sans Light/Regular/SemiBold from repo/fonts (OFL, bundled)\n'
        printf 'built on:   %s\n' "$(uname -srm)"
        printf 'jdk:        %s\n' "$("$JDK/bin/java" -version 2>&1 | head -n 1)"
    } > "$EVID/artifact-facts.txt"
    {
        printf 'apk:        %s\n' "$APK_NAME"
        printf 'size:       %s bytes\n' "$size"
        printf 'sha256:     %s\n' "$sha"
        printf 'window:     TYPE_ACCESSIBILITY_OVERLAY (2032), MATCH_PARENT x 28 dp, gravity TOP|START\n'
        printf 'flags:      NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS\n'
        printf 'background: #000000 opaque, full width, exactly 28 dp tall\n'
        printf 'left:       battery outline + white fill (+ plug when charging), Wi-Fi three arcs, signal four bars\n'
        printf 'right:      HH:mm 12 sp, light white\n'
        printf 'sources:    ACTION_BATTERY_CHANGED; WifiManager.isWifiEnabled + default-network callback; TelephonyManager.getSignalStrength + signal callback; ACTION_TIME_TICK\n'
        printf 'clock:      ACTION_TIME_TICK / TIME_CHANGED / TIMEZONE_CHANGED only; no 1 Hz timer; no wakelock\n'
        printf 'keyguard:   removed on ACTION_SCREEN_OFF / keyguard locked; re-added on ACTION_USER_PRESENT / screen-on unlocked\n'
        printf 'cutout:     layoutInDisplayCutoutMode=ALWAYS (API 30+), window remains 28 dp\n'
        printf 'fail-safe:  addView failure caught; logs and leaves the stock bar untouched\n'
    } > "$EVID/status-strip-facts.txt"
    grep -Ei 'status strip|strip|signal|battery|clock|wifi|overlay|READ_PHONE_STATE' \
        "$CHECKS" > "$EVID/status-strip-verify.txt" 2>/dev/null || true
    cp "$APK" "$EVID/$APK_NAME"

    cat "$CHECKS"
    [ "$failed" -eq 0 ] || die "static verification failed (see $CHECKS)"
    printf 'size: %s bytes, sha256: %s\n' "$size" "$sha"
}

collect_status_strip() {
    mkdir -p "$STATUS_EVIDENCE"
    cp "$EVIDENCE/$APK_NAME" "$STATUS_EVIDENCE/$APK_NAME"
    cp "$EVIDENCE/$APK_NAME.sha256" "$STATUS_EVIDENCE/$APK_NAME.sha256" 2>/dev/null || true
    cp "$EVIDENCE/verify.txt" "$STATUS_EVIDENCE/verify.txt"
    cp "$EVIDENCE/status-strip-verify.txt" "$STATUS_EVIDENCE/status-strip-verify.txt"
    cp "$EVIDENCE/status-strip-facts.txt" "$STATUS_EVIDENCE/status-strip-facts.txt"
    cp "$EVIDENCE/artifact-facts.txt" "$STATUS_EVIDENCE/artifact-facts.txt"
    cp "$EVIDENCE/aapt2-manifest.xmltree.txt" "$STATUS_EVIDENCE/aapt2-manifest.xmltree.txt"
    cp "$EVIDENCE/aapt2-resources.txt" "$STATUS_EVIDENCE/aapt2-resources.txt"
    cp "$EVIDENCE/aapt2-xmltree-status_strip_service.txt" \
       "$STATUS_EVIDENCE/aapt2-xmltree-status_strip_service.txt"
    cp "$EVIDENCE/dexdump-classes.txt" "$STATUS_EVIDENCE/dexdump-classes.txt"
    cp "$EVIDENCE/apksigner-verify.txt" "$STATUS_EVIDENCE/apksigner-verify.txt"
    printf 'status-strip evidence: %s\n' "$STATUS_EVIDENCE"
}

collect_local() {
    mkdir -p "$EVIDENCE"
    cp "$APK" "$EVIDENCE/$APK_NAME"
    cp "$EVID/aapt2-badging.txt" "$EVID/aapt2-manifest.xmltree.txt" \
       "$EVID/aapt2-resources.txt" "$EVID/aapt2-xmltree-shade_bridge.txt" \
       "$EVID/aapt2-xmltree-status_strip_service.txt" \
       "$EVID/dexdump-classes.txt" "$EVID/apksigner-verify.txt" \
       "$EVID/artifact-facts.txt" "$EVID/verify.txt" \
       "$EVID/status-strip-facts.txt" "$EVID/status-strip-verify.txt" \
       "$EVID/recents-facts.txt" \
       "$EVID/$APK_NAME.sha256" "$EVIDENCE/"
    collect_status_strip
}

remote_build() {
    say "staging sources on $REMOTE:$RWORK"
    ssh "$REMOTE" "mkdir -p '$RWORK'"
    rsync -a --delete "$ROOT/actioncentre/app/" "$REMOTE:$RWORK/app/"
    scp -q "$SCRIPT_DIR/build-actioncentre.sh" "$REMOTE:$RWORK/build-actioncentre.sh"
    say "remote build on $REMOTE (SDK ~/android-sdk, JDK ~/android-jdk)"
    ssh "$REMOTE" "cd '$RWORK' && ACTIONCENTRE_STAGE=1 APP_DIR=\"\$PWD/app/src/main\" BUILD_DIR=\"\$PWD/build\" bash ./build-actioncentre.sh"
    mkdir -p "$OUT" "$EVIDENCE"
    scp -q "$REMOTE:$RWORK/build/out/$APK_NAME" "$OUT/$APK_NAME"
    scp -q "$REMOTE:$RWORK/build/evidence/"* "$EVIDENCE/"
    cp "$OUT/$APK_NAME" "$EVIDENCE/$APK_NAME"
    # The remote half fills artifact-facts with its own default evidence path;
    # rewrite it to the directory this run actually copied into.
    if [ -f "$EVIDENCE/artifact-facts.txt" ]; then
        sed -i.bak "s|^evidence:.*|evidence:   ${EVIDENCE#$ROOT/}/$APK_NAME|" \
            "$EVIDENCE/artifact-facts.txt"
        rm -f "$EVIDENCE/artifact-facts.txt.bak"
    fi
    local local_sha remote_sha
    local_sha=$(sha256 "$OUT/$APK_NAME")
    remote_sha=$(awk '{print $1}' "$EVIDENCE/$APK_NAME.sha256")
    [ "$local_sha" = "$remote_sha" ] \
        || die "sha256 mismatch after scp: local $local_sha remote $remote_sha"
    collect_status_strip
    say "artifact copied back; sha256 matches remote"
}

summary() {
    local size sha
    size=$(wc -c < "$EVIDENCE/$APK_NAME" | tr -d ' ')
    sha=$(sha256 "$EVIDENCE/$APK_NAME")
    say "done"
    printf 'APK:      %s\n' "$EVIDENCE/$APK_NAME"
    printf 'build:    %s\n' "$OUT/$APK_NAME"
    printf 'size:     %s bytes (%s)\n' "$size" "$(du -h "$EVIDENCE/$APK_NAME" | awk '{print $1}')"
    printf 'sha256:   %s\n' "$sha"
    printf 'evidence: %s\n' "$EVIDENCE"
}

main() {
    if [ "${1:-}" = "--help" ]; then
        printf 'usage: %s\n' "$0"
        printf 'Builds repo/actioncentre. On a host without android-36 it stages\n'
        printf 'the sources on $REMOTE:~/%s and copies the evidence back.\n' "$RWORK"
        exit 0
    fi

    if [ "${ACTIONCENTRE_STAGE:-0}" = "1" ]; then
        # Remote half: sources and build directory are already staged.
        build_apk
        return
    fi

    if resolve_sdk && resolve_jdk; then
        build_apk
        collect_local
        summary
    else
        remote_build
        summary
    fi
}

main "$@"
