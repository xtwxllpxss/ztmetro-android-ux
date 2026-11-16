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
    local badging xmltree resources shade_xml size sha failed=0
    badging=$("$BT/aapt2" dump badging "$APK")
    xmltree=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK")
    resources=$("$BT/aapt2" dump resources "$APK")
    shade_xml=$("$BT/aapt2" dump xmltree --file res/xml/shade_bridge.xml "$APK" 2>/dev/null || true)

    printf '%s\n' "$badging" > "$EVID/aapt2-badging.txt"
    printf '%s\n' "$xmltree" > "$EVID/aapt2-manifest.xmltree.txt"
    printf '%s\n' "$resources" > "$EVID/aapt2-resources.txt"
    printf '%s\n' "$shade_xml" > "$EVID/aapt2-xmltree-shade_bridge.txt"
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
    check "gesture settings row strings" "$resources" "gesture_enable"
    check "tile activity alias" "$xmltree" ".TileAlias"
    check "CAMERA permission" "$xmltree" "android.permission.CAMERA"
    check "WRITE_SETTINGS appop permission" "$xmltree" "android.permission.WRITE_SETTINGS"
    check "slide-up animation resource" "$resources" "ac_slide_up"
    check "slide-down animation resource" "$resources" "ac_slide_down"

    # The accessibility config is binary XML; aapt2 prints packed flag values.
    # typeWindowStateChanged = 0x20, feedbackGeneric = 0x10,
    # flagDefault|flagReportViewIds|flagRetrieveInteractiveWindows = 0x51.
    [ -n "$shade_xml" ] || { printf 'FAIL  accessibility config XML readable\n' >> "$CHECKS"; failed=1; }
    check "accessibility config packaged" "$(unzip -l "$APK")" \
        "res/xml/shade_bridge.xml"
    check_re "accessibility config: TYPE_WINDOW_STATE_CHANGED only" "$shade_xml" \
        "accessibilityEventTypes[^=]*=0x0*20([[:space:]]|$)"
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
    # Regression guard: exactly one BACK in the whole service. A later BACK can
    # dismiss our own panel once it has focus.
    back_calls=$(grep -c "performGlobalAction" \
        "$APP/java/org/ztmetro/actioncentre/ShadeBridgeService.java" 2>/dev/null || true)
    if [ "${back_calls:-0}" -eq 1 ]; then
        printf 'PASS  exactly one BACK call in ShadeBridgeService\n' >> "$CHECKS"
    else
        printf 'FAIL  expected exactly one BACK call, found %s\n' "${back_calls:-0}" >> "$CHECKS"
        failed=1
    fi
    if grep -aq "androidx" "$BUILD/verify.dex"; then
        printf 'FAIL  AndroidX classes found in dex\n' >> "$CHECKS"
        failed=1
    else
        printf 'PASS  no androidx in classes.dex\n' >> "$CHECKS"
    fi
    local symbol
    for symbol in ActionCentreActivity ActionCentreListener ShadeBridgeService \
                  QuickTile QuickGrid NotificationRowView StatusStripView Motion; do
        if grep -aq "$symbol" "$BUILD/verify.dex"; then
            printf 'PASS  class %s in dex\n' "$symbol" >> "$CHECKS"
        else
            printf 'FAIL  class %s in dex\n' "$symbol" >> "$CHECKS"
            failed=1
        fi
    done

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
        printf 'shade open         260 ms  ztmetro.c:138 SHADE_MS (animate, ztmetro.c:6558)\n'
        printf 'shade close        220 ms  res/anim/ac_slide_down.xml (C uses SHADE_MS 260 both ways)\n'
        printf 'drag commit        40 dp   ztmetro.c:11761 (dy > 40)\n'
        printf 'drag travel        1:1 with the finger, handle drag\n'
        printf 'scale              Settings.Global.ANIMATOR_DURATION_SCALE read at every motion start; 0 = instant\n'
        printf 'window             translucent, no window animation: the content slides itself\n'
        printf 'easing             (1 - pow(0.1, t)) / 0.9  core/zrnmetrocore.c:1628 (Log(10))\n'
        printf 'frames at 60 Hz    open 15.6, close 13.2\n'
    } > "$EVID/motion-facts.txt"
    {
        printf 'apk:        actioncentre/build/out/%s\n' "$APK_NAME"
        printf 'evidence:   evidence/actioncentre/%s\n' "$APK_NAME"
        printf 'size:       %s bytes\n' "$size"
        printf 'sha256:     %s\n' "$sha"
        printf 'package:    org.ztmetro.actioncentre\n'
        printf 'version:    1 (1.0)\n'
        printf 'min/target: %s / 36\n' "$MIN_API"
        printf 'build:      plain javac + aapt2 + d8 + apksigner (no Gradle, no AndroidX)\n'
        printf 'bridge:     ShadeBridgeService (res/xml/shade_bridge.xml), BIND_ACCESSIBILITY_SERVICE, no new permissions\n'
        printf 'fonts:      Open Sans Light/Regular/SemiBold from repo/fonts (OFL, bundled)\n'
        printf 'built on:   %s\n' "$(uname -srm)"
        printf 'jdk:        %s\n' "$("$JDK/bin/java" -version 2>&1 | head -n 1)"
    } > "$EVID/artifact-facts.txt"
    cp "$APK" "$EVID/$APK_NAME"

    cat "$CHECKS"
    [ "$failed" -eq 0 ] || die "static verification failed (see $CHECKS)"
    printf 'size: %s bytes, sha256: %s\n' "$size" "$sha"
}

collect_local() {
    mkdir -p "$EVIDENCE"
    cp "$APK" "$EVIDENCE/$APK_NAME"
    cp "$EVID/aapt2-badging.txt" "$EVID/aapt2-manifest.xmltree.txt" \
       "$EVID/aapt2-resources.txt" "$EVID/aapt2-xmltree-shade_bridge.txt" \
       "$EVID/dexdump-classes.txt" "$EVID/apksigner-verify.txt" \
       "$EVID/artifact-facts.txt" "$EVID/verify.txt" \
       "$EVID/$APK_NAME.sha256" "$EVIDENCE/"
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
    local local_sha remote_sha
    local_sha=$(sha256 "$OUT/$APK_NAME")
    remote_sha=$(awk '{print $1}' "$EVIDENCE/$APK_NAME.sha256")
    [ "$local_sha" = "$remote_sha" ] \
        || die "sha256 mismatch after scp: local $local_sha remote $remote_sha"
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
