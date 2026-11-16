#!/usr/bin/env bash
# Build the two ztmetro status-strip RROs and print their absolute paths + sha256.
#
#   * overlay/status/framework  -> framework-res RRO   (targetPackage "android")
#   * overlay/status/systemui   -> SystemUI RRO        (targetPackage com.android.systemui)
#
# Portable + idempotent:
#   * uses aapt2/zipalign/apksigner + keytool from $ANDROID_SDK / $ANDROID_JDK when
#     they are present locally; otherwise drives `ssh hermes-ubuntu` (the project
#     rule: heavy builds on hermes, workdir ~/ztux/status) and copies the APKs
#     and dump files back into evidence/status-strip/;
#   * reuses one throwaway signing key, so repeated runs are upgrade-compatible;
#   * wipes only its own scratch dir ($OUT_DIR/.build), never sources or the key;
#   * overwrites the APKs, the aapt2 dumps and the apksigner transcript.
#
# Env overrides: BUILD_LOCAL=1, BUILD_HOST, ANDROID_SDK, ANDROID_JDK,
#                BUILD_TOOLS_VERSION, OUT_DIR, REMOTE_WORK, KEYSTORE.
#
# Usage: repo/scripts/build-overlay-status.sh
set -euo pipefail

_self="${BASH_SOURCE[0]:-}"
if [ -n "$_self" ]; then
  REPO_ROOT="$(cd "$(dirname "$_self")/.." && pwd)"
else
  REPO_ROOT="$(pwd)"
fi

SRC_ROOT="${SRC_ROOT:-$REPO_ROOT/overlay/status}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/evidence/status-strip}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
ANDROID_JDK="${ANDROID_JDK:-$HOME/android-jdk}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
BUILD_HOST="${BUILD_HOST:-hermes-ubuntu}"
REMOTE_WORK="${REMOTE_WORK:-ztux/status}"
KEYSTORE="${KEYSTORE:-$HOME/.ztmetro-overlay-debug.keystore}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-36/android.jar"
PRINT_PATHS="${PRINT_PATHS:-1}"

# name:apk-name pairs (order is stable for the transcript)
OVERLAYS="framework:ztmetro-status-framework-rro.apk systemui:ztmetro-status-systemui-rro.apk"

die() { echo "build-overlay-status: $*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "no sha256 tool (need sha256sum or shasum)"
  fi
}

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

print_paths() {
  mkdir -p "$OUT_DIR"
  echo
  echo "== APKs =="
  local entry name apk abs sum
  for entry in $OVERLAYS; do
    name="${entry%%:*}"
    apk="${entry#*:}"
    abs="$(cd "$OUT_DIR" && pwd)/$apk"
    [ -f "$abs" ] || die "expected APK missing: $abs"
    sum="$(sha256_of "$abs")"
    printf 'APK    %s\nsha256 %s\n' "$abs" "$sum"
  done
}

build_one() { # name apk-name
  local name="$1" apkname="$2"
  local srcdir="$SRC_ROOT/$name"
  local work="$OUT_DIR/.build/$name"
  [ -d "$srcdir/res" ] || die "missing sources: $srcdir/res"
  [ -f "$srcdir/AndroidManifest.xml" ] || die "missing manifest: $srcdir/AndroidManifest.xml"

  rm -rf "$work"
  mkdir -p "$work" "$OUT_DIR"

  echo "== [$name] compile =="
  "$AAPT2" compile --dir "$srcdir/res" -o "$work/res.zip"

  echo "== [$name] link =="
  if ! "$AAPT2" link \
    -o "$work/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$srcdir/AndroidManifest.xml" \
    --min-sdk-version 34 \
    --target-sdk-version 36 \
    --no-resource-deduping \
    --no-resource-removal \
    "$work/res.zip" > "$work/link.log" 2>&1; then
    cat "$work/link.log" >&2
    die "aapt2 link failed for $name"
  fi
  grep -E 'error|warning' "$work/link.log" || true

  echo "== [$name] align =="
  "$ZIPALIGN" -p -f 4 "$work/unsigned.apk" "$work/aligned.apk" >/dev/null

  echo "== [$name] sign (throwaway key: $KEYSTORE) =="
  if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias ztmetro \
      -storepass ztmetro -keypass ztmetro -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=ztmetro overlay (throwaway)" >/dev/null 2>&1
  fi
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:ztmetro --key-pass pass:ztmetro \
    --min-sdk-version 34 --v4-signing-enabled false \
    --out "$OUT_DIR/$apkname" "$work/aligned.apk"
  rm -f "$OUT_DIR/$apkname.idsig"

  echo "== [$name] verify =="
  "$APKSIGNER" verify --min-sdk-version 34 --print-certs "$OUT_DIR/$apkname" \
    > "$OUT_DIR/apksigner-verify-$name.txt" 2>&1
  cat "$OUT_DIR/apksigner-verify-$name.txt"
  "$AAPT2" dump badging "$OUT_DIR/$apkname" > "$OUT_DIR/aapt2-dump-badging-$name.txt"
  "$AAPT2" dump resources "$OUT_DIR/$apkname" > "$OUT_DIR/aapt2-dump-resources-$name.txt"
  grep -E '^(package|overlay|application)' "$OUT_DIR/aapt2-dump-badging-$name.txt" || true
  if command -v unzip >/dev/null 2>&1; then
    echo "entries: $(unzip -l "$OUT_DIR/$apkname" | tail -1)"
  fi
}

build_all() {
  export JAVA_HOME="$ANDROID_JDK"
  PATH="$ANDROID_JDK/bin:$PATH"
  export PATH
  command -v keytool >/dev/null 2>&1 || die "keytool not found (set ANDROID_JDK)"
  command -v java >/dev/null 2>&1 || die "java not found (set ANDROID_JDK)"
  [ -f "$ANDROID_JAR" ] || die "missing $ANDROID_JAR"
  AAPT2="$(find_tool aapt2)"
  ZIPALIGN="$(find_tool zipalign)"
  APKSIGNER="$(find_tool apksigner)"
  KEYTOOL="$(find_tool keytool)"

  local entry name apkname
  for entry in $OVERLAYS; do
    name="${entry%%:*}"
    apkname="${entry#*:}"
    build_one "$name" "$apkname"
  done
  rm -rf "$OUT_DIR/.build"
}

# ---------------------------------------------------------------------------
# Remote child: running on the build host via `bash -s`, all tools present.
# ---------------------------------------------------------------------------
if [ "${REMOTE_CHILD:-0}" = "1" ]; then
  build_all
  [ "$PRINT_PATHS" = "1" ] && print_paths
  exit 0
fi

# ---------------------------------------------------------------------------
# Pick local or ssh build.
# ---------------------------------------------------------------------------
if [ "${BUILD_LOCAL:-0}" = "1" ] || \
   { [ -x "$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2" ] && [ -f "$ANDROID_JAR" ]; }; then
  echo "== building locally ($ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION) =="
  build_all
else
  echo "== no local aapt2/android.jar: building on $BUILD_HOST (~/$REMOTE_WORK) =="
  command -v rsync >/dev/null 2>&1 || die "rsync required for remote build"
  ssh "$BUILD_HOST" "mkdir -p ~/$REMOTE_WORK/src ~/$REMOTE_WORK/out"
  rsync -a --delete "$SRC_ROOT/." "$BUILD_HOST:$REMOTE_WORK/src/"
  # The remote half uses its own HOME defaults (SDK/JDK/keystore live on hermes).
  ssh "$BUILD_HOST" \
    "REMOTE_CHILD=1 PRINT_PATHS=0 \
     SRC_ROOT=\$HOME/$REMOTE_WORK/src OUT_DIR=\$HOME/$REMOTE_WORK/out \
     ANDROID_SDK=\$HOME/android-sdk ANDROID_JDK=\$HOME/android-jdk \
     BUILD_TOOLS_VERSION='$BUILD_TOOLS_VERSION' \
     KEYSTORE=\$HOME/.ztmetro-overlay-debug.keystore \
     bash -s" < "${BASH_SOURCE[0]}"
  mkdir -p "$OUT_DIR"
  rsync -a --exclude '.build' "$BUILD_HOST:$REMOTE_WORK/out/" "$OUT_DIR/"
fi

print_paths
