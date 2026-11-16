#!/usr/bin/env bash
# Build the stub LSPosed transition-tracking module (no device run).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"; bt="${bt%/}"
jar="$sdk/platforms/android-36/android.jar"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
out="$here/artifacts"; rm -rf "$out"; mkdir -p "$out/classes" "$out/dex"
javac -source 17 -target 17 -Xlint:-options -d "$out/classes" -classpath "$jar" \
  $(find "$here/src" "$here/stubs" "$here/hidden" -name '*.java')
"$bt/d8" --release --min-api 36 --lib "$jar" --output "$out/dex" \
  $(find "$out/classes/org" -name '*.class')
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$jar" \
  --manifest "$here/AndroidManifest.xml" --min-sdk-version 36 --target-sdk-version 36 \
  -A "$here/assets"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
keytool -genkeypair -keystore "$out/throwaway.p12" -storetype PKCS12 \
  -storepass throwaway -keypass throwaway -alias zt -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=ztmetro transition track, O=ztOS" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$out/throwaway.p12" --ks-pass pass:throwaway \
  --out "$out/ZtMetroTransitionTrack.apk" "$out/aligned.apk"
"$bt/apksigner" verify --print-certs "$out/ZtMetroTransitionTrack.apk" > "$out/apksigner-verify.txt"
unzip -l "$out/ZtMetroTransitionTrack.apk" > "$out/apk-contents.txt"
"$bt/dexdump" -f "$out/ZtMetroTransitionTrack.apk" > "$out/dexdump.txt" 2>&1 || true
grep -E "Class descriptor|name          :|type          :" "$out/dexdump.txt" > "$out/dex-classes.txt" || true
sha256sum "$out/ZtMetroTransitionTrack.apk" | cut -d' ' -f1 > "$out/sha256" 2>/dev/null || \
  shasum -a 256 "$out/ZtMetroTransitionTrack.apk" | cut -d' ' -f1 > "$out/sha256"
echo "apk_bytes=$(wc -c < "$out/ZtMetroTransitionTrack.apk")" | tee "$out/BUILD.txt"
echo "sha256=$(cat "$out/sha256")" | tee -a "$out/BUILD.txt"
echo "dex_files=$(unzip -l "$out/ZtMetroTransitionTrack.apk" | grep -c 'classes.*dex')" | tee -a "$out/BUILD.txt"
