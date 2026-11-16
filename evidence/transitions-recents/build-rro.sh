#!/usr/bin/env bash
# Build the THROWAWAY recents-component RRO for the LineageOS 23.2 experiment.
#
#   repo/evidence/transitions-recents/build-rro.sh
#
# The APK is evidence, not a deliverable: do not ship it in ztmetro-start or
# ztmetro-actioncentre. It targets package "android" and overrides
# android:string/config_recentsComponentName (framework id 0x010402cb).
#
# No Android SDK on this Mac -> stage on hermes-ubuntu, build there, copy back.
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SRC="$HERE/rro"
REMOTE="${RRO_REMOTE:-hermes-ubuntu}"
RWORK="${RRO_REMOTE_DIR:-ztux/transitions/rro}"
OUT="$HERE/ztmetro-recents-rro.apk"
SDK_REMOTE="${RRO_SDK:-android-sdk}"
JDK_REMOTE="${RRO_JDK:-android-jdk}"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
    else shasum -a 256 "$1" | awk '{print $1}'; fi
}

printf '==> staging throwaway RRO on %s:%s\n' "$REMOTE" "$RWORK"
ssh "$REMOTE" "mkdir -p '$RWORK'"
rsync -a --delete "$SRC/" "$REMOTE:$RWORK/rro/"
scp -q "$0" "$REMOTE:$RWORK/build-rro.sh"

printf '==> remote aapt2 link + sign\n'
ssh "$REMOTE" "set -e; export JAVA_HOME=~/$JDK_REMOTE; export PATH=~/$JDK_REMOTE/bin:\$PATH; cd '$RWORK'; \
  BT=\$(ls -1d ~/$SDK_REMOTE/build-tools/* | sort -V | tail -n1); \
  JAR=~/$SDK_REMOTE/platforms/android-36/android.jar; \
  rm -rf build; mkdir -p build; \
  \$BT/aapt2 compile --dir rro/res -o build/res.zip; \
  \$BT/aapt2 link -o build/unsigned.apk -I \$JAR \
      --manifest rro/AndroidManifest.xml --auto-add-overlay build/res.zip; \
  if [ ! -f build/rro.p12 ]; then \
    ~/$JDK_REMOTE/bin/keytool -genkeypair -keystore build/rro.p12 -storetype PKCS12 \
      -storepass android -keypass android -alias rro -keyalg RSA -keysize 2048 \
      -validity 10000 -dname 'CN=ztmetro throwaway rro, O=ztmetro, C=US' >/dev/null 2>&1; \
  fi; \
  \$BT/apksigner sign --ks build/rro.p12 --ks-pass pass:android --key-pass pass:android \
      --ks-key-alias rro --min-sdk-version 29 \
      --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
      --out build/ztmetro-recents-rro.apk build/unsigned.apk; \
  \$BT/apksigner verify --min-sdk-version 29 build/ztmetro-recents-rro.apk; \
  \$BT/aapt2 dump resources build/ztmetro-recents-rro.apk > build/rro-resources.txt; \
  \$BT/aapt2 dump xmltree --file AndroidManifest.xml build/ztmetro-recents-rro.apk > build/rro-manifest.txt"

scp -q "$REMOTE:$RWORK/build/ztmetro-recents-rro.apk" "$OUT"
scp -q "$REMOTE:$RWORK/build/rro-resources.txt" "$HERE/rro-resources.txt"
scp -q "$REMOTE:$RWORK/build/rro-manifest.txt" "$HERE/rro-manifest.txt"
printf '%s  ztmetro-recents-rro.apk (THROWAWAY, do not ship)\n' \
    "$(sha256_of "$OUT")" > "$HERE/ztmetro-recents-rro.apk.sha256"
printf 'APK: %s\nsha256: %s\n' "$OUT" "$(sha256_of "$OUT")"
