#!/usr/bin/env bash
# Build the ztMetro IME APK (package org.ztmetro.ime) with only the SDK + JDK.
#
#   repo/scripts/build-ime.sh
#
# No Gradle, no AndroidX, no network, no adb. Produces repo/ime/build/out/
# ztmetro-ime.apk and copies the APK plus static-verification evidence into
# repo/evidence/ime/. The keystore is a throwaway key under ime/build
# (gitignored) so rebuilds keep the same signature.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
APP="$ROOT/ime/app/src/main"
BUILD="$ROOT/ime/build"
OUT="$BUILD/out"
EVIDENCE="$ROOT/evidence/ime"
APK="$OUT/ztmetro-ime.apk"
PACKAGE="org.ztmetro.ime"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v zip >/dev/null 2>&1 || die "zip is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# --- SDK ---------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-36/android.jar" ]; then
    for candidate in "$HOME/android-sdk" "$HOME/Android/Sdk" /opt/android-sdk; do
        if [ -f "$candidate/platforms/android-36/android.jar" ]; then
            SDK="$candidate"
            break
        fi
    done
fi
[ -n "$SDK" ] && [ -f "$SDK/platforms/android-36/android.jar" ] \
    || die "Android SDK platform android-36 not found (set ANDROID_HOME)"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"

BT="$SDK/build-tools/36.0.0"
if [ ! -x "$BT/aapt2" ]; then
    BT=$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)
fi
for tool in aapt2 d8 dexdump zipalign apksigner; do
    [ -x "$BT/$tool" ] || die "missing $BT/$tool"
done

# --- JDK ---------------------------------------------------------------------
JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
    JDK="$HOME/android-jdk"
fi
[ -x "$JDK/bin/javac" ] || die "JDK with javac not found (set JAVA_HOME)"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

# --- clean (keep the keystore: signatures stay stable across rebuilds) --------
rm -rf "$BUILD/res" "$BUILD/rjava" "$BUILD/classes" "$BUILD/dex"
rm -f "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/unsigned.apk" "$BUILD/aligned.apk" \
      "$BUILD/sources.txt" "$BUILD/verify.dex"
mkdir -p "$BUILD/res" "$BUILD/rjava" "$BUILD/classes" "$BUILD/dex" \
         "$BUILD/keystore" "$OUT" "$EVIDENCE"

say "SDK   $SDK"
say "tools $BT"
say "JDK   $JDK"

# --- 1. resources ------------------------------------------------------------
say "aapt2 compile + link"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$BUILD/rjava" \
    --auto-add-overlay \
    "$BUILD/res.zip"

# --- 2. compile + dex --------------------------------------------------------
say "javac (Java 8 bytecode against android-36)"
find "$APP/java" "$BUILD/rjava" -name '*.java' -print > "$BUILD/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

say "d8"
CLASSES=()
while IFS= read -r class_file; do
    CLASSES+=("$class_file")
done < <(find "$BUILD/classes" -name '*.class' -print)
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "${CLASSES[@]}"

# --- 3. package + align + sign ----------------------------------------------
say "packaging classes.dex"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
TZ=UTC touch -t 198001010000.00 "$BUILD/dex/classes.dex"
( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex )
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KS="$BUILD/keystore/ztmetro-ime.p12"
if [ ! -f "$KS" ]; then
    say "generating throwaway signing key (gitignored)"
    "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -storepass android -keypass android -alias ztmetro-ime \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=ztMetro keyboard, O=ztmetro, C=US" >/dev/null 2>&1 \
        || die "keytool failed"
fi

say "apksigner"
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias ztmetro-ime \
    --min-sdk-version 29 \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$APK" "$BUILD/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 29 "$APK"

# --- 4. static verification --------------------------------------------------
say "verifying APK"
BADGING=$("$BT/aapt2" dump badging "$APK")
printf '%s\n' "$BADGING" | sed -n '1,8p'

printf '%s\n' "$BADGING" | grep -q "package: name='$PACKAGE'" \
    || die "package is not $PACKAGE"
printf '%s\n' "$BADGING" | grep -q "application-label:'ztMetro keyboard'" \
    || die "application label is not 'ztMetro keyboard'"
printf '%s\n' "$BADGING" | grep -q "minSdkVersion:'29'" || die "minSdk 29 missing"
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'36'" || die "targetSdk 36 missing"
printf '%s\n' "$BADGING" | grep -q "versionCode='1'" || die "versionCode 1 missing"
printf '%s\n' "$BADGING" | grep -q "versionName='1.0'" || die "versionName 1.0 missing"

XMLTREE=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK")
printf '%s\n' "$XMLTREE" | grep -q "android.permission.BIND_INPUT_METHOD" \
    || die "BIND_INPUT_METHOD is missing from the merged manifest"
printf '%s\n' "$XMLTREE" | grep -q "android.view.InputMethod" \
    || die "android.view.InputMethod intent filter missing"
printf '%s\n' "$XMLTREE" | grep -q "android.view.im" \
    || die "android.view.im meta-data missing"
printf '%s\n' "$XMLTREE" | grep -q "ZtMetroIme" \
    || die "ZtMetroIme service missing from the merged manifest"
if printf '%s\n' "$XMLTREE" | grep -q "E: uses-permission"; then
    die "the IME must not request any permission"
fi

METHODTREE=$("$BT/aapt2" dump xmltree --file res/xml/method.xml "$APK")
printf '%s\n' "$METHODTREE" | grep -q "ru-RU" || die "method.xml ru subtype missing"
printf '%s\n' "$METHODTREE" | grep -q "en-US" || die "method.xml en subtype missing"
printf '%s\n' "$METHODTREE" | grep -q "keyboard" || die "method.xml keyboard mode missing"

unzip -l "$APK" | grep -q " classes.dex" || die "classes.dex missing from APK"
unzip -p "$APK" classes.dex > "$BUILD/verify.dex"
if grep -aq "androidx" "$BUILD/verify.dex"; then
    die "AndroidX classes found in classes.dex"
fi
"$BT/dexdump" -d "$BUILD/verify.dex" > "$BUILD/dexdump.txt"
grep -q "Class descriptor  : 'Lorg/ztmetro/ime/ZtMetroIme;'" "$BUILD/dexdump.txt" \
    || die "ZtMetroIme class missing from dex"
grep -q "Class descriptor  : 'Lorg/ztmetro/ime/ZtKeyboardView;'" "$BUILD/dexdump.txt" \
    || die "ZtKeyboardView class missing from dex"
grep -q "Landroid/inputmethodservice/InputMethodService;" "$BUILD/dexdump.txt" \
    || die "ZtMetroIme is not an InputMethodService in dex"

SIZE_BYTES=$(wc -c < "$APK" | tr -d ' ')
if [ "$SIZE_BYTES" -ge 2097152 ]; then
    die "APK is larger than 2 MB ($SIZE_BYTES bytes)"
fi

if command -v sha256sum >/dev/null 2>&1; then
    SHA=$(sha256sum "$APK" | awk '{print $1}')
else
    SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
fi

# --- 5. evidence --------------------------------------------------------------
cp "$APK" "$EVIDENCE/ztmetro-ime.apk"
printf '%s  ztmetro-ime.apk\n' "$SHA" > "$EVIDENCE/ztmetro-ime.apk.sha256"
printf '%s\n' "$BADGING" > "$EVIDENCE/aapt2-badging.txt"
printf '%s\n' "$XMLTREE" > "$EVIDENCE/aapt2-manifest.xmltree.txt"
printf '%s\n' "$METHODTREE" > "$EVIDENCE/aapt2-method.xmltree.txt"
unzip -lv "$APK" > "$EVIDENCE/apk-contents.txt"
"$BT/apksigner" verify --min-sdk-version 29 --verbose --print-certs "$APK" \
    > "$EVIDENCE/apksigner-verify.txt" 2>&1 || die "apksigner verify failed"
{
    grep -E -A3 "Class descriptor  : 'Lorg/ztmetro/ime/(ZtMetroIme|ZtKeyboardView);'" \
        "$BUILD/dexdump.txt" | sed 's/^ *//'
} > "$EVIDENCE/dexdump-service.txt"
[ -s "$EVIDENCE/dexdump-service.txt" ] || die "dexdump service excerpt is empty"
{
    printf 'classes.dex: %s bytes\n' "$(wc -c < "$BUILD/verify.dex" | tr -d ' ')"
    printf 'androidx occurrences: 0 (build fails if any)\n'
    printf 'uses-permission elements: 0 (build fails if any)\n'
    printf 'service classes (dexdump):\n'
    grep -E "Class descriptor  : 'Lorg/ztmetro/ime/(ZtMetroIme|ZtKeyboardView);'" \
        "$BUILD/dexdump.txt" | sed 's/^ *//'
    printf 'superclass: Landroid/inputmethodservice/InputMethodService; (ZtMetroIme)\n'
} > "$EVIDENCE/dex-facts.txt"
{
    printf 'apk:       ime/build/out/ztmetro-ime.apk (evidence/ime/ztmetro-ime.apk)\n'
    printf 'size:      %s bytes\n' "$SIZE_BYTES"
    printf 'sha256:    %s\n' "$SHA"
    printf 'package:   %s\n' "$PACKAGE"
    printf 'version:   1 (1.0)\n'
    printf 'min/target: 29 / 36\n'
    printf 'build:     %s, %s\n' "$(basename "$SDK")" "$("$JDK/bin/java" -version 2>&1 | head -n 1)"
    printf 'checks:    package/label, BIND_INPUT_METHOD service, InputMethod filter,\n'
    printf '           android.view.im meta-data, ru+en subtypes, dexdump service,\n'
    printf '           no uses-permission, no androidx, signed v3, <2 MB\n'
} > "$EVIDENCE/artifact-facts.txt"

say "done"
printf 'APK:     %s\n' "$APK"
printf 'evidence:%s\n' "$EVIDENCE/ztmetro-ime.apk"
printf 'size:    %s bytes\n' "$SIZE_BYTES"
printf 'sha256:  %s\n' "$SHA"
printf 'verify:  package/label, IME service + BIND, ru/en subtypes, dexdump,\n'
printf '         no permissions, no androidx, v3 signature, <2 MB\n'
