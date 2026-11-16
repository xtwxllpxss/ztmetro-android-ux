#!/usr/bin/env bash
# Build the ztmetro Start HOME launcher APK (v4, WP redraw + live tiles) with only the SDK + JDK.
#
#   repo/scripts/build-launcher.sh
#
# On a Mac without an Android SDK the script stages the sources on
# hermes-ubuntu (~/ztux/transitions/launcher), builds there and copies the APK
# and the static-verification evidence back. On a Linux host with android-36
# present it builds locally. Override the remote with LAUNCHER_REMOTE.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
APP="${APP_DIR:-$ROOT/launcher/app/src/main}"
BUILD="${BUILD_DIR:-$ROOT/launcher/build}"
OUT="$BUILD/out"
EVIDENCE="${EVIDENCE_DIR:-$ROOT/evidence/live-tiles}"
APK="$OUT/ztmetro-start.apk"
REMOTE="${LAUNCHER_REMOTE:-hermes-ubuntu}"
RWORK="${LAUNCHER_REMOTE_DIR:-ztux/transitions/launcher}"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

has_local_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                     "$HOME/android-sdk" "$HOME/Android/Sdk" /opt/android-sdk; do
        if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-36/android.jar" ]; then
            return 0
        fi
    done
    return 1
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Build on hermes-ubuntu when this host has no android-36. Only the tree the
# launcher build actually reads is staged: app sources/resources, the token
# compiler, the glyph-sheet tool and this script.
remote_build() {
    command -v ssh >/dev/null 2>&1 || die "ssh is required for the remote build"
    command -v rsync >/dev/null 2>&1 || die "rsync is required for the remote build"
    command -v scp >/dev/null 2>&1 || die "scp is required for the remote build"
    say "staging launcher sources on $REMOTE:$RWORK"
    ssh "$REMOTE" "mkdir -p '$RWORK/launcher' '$RWORK/design' '$RWORK/scripts'"
    rsync -a --delete --exclude '.DS_Store' \
        "$ROOT/launcher/app/" "$REMOTE:$RWORK/launcher/app/"
    rsync -a --delete --exclude '.DS_Store' \
        "$ROOT/launcher/tools/" "$REMOTE:$RWORK/launcher/tools/"
    rsync -a --delete --exclude '.DS_Store' \
        "$ROOT/design/tokens/" "$REMOTE:$RWORK/design/tokens/"
    scp -q "$SCRIPT_DIR/build-launcher.sh" "$REMOTE:$RWORK/scripts/build-launcher.sh"
    say "remote build on $REMOTE (SDK ~/android-sdk, JDK ~/android-jdk)"
    ssh "$REMOTE" "cd '$RWORK' && LAUNCHER_STAGE=1 \
        APP_DIR=\"\$PWD/launcher/app/src/main\" \
        BUILD_DIR=\"\$PWD/launcher/build\" \
        EVIDENCE_DIR=\"\$PWD/evidence/transitions-recents/launcher\" \
        bash ./scripts/build-launcher.sh"
    mkdir -p "$OUT" "$EVIDENCE"
    scp -q "$REMOTE:$RWORK/launcher/build/out/ztmetro-start.apk" "$APK"
    scp -q "$REMOTE:$RWORK/evidence/transitions-recents/launcher/"* "$EVIDENCE/"
    local local_sha remote_sha
    local_sha=$(sha256_of "$APK")
    remote_sha=$(awk '{print $1}' "$EVIDENCE/ztmetro-start.apk.sha256")
    [ "$local_sha" = "$remote_sha" ] \
        || die "sha256 mismatch after scp: local $local_sha remote $remote_sha"
    say "artifact copied back; sha256 matches remote"
    printf 'APK:     %s\n' "$APK"
    printf 'evidence:%s\n' "$EVIDENCE/ztmetro-start.apk"
    printf 'sha256:  %s\n' "$local_sha"
}

command -v python3 >/dev/null 2>&1 || die "python3 is required (token generation)"
command -v zip >/dev/null 2>&1 || die "zip is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# --- host selection ----------------------------------------------------------
if [ "${LAUNCHER_STAGE:-0}" != "1" ] && ! has_local_sdk; then
    remote_build
    exit 0
fi

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
for tool in aapt2 d8 zipalign apksigner; do
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

# --- clean (keep the keystore: signatures stay stable across rebuilds) ---------
rm -rf "$BUILD/res" "$BUILD/rjava" "$BUILD/classes" "$BUILD/dex" "$BUILD/gen-tokens"
rm -f "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/unsigned.apk" "$BUILD/aligned.apk" \
      "$BUILD/sources.txt" "$BUILD/verify.dex"
mkdir -p "$BUILD/gen-tokens" "$BUILD/keystore" "$BUILD/out" "$EVIDENCE"

say "SDK   $SDK"
say "tools $BT"
say "JDK   $JDK"

# --- 1. tokens: validate provenance, then merge into res/values/metro_tokens.xml
say "generating Metro resources from design/tokens/ztmetro.tokens.json"
python3 "$ROOT/design/tokens/gen_android.py" --out "$BUILD/gen-tokens"
cp -R "$APP/res/." "$BUILD/res/"
python3 - "$ROOT" "$BUILD" <<'PY'
import os
import sys

root, build = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(root, "design", "tokens"))
import gen_android as gen

tokens_path = os.path.join(root, "design", "tokens", "ztmetro.tokens.json")
tokens = gen.load_tokens(tokens_path)
problems = gen.check_origins(tokens)
if problems:
    sys.stderr.write("token provenance check failed:\n")
    for problem in problems:
        sys.stderr.write("  - %s\n" % problem)
    sys.exit(2)


def body(xml):
    return xml.split("<resources>", 1)[1].rsplit("</resources>", 1)[0]


colors = body(gen.emit_colors(tokens))
dimens = body(gen.emit_dimens(tokens))
styles = body(gen.emit_styles(tokens))

# Extra TextAppearances read from the same ramp. AppListTitle/BodyLarge are
# shared with v1/v2; Pivot and SectionHeader are the v3 20sp pivot and the
# 12sp caps app-list section head. SectionHeader keeps the caption size/line
# rhythm but forces the semibold family, as a WP section head is semibold.
ramp = gen.ramp_by_name(tokens)
extra_styles = []


def ramp_style(entry, suffix, color, weight, all_caps=False):
    size = float(entry["size_sp_at_density_1x"])
    multiplier = float(entry["line_height"]) / size
    if weight <= 350:
        family = gen.FAMILY_ANDROID["display"]
    elif weight >= 500:
        family = gen.FAMILY_ANDROID["semibold"]
    else:
        family = gen.FAMILY_ANDROID["body"]
    lines = [
        "",
        "    <!-- %s: %s -->" % (entry["name"], entry["usage"]),
        '    <style name="TextAppearance.Metro.%s" parent="android:TextAppearance">' % suffix,
        '        <item name="android:fontFamily">%s</item>' % family,
        '        <item name="android:textSize">%ssp</item>' % gen.num(size),
        '        <item name="android:textColor">%s</item>' % color,
        '        <item name="android:lineSpacingMultiplier">%.3f</item>' % multiplier,
    ]
    if all_caps:
        lines.append('        <item name="android:textAllCaps">true</item>')
    lines.append("    </style>")
    return lines


extra_styles += ramp_style(ramp["app_list_title"], "AppListTitle",
                            "@color/metro_text_primary",
                            ramp["app_list_title"]["semibold_weight"])
extra_styles += ramp_style(ramp["body_large"], "BodyLarge",
                           "@color/metro_text_primary",
                           ramp["body_large"]["semibold_weight"])
extra_styles += ramp_style(ramp["caption"], "SectionHeader",
                           "@color/metro_accent", 600, True)
# App-list letter head: the mockup (mockups/screens/app-list.html) draws a
# 27sp Light accent letter at the page margin, not the C's 1.5dp outline square
# (ztmetro.c:4514-4518). 27 is the C's letter size (ztmetro.c:4517); the face
# is the app-list title family (sans-serif-light).
extra_styles += [
    "",
    "    <!-- mockup app-list letter head: 27sp Light, accent -->",
    '    <style name="TextAppearance.Metro.SectionLetter" parent="android:TextAppearance">',
    '        <item name="android:fontFamily">sans-serif-light</item>',
    '        <item name="android:textSize">27sp</item>',
    '        <item name="android:textColor">@color/metro_accent</item>',
    '        <item name="android:lineSpacingMultiplier">1.26</item>',
    "    </style>",
]
# Live-tile second face: caption 12sp in white, sentence case. The count and
# the latest line sit on an accent tile, so the colour is on_accent.
extra_styles += ramp_style(ramp["caption"], "LiveText",
                           "@color/metro_on_accent", 400)

header_gap = gen.num(tokens["type"]["header"]["gap"])
scrim = tokens["color"]["chrome"]["scrim_alpha"]
scrim_pct = int(round(float(scrim) * 100))
tilt_deg = gen.num(tokens["motion"]["tilt_deg"])

xml = "\n".join([
    '<?xml version="1.0" encoding="utf-8"?>',
    "<!-- GENERATED by scripts/build-launcher.sh from design/tokens/ztmetro.tokens.json.",
    "     Do not edit by hand and do not hand-duplicate these numbers in Java or XML.",
    "     Sources: design/tokens/gen_android.py (colours, dimens, styles);",
    "     header gap, scrim, pivot/section styles and the v3 launcher extras are",
    "     read from the same JSON here. -->",
    "<resources>",
    colors,
    dimens,
    styles,
    "",
    "    <!-- type.header.gap -->",
    '    <dimen name="metro_header_gap">%sdp</dimen>' % header_gap,
    "    <!-- color.chrome.scrim_alpha -->",
    '    <fraction name="metro_scrim_alpha">%d%%</fraction>' % scrim_pct,
    "",
    "    <!-- Launcher-v3 additions, not ztmetro C facts. Tile spacing is the",
    "         token spacing (start_margin 14dp, tile_gap 7dp) because the official",
    "         ztmetro Start frame measures 14dp/7dp at 360dp; the v3 brief's 2-3dp",
    "         gutters were a misread and are not used. The remaining values are",
    "         chrome that the C app list/Start draws: 55dp rows (LIST_ROW_H),",
    "         46% white glyph, 13dp list label gap, 48dp circled glyph, 56dp",
    "         bottom strip. 55/46 come from the mockup: the C pitch is 55",
    "         (ztmetro.c:159-165) and draw_tile is 0.46 h (ztmetro.c:3677). -->",
    '    <dimen name="metro_v3_tile_pad">8dp</dimen>',
    '    <dimen name="metro_v3_list_row">55dp</dimen>',
    '    <dimen name="metro_v3_list_gap">13dp</dimen>',
    '    <dimen name="metro_v3_search_underline">1dp</dimen>',
    '    <dimen name="metro_v3_search_pad">8dp</dimen>',
    '    <dimen name="metro_v3_stroke">1.5dp</dimen>',
    '    <dimen name="metro_v3_arrow">48dp</dimen>',
    '    <dimen name="metro_v3_arrow_right">12dp</dimen>',
    '    <dimen name="metro_v3_arrow_bottom">64dp</dimen>',
    '    <dimen name="metro_v3_header_pad_top">8dp</dimen>',
    '    <dimen name="metro_v3_section_row">36dp</dimen>',
    '    <dimen name="metro_v3_section_gap">10dp</dimen>',
    '    <dimen name="metro_v3_bottom_bar">56dp</dimen>',
    '    <fraction name="metro_v3_icon_fraction">44%</fraction>',
    '    <integer name="metro_motion_tilt_deg">%s</integer>' % tilt_deg,
    '',
    '    <!-- Live-tile second face, not ztmetro C facts. The flip is deliberately',
    '         in the 220-260ms band the brief fixes for the WP flip (240ms); the',
    '         calendar day and corner count sizes are fractions of the short side. -->',
    '    <integer name="metro_live_flip_ms">240</integer>',
    '    <fraction name="metro_live_date_fraction">34%</fraction>',
    '    <fraction name="metro_live_count_fraction">15%</fraction>',
    '',
    '    <!-- ztmetro per-app tile palette (core/zrnmetrocore.c:203-208, as',
    "         measured for the v3 reference frame). Cobalt is the default and",
    "         is weighted in TileArt; these are not Material pastels. -->",
    '    <color name="metro_tile_lime">#FFA4C400</color>',
    '    <color name="metro_tile_green">#FF60A917</color>',
    '    <color name="metro_tile_emerald">#FF008A00</color>',
    '    <color name="metro_tile_teal">#FF00ABA9</color>',
    '    <color name="metro_tile_cyan">#FF1BA1E2</color>',
    '    <color name="metro_tile_magenta">#FFD80073</color>',
    '    <color name="metro_tile_crimson">#FFA20025</color>',
    '    <color name="metro_tile_red">#FFE51400</color>',
    '    <color name="metro_tile_brown">#FF825A2C</color>',
    '    <color name="metro_tile_olive">#FF6D8764</color>',
    '    <color name="metro_tile_steel">#FF647687</color>',
] + extra_styles + ["</resources>", ""])

out_path = os.path.join(build, "res", "values", "metro_tokens.xml")
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(xml)
print("wrote %s (colors + dimens + styles + launcher extras)" % out_path)
PY

# --- 2. resources ------------------------------------------------------------
say "aapt2 compile + link"
"$BT/aapt2" compile --dir "$BUILD/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$BUILD/rjava" \
    --auto-add-overlay \
    "$BUILD/res.zip"

# --- 3. compile + dex --------------------------------------------------------
say "javac (Java 8 bytecode against android-36)"
find "$APP/java" "$BUILD/rjava" -name '*.java' -print > "$BUILD/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

say "d8"
mkdir -p "$BUILD/dex"
CLASSES=()
while IFS= read -r class_file; do
    CLASSES+=("$class_file")
done < <(find "$BUILD/classes" -name '*.class' -print)
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "${CLASSES[@]}"

# --- 4. package + align + sign ----------------------------------------------
say "packaging classes.dex"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
TZ=UTC touch -t 198001010000.00 "$BUILD/dex/classes.dex"   # stable APK hash across rebuilds
( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex )
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KS="$BUILD/keystore/ztmetro-start.p12"
if [ ! -f "$KS" ]; then
    say "generating throwaway signing key (gitignored)"
    "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
        -storepass android -keypass android -alias ztmetro \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=ztmetro start, O=ztmetro, C=US" >/dev/null 2>&1 \
        || die "keytool failed"
fi

say "apksigner"
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias ztmetro \
    --min-sdk-version 29 \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$APK" "$BUILD/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 29 "$APK"

# --- 5. verify the artifact --------------------------------------------------
say "verifying APK"
BADGING=$("$BT/aapt2" dump badging "$APK")
printf '%s\n' "$BADGING" | sed -n '1,6p'
printf '%s\n' "$BADGING" | grep -q "application-label:'ztmetro start'" \
    || die "application label is not 'ztmetro start'"
printf '%s\n' "$BADGING" | grep -q "application-icon" \
    || die "no application icon in badging"

XMLTREE=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK")
printf '%s\n' "$XMLTREE" | grep -q "android.intent.category.HOME" \
    || die "HOME category missing from the merged manifest"
printf '%s\n' "$BADGING" | grep -q "minSdkVersion:'29'" || die "minSdk 29 missing"
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'36'" || die "targetSdk 36 missing"
printf '%s\n' "$BADGING" | grep -q "versionCode='4'" || die "versionCode 4 missing"
printf '%s\n' "$BADGING" | grep -q "versionName='4.0'" || die "versionName 4.0 missing"

# v3 keeps the v2 notification listener service (optional live counts) and must
# still request no permission; the HOME role is the only role it owns.
printf '%s\n' "$XMLTREE" | grep -q "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" \
    || die "listener service lacks BIND_NOTIFICATION_LISTENER_SERVICE"
printf '%s\n' "$XMLTREE" | grep -q "android.service.notification.NotificationListenerService" \
    || die "NotificationListenerService intent filter missing"
printf '%s\n' "$XMLTREE" | grep -q "StartNotificationListener" \
    || die "StartNotificationListener missing from the merged manifest"
if printf '%s\n' "$XMLTREE" | grep -q "uses-permission"; then
    die "the launcher must not request any permission"
fi

unzip -l "$APK" | grep -q " classes.dex" || die "classes.dex missing from APK"
unzip -p "$APK" classes.dex > "$BUILD/verify.dex"
if grep -aq "androidx" "$BUILD/verify.dex"; then
    die "AndroidX classes found in classes.dex"
fi
for symbol in StartNotificationListener MetroTileView MetroGridView \
              MetroListRowView MetroPager MetroCircleGlyphView \
              MetroSectionHeaderView SystemGlyphs GlyphSet TileArt LiveTiles; do
    grep -aq "$symbol" "$BUILD/verify.dex" \
        || die "$symbol class missing from classes.dex"
done

# The glyph pipeline order is the point of v4: the system package map must be
# consulted before the monochrome layer, otherwise Browser/Contacts go back to
# being a blob. Check source order and that no circular backdrop is drawn.
TILE_ART="$APP/java/org/ztmetro/start/TileArt.java"
KIND_LINE=$(grep -n 'SystemGlyphs.kindFor' "$TILE_ART" | head -n 1 | cut -d: -f1)
LOAD_LINE=$(grep -n 'Drawable original = loadDrawable' "$TILE_ART" | head -n 1 | cut -d: -f1)
MONO_LINE=$(grep -n 'Monochrome33.get' "$TILE_ART" | head -n 1 | cut -d: -f1)
[ -n "$KIND_LINE" ] && [ -n "$LOAD_LINE" ] && [ -n "$MONO_LINE" ] \
    || die "TileArt lost the system-map, load or monochrome step"
[ "$KIND_LINE" -lt "$LOAD_LINE" ] \
    || die "TileArt loads the icon before the system package map"
[ "$KIND_LINE" -lt "$MONO_LINE" ] \
    || die "TileArt asks for monochrome before the system package map"
if grep -q 'drawCircle\|addCircle' "$TILE_ART"; then
    die "TileArt draws a circular icon backdrop; tiles must stay flat"
fi
for literal in org.lineageos.etar org.lineageos.aperture org.lineageos.jelly \
               org.lineageos.twelve com.android.camera2 com.android.deskclock \
               com.android.documentsui com.android.calculator2 \
               org.ztmetro.actioncentre; do
    grep -aq "$literal" "$BUILD/verify.dex" \
        || die "system glyph map is missing $literal"
done

# The v3 redraw must actually be in the resource table, not just in Java.
RESOURCES=$("$BT/aapt2" dump resources "$APK")
for symbol in metro_tile_gap metro_tile_steel metro_v3_icon_fraction \
              metro_v3_arrow TextAppearance.Metro.SectionHeader \
              metro_live_flip_ms metro_live_date_fraction \
              metro_live_count_fraction TextAppearance.Metro.LiveText \
              zt_turnstile_in zt_turnstile_out Animation.ZtmetroStart; do
    printf '%s\n' "$RESOURCES" | grep -q "$symbol" \
        || die "$symbol missing from the compiled resources"
done

# Launcher-initiated app launches must carry the ztmetro turnstile; the exit
# is WMShell-owned. Check the call, the two anim resources and the source path.
grep -aq "makeCustomAnimation" "$BUILD/verify.dex" \
    || die "ActivityOptions.makeCustomAnimation is missing from classes.dex"
grep -q "zt_turnstile_in" "$APP/java/org/ztmetro/start/StartActivity.java" \
    || die "launchApp does not reference zt_turnstile_in"
grep -q "zt_turnstile_out" "$APP/java/org/ztmetro/start/StartActivity.java" \
    || die "launchApp does not reference zt_turnstile_out"

# Motion: the classes and the C-sourced durations must be in the artifact, not
# just in the sources. Values are the compiled tokens; origins are the C lines
# quoted in evidence/motion/README.md.
for symbol in Motion Turnstile; do
    grep -aq "$symbol" "$BUILD/verify.dex" \
        || die "$symbol class missing from classes.dex"
done
TOKENS_XML="$BUILD/res/values/metro_tokens.xml"
for expect in \
    'metro_motion_row_out_ms">128<' \
    'metro_motion_row_in_ms">180<' \
    'metro_motion_row_stagger_ms">33<' \
    'metro_motion_press_delay_ms">80<' \
    'metro_motion_press_in_ms">90<' \
    'metro_motion_press_out_ms">200<' \
    'metro_motion_long_press_ms">450<'; do
    grep -q "$expect" "$TOKENS_XML" \
        || die "motion token $expect missing from $TOKENS_XML"
done
grep -q 'metro_motion_tilt_deg">14<' "$TOKENS_XML" \
    || die "motion token tilt_deg 14 missing from $TOKENS_XML"
for token in metro_motion_row_out_ms metro_motion_row_in_ms metro_motion_row_stagger_ms; do
    printf '%s
' "$RESOURCES" | grep -q "$token" \
        || die "$token missing from the compiled resources"
done

# Keyguard-return home entrance: only after a real keyguard dismissal, 180 ms,
# alpha 0->1 and scale 0.985->1 through Motion.ms (animator scale respected).
START_SRC="$APP/java/org/ztmetro/start/StartActivity.java"
grep -q "HOME_ENTER_MS = 180" "$START_SRC" \
    || die "home entrance duration 180 ms missing"
grep -q "HOME_ENTER_SCALE = 0.985f" "$START_SRC" \
    || die "home entrance scale 0.985 missing"
grep -q "ACTION_USER_PRESENT" "$START_SRC" \
    || die "home entrance is not gated on the keyguard dismissal"
grep -q "playHomeEntranceIfFromKeyguard" "$START_SRC" \
    || die "home entrance is not wired into onResume"
grep -aq "ZtStartMotion" "$BUILD/verify.dex" \
    || die "home entrance log tag missing from classes.dex"

# Live tiles: the listener class is a real service in the dex (not just a
# manifest entry), the model/mapping strings are compiled in, and the flip
# duration is inside the brief's 220-260ms band.
DEXDUMP="$BT/dexdump"
if [ -x "$DEXDUMP" ]; then
    "$DEXDUMP" -l plain "$BUILD/verify.dex" > "$EVIDENCE/dexdump-classes.txt" 2>/dev/null \
        || die "dexdump failed"
    grep -q "Lorg/ztmetro/start/StartNotificationListener;" \
        "$EVIDENCE/dexdump-classes.txt" \
        || die "dexdump does not list StartNotificationListener"
    grep -q "Lorg/ztmetro/start/LiveTiles" "$EVIDENCE/dexdump-classes.txt" \
        || die "dexdump does not list LiveTiles"
    grep -q "Lorg/ztmetro/start/MetroTileView;" "$EVIDENCE/dexdump-classes.txt" \
        || die "dexdump does not list MetroTileView"
else
    die "dexdump missing from $BT"
fi
for literal in animator_duration_scale enabled_notification_listeners; do
    grep -aq "$literal" "$BUILD/verify.dex" \
        || die "$literal missing from classes.dex"
done

# Pass 2 (perf): the notification listener is released after two minutes hidden
# and rebound on resume; an ordinary resume no longer reloads the package set or
# rebuilds the tile views.
if [ -x "$DEXDUMP" ]; then
    grep -q "Lorg/ztmetro/start/ListenerWindow;" "$EVIDENCE/dexdump-classes.txt" \
        || die "dexdump does not list ListenerWindow"
fi
for literal in requestUnbind requestRebind tileSignature appSignature; do
    grep -aq "$literal" "$BUILD/verify.dex" \
        || die "$literal missing from classes.dex (pass-2 listener cap / tile signature)"
done
grep -q "HIDDEN_UNBIND_MS = 2L \* 60L \* 1000L" \
    "$APP/java/org/ztmetro/start/ListenerWindow.java" \
    || die "two-minute hidden window is not the configured release delay"
if grep -q "ListenerWindow.onVisible" "$START_SRC" \
        && grep -q "ListenerWindow.onHidden" "$START_SRC"; then
    :
else
    die "listener window is not wired into onResume/onPause"
fi
if grep -q "refreshIfNeeded" "$START_SRC"; then
    :
else
    die "onResume/onNewIntent still reload unconditionally"
fi
grep -q 'metro_live_flip_ms">240<' "$TOKENS_XML" \
    || die "live flip token is not 240ms"

SIZE_BYTES=$(wc -c < "$APK" | tr -d ' ')
if [ "$SIZE_BYTES" -ge 3145728 ]; then
    die "APK is larger than 3 MB ($SIZE_BYTES bytes)"
fi

if command -v sha256sum >/dev/null 2>&1; then
    SHA=$(sha256sum "$APK" | awk '{print $1}')
else
    SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
fi

cp "$APK" "$EVIDENCE/ztmetro-start.apk"
printf '%s  ztmetro-start.apk\n' "$SHA" > "$EVIDENCE/ztmetro-start.apk.sha256"
printf '%s\n' "$BADGING" > "$EVIDENCE/aapt2-badging.txt"
printf '%s\n' "$XMLTREE" > "$EVIDENCE/aapt2-manifest.xmltree.txt"
unzip -lv "$APK" > "$EVIDENCE/apk-contents.txt"
"$BT/apksigner" verify --min-sdk-version 29 --verbose --print-certs "$APK" \
    > "$EVIDENCE/apksigner-verify.txt" 2>&1 || die "apksigner verify failed"
cp "$BUILD/res/values/metro_tokens.xml" "$EVIDENCE/metro_tokens.generated.xml"
{
    printf 'turnstile out       %s ms  core/zrnmetrocore.c:50 ROW_OUT_MS\n' "128"
    printf 'turnstile in        %s ms  core/zrnmetrocore.c:51 ROW_IN_MS\n' "180"
    printf 'turnstile stagger   %s ms  core/zrnmetrocore.c:52 ROW_STAGGER_MS\n' "33"
    printf 'turnstile turn      out 50 deg, in 80 deg  core/zrnmetrocore.c:66-67\n'
    printf 'tilt delay          %s ms  ztmetro.c:148 PRESS_DELAY_MS\n' "80"
    printf 'tilt in             %s ms  core/zrnmetrocore.h:35 ZMC_TILT_IN_MS\n' "90"
    printf 'tilt out            %s ms  core/zrnmetrocore.h:36 ZMC_TILT_OUT_MS\n' "200"
    printf 'tilt geometry       %s deg  core/zrnmetrocore.h:32 ZMC_TILT_DEG\n' "14"
    printf 'long press          %s ms  ztmetro.c:145 LONG_PRESS_MS\n' "450"
    printf 'press highlight     %s ms before the lean (one 60 Hz frame)\n' "16"
    printf 'scale               Settings.Global.ANIMATOR_DURATION_SCALE read at every motion start; 0 = instant\n'
    printf 'frames at 60 Hz     out 7.7, in 10.8, stagger 2.0, tilt in 5.4, tilt out 12.0, press delay 4.8\n'
    printf 'easing              (1 - pow(0.1, t)) / 0.9  core/zrnmetrocore.c:1628 (Log(10))\n'
} > "$EVIDENCE/motion-facts.txt"
{
    printf 'launcher launch     ActivityOptions.makeCustomAnimation(context, in, out)\n'
    printf 'incoming app window %s ms from +100%%p to 0, alpha 0 -> 1, startOffset %s ms\n' "180" "33"
    printf 'outgoing Start      %s ms from 0 to -100%%p, alpha 1 -> 0\n' "128"
    printf 'easing              in decelerate_cubic / out accelerate_cubic; no overshoot\n'
    printf 'sources             core/zrnmetrocore.c:50 ROW_OUT_MS 128, :51 ROW_IN_MS 180, :52 ROW_STAGGER_MS 33\n'
    printf 'wired               tile tap, app-list row, both long-press sheet OPEN items, Settings shortcuts\n'
    printf 'returns to home     WMShell-owned; not changed by this APK (startActivity cannot supply it)\n'
} > "$EVIDENCE/launch-transition-facts.txt"
DEX_BYTES=$(wc -c < "$BUILD/verify.dex" | tr -d ' ')
{
    printf 'classes.dex: %s bytes\n' "$DEX_BYTES"
    printf 'androidx occurrences: 0 (build fails if any)\n'
    printf 'uses-permission elements: 0 (build fails if any)\n'
    printf 'package classes:\n'
    for symbol in StartActivity MetroTileView MetroGridView MetroPager \
                  MetroCircleGlyphView MetroSectionHeaderView MetroListRowView \
                  SystemGlyphs GlyphSet TileArt TileStore StartNotificationListener \
                  LiveTiles Motion Turnstile; do
        if grep -aq "$symbol" "$BUILD/verify.dex"; then
            printf '  %s: present\n' "$symbol"
        else
            printf '  %s: MISSING\n' "$symbol"
        fi
    done
} > "$EVIDENCE/dex-facts.txt"
{
    printf 'service              org.ztmetro.start/org.ztmetro.start.StartNotificationListener\n'
    printf 'flip                 rotateY 0 -> 180, %s ms, face swap at 90 deg\n' "240"
    printf 'trigger              LiveTiles.Face.signature() change only; no periodic timer\n'
    printf 'animator scale       Settings.Global.ANIMATOR_DURATION_SCALE; 0 applies the face instantly\n'
    printf 'content              messaging/email: count + latest title/text; calendar: big day;\n'
    printf '                     music: track title while a media notification exists\n'
    printf 'persistence          none: bounded in-memory map (24 packages, 48 keys/package)\n'
    printf 'listener enable      adb shell cmd notification allow_listener\n'
    printf '                     org.ztmetro.start/org.ztmetro.start.StartNotificationListener\n'
    printf 'settings path        Settings > Apps > Special access > Notification access > ztmetro start\n'
} > "$EVIDENCE/live-facts.txt"
{
    printf 'apk:      launcher/build/out/ztmetro-start.apk\n'
    printf 'evidence: %s/ztmetro-start.apk\n' "$EVIDENCE"
    printf 'size:     %s bytes\n' "$SIZE_BYTES"
    printf 'sha256:   %s\n' "$SHA"
    printf 'package:  org.ztmetro.start\n'
    printf 'version:  4 (4.0)\n'
    printf 'glyphs:   18 white 24dp paths (GlyphSet); system map before monochrome\n'
    printf 'min/target: 29 / 36\n'
    printf 'build:    %s, %s\n' "$(basename "$SDK")" "$("$JDK/bin/java" -version 2>&1 | head -n 1)"
    printf 'checks:   HOME category, notification listener + BIND permission, no uses-permission,\n'
    printf '          no androidx, live flip 240ms + dexdump service, <3 MB\n'
} > "$EVIDENCE/artifact-facts.txt"

# --- 6. offline glyph-sheet proof -------------------------------------------
# The sheet uses the same GlyphSet.java that just went into classes.dex, bound
# to Java2D instead of android.graphics.Path. It proves the 48 dp and 24 dp
# shapes without adb or a device.
say "rendering glyph sheet (48 dp + 24 dp)"
rm -rf "$BUILD/glyphsheet"
mkdir -p "$BUILD/glyphsheet"
"$JDK/bin/javac" -encoding UTF-8 -d "$BUILD/glyphsheet" \
    "$APP/java/org/ztmetro/start/GlyphSet.java" \
    "$ROOT/launcher/tools/GlyphSheet.java"
"$JDK/bin/java" -Djava.awt.headless=true -cp "$BUILD/glyphsheet" \
    GlyphSheet "$EVIDENCE/glyphs.png"

say "done"
printf 'APK:     %s\n' "$APK"
printf 'evidence:%s\n' "$EVIDENCE/ztmetro-start.apk"
printf 'size:    %s bytes (%s)\n' "$SIZE_BYTES" "$(du -h "$APK" | awk '{print $1}')"
printf 'sha256:  %s\n' "$SHA"
printf 'glyphs:  %s (48 dp + 24 dp, white on black)\n' "$EVIDENCE/glyphs.png"
printf 'verify:  HOME category, label/icon, minSdk 29 / targetSdk 36, listener service,\n'
printf '         no uses-permission, no androidx, live flip 240ms, dexdump service, <3 MB\n'
