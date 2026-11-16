#!/usr/bin/env bash
# Build the two ztmetro "extra surface" RROs and print their absolute APK paths:
#
#   * org.ztos.overlay.systemui.extra -> com.android.systemui
#       volume panel shell, power menu (View surfaces)
#   * org.ztos.overlay.framework.extra -> android
#       toast_frame + TextAppearance.Toast, dialog_background_material
#
# Portable + idempotent, same contract as scripts/build-overlay-sysui.sh:
#   * with a local aapt2/zipalign/apksigner it builds locally;
#   * otherwise it rsyncs repo/overlay/systemui-extra to `ssh hermes-ubuntu`
#     (~/ztux/vpt), builds there with ~/android-sdk + ~/android-jdk, and copies
#     both signed APKs back into repo/evidence/volume-power-toast/.
#   * a throwaway signing key is reused across builds so upgrades verify.
#
# Env overrides: ANDROID_SDK, ANDROID_JDK, OUT_DIR, KEYSTORE, BUILD_HOST,
#                REMOTE_WORK, BUILD_TOOLS_VERSION, DUMP=1.
#
# Usage: repo/scripts/build-overlay-systemui-extra.sh
set -euo pipefail

_self="${BASH_SOURCE[0]:-}"
if [ -n "$_self" ]; then
  REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$_self")/.." && pwd)}"
else
  REPO_ROOT="${REPO_ROOT:-$(pwd)}"
fi
SRC_ROOT="${SRC_ROOT:-$REPO_ROOT/overlay/systemui-extra}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/evidence/volume-power-toast}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
ANDROID_JDK="${ANDROID_JDK:-$HOME/android-jdk}"
REMOTE_WORK="${REMOTE_WORK:-ztux/vpt}"
KEYSTORE="${KEYSTORE:-$HOME/.ztmetro-overlay-debug.keystore}"

SYSUI_SRC="$SRC_ROOT/systemui"
FW_SRC="$SRC_ROOT/framework"
SYSUI_APK="ztmetro-systemui-extra-rro.apk"
FW_APK="ztmetro-framework-extra-rro.apk"

die() { echo "build-overlay-systemui-extra: $*" >&2; exit 1; }
note() { echo "==> $*"; }
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

find_tool() {
  local n="$1"
  if [ -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/$n" ]; then
    echo "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/$n"
  elif command -v "$n" >/dev/null 2>&1; then
    command -v "$n"
  else
    die "missing build tool: $n (looked in $ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION and PATH)"
  fi
}

build_module() { # $1 = module dir, $2 = apk name, $3 = expected target package
  local src="$1" apk_name="$2" target="$3"
  [ -f "$src/AndroidManifest.xml" ] || die "missing $src/AndroidManifest.xml"
  [ -d "$src/res" ] || die "missing $src/res"

  local work="$OUT_DIR/.build.$(basename "$src")"
  rm -rf "$work"; mkdir -p "$work" "$OUT_DIR"

  note "[$apk_name] aapt2 compile"
  "$AAPT2" compile --dir "$src/res" -o "$work/res.zip"

  note "[$apk_name] aapt2 link (target: $target)"
  if ! "$AAPT2" link \
      -o "$work/unsigned.apk" \
      -I "$ANDROID_JAR" \
      --manifest "$src/AndroidManifest.xml" \
      --min-sdk-version 36 --target-sdk-version 36 \
      --no-resource-deduping --no-resource-removal \
      "$work/res.zip" > "$work/link.log" 2>&1; then
    cat "$work/link.log" >&2
    die "[$apk_name] aapt2 link failed"
  fi
  grep -E 'warning|error' "$work/link.log" || true

  note "[$apk_name] zipalign + apksigner"
  "$ZIPALIGN" -p -f 4 "$work/unsigned.apk" "$work/aligned.apk"
  [ -f "$KEYSTORE" ] || "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias ztmetro \
      -storepass ztmetro -keypass ztmetro -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=ztmetro overlay (throwaway)" >/dev/null 2>&1 \
      || die "could not create $KEYSTORE"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:ztmetro --key-pass pass:ztmetro \
      --min-sdk-version 36 --out "$OUT_DIR/$apk_name" "$work/aligned.apk"

  note "[$apk_name] verify"
  "$APKSIGNER" verify --min-sdk-version 36 "$OUT_DIR/$apk_name"
  "$AAPT2" dump badging "$OUT_DIR/$apk_name" | grep -E '^(package|overlay|application)' || true
  printf '%s\n' "$("$AAPT2" dump xmltree "$OUT_DIR/$apk_name" --file AndroidManifest.xml)" \
      | grep -q "targetPackage.*\"$target\"" || die "[$apk_name] targetPackage is not $target"
  printf '%s\n' "$("$AAPT2" dump xmltree "$OUT_DIR/$apk_name" --file AndroidManifest.xml)" \
      | grep -q 'hasCode.*false' || die "[$apk_name] hasCode is not false"
  [ -z "$(unzip -l "$OUT_DIR/$apk_name" | grep -E 'classes[0-9]*\.dex' || true)" ] \
      || die "[$apk_name] APK contains dex code"

  local count
  count="$("$AAPT2" dump resources "$OUT_DIR/$apk_name" | grep -cE '^\s+resource 0x' || true)"
  [ "${count:-0}" -gt 0 ] || die "[$apk_name] no resources in table"
  note "[$apk_name] override entries: $count"
  if [ "${DUMP:-0}" = 1 ]; then
    "$AAPT2" dump resources "$OUT_DIR/$apk_name" \
      | grep -E '^  type |^    resource ' | sed 's/^/    /'
  fi
  rm -rf "$work"
  echo "APK      $OUT_DIR/$apk_name"
  echo "sha256   $(sha256 "$OUT_DIR/$apk_name")"
}

build_local() {
  command -v keytool >/dev/null 2>&1 || [ -x "$ANDROID_JDK/bin/keytool" ] || die "keytool not found"
  if [ -d "$ANDROID_JDK" ]; then export JAVA_HOME="$ANDROID_JDK"; PATH="$ANDROID_JDK/bin:$PATH"; fi
  AAPT2="$(find_tool aapt2)"
  ZIPALIGN="$(find_tool zipalign)"
  APKSIGNER="$(find_tool apksigner)"
  KEYTOOL="$(command -v keytool || echo "$ANDROID_JDK/bin/keytool")"
  ANDROID_JAR="$ANDROID_SDK/platforms/android-36/android.jar"
  [ -f "$ANDROID_JAR" ] || die "missing $ANDROID_JAR"
  note "SDK $ANDROID_SDK | build-tools $BUILD_TOOLS_VERSION | JDK ${JAVA_HOME:-$ANDROID_JDK}"
  build_module "$SYSUI_SRC" "$SYSUI_APK" "com.android.systemui"
  build_module "$FW_SRC" "$FW_APK" "android"
}

local_aapt2=""
if [ -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2" ]; then
  local_aapt2="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2"
elif command -v aapt2 >/dev/null 2>&1; then
  local_aapt2="$(command -v aapt2)"
fi

if [ -n "$local_aapt2" ]; then
  build_local
else
  BUILD_HOST="${BUILD_HOST:-hermes-ubuntu}"
  note "no local aapt2: building on $BUILD_HOST (~/$REMOTE_WORK)"
  ssh "$BUILD_HOST" "mkdir -p ~/$REMOTE_WORK/src ~/$REMOTE_WORK/out"
  rsync -a --delete "$SRC_ROOT/." "$BUILD_HOST:$REMOTE_WORK/src/"
  ssh "$BUILD_HOST" \
    "REPO_ROOT=~/$REMOTE_WORK SRC_ROOT=~/$REMOTE_WORK/src OUT_DIR=~/$REMOTE_WORK/out \
     BUILD_TOOLS_VERSION='$BUILD_TOOLS_VERSION' ${DUMP:+DUMP=$DUMP} bash -s" < "${BASH_SOURCE[0]}" \
    | sed "s|~/$REMOTE_WORK|$REMOTE_WORK|g"
  mkdir -p "$OUT_DIR"
  for apk in "$SYSUI_APK" "$FW_APK"; do
    scp -q "$BUILD_HOST:$REMOTE_WORK/out/$apk" "$OUT_DIR/$apk"
  done
  echo
  echo "== local copies =="
  for apk in "$SYSUI_APK" "$FW_APK"; do
    echo "APK      $OUT_DIR/$apk"
    echo "sha256   $(sha256 "$OUT_DIR/$apk")"
  done
fi
