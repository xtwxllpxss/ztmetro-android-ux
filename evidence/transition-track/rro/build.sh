#!/usr/bin/env bash
# Build the transition-animation RRO experiment on a Linux host with an Android SDK.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"; bt="${bt%/}"
jar="$sdk/platforms/android-36/android.jar"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -x "$bt/aapt2" ] || { echo "no aapt2" >&2; exit 1; }
[ -f "$jar" ] || { echo "no android.jar" >&2; exit 1; }
out="$here/artifacts"; rm -rf "$out"; mkdir -p "$out"
"$bt/aapt2" compile --dir "$here/res" -o "$out/compiled.zip"
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$jar" \
  --manifest "$here/AndroidManifest.xml" \
  --min-sdk-version 36 --target-sdk-version 36 --auto-add-overlay \
  "$out/compiled.zip"
keytool -genkeypair -keystore "$out/throwaway.p12" -storetype PKCS12 \
  -storepass throwaway -keypass throwaway -alias zt -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=ztmetro transition experiment, O=ztOS" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$out/throwaway.p12" --ks-pass pass:throwaway \
  --out "$out/ZtMetroTransitions.apk" "$out/unsigned.apk"
"$bt/apksigner" verify --print-certs "$out/ZtMetroTransitions.apk" > "$out/apksigner-verify.txt"
"$bt/aapt2" dump resources "$out/ZtMetroTransitions.apk" > "$out/aapt2-dump-resources.txt"
"$bt/aapt2" dump badging "$out/ZtMetroTransitions.apk" > "$out/aapt2-badging.txt"
"$bt/aapt2" dump xmltree "$out/ZtMetroTransitions.apk" --file AndroidManifest.xml > "$out/aapt2-manifest.xmltree.txt"
unzip -l "$out/ZtMetroTransitions.apk" > "$out/apk-contents.txt"
# Check that the target framework has these names (from android.jar, API 36).
"$bt/aapt2" dump resources "$jar" | grep -E "anim/task_(open|close)_(enter|exit)" > "$out/framework-target-names.txt" || true
"$bt/aapt2" dump resources "$jar" | grep -E "wallpaper(Open|Close)(Enter|Exit)Animation|task(Open|Close|ToFront|ToBack)(Enter|Exit)Animation|anim/(task|wallpaper)_(open|close)_(enter|exit)" > "$out/framework-anim-entries.txt" || true
sha256sum "$out/ZtMetroTransitions.apk" | cut -d' ' -f1 > "$out/ZtMetroTransitions.apk.sha256" 2>/dev/null || \
  shasum -a 256 "$out/ZtMetroTransitions.apk" | cut -d' ' -f1 > "$out/ZtMetroTransitions.apk.sha256"
{
  echo "apk=$(wc -c < "$out/ZtMetroTransitions.apk") bytes"
  echo "sha256=$(cat "$out/ZtMetroTransitions.apk.sha256")"
  echo "entries=$(grep -c 'anim/' "$out/aapt2-dump-resources.txt" || true)"
  echo "dex=$(unzip -l "$out/ZtMetroTransitions.apk" | grep -c 'classes.*dex' || true)"
  echo "target_names:"; cat "$out/framework-target-names.txt"
} | tee "$out/BUILD.txt"
