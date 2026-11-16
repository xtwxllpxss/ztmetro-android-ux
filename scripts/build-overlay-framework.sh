#!/usr/bin/env bash
# Build the ztmetro framework-res RRO (target package "android").
#
# Portability contract: needs only bash, coreutils, unzip, python3 (optional),
# an Android SDK with build-tools 34+ and platforms/android-3x/android.jar, and
# a JDK (keytool + java for apksigner).  No absolute user paths: everything is
# resolved from ANDROID_SDK_ROOT / ANDROID_HOME / JAVA_HOME, or from the SDK and
# JDK next to the build host's home directory.
#
# Idempotent: every run regenerates res/values from the token file, rebuilds
# from scratch in an isolated work directory and re-signs with a throwaway key.
# The key is created in a mktemp directory, never inside the repository.
#
# Usage: repo/scripts/build-overlay-framework.sh [--out DIR] [--unsigned] [--no-regen]
# Prints the signed APK path (and its sha256) on success.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
overlay_dir="$repo_root/overlay/framework"
out_dir="${OUT_DIR:-$overlay_dir/out}"
do_sign=1
do_regen=1

usage() {
  cat <<'USAGE'
Build the ztmetro framework-res RRO (target package "android").

  --out DIR     output directory (default: $OUT_DIR or overlay/framework/out)
  --unsigned    stop after aapt2 link (no signing)
  --no-regen    do not run gen_overlay.py; build the committed res/values
  -h, --help    this text

Needs bash, coreutils, unzip, an Android SDK (build-tools + platforms/android-3x
/android.jar) and a JDK.  Set ANDROID_SDK_ROOT / ANDROID_HOME / JAVA_HOME, or
keep the SDK and JDK in $HOME/android-sdk and $HOME/android-jdk.  Idempotent:
regenerates res/values, rebuilds from scratch, signs with a throwaway key kept
outside the repository.  Prints the signed APK path on success.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)      out_dir="${2:?--out needs a directory}"; shift 2 ;;
    --unsigned) do_sign=0; shift ;;
    --no-regen) do_regen=0; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
note() { echo "==> $*"; }
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# ---------------------------------------------------------------- toolchain
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
[ -d "$sdk_root" ] || die "Android SDK not found: $sdk_root (set ANDROID_SDK_ROOT)"

build_tools="${ANDROID_BUILD_TOOLS:-}"
if [ -z "$build_tools" ]; then
  build_tools="$(ls -d "$sdk_root"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)"
fi
build_tools="${build_tools%/}"
[ -x "$build_tools/aapt2" ] || die "aapt2 not found in ${build_tools:-<no build-tools>} (set ANDROID_BUILD_TOOLS)"

android_jar="${ANDROID_JAR:-}"
if [ -z "$android_jar" ]; then
  if [ -f "$sdk_root/platforms/android-36/android.jar" ]; then
    android_jar="$sdk_root/platforms/android-36/android.jar"
  else
    android_jar="$(ls "$sdk_root"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)"
  fi
fi
[ -f "$android_jar" ] || die "android.jar not found under $sdk_root/platforms"

java_home="${JAVA_HOME:-$HOME/android-jdk}"
if [ ! -x "$java_home/bin/java" ]; then
  java_home="$(dirname "$(dirname "$(command -v java || true)")")"
fi
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"
command -v java >/dev/null || die "java not found (set JAVA_HOME)"
keytool="$(command -v keytool || echo "$JAVA_HOME/bin/keytool")"
[ -x "$keytool" ] || die "keytool not found (set JAVA_HOME)"
if [ "$do_sign" = 1 ]; then
  [ -x "$build_tools/apksigner" ] || die "apksigner not found in $build_tools"
fi

note "SDK          $sdk_root"
note "build-tools  $build_tools ($("$build_tools/aapt2" version 2>&1 | head -n 1 || echo '?'))"
note "android.jar  $android_jar"
note "JDK          $JAVA_HOME"

# ------------------------------------------------------------------ sources
[ -f "$overlay_dir/AndroidManifest.xml" ] || die "missing $overlay_dir/AndroidManifest.xml"
[ -d "$overlay_dir/res/values" ] || die "missing $overlay_dir/res/values"
if [ "$do_regen" = 1 ] && command -v python3 >/dev/null; then
  note "regenerating res/values from design/tokens/ztmetro.tokens.json"
  python3 "$overlay_dir/gen_overlay.py" >/dev/null
elif [ "$do_regen" = 1 ]; then
  note "python3 not found - using the committed res/values (already generated)"
fi

# -------------------------------------------------------------------- build
mkdir -p "$out_dir"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/ztmetro-framework-rro.XXXXXX")"
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT

note "aapt2 compile"
"$build_tools/aapt2" compile --dir "$overlay_dir/res" -o "$work_dir/compiled.zip"

unsigned_apk="$out_dir/ZtMetroFramework-unsigned.apk"
rm -f "$unsigned_apk"
note "aapt2 link (target package: android)"
"$build_tools/aapt2" link \
  -o "$unsigned_apk" \
  -I "$android_jar" \
  --manifest "$overlay_dir/AndroidManifest.xml" \
  --min-sdk-version 36 --target-sdk-version 36 \
  --auto-add-overlay \
  "$work_dir/compiled.zip"

payload_sha="$(sha256 "$unsigned_apk")"

# --------------------------------------------------------------------- sign
final_apk="$unsigned_apk"
if [ "$do_sign" = 1 ]; then
  keystore="${ZTMETRO_KEYSTORE:-$work_dir/throwaway.jks}"
  if [ ! -f "$keystore" ]; then
    note "generating a throwaway signing key (not stored in the repo)"
    "$keytool" -genkeypair \
      -keystore "$keystore" -storetype PKCS12 \
      -storepass ztmetro -keypass ztmetro \
      -alias ztmetro -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=ztmetro framework overlay, O=ztOS" >/dev/null 2>&1 \
      || die "keytool failed"
  fi
  final_apk="$out_dir/ZtMetroFramework.apk"
  rm -f "$final_apk"
  note "apksigner sign"
  "$build_tools/apksigner" sign \
    --ks "$keystore" --ks-key-alias ztmetro \
    --ks-pass pass:ztmetro --key-pass pass:ztmetro \
    --min-sdk-version 36 \
    --out "$final_apk" "$unsigned_apk"
  rm -f "$unsigned_apk"
  "$build_tools/apksigner" verify --min-sdk-version 36 --print-certs "$final_apk" \
    | sed 's/^/    /'
fi

# ------------------------------------------------------------------- verify
note "verifying the package"
manifest_dump="$("$build_tools/aapt2" dump xmltree "$final_apk" --file AndroidManifest.xml)"
printf '%s\n' "$manifest_dump" | grep -q 'targetPackage.*"android"' \
  || die "overlay targetPackage is not android"
printf '%s\n' "$manifest_dump" | grep -q 'hasCode.*false' \
  || die "application does not declare hasCode=false"
[ -z "$(unzip -l "$final_apk" | grep -E 'classes[0-9]*\.dex' || true)" ] \
  || die "APK contains dex code"

resource_count="$("$build_tools/aapt2" dump resources "$final_apk" | grep -cE '^\s+resource 0x' || true)"
[ "${resource_count:-0}" -gt 0 ] || die "aapt2 dump resources found no resources"

note "overridden resources: $resource_count"
echo
echo "APK      $final_apk"
echo "sha256   $(sha256 "$final_apk")"
echo "payload  $payload_sha"
echo "size     $(wc -c < "$final_apk") bytes"
