#!/usr/bin/env bash
# Build the ztmetro Settings RRO (preinstalled overlay for com.android.settings,
# LineageOS 23.2 / Android 16) and print the absolute APK path on the last line.
#
# Portable + idempotent:
#   * runs on a build host that has aapt2/zipalign/apksigner + android.jar and, from a
#     machine without an SDK, drives `ssh hermes-ubuntu` (project rule: heavy builds on
#     hermes, work dir ~/ztux/settings-rro);
#   * reuses a throwaway signing key, so repeated builds stay upgrade-compatible;
#   * rebuilds in a scratch dir under the output dir and never touches sources or the key;
#   * fails unless the APK really is an RRO: manifest says
#       <overlay android:targetPackage="com.android.settings">, android:hasCode="false",
#       there is no classes*.dex, and `aapt2 dump resources` shows every entry.
#
# Env overrides: ANDROID_SDK, ANDROID_JDK, OUT_DIR, KEYSTORE, BUILD_HOST,
#                REMOTE_WORK, BUILD_TOOLS_VERSION, APK_NAME.
#
# Usage: repo/scripts/build-overlay-settings.sh [--out DIR]
set -euo pipefail

# BASH_SOURCE is empty when this file is piped to `bash -s` on the build host.
_self="${BASH_SOURCE[0]:-}"
if [ -n "$_self" ]; then
  REPO_ROOT="$(cd "$(dirname "$_self")/.." && pwd)"
else
  REPO_ROOT="$(pwd)"
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --out=*) OUT_DIR="${1#--out=}"; shift ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]:-$0}"; exit 0 ;;
    *) echo "build-overlay-settings: unknown arg: $1" >&2; exit 2 ;;
  esac
done

SRC_DIR="${SRC_DIR:-$REPO_ROOT/overlay/settings}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/evidence/settings-rro}"
APK_NAME="${APK_NAME:-ztmetro-settings-rro.apk}"
TARGET_PACKAGE="${TARGET_PACKAGE:-com.android.settings}"
TARGET_SDK="${TARGET_SDK:-36}"
MIN_SDK="${MIN_SDK:-34}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
ANDROID_JDK="${ANDROID_JDK:-$HOME/android-jdk}"
REMOTE_WORK="${REMOTE_WORK:-ztux/settings-rro/build}"
KEYSTORE="${KEYSTORE:-$HOME/.ztmetro-overlay-debug.keystore}"

die() { echo "build-overlay-settings: $*" >&2; exit 1; }

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

sha256_of() { ( cd "$(dirname "$1")" && { shasum -a 256 "$(basename "$1")" 2>/dev/null || sha256sum "$(basename "$1")"; } ) | awk '{print $1}'; }

build_local() {
  local aapt2 zipalign apksigner keytool androidjar
  [ -d "$ANDROID_JDK" ] && export JAVA_HOME="$ANDROID_JDK" && PATH="$ANDROID_JDK/bin:$PATH"
  aapt2="$(find_tool aapt2)"
  zipalign="$(find_tool zipalign)"
  apksigner="$(find_tool apksigner)"
  keytool="$(command -v keytool || echo "$ANDROID_JDK/bin/keytool")"
  androidjar="$ANDROID_SDK/platforms/android-$TARGET_SDK/android.jar"
  [ -f "$androidjar" ] || die "missing $androidjar"
  [ -d "$SRC_DIR/res" ] || die "missing $SRC_DIR/res"

  local aapt2_version
  aapt2_version="$("$aapt2" version 2>&1 | head -1)"
  echo "== SDK          $ANDROID_SDK"
  echo "== build-tools  $aapt2_version"
  echo "== android.jar  $androidjar"
  echo "== JDK          ${JAVA_HOME:-$ANDROID_JDK}"
  echo "== source       $SRC_DIR"

  local work="$OUT_DIR/.build"
  rm -rf "$work" && mkdir -p "$work" "$OUT_DIR"

  echo "== aapt2 compile"
  "$aapt2" compile --dir "$SRC_DIR/res" -o "$work/res.zip" 2>&1 | sed 's/^/   /'

  echo "== aapt2 link (target: $TARGET_PACKAGE)"
  if ! "$aapt2" link \
      -o "$work/unsigned.apk" \
      -I "$androidjar" \
      --manifest "$SRC_DIR/AndroidManifest.xml" \
      --min-sdk-version "$MIN_SDK" \
      --target-sdk-version "$TARGET_SDK" \
      --auto-add-overlay \
      --no-resource-deduping \
      --no-resource-removal \
      "$work/res.zip" > "$work/link.log" 2>&1; then
    cat "$work/link.log" >&2
    die "aapt2 link failed"
  fi
  sed 's/^/   /' "$work/link.log"

  echo "== zipalign"
  "$zipalign" -p -f 4 "$work/unsigned.apk" "$work/aligned.apk"

  echo "== sign (throwaway key: $KEYSTORE)"
  if [ ! -f "$KEYSTORE" ]; then
    "$keytool" -genkeypair -keystore "$KEYSTORE" -alias ztmetro \
      -storepass ztmetro -keypass ztmetro -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=ztmetro overlay (throwaway)" >/dev/null 2>&1
  fi
  "$apksigner" sign --ks "$KEYSTORE" --ks-pass pass:ztmetro --key-pass pass:ztmetro \
    --min-sdk-version "$MIN_SDK" --out "$OUT_DIR/$APK_NAME" "$work/aligned.apk"
  "$apksigner" verify --min-sdk-version "$MIN_SDK" "$OUT_DIR/$APK_NAME" >/dev/null \
    && echo "   apksigner verify: OK"

  echo "== verify overlay semantics"
  "$aapt2" dump xmltree "$OUT_DIR/$APK_NAME" --file AndroidManifest.xml > "$work/manifest.txt"
  grep -q 'A: package="org.ztos.overlay.settings"' "$work/manifest.txt" \
    || die "unexpected overlay package name"
  grep -Eq "E: overlay .*|targetPackage\(0x[0-9a-f]+\)=\"$TARGET_PACKAGE\"" "$work/manifest.txt" \
    || die "targetPackage is not $TARGET_PACKAGE"
  grep -Eq 'hasCode\(0x[0-9a-f]+\)=false' "$work/manifest.txt" \
    || die "manifest does not set hasCode=false"
  if unzip -l "$OUT_DIR/$APK_NAME" | grep -q 'classes.*\.dex'; then
    die "APK contains dex: an RRO must be resources only"
  fi

  echo "== verify resources"
  "$aapt2" dump resources "$OUT_DIR/$APK_NAME" > "$work/resources.txt"
  local entries
  entries="$(grep -c '^    resource 0x' "$work/resources.txt")"
  [ "$entries" -gt 0 ] || die "aapt2 dump resources reports no entries"
  echo "   package: $(grep -m1 '^Package name=' "$work/resources.txt")"
  echo "   overridden resource entries: $entries"
  cp "$work/resources.txt" "$OUT_DIR/aapt2-dump-resources.txt"
  cp "$work/manifest.txt" "$OUT_DIR/aapt2-dump-manifest.txt"
  "$aapt2" dump badging "$OUT_DIR/$APK_NAME" > "$OUT_DIR/aapt2-dump-badging.txt" 2>&1 || true

  local size sha
  size="$(wc -c < "$OUT_DIR/$APK_NAME" | tr -d ' ')"
  sha="$(sha256_of "$OUT_DIR/$APK_NAME")"
  rm -rf "$work"

  cat <<EOF

APK      $OUT_DIR/$APK_NAME
package  org.ztos.overlay.settings -> $TARGET_PACKAGE
entries  $entries
sha256   $sha
size     $size bytes
EOF
  echo "$OUT_DIR/$APK_NAME"
}

# ---- remote mode: no local aapt2 -> build on hermes, copy the APK back ----
if [ ! -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2" ] && \
   ! command -v aapt2 >/dev/null 2>&1; then
  BUILD_HOST="${BUILD_HOST:-hermes-ubuntu}"
  echo "== no local aapt2: building on $BUILD_HOST (~/$REMOTE_WORK)"
  ssh "$BUILD_HOST" "mkdir -p ~/$REMOTE_WORK/src ~/$REMOTE_WORK/out"
  rsync -a --delete "$SRC_DIR/." "$BUILD_HOST:$REMOTE_WORK/src/"
  # The remote half uses its own HOME defaults (SDK/JDK/keystore live on hermes).
  ssh "$BUILD_HOST" \
    "SRC_DIR=~/$REMOTE_WORK/src OUT_DIR=~/$REMOTE_WORK/out APK_NAME='$APK_NAME' \
     BUILD_TOOLS_VERSION='$BUILD_TOOLS_VERSION' TARGET_SDK='$TARGET_SDK' \
     TARGET_PACKAGE='$TARGET_PACKAGE' bash -s" < "${BASH_SOURCE[0]}" \
    | sed "s|~/$REMOTE_WORK|$REMOTE_WORK|g"
  mkdir -p "$OUT_DIR"
  scp -q "$BUILD_HOST:$REMOTE_WORK/out/$APK_NAME" "$OUT_DIR/$APK_NAME"
  for f in aapt2-dump-resources.txt aapt2-dump-manifest.txt aapt2-dump-badging.txt; do
    scp -q "$BUILD_HOST:$REMOTE_WORK/out/$f" "$OUT_DIR/$f" 2>/dev/null || true
  done
  echo
  echo "APK      $OUT_DIR/$APK_NAME"
  echo "package  org.ztos.overlay.settings -> $TARGET_PACKAGE"
  echo "sha256   $(sha256_of "$OUT_DIR/$APK_NAME")"
  echo "size     $(wc -c < "$OUT_DIR/$APK_NAME" | tr -d ' ') bytes"
  echo "$OUT_DIR/$APK_NAME"
else
  build_local
fi
