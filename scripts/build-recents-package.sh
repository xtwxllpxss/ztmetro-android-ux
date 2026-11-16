#!/usr/bin/env bash
# Build the ztmetro-recents system-change package:
#
#   1. the static framework RRO that sets config_recentsComponentName to
#      org.ztmetro.actioncentre/.RecentsActivity
#   2. the Action Centre APK with the system recents surface (RecentsActivity)
#      and the recents permissions declared
#   3. the Magisk module ztmetro-recents that installs the APK into
#      /system_ext/priv-app, the priv-app allowlist into /system_ext/etc/permissions
#      and the RRO into /product/overlay
#
# Outputs land in repo/evidence/recents-package/ with hashes and verification.
#
#   repo/scripts/build-recents-package.sh            # build here, else on hermes-ubuntu
#   repo/scripts/build-recents-package.sh --host     # force a local build
#   repo/scripts/build-recents-package.sh --remote   # force the staged host build
#
# Needs bash, coreutils, zip/unzip, python3, an Android SDK with build-tools
# (aapt2/d8/zipalign/apksigner/dexdump) and platforms/android-36/android.jar, and
# a JDK (javac/keytool). Resolution order: ANDROID_SDK_ROOT / ANDROID_HOME /
# ~/android-sdk, JAVA_HOME / ~/android-jdk / system javac.
#
# The Mac has no SDK, so by default the sources are staged to hermes-ubuntu at
# ~/ztux/recentspkg, built there, and the evidence is copied back. Signing uses
# the Action Centre keystore when it exists (so the system APK is a same-signature
# update of the deployed 1.0 build); otherwise a throwaway key is generated in the
# build directory. No key is ever written into the repository or the evidence.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PKG="$ROOT/sysui/recents-package"
EVID="$ROOT/evidence/recents-package"
APK_NAME=ztmetro-actioncentre.apk
RRO_NAME=ztmetro-recents-rro.apk
ZIP_NAME=ztmetro-recents.zip
PERM_NAME=privapp-permissions-ztmetro-recents.xml

REMOTE="${RECENTS_REMOTE:-hermes-ubuntu}"
RWORK="${RECENTS_REMOTE_DIR:-ztux/recentspkg}"
MIN_API=29
TARGET_SDK=36
MAX_APK_BYTES=2621440

MODE=auto
case "${1:-}" in
  --host)   MODE=host ;;
  --remote) MODE=remote ;;
  -h|--help)
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
    exit 0 ;;
  "") ;;
  *) echo "error: unknown argument: $1" >&2; exit 2 ;;
esac

say()  { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# --------------------------------------------------------------- toolchain --

SDK=""; BT=""; JAR=""; JDK=""

try_tools() {
  local candidate
  for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/android-sdk" /opt/android-sdk; do
    if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-$TARGET_SDK/android.jar" ]; then
      SDK="$candidate"; break
    fi
  done
  [ -n "$SDK" ] || return 1
  JAR="$SDK/platforms/android-$TARGET_SDK/android.jar"
  BT=$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)
  BT="${BT%/}"
  [ -x "$BT/aapt2" ] || return 1
  for candidate in "${JAVA_HOME:-}" "$HOME/android-jdk"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
      JDK="$candidate"; break
    fi
  done
  if [ -z "$JDK" ]; then
    candidate=$(dirname "$(dirname "$(command -v javac 2>/dev/null || true)")")
    [ -x "$candidate/bin/javac" ] && JDK="$candidate"
  fi
  [ -n "$JDK" ] || return 1
  export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH"
  return 0
}

need_tool() { [ -x "$BT/$1" ] || die "missing $BT/$1"; }

# ---------------------------------------------------------------- keystore --

KS=""; KS_ALIAS="ztmetro-recents"; KS_PASS="android"

resolve_keystore() {
  local candidate
  for candidate in "${RECENTS_KEYSTORE:-}" \
                   "$ROOT/actioncentre/build/keystore/actioncentre.p12"; do
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then
      KS="$candidate"; KS_ALIAS="ztmetro-actioncentre"; return 0
    fi
  done
  KS="$W/keystore/ztmetro-recents.p12"
  mkdir -p "$(dirname "$KS")"
  say "generating a throwaway signing key in the build dir (not committed)"
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=ztmetro recents package, O=ztOS, C=US" >/dev/null 2>&1 \
    || die "keytool failed"
}

sign_apk() { # unsigned.apk signed.apk
  "$BT/apksigner" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --min-sdk-version "$MIN_API" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$2" "$1"
  "$BT/apksigner" verify --min-sdk-version "$MIN_API" "$2" >/dev/null \
    || die "apksigner verify failed for $2"
}

# ------------------------------------------------------------------ build ---

build_rro() {
  say "RRO: aapt2 compile + link (target package android)"
  "$BT/aapt2" compile --dir "$PKG/rro/res" -o "$W/rro-res.zip"
  "$BT/aapt2" link -o "$W/rro-unsigned.apk" -I "$JAR" \
    --manifest "$PKG/rro/AndroidManifest.xml" --auto-add-overlay "$W/rro-res.zip"
  sign_apk "$W/rro-unsigned.apk" "$W/$RRO_NAME"
}

build_apk() {
  say "APK: staging the Action Centre tree (repo/actioncentre is not modified)"
  cp -a "$ROOT/actioncentre/app/src/main" "$W/app"
  cp "$PKG/app/RecentsActivity.java" \
     "$W/app/java/org/ztmetro/actioncentre/RecentsActivity.java"
  cp "$PKG/app/res/values/recents_package.xml" "$W/app/res/values/"

  say "APK: manifest permissions + versionCode 2 (supersedes the data 1.0 install)"
  python3 - "$W/app/AndroidManifest.xml" "$PKG/app/AndroidManifest.permissions.xml" <<'PY'
import sys
manifest_path, fragment_path = sys.argv[1], sys.argv[2]
with open(manifest_path, encoding="utf-8") as f:
    text = f.read()
anchor = '    <uses-permission android:name="android.permission.REORDER_TASKS" />'
if text.count(anchor) != 1:
    sys.exit("manifest anchor (REORDER_TASKS) not found exactly once")
with open(fragment_path, encoding="utf-8") as f:
    fragment = f.read().strip()
text = text.replace(anchor, anchor + "\n\n" + fragment)
if 'android:versionCode="1"' in text:
    text = text.replace('android:versionCode="1"', 'android:versionCode="2"', 1)
if 'android:versionName="1.0"' in text:
    text = text.replace('android:versionName="1.0"', 'android:versionName="1.0-recents"', 1)
with open(manifest_path, "w", encoding="utf-8") as f:
    f.write(text)
PY
  [ $? -eq 0 ] || die "manifest staging failed"

  say "APK: aapt2 / javac / d8"
  mkdir -p "$W/classes" "$W/dex"
  "$BT/aapt2" compile --dir "$W/app/res" -o "$W/res.zip"
  "$BT/aapt2" link -o "$W/base.apk" -I "$JAR" \
    --manifest "$W/app/AndroidManifest.xml" --java "$W/rjava" \
    -A "$W/app/assets" --auto-add-overlay "$W/res.zip"
  find "$W/app/java" "$W/rjava" -name '*.java' -print > "$W/sources.txt"
  "$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$JAR" -d "$W/classes" @"$W/sources.txt"
  find "$W/classes" -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --release --min-api "$MIN_API" --lib "$JAR" --output "$W/dex"

  cp "$W/base.apk" "$W/unsigned.apk"
  TZ=UTC touch -t 198001010000.00 "$W/dex/classes.dex"
  ( cd "$W/dex" && zip -q -X "$W/unsigned.apk" classes.dex )
  "$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
  sign_apk "$W/aligned.apk" "$W/$APK_NAME"
}

build_module() {
  say "module: assembling ztmetro-recents"
  MOD="$W/module"
  cp -a "$PKG/module/." "$MOD/"
  mkdir -p "$MOD/system_ext/priv-app/ActionCentre" \
           "$MOD/system_ext/etc/permissions" \
           "$MOD/system/product/overlay"
  cp "$W/$APK_NAME" "$MOD/system_ext/priv-app/ActionCentre/$APK_NAME"
  cp "$W/$RRO_NAME" "$MOD/system/product/overlay/$RRO_NAME"
  chmod 0644 "$MOD/system_ext/priv-app/ActionCentre/$APK_NAME" \
             "$MOD/system/product/overlay/$RRO_NAME" \
             "$MOD/system_ext/etc/permissions/$PERM_NAME" "$MOD/module.prop"
  chmod 0755 "$MOD/customize.sh" "$MOD/service.sh" "$MOD/uninstall.sh"
  rm -f "$W/$ZIP_NAME"
  ( cd "$MOD" && zip -q -X -r "$W/$ZIP_NAME" \
      module.prop customize.sh service.sh uninstall.sh system system_ext )
}

# ------------------------------------------------------------- verification --

TOTAL=0; FAILED=0; CHECKS="$EVID/verify.txt"

# The haystack can be a 581 KB dexdump; writing it to a file avoids the
# printf|grep -q SIGPIPE that pipefail would report as a failed check.
check() { # description haystack needle
  TOTAL=$((TOTAL + 1))
  printf '%s\n' "$2" > "$W/check-haystack.txt"
  if grep -qF -- "$3" "$W/check-haystack.txt"; then
    printf 'PASS  %s\n' "$1" >> "$CHECKS"
  else
    printf 'FAIL  %s   (missing: %s)\n' "$1" "$3" >> "$CHECKS"
    FAILED=$((FAILED + 1))
  fi
}

check_re() { # description haystack regex
  TOTAL=$((TOTAL + 1))
  printf '%s\n' "$2" > "$W/check-haystack.txt"
  if grep -qE -- "$3" "$W/check-haystack.txt"; then
    printf 'PASS  %s\n' "$1" >> "$CHECKS"
  else
    printf 'FAIL  %s   (no match: %s)\n' "$1" "$3" >> "$CHECKS"
    FAILED=$((FAILED + 1))
  fi
}

check_absent() { # description haystack needle
  TOTAL=$((TOTAL + 1))
  printf '%s\n' "$2" > "$W/check-haystack.txt"
  if grep -qF -- "$3" "$W/check-haystack.txt"; then
    printf 'FAIL  %s   (unexpected: %s)\n' "$1" "$3" >> "$CHECKS"
    FAILED=$((FAILED + 1))
  else
    printf 'PASS  %s\n' "$1" >> "$CHECKS"
  fi
}

verify_all() {
  say "verification"
  mkdir -p "$EVID"
  : > "$CHECKS"

  local rro="$W/$RRO_NAME" apk="$W/$APK_NAME" zip="$W/$ZIP_NAME"
  local rro_manifest rro_resources apk_badging apk_manifest dex_dump zip_list

  rro_manifest=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$rro")
  printf '%s\n' "$rro_manifest" > "$EVID/rro-manifest.xmltree.txt"
  rro_resources=$("$BT/aapt2" dump resources "$rro")
  printf '%s\n' "$rro_resources" > "$EVID/rro-resources.txt"
  apk_badging=$("$BT/aapt2" dump badging "$apk")
  printf '%s\n' "$apk_badging" > "$EVID/apk-badging.txt"
  apk_manifest=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
  printf '%s\n' "$apk_manifest" > "$EVID/apk-manifest.xmltree.txt"
  "$BT/dexdump" "$W/dex/classes.dex" > "$W/dexdump.txt" 2>/dev/null || true
  dex_dump=$(cat "$W/dexdump.txt")
  zip_list=$(unzip -l "$zip")

  check_re "RRO targets package android" "$rro_manifest" \
    'targetPackage\(0x[0-9a-f]+\)="android"'
  check_re "RRO is static (immutable, applied at boot)" "$rro_manifest" \
    'isStatic\(0x[0-9a-f]+\)=true'
  check "RRO overrides config_recentsComponentName" "$rro_resources" \
    'config_recentsComponentName'
  check "RRO points at the Action Centre RecentsActivity" "$rro_resources" \
    'org.ztmetro.actioncentre/org.ztmetro.actioncentre.RecentsActivity'
  check_absent "RRO carries no dex code" "$(unzip -l "$rro")" 'classes.dex'
  check "RRO signature verifies" \
    "$("$BT/apksigner" verify --min-sdk-version "$MIN_API" "$rro" 2>&1; echo rc=$?)" \
    'rc=0'

  check "APK package is org.ztmetro.actioncentre" "$apk_badging" \
    "package: name='org.ztmetro.actioncentre'"
  check "APK versionCode is 2" "$apk_badging" "versionCode='2'"
  check "APK minSdk 29" "$apk_badging" "minSdkVersion:'29'"
  check "APK targetSdk 36" "$apk_badging" "targetSdkVersion:'36'"
  for perm in REAL_GET_TASKS REMOVE_TASKS MANAGE_ACTIVITY_TASKS START_TASKS_FROM_RECENTS; do
    check "APK requests android.permission.$perm" "$apk_manifest" \
      "android.permission.$perm"
  done
  check "RecentsActivity class in dex" "$dex_dump" \
    "Class descriptor  : 'Lorg/ztmetro/actioncentre/RecentsActivity;'"
  check "recents surface lists tasks (getRunningTasks)" \
    "$(LC_ALL=C grep -a -o -F 'getRunningTasks' "$W/dex/classes.dex" | head -n 1)" \
    "getRunningTasks"
  check "recents surface removes tasks (removeTask)" "$dex_dump" "removeTask"
  check "removal uses the Android 16 entry point (ActivityTaskManager)" \
    "$(LC_ALL=C grep -a -o -F 'android.app.ActivityTaskManager' "$W/dex/classes.dex" | head -n 1)" \
    "android.app.ActivityTaskManager"
  check "removal falls back to the activity_task AIDL transaction" \
    "$(LC_ALL=C grep -a -o -F 'android.os.ServiceManager' "$W/dex/classes.dex" | head -n 1)" \
    "android.os.ServiceManager"
  check "AIDL fallback uses the IActivityTaskManager interface token" \
    "$(LC_ALL=C grep -a -o -F 'android.app.IActivityTaskManager' "$W/dex/classes.dex" | head -n 1)" \
    "android.app.IActivityTaskManager"
  check "APK signature verifies" \
    "$("$BT/apksigner" verify --min-sdk-version "$MIN_API" "$apk" 2>&1; echo rc=$?)" \
    'rc=0'
  TOTAL=$((TOTAL + 1))
  if [ "$(wc -c < "$apk")" -le "$MAX_APK_BYTES" ]; then
    printf 'PASS  APK size <= %s bytes\n' "$MAX_APK_BYTES" >> "$CHECKS"
  else
    printf 'FAIL  APK size > %s bytes\n' "$MAX_APK_BYTES" >> "$CHECKS"
    FAILED=$((FAILED + 1))
  fi

  for entry in module.prop customize.sh service.sh uninstall.sh \
               "system_ext/priv-app/ActionCentre/$APK_NAME" \
               "system_ext/etc/permissions/$PERM_NAME" \
               "system/product/overlay/$RRO_NAME"; do
    check "module zip contains $entry" "$zip_list" "$entry"
  done
  check "module.prop id=ztmetro-recents" "$(unzip -p "$zip" module.prop)" \
    'id=ztmetro-recents'

  local tmp="$W/unzipped"
  rm -rf "$tmp"; mkdir -p "$tmp"
  ( cd "$tmp" && unzip -q "$zip" )
  check "module APK is byte-identical to the built APK" \
    "$(sha256 "$tmp/system_ext/priv-app/ActionCentre/$APK_NAME")" "$(sha256 "$apk")"
  check "module RRO is byte-identical to the built RRO" \
    "$(sha256 "$tmp/system/product/overlay/$RRO_NAME")" "$(sha256 "$rro")"
  check "privapp XML names the package" \
    "$(cat "$tmp/system_ext/etc/permissions/$PERM_NAME")" \
    'package="org.ztmetro.actioncentre"'
  for perm in REAL_GET_TASKS REMOVE_TASKS MANAGE_ACTIVITY_TASKS START_TASKS_FROM_RECENTS; do
    check "privapp XML lists $perm" \
      "$(cat "$tmp/system_ext/etc/permissions/$PERM_NAME")" "android.permission.$perm"
  done

  # Keep the human-readable evidence next to the hashes.
  cp "$W/dexdump.txt" "$EVID/apk-dexdump.txt"
  cp "$PKG/README.md" "$EVID/README.md" 2>/dev/null || true
  chmod 0644 "$EVID"/*.txt "$EVID"/*.md 2>/dev/null || true

  say "verification: $((TOTAL - FAILED))/$TOTAL checks passed"
}

# ------------------------------------------------------------------ output --

publish() {
  mkdir -p "$EVID"
  cp "$W/$ZIP_NAME" "$EVID/$ZIP_NAME"
  cp "$W/$APK_NAME" "$EVID/$APK_NAME"
  cp "$W/$RRO_NAME" "$EVID/$RRO_NAME"
  cp "$W/module/system_ext/etc/permissions/$PERM_NAME" "$EVID/$PERM_NAME"

  local signer
  signer=$("$BT/apksigner" verify --print-certs "$W/$APK_NAME" \
    | grep -m1 'SHA-256 digest' | sed 's/.*digest: //')

  {
    printf '%s  %s\n' "$(sha256 "$EVID/$ZIP_NAME")" "$ZIP_NAME"
    printf '%s  %s\n' "$(sha256 "$EVID/$APK_NAME")" "$APK_NAME"
    printf '%s  %s\n' "$(sha256 "$EVID/$RRO_NAME")" "$RRO_NAME"
    printf '%s  %s\n' "$(sha256 "$EVID/$PERM_NAME")" "$PERM_NAME"
  } > "$EVID/hashes.txt"

  {
    printf '# ztmetro-recents build report\n\n'
    printf -- '- built: %s UTC\n' "$(date -u '+%Y-%m-%d %H:%M')"
    printf -- '- host: %s\n' "$(uname -srm)"
    printf -- '- SDK: %s\n' "$SDK"
    printf -- '- android.jar: %s\n' "$JAR"
    printf -- '- signing key: %s (alias %s)\n' "$KS" "$KS_ALIAS"
    printf -- '- APK signer SHA-256: %s\n\n' "$signer"
    printf '## Artifacts\n\n```\n'
    cat "$EVID/hashes.txt"
    printf '```\n\n'
    printf '## Verification\n\n%s\n\n' \
      "$(grep -c '^PASS' "$EVID/verify.txt" || true) checks PASS, $(grep -c '^FAIL' "$EVID/verify.txt" || true) FAIL"
    printf 'Full log: `verify.txt`. Install / verify / rollback: `README.md`.\n'
  } > "$EVID/REPORT.md"

  say "artifacts written to $EVID"
  cat "$EVID/hashes.txt"
  if [ "$FAILED" -ne 0 ]; then
    printf '\nFAILED CHECKS:\n' >&2
    grep '^FAIL' "$CHECKS" >&2 || true
    return 1
  fi
  return 0
}

# ------------------------------------------------------------------- modes --

build_local() {
  W=$(mktemp -d "${TMPDIR:-/tmp}/ztmetro-recents.XXXXXX")
  cleanup() { rm -rf "$W"; }
  trap cleanup EXIT
  try_tools || die "local toolchain incomplete"
  need_tool aapt2; need_tool d8; need_tool zipalign; need_tool apksigner
  say "SDK          $SDK"
  say "build-tools  $BT ($("$BT/aapt2" version 2>&1 | head -n 1))"
  say "android.jar  $JAR"
  say "JDK          $JDK"
  resolve_keystore
  build_rro
  build_apk
  build_module
  verify_all
  publish
}

stage_and_remote() {
  command -v rsync >/dev/null || die "rsync is required for the staged build"
  say "staging sources on $REMOTE:$RWORK/repo"
  ssh "$REMOTE" "mkdir -p '$RWORK/repo/scripts' '$RWORK/repo/evidence' \
      '$RWORK/repo/actioncentre' '$RWORK/repo/sysui'" \
    || die "cannot reach $REMOTE"
  rsync -a --delete --exclude build --exclude .git \
    "$ROOT/actioncentre/app" "$REMOTE:$RWORK/repo/actioncentre/" \
    || die "rsync of the Action Centre sources failed"
  rsync -a --delete "$PKG/" "$REMOTE:$RWORK/repo/sysui/recents-package/" \
    || die "rsync of the recents package sources failed"
  scp -q "$0" "$REMOTE:$RWORK/repo/scripts/build-recents-package.sh" \
    || die "scp of the build script failed"
  ssh "$REMOTE" "mkdir -p '$RWORK/repo/actioncentre/build/keystore'; \
      cp ~/ztux/actioncentre/build/keystore/actioncentre.p12 \
         '$RWORK/repo/actioncentre/build/keystore/' 2>/dev/null || true; \
      bash '$RWORK/repo/scripts/build-recents-package.sh' --host" \
    || die "remote build failed (log above)"
  mkdir -p "$EVID"
  rsync -a --delete "$REMOTE:$RWORK/repo/evidence/recents-package/" "$EVID/" \
    || die "copying the evidence back failed"
  say "artifacts in $EVID"
  cat "$EVID/hashes.txt"
}

if [ "$MODE" = host ]; then
  build_local
elif [ "$MODE" = remote ]; then
  stage_and_remote
elif try_tools; then
  build_local
else
  stage_and_remote
fi
