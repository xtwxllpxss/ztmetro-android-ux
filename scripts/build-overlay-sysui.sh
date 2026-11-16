#!/usr/bin/env bash
# Build the ztmetro SystemUI RRO (preinstalled overlay for com.android.systemui,
# LineageOS 23.2 / Android 16) and print the absolute APK path on the last line.
#
# Portable + idempotent:
#   * works on the build host (aapt2/zipalign/apksigner + android.jar) and, from a
#     machine without the SDK, drives `ssh hermes-ubuntu` (the project rule: heavy
#     builds on hermes, workdir ~/ztux/sysui-rro);
#   * reuses a throwaway signing key, so repeated builds are upgrade-compatible;
#   * rebuilds from a clean work dir but never touches sources or the key;
#   * verifies the result with `aapt2 dump badging` and `apksigner verify`.
#
# Env overrides: ANDROID_SDK, ANDROID_JDK, OUT_DIR, KEYSTORE, BUILD_HOST,
#                REMOTE_WORK (remote build dir), BUILD_TOOLS_VERSION.
#                DUMP=1 additionally prints the signed overlay's resource table
#                (every entry idmap2 can map) next to its sha256.
#
# Usage: repo/scripts/build-overlay-sysui.sh
set -euo pipefail

# BASH_SOURCE is empty when this file is piped to `bash -s` on the build host.
_self="${BASH_SOURCE[0]:-}"
if [ -n "$_self" ]; then
  REPO_ROOT="$(cd "$(dirname "$_self")/.." && pwd)"
else
  REPO_ROOT="$(pwd)"
fi
SRC_DIR="${SRC_DIR:-$REPO_ROOT/overlay/systemui}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/evidence/sysui-rro}"
APK_NAME="${APK_NAME:-ztmetro-systemui-rro.apk}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
ANDROID_JDK="${ANDROID_JDK:-$HOME/android-jdk}"
REMOTE_WORK="${REMOTE_WORK:-ztux/sysui-rro/build}"
KEYSTORE="${KEYSTORE:-$HOME/.ztmetro-overlay-debug.keystore}"

die() { echo "build-overlay-sysui: $*" >&2; exit 1; }

find_tool() { # name -> absolute path
  local n="$1"
  if [ -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/$n" ]; then
    echo "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/$n"
  elif command -v "$n" >/dev/null 2>&1; then
    command -v "$n"
  else
    die "missing build tool: $n (looked in $ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION and PATH)"
  fi
}

build_local() {
  command -v keytool >/dev/null 2>&1 || [ -x "$ANDROID_JDK/bin/keytool" ] \
    || die "keytool not found"
  local aapt2 zipalign apksigner java_home androidjar keytool
  [ -d "$ANDROID_JDK" ] && export JAVA_HOME="$ANDROID_JDK" && PATH="$ANDROID_JDK/bin:$PATH"
  aapt2="$(find_tool aapt2)"
  zipalign="$(find_tool zipalign)"
  apksigner="$(find_tool apksigner)"
  keytool="$(command -v keytool || echo "$ANDROID_JDK/bin/keytool")"
  androidjar="$ANDROID_SDK/platforms/android-36/android.jar"
  [ -f "$androidjar" ] || die "missing $androidjar"

  local work="$OUT_DIR/.build"
  rm -rf "$work" && mkdir -p "$work/res" "$work/gen" "$OUT_DIR"
  echo "== compile =="
  "$aapt2" compile --dir "$SRC_DIR/res" -o "$work/res.zip"

  echo "== link =="
  if ! "$aapt2" link \
    -o "$work/unsigned.apk" \
    -I "$androidjar" \
    --manifest "$SRC_DIR/AndroidManifest.xml" \
    --min-sdk-version 34 \
    --target-sdk-version 36 \
    --no-resource-deduping \
    --no-resource-removal \
    "$work/res.zip" > "$work/link.log" 2>&1; then
    cat "$work/link.log" >&2
    die "aapt2 link failed"
  fi
  grep -E 'error|warning' "$work/link.log" || true

  echo "== align =="
  "$zipalign" -p -f 4 "$work/unsigned.apk" "$work/aligned.apk"

  echo "== sign (throwaway key: $KEYSTORE) =="
  if [ ! -f "$KEYSTORE" ]; then
    "$keytool" -genkeypair -keystore "$KEYSTORE" -alias ztmetro \
      -storepass ztmetro -keypass ztmetro -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=ztmetro overlay (throwaway)" >/dev/null 2>&1
  fi
  "$apksigner" sign --ks "$KEYSTORE" --ks-pass pass:ztmetro --key-pass pass:ztmetro \
    --min-sdk-version 34 --out "$OUT_DIR/$APK_NAME" "$work/aligned.apk"

  echo "== verify =="
  "$apksigner" verify --min-sdk-version 34 "$OUT_DIR/$APK_NAME"
  "$aapt2" dump badging "$OUT_DIR/$APK_NAME" | grep -E '^(package|overlay|application)' || true
  echo "entries: $(unzip -l "$OUT_DIR/$APK_NAME" | tail -1)"
  if [ "${DUMP:-0}" = 1 ]; then
    echo "== overlay resource table (what idmap2 can map) =="
    "$aapt2" dump resources "$OUT_DIR/$APK_NAME" | grep -E '^  type |^    resource '
  fi
  rm -rf "$work"
  ( cd "$OUT_DIR" && shasum -a 256 "$APK_NAME" 2>/dev/null || sha256sum "$APK_NAME" )
  echo "$OUT_DIR/$APK_NAME"
}

# ---- remote mode: no SDK on this machine -> build on hermes, copy the APK back ----
if [ ! -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2" ] && \
   ! command -v aapt2 >/dev/null 2>&1; then
  BUILD_HOST="${BUILD_HOST:-hermes-ubuntu}"
  echo "== no local aapt2: building on $BUILD_HOST (~/$REMOTE_WORK) =="
  ssh "$BUILD_HOST" "mkdir -p ~/$REMOTE_WORK/src ~/$REMOTE_WORK/out"
  rsync -a --delete "$SRC_DIR/." "$BUILD_HOST:$REMOTE_WORK/src/"
  # The remote half uses its own HOME defaults (SDK/JDK/keystore live on hermes).
  ssh "$BUILD_HOST" \
    "SRC_DIR=~/$REMOTE_WORK/src OUT_DIR=~/$REMOTE_WORK/out APK_NAME='$APK_NAME' \
     BUILD_TOOLS_VERSION='$BUILD_TOOLS_VERSION' ${DUMP:+DUMP=$DUMP} bash -s" < "${BASH_SOURCE[0]}" \
    | sed "s|~/$REMOTE_WORK|$REMOTE_WORK|g"
  mkdir -p "$OUT_DIR"
  scp -q "$BUILD_HOST:$REMOTE_WORK/out/$APK_NAME" "$OUT_DIR/$APK_NAME"
  echo "$OUT_DIR/$APK_NAME"
else
  build_local
fi
