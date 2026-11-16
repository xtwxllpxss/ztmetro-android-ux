#!/usr/bin/env bash
# Build, sign and verify the ztMetro keyguard LSPosed module (org.ztmetro.keyguard).
#
# Portable (macOS + Linux/Ubuntu), idempotent, offline after the one pinned
# download.  Everything it writes lives under the module's gitignored build/
# directory; the signed APK is reported at the end.
#
#   scripts/build-keyguard-module.sh
#
# Environment overrides:
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK root (default: ~/android-sdk)
#   JAVA_HOME                        JDK (default: ~/android-jdk, then PATH)
#   KEYGUARD_BUILD_DIR               build dir (default: <module>/build)
#   KEYGUARD_EVIDENCE_DIR            if set, copy APK + logs there (evidence keyguard-module)
#   KEYGUARD_KEYSTORE_PASS           dev signing password (default below)
#
# The downloaded Xposed API jar is the official Xposed Maven artifact:
#   https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
#   sha256 f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
# It is compile-only (never packaged); xposedminversion 93 is the legacy level
# LSPosed (and Vector on Android 16) reports to modules.

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(dirname "$SCRIPT_DIR")
MODULE_DIR=${KEYGUARD_MODULE_DIR:-$REPO_DIR/sysui/keyguard-module}
BUILD_DIR=${KEYGUARD_BUILD_DIR:-$MODULE_DIR/build}
GEN_DIR="$BUILD_DIR/gen"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
DIST_DIR="$BUILD_DIR/dist"
KEYSTORE_DIR="$BUILD_DIR/keystore"
KEYSTORE="$KEYSTORE_DIR/ztmetro-keyguard.jks"
KEY_ALIAS=ztmetro-keyguard
KEY_PASS=${KEYGUARD_KEYSTORE_PASS:-ztmetro-keyguard-dev}

XPOSED_URL="https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar"
XPOSED_SHA256="f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"
XPOSED_JAR="$BUILD_DIR/deps/xposed-api-82.jar"

MIN_SDK=29
TARGET_SDK=36
MODULE_PACKAGE=org.ztmetro.keyguard

say() { printf '==> %s\n' "$*"; }
die() { printf 'build-keyguard-module: %s\n' "$*" >&2; exit 1; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "no sha256sum/shasum on this host"
    fi
}

find_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                     "$HOME/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate/build-tools" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

SDK=$(find_sdk) || die "Android SDK not found (set ANDROID_HOME)"
ANDROID_JAR="$SDK/platforms/android-$TARGET_SDK/android.jar"
[ -f "$ANDROID_JAR" ] || die "missing $ANDROID_JAR (install platforms;android-$TARGET_SDK)"

BUILD_TOOLS=""
if [ -d "$SDK/build-tools/$TARGET_SDK.0" ]; then
    BUILD_TOOLS="$SDK/build-tools/$TARGET_SDK.0"
else
    for candidate in "$SDK"/build-tools/*; do
        [ -d "$candidate" ] || continue
        if [ -z "$BUILD_TOOLS" ] || [ "$(basename "$candidate")" \> "$(basename "$BUILD_TOOLS")" ]; then
            BUILD_TOOLS="$candidate"
        fi
    done
fi
[ -n "$BUILD_TOOLS" ] || die "no build-tools under $SDK"
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
DEXDUMP="$BUILD_TOOLS/dexdump"
for tool in "$AAPT2" "$ZIPALIGN" "$APKSIGNER"; do
    [ -x "$tool" ] || die "missing tool $tool"
done

if [ -x "${JAVA_HOME:-}/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    KEYTOOL="$JAVA_HOME/bin/keytool"
elif [ -d "$HOME/android-jdk/bin" ]; then
    JAVAC="$HOME/android-jdk/bin/javac"
    KEYTOOL="$HOME/android-jdk/bin/keytool"
elif command -v javac >/dev/null 2>&1; then
    JAVAC=$(command -v javac)
    KEYTOOL=$(command -v keytool)
else
    die "no javac (set JAVA_HOME)"
fi
# d8/apksigner are shell wrappers that call `java` from PATH.
export PATH="$(dirname "$JAVAC"):$PATH"
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="$(cd "$(dirname "$JAVAC")/.." && pwd)"
fi

command -v curl >/dev/null 2>&1 || die "curl is required for the pinned download"
command -v zip >/dev/null 2>&1 || die "zip is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

mkdir -p "$BUILD_DIR/deps" "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR" "$DIST_DIR" "$KEYSTORE_DIR"

# ---------------------------------------------------------------- 1. Xposed API
if [ -f "$XPOSED_JAR" ] && [ "$(sha256_of "$XPOSED_JAR")" = "$XPOSED_SHA256" ]; then
    say "using cached Xposed API jar ($XPOSED_JAR)"
else
    say "downloading Xposed API 82 (compile-only) from api.xposed.info"
    tmp="$XPOSED_JAR.part"
    curl -fsSL --retry 3 --max-time 60 -o "$tmp" "$XPOSED_URL" \
        || die "download failed: $XPOSED_URL"
    actual=$(sha256_of "$tmp")
    if [ "$actual" != "$XPOSED_SHA256" ]; then
        rm -f "$tmp"
        die "sha256 mismatch for $XPOSED_URL (got $actual)"
    fi
    mv "$tmp" "$XPOSED_JAR"
fi

# ---------------------------------------------------------------- 2. aapt2
say "aapt2 compile + link (minSdk $MIN_SDK, targetSdk $TARGET_SDK)"
rm -rf "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR"
mkdir -p "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR"
rm -f "$BUILD_DIR/res.zip" "$BUILD_DIR/unsigned.apk" \
      "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
(cd "$MODULE_DIR" && "$AAPT2" compile --dir res -o "$BUILD_DIR/res.zip")
"$AAPT2" link \
    -o "$BUILD_DIR/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MODULE_DIR/AndroidManifest.xml" \
    --java "$GEN_DIR" \
    -A "$MODULE_DIR/assets" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --auto-add-overlay \
    "$BUILD_DIR/res.zip"

# ---------------------------------------------------------------- 3. javac + d8
say "javac + d8"
JAVA_SOURCES=$(find "$GEN_DIR" "$MODULE_DIR/src" -name '*.java' | sort)
# shellcheck disable=SC2086
"$JAVAC" --release 11 -encoding UTF-8 -Xlint:-options \
    -classpath "$ANDROID_JAR:$XPOSED_JAR" \
    -d "$CLASSES_DIR" \
    $JAVA_SOURCES
CLASS_FILES=$(find "$CLASSES_DIR" -name '*.class' | sort)
# shellcheck disable=SC2086
"$BUILD_TOOLS/d8" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$DEX_DIR" $CLASS_FILES
[ -f "$DEX_DIR/classes.dex" ] || die "d8 produced no classes.dex"

# ---------------------------------------------------------------- 4. package
say "packaging + zipalign + signing"
cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/unaligned.apk"
(cd "$DEX_DIR" && zip -q -X "$BUILD_DIR/unaligned.apk" classes.dex)
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    say "creating a dev signing key (not a release key; never commit it)"
    "$KEYTOOL" -genkeypair -noprompt \
        -keystore "$KEYSTORE" -storetype PKCS12 \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=ztMetro keyguard dev, OU=ztOS, O=ztOS, C=RU" >/dev/null
fi

APK="$DIST_DIR/ztmetro-keyguard.apk"
rm -f "$APK"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --out "$APK" "$BUILD_DIR/aligned.apk"

# ---------------------------------------------------------------- 5. verify
say "verifying signature, manifest metadata, scope and dex contents"
VERIFY="$BUILD_DIR/verify.txt"
: > "$VERIFY"
{
    echo "## apksigner verify"
    "$APKSIGNER" verify --verbose --print-certs "$APK"
    echo
    echo "## aapt2 dump badging"
    "$AAPT2" dump badging "$APK"
    echo
    echo "## AndroidManifest xposed metadata"
    "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" \
        | grep -E 'xposed|package=|minSdkVersion|targetSdkVersion' || true
    echo
    echo "## module resources (scope array, label)"
    "$AAPT2" dump resources "$APK" \
        | grep -iE -A4 'array/xposed_scope|string/app_name' || true
    echo
    echo "## classes.dex"
    unzip -l "$APK"
    rm -f "$BUILD_DIR/classes.dex"
    unzip -p "$APK" classes.dex > "$BUILD_DIR/classes.dex"
    echo "## hook target names referenced by classes.dex"
    LC_ALL=C grep -a -o -E \
        'com\.android\.systemui\.(shade\.NotificationShadeWindowView|keyguard\.ui\.binder\.KeyguardClockViewBinder|shared\.clocks\.AnimatableClockView|statusbar\.phone\.KeyguardIndicationTextView)|com\.android\.keyguard\.KeyguardSliceView|keyguard_root_view|keyguard_slice_view' \
        "$BUILD_DIR/classes.dex" | sort -u || true
    echo "## hook target methods referenced by classes.dex"
    LC_ALL=C grep -a -o -E \
        'onAttachedToWindow|KeyguardClockViewBinder|bind|setNextIndication|showSlice|refreshTime|onMeasure|setColors' \
        "$BUILD_DIR/classes.dex" | sort -u || true
    if [ -x "$DEXDUMP" ]; then
        "$DEXDUMP" -f "$BUILD_DIR/classes.dex" | sed -n '1,40p'
        "$DEXDUMP" -d "$BUILD_DIR/classes.dex" \
            | grep -E "Class descriptor|name +: '(onAttachedToWindow|bind|setNextIndication|showSlice|refreshTime|onMeasure|setColors)'" \
            | sed -n '1,60p'
    else
        echo "dexdump not available in $BUILD_TOOLS"
    fi
} >>"$VERIFY" 2>&1

grep -q "package: name='$MODULE_PACKAGE'" "$VERIFY" \
    || die "badging does not show the module package"
grep -q "targetSdkVersion:'$TARGET_SDK'" "$VERIFY" \
    || die "badging does not show targetSdkVersion $TARGET_SDK"
grep -q "xposedmodule" "$VERIFY" || die "manifest has no xposedmodule metadata"
grep -q "xposedminversion" "$VERIFY" || die "manifest has no xposedminversion metadata"
grep -q "xposedscope" "$VERIFY" || die "manifest has no xposedscope metadata"
grep -q "assets/xposed_init" "$VERIFY" \
    || die "APK has no assets/xposed_init (legacy Xposed entry point)"
grep -q "com.android.systemui" "$VERIFY" || die "scope array does not name SystemUI"
LC_ALL=C grep -a -q "NotificationShadeWindowView" "$VERIFY" \
    || die "dex does not reference the shade window anchor"
LC_ALL=C grep -a -q "KeyguardClockViewBinder" "$VERIFY" \
    || die "dex does not reference the clock binder"
LC_ALL=C grep -a -q "AnimatableClockView" "$VERIFY" \
    || die "dex does not reference the stock clock view"
LC_ALL=C grep -a -q "KeyguardSliceView" "$VERIFY" \
    || die "dex does not reference the date slice view"
LC_ALL=C grep -a -q "KeyguardIndicationTextView" "$VERIFY" \
    || die "dex does not reference the indication view"
LC_ALL=C grep -a -q "setNextIndication" "$VERIFY" \
    || die "dex does not reference KeyguardIndicationTextView#setNextIndication"
LC_ALL=C grep -a -q "refreshTime" "$VERIFY" \
    || die "dex does not reference AnimatableClockView#refreshTime"
LC_ALL=C grep -a -q "setColors" "$VERIFY" \
    || die "dex does not reference AnimatableClockView#setColors"
if [ -x "$DEXDUMP" ]; then
    grep -q 'Lorg/ztmetro/keyguard/KeyguardHooks;' "$VERIFY" \
        || die "classes.dex does not contain KeyguardHooks"
    grep -q 'Lorg/ztmetro/keyguard/ModuleEntry;' "$VERIFY" \
        || die "classes.dex does not contain ModuleEntry"
    grep -q 'Lorg/ztmetro/keyguard/MetroKeyguard;' "$VERIFY" \
        || die "classes.dex does not contain MetroKeyguard"
fi

SIZE=$(wc -c <"$APK" | tr -d ' ')
SHA=$(sha256_of "$APK")
cat >"$BUILD_DIR/build-info.txt" <<EOF
apk=$APK
size_bytes=$SIZE
sha256=$SHA
min_sdk=$MIN_SDK
target_sdk=$TARGET_SDK
package=$MODULE_PACKAGE
xposed_api_jar=$XPOSED_JAR
xposed_api_sha256=$XPOSED_SHA256
keystore=$KEYSTORE
build_tools=$BUILD_TOOLS
verify_log=$VERIFY
EOF

say "done"
printf 'APK:    %s\n' "$APK"
printf 'SIZE:   %s bytes\n' "$SIZE"
printf 'SHA256: %s\n' "$SHA"
printf 'LOG:    %s\n' "$VERIFY"

if [ -n "${KEYGUARD_EVIDENCE_DIR:-}" ]; then
    mkdir -p "$KEYGUARD_EVIDENCE_DIR/logs"
    cp "$APK" "$KEYGUARD_EVIDENCE_DIR/ztmetro-keyguard.apk"
    cp "$VERIFY" "$KEYGUARD_EVIDENCE_DIR/logs/build-verify.txt"
    cp "$BUILD_DIR/build-info.txt" "$KEYGUARD_EVIDENCE_DIR/logs/build-info.txt"
    say "copied APK + logs to $KEYGUARD_EVIDENCE_DIR"
fi
