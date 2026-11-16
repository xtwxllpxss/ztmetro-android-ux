# 18 — Coverage and reproducible build

What changed **automatically** through platform inheritance, what needed a **separate
integration**, what was actually **seen on the device**, and the exact commands to
rebuild and reapply the stack.

Snapshot: **2026-11-16 12:15 MSK**. Device: Xiaomi Redmi Note 5A (`ugglite`,
MSM8917, 2 GB, 720×1280) running **LineageOS-for-microG 23.2** (Android 16, API 36).
Host tools used by the builds: `aapt2` 2.20 / build-tools 36.0.0, JDK 17, Python 3;
heavy builds ran on `hermes-ubuntu` over SSH when the Mac had no SDK.

Labels: **[F]** read in the named evidence file / source at the cited commit,
**[E]** engineering inference from that evidence, **[?]** not verified (needs the
device, or the evidence is missing). All paths are relative to `repo/`.

Authoritative per-artifact facts (hash, size, build log) live next to each artifact;
this document summarises them and does not replace them.

---

## 1. Mechanism table

| # | Block | Mechanism | Exactly what changed | Verified on device? | Evidence that proves it |
|---|---|---|---|---|---|
| 1 | **framework accent / typography** | preinstalled RRO on **`android`** (`ZtMetroFramework.apk`, `isStatic=true`, targetSdk 36) | 92 entries — 63 colour, 7 dimen, 2 bool, 20 style: `accent_device_default*`, `accent_material_*`, `ripple_material_*`, `system_accent1_*`, the M3 roles `system_primary_*`/`system_on_primary_*`/`system_accent2/3_*`, `TextAppearance.Material.*` (13/17/20/22/25.5/48/56/92 sp), `config_*CornerRadius=0dp`, `action_bar_default_height_material=48dp`, `config_buttonTextAllCaps=false` | **no** — no frame shows a framework-tinted control changing; active QS tiles are still teal in `93-shade.png`, which `customColorShadeActive` would have changed | [`evidence/framework-rro/REPORT.md`](../evidence/framework-rro/REPORT.md), `aapt2-dump-resources.txt`, `verified-resources.csv` (92/92 names); counter-evidence [`ux-compare/current/93-shade.png`](../evidence/ux-compare/current/93-shade.png) |
| 2 | **control styles** (buttons, dialogs, ripples) | same framework RRO (values only) | `config_buttonCornerRadius`, `config_dialogCornerRadius`, `config_bottomDialogCornerRadius`, `config_progressBarCornerRadius`, `control_corner_material`, `dialog_corner_radius` → `0dp`; framework button label `TextAppearance.Material.Button` 13 sp sentence case; ripple accent | **no** — Settings' square controls come from the *Settings* RRO, not from this one; no stock-app dialog/button was captured | [`evidence/framework-rro/REPORT.md`](../evidence/framework-rro/REPORT.md) §3/§6; [`settings-rro/REPORT.md`](../evidence/settings-rro/REPORT.md) §3 |
| 3 | **Settings chrome** (home, lists, dialogs) | preinstalled RRO on **`com.android.settings`** (`org.ztos.overlay.settings`, priority 10, **not** static) | 56 verified entries at the last recorded build (2 attr, 22 colour, 15 dimen, 2 drawable, 15 style): homepage tile palette → `#0050EF`/white, radii → `0dp`, `TextAppearance.*` ramp, `preference_background`, `search_bar_selected_background`, Wi-Fi rows full-bleed. The 12:13 build adds the Settings-local accent/geometry block (`primary_material_*`, `settingslib_expressive_radius_*`, switchbar tints) — see §6 for the evidence lag | **yes** — cobalt tiles at 11:29 (`60-settings.png`), full-bleed rows + smaller type at 11:52–12:03 (`90-settings.png`, `a0-settings.png`), caps section labels at 11:58 (`96-wifi.png`) | [`evidence/settings-rro/REPORT.md`](../evidence/settings-rro/REPORT.md), `verified-resources.csv`; frames [`a0-settings.png`](../evidence/ux-compare/current/a0-settings.png), [`a1-wifi.png`](../evidence/ux-compare/current/a1-wifi.png) |
| 4 | **SystemUI shade / Quick Settings** | preinstalled RRO on **`com.android.systemui`** (`org.ztos.overlay.systemui`, priority 10, not static) | 25 entries: `qs_corner_radius` 28→0 dp, `qs_tile_height` 80→68 dp, paddings/margins 4 dp, `qs_tile_text_size` 14→13 sp, `notification_corner_radius*` →0, `shade_panel_fallback`/`notification_scrim_*` →`#000`, `shade_header_text_color` →white, `TextAppearance.QS.*` 20 sp light, square `qs_tile_background_shape` | **partial, and the square pass is blocked by Compose** — measured on `93-shade.png`: the tile gap is 7 px = **4.31 dp**, so `qs_tile_margin_horizontal` *is* applied; but the 2025 Compose tiles are **72 dp tall** with **~24 dp (active) / ~36 dp (inactive)** corners computed in `CommonTileDefaults`, so `qs_corner_radius`/`qs_tile_height`/`qs_tile_background_shape` never reach them. The black panel is stock-like and proves nothing | [`evidence/sysui-rro/REPORT.md`](../evidence/sysui-rro/REPORT.md); measurement [`qs-square/forensics/measure-shade.txt`](../evidence/qs-square/forensics/measure-shade.txt); frame [`93-shade.png`](../evidence/ux-compare/current/93-shade.png); gap in [`docs/16`](16-ux-gap-analysis.md) §2 |
| 5 | **system font** | Magisk module **`ztmetro-font` 1.1.0** (no APK, no overlay) | rewrites the live `system/etc/font_fallback.xml` **and** `system/etc/fonts.xml`: only the `<family name="sans-serif">` block → 20 static Open Sans 3.003 entries (8 faces: Light/Regular/SemiBold/Bold + italics); aliases, emoji, CJK, mono, serif untouched | **no** — no `grep`/`ls` readback and no before/after screenshot pair with identical layout; the `a0`/`a1` frames follow a typography overlay, so glyphs cannot be attributed | [`evidence/font-module-opensans/REPORT.md`](../evidence/font-module-opensans/REPORT.md) §5–§7; zip [`artifacts/ztmetro-font-1.1.0.zip`](../artifacts/ztmetro-font-1.1.0.zip) |
| 6 | **Settings header** | **LSPosed** module `org.ztmetro.settingsheader` (Zygisk) | four hooks (`SettingsBaseActivity.setContentView(int)`, `setTitle`, `onResume`, `SettingsHomepageActivity.onCreate`) insert a 56 dp cobalt header at index 0, clear the stock toolbar title, and `MetroTinter` walks the view tree to tint `CompoundButton`s cobalt | **yes** — header on home at 11:31 (`70-settings.png`), on a sub-screen at 11:35 (`71-wifi.png`), final header-only layout at 12:03–12:04 (`a0`/`a1`); cobalt switch with white thumb at 12:01+ (`97-wifi.png`, `a1-wifi.png`) | [`evidence/settings-header/REPORT.md`](../evidence/settings-header/REPORT.md); APK [`evidence/settings-header/ztmetro-settings-header.apk`](../evidence/settings-header/ztmetro-settings-header.apk) |
| 7 | **Start** | **own APK** `org.ztmetro.start`, default HOME | Metro tile grid, app list with A–Z semantic zoom, two-line caps/title header on the app list, live tiles via `NotificationListenerService` (opt-in), no `uses-permission` in the manifest; v3 redraw built | **yes, v1 look** — `23-start.png` (11:12), `40-home.png` (11:25), `92-start.png` (11:53) show `ZTMETRO`/`start`/pivot and cobalt tiles as HOME. v2/v3 were built later and are **not** in any frame | [`evidence/launcher/REPORT.md`](../evidence/launcher/REPORT.md), [`launcher-v2/REPORT.md`](../evidence/launcher-v2/REPORT.md), [`launcher-v3/REPORT.md`](../evidence/launcher-v3/REPORT.md) |
| 8 | **Action Centre** | **own APK** `org.ztmetro.actioncentre` + `NotificationListenerService` | full-screen Metro panel: own status strip, 4×2 square quick tiles (Wi-Fi/data/BT/airplane/rotation/torch/brightness/settings), `CLEAR ALL` / `ALL SETTINGS`, notification list; bundled Open Sans; 296 359 B APK | **no** — built 12:04, not installed; no frame shows it | [`evidence/actioncentre/artifact-facts.txt`](../evidence/actioncentre/artifact-facts.txt), `verify.txt`; source [`actioncentre/`](../actioncentre/) |
| 9 | **lock screen** | **LSPosed** module `org.ztmetro.keyguard` (scope `com.android.systemui`) | seven guarded hooks: white 56 sp light clock (`AnimatableClockView.refreshTime`/`onMeasure`/`setColors`), date 13 sp caps, indication accent `#3D7BFF`, fallback clock only if no View clock exists | **no** — APK built, device untouched per report; the 11:11 lock screen is stock | [`evidence/keyguard-module/REPORT.md`](../evidence/keyguard-module/REPORT.md) |
| 10 | **status strip** (in progress) | two RROs: framework (`static`, priority 20) + SystemUI (priority 20), now carried by the same overlays module | `status_bar_height*` 24→28 dp, `status_bar_system_icon_size` 15→13 dp, `status_bar_icon_drawing_{size,alpha}` 13 dp/70 %, `status_bar_padding_{start,top}` 8/2 dp, `TextAppearance.StatusBar.Default` 12 sp light white | **no** — built 12:10 and wired into the module, no device run | [`evidence/status-strip/REPORT.md`](../evidence/status-strip/REPORT.md), `name-verification.txt` |

**Verified count for this table: 3 yes / 1 partial / 6 no (10 blocks).** The three
verified blocks are Settings chrome, Settings header and Start; everything else is
host-verified only. Screenshot-by-screenshot reasoning:
[`evidence/coverage/screenshots.md`](../evidence/coverage/screenshots.md). Machine form:
[`evidence/coverage/mechanisms.csv`](../evidence/coverage/mechanisms.csv).

Two rows need a second mechanism to be complete on the device:

- **Settings controls / switch accent** are *not* reachable from the framework RRO
  alone (the Material3 switch is built by the Material library and reads
  `?attr/colorPrimary`, which `Theme.Settings` supplies). The cobalt switch on screen is
  produced by the Settings RRO's SettingsLib tints **and/or** the header module's
  `MetroTinter`; without a log the exact attribution is **[E]**.
- **SystemUI tile fill** (`shadeActive`/`shadeInactive`) is an **attr** pointing at
  framework-private colours, so a `com.android.systemui` overlay cannot set it; the
  framework overlay's `customColorShade*` entries are the only resource-level route,
  and that route is not visible on this device (row 1).

---

## 2. Why coverage is partial: inherited vs overridden

The same resource can be reached in one app and not in the next for two different
reasons: it may not exist in the target, or the target may override the theme attribute
that points at it. This is the mechanism behind "the overlay works everywhere except
where it doesn't".

### 2.1 What a stock app inherits automatically

Any app on `Theme.Material.*` / `Theme.DeviceDefault.*` (or Material Components with
dynamic colour) receives these from the **framework** RRO without an app-specific
overlay [F: AOSP 16 `core/res/res/values`, names verified in
[`framework-rro/REPORT.md`](../evidence/framework-rro/REPORT.md) §3]:

| Surface | Resource(s) that carry it | What the user sees |
|---|---|---|
| Accent of AppCompat/Material controls | `?android:attr/colorAccent` → `accent_device_default_light` / `_dark`, `accent_material_light/dark` | cobalt switches, checkboxes, radio, text-field underline, links, list arrows |
| Pressed state | `?android:attr/colorControlHighlight` → `ripple_material_light/dark` | cobalt ripple |
| Activated controls | `?android:attr/colorControlActivated` → `?attr/colorAccent` (transitive) | same cobalt on the active track |
| Material3 palette | `system_accent1_{100,200,500,600,700}[_light,_dark]`, `system_primary_{light,dark}`, `system_on_primary_{light,dark}`, partial `system_accent2/3_*` | M3 widgets that read dynamic colour (`MaterialSwitch`, slider, chips) |
| Type ramp | `TextAppearance.Material` + 19 variants (`Body1/2`, `Title`, `Headline`, `Display1–4`, `Button`, `Notification.*`, `Widget.ActionBar.*`) | 13 sp body, 20 sp semilight titles, 48/56/92 sp display, sentence-case buttons |
| Corner geometry | `config_buttonCornerRadius`, `config_dialogCornerRadius`, `config_bottomDialogCornerRadius`, `config_progressBarCornerRadius`, `control_corner_material`, `dialog_corner_radius` | square buttons/dialogs/progress bars |
| Chrome height | `action_bar_default_height_material` | 48 dp app bar |
| Caps | `config_buttonTextAllCaps`, `config_actionMenuItemAllCaps = false` | sentence case |
| QS tile fill | framework-private `customColorShadeActive/Inactive/Disabled`, `customColorOnShadeActive*` (read by SystemUI's `shadeActive`/`shadeInactive` attrs) | accent/grey tile fills — only if the framework overlay is effective |

That is the entire "free" surface. It is real: an app that uses `?android:attr/colorAccent`
or `TextAppearance.Material.Body1` is restyled by installing one APK, with no per-app
work [F mechanism; `[E]` which third-party apps use those names].

### 2.2 What each app overrides, and therefore needs its own integration

| App / component | Own resource(s) or theme item | Consequence |
|---|---|---|
| **Settings** | `Theme.Settings` sets `colorPrimary` to `@*android:color/primary_device_default_settings_light` (light) / `primary_device_default_settings` (night) and `colorAccent` to `accent_device_default_light` [F: Settings `res/values/themes.xml:68,188,249`, `values-night/themes.xml:21`; framework REPORT §3c]. Settings-local `primary_material_dark` / `primary_material_light` and `primary_dark_material_*` are also defined in the Settings package [E: per the overlay source comment in [`overlay/settings/.../ztmetro_accent_and_shapes.xml`](../overlay/settings/res/values/ztmetro_accent_and_shapes.xml); §6.5 explains why this is not settled]. SettingsLib adds `settingslib_expressive_radius_{none,small,medium,large1-3,extralarge1-3,extrasmall1-2}`, `settingslib_entry_bg_radius`, `settingslib_preference_corner_radius`, `settingslib_switch_bar_radius`, `settingslib_switchbar_switch_{track,thumb}_tint`, `settingslib_materialColorSecondaryContainer` [F: same source] | a framework-only overlay leaves Settings' M3 switch/slider, dashboard tiles, rows and radii untouched → separate `com.android.settings` RRO |
| **Settings, not reachable by any RRO** | `Theme.Settings`' `android:listPreferredItemHeight=72dip`, `android:colorBackground=@color/settingslib_materialColorSurfaceContainerLow`, dashboard tile **layout/labels** (`settingslib_dashboard_tile*`), `TextAppearance.CategoryTitle.SettingsLib`, search-bar `android:minHeight=72dp` hardcoded in `search_bar.xml:28` | row pitch, tile labels and the search bar height need the theme bag (not overlayable without dropping `preferenceTheme`/`alertDialogTheme`) or a code hook; documented in [`settings-rro/REPORT.md`](../evidence/settings-rro/REPORT.md) §6 |
| **SystemUI** | `Theme.SystemUI.QuickSettings` sets the **attrs** `shadeActive`/`onShadeActive`/`shadeInactive`/`onShadeInactive` to `@androidprv:color/customColorShade*`; its own `qs_corner_radius`, `qs_tile_height`, `qs_tile_padding*`, `TextAppearance.QS.TileLabel/Status/Build`, `notification_corner_radius(_small)` [F: SystemUI REPORT §1–§3, `evidence/sysui-rro/evidence-verify.txt`] | a `com.android.systemui` overlay can restyle geometry/typography but **not** the tile fill; a whole-bag replacement of the QS theme is not buildable standalone |
| **Telegram** | bundles `assets/fonts/rmedium.ttf`, `rextrabold.ttf`, `rmediumitalic.ttf`, `rmono.ttf`, `rmono_var.ttf`, `mw_bold.ttf`, loaded with `Typeface.createFromAsset` via `AndroidUtilities.getTypeface` [F: Telegram source tree as recorded in `docs/10a` §4.3] | the system font module does not change Telegram's branded headers/titles; ordinary text drawn with the platform default may follow the module **[E]**, never verified per-view |
| **Any app bundling fonts / using its own theme** (Flutter, React Native, games, webfont pages) | app-private assets or a `fontFamily` provider | outside system-font and RRO coverage by design; see [`font-module-opensans/REPORT.md`](../evidence/font-module-opensans/REPORT.md) §6 |
| **Launcher / Action Centre** | own resources, own theme | not covered by any platform overlay at all; they ship their own tokens and are the only blocks that are trivially device-independent |

**The consequence for coverage:** the framework overlay covers *widgets inside apps*,
the per-target overlays cover *app chrome*, the font module covers *text painted with
the system family*, and the LSPosed modules cover *views that do not exist in the
target*. No single mechanism can close the other three. That is why the delivery is a set of
artifacts, one per mechanism (and two per target where a hook is involved), not one APK.

---

## 3. Rebuild and reapply, in order

### 3.0 Host prerequisites

- Android SDK with `platforms/android-36/android.jar` and `build-tools/36.0.0`
  (`aapt2`, `zipalign`, `apksigner`), JDK 17 (`javac`, `keytool`), Python 3.
- The heavy scripts can drive the build host over SSH when the Mac has no SDK:
  `hermes-ubuntu` with `~/android-sdk`, `~/android-jdk` (the layout used by every
  report). Override with `ANDROID_SDK` / `ANDROID_JDK` / `BUILD_HOST` / `REMOTE_WORK`
  where the script supports it.
- For the device: `adb` and `fastboot`; root through Magisk (`su -c`), because the
  LineageOS user build has no `adb root` [E: build type not recorded].

Run every build from the workspace root (`android-ux-research/`, the parent of `repo/`)
unless a script says otherwise.

### 3.1 One-time: flash LineageOS-for-microG 23.2 for `Mi8917`

Full plan and gates: [`evidence/device-flash/PLAN.md`](../evidence/device-flash/PLAN.md).
The commands below are exactly its §3, with `$FB` = the platform-tools `fastboot`
(`~/android-sdk/platform-tools/fastboot` on the Ubuntu host).

```sh
# fetch + verify (download measured: 90.2 s, 13.4 MB/s)
mkdir -p ~/lineage-flash && cd ~/lineage-flash
curl -O https://download.lineage.microg.org/Mi8917/lineage-23.2-20260812-microG-Mi8917{,-boot.img,-recovery.img,-super_empty.img}{,.sha256sum}
sha256sum -c *.sha256sum                      # all four OK

# GATE-0: battery >= 50 %, phone data exported, USB direct to the host
$FB devices
$FB getvar product                            # MUST be: ugglite
$FB getvar all 2>&1 | grep -E 'version-baseband|version-bootloader|battery-voltage|unlocked'

$FB flash recovery ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917-recovery.img
$FB boot     ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917-recovery.img
adb devices                                   # recovery
# recovery UI: Factory reset -> Format data / factory reset
# recovery UI: Apply update -> Apply from ADB
adb -d sideload ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917.zip
# recovery UI: Reboot system now

adb devices
adb shell getprop ro.lineage.version          # 23.2
adb shell getprop ro.build.version.release    # 16
adb shell getprop ro.build.version.sdk        # 36
```

Do **not** flash `super_empty.img`: `super` is retrofit over p24 `system` (3 GiB) + p47
`cust` (512 MiB), and the recovery/sideload path creates the metadata itself [F:
PLAN §4]. The microG recovery will ask to install an unsigned/other-key zip — accept;
do not mix it with an official LineageOS zip [F: PLAN §9, risk 5].

**Evidence status:** the command list is [F] from PLAN; the actual flash has **no log**
in this repo — the proof it happened is the on-device LineageOS screenshots, not a
transcript. See §6.1.

### 3.2 One-time: Magisk, Zygisk, LSPosed/Vector

1. Patch the ROM's `boot.img` with the Magisk app on the phone
   (`Install → Select and Patch a File`), then flash the patched image [F: `docs/13` §1]:
   ```sh
   $FB flash boot magisk_patched-*.img
   $FB reboot
   adb shell su -c 'magisk -v'        # v31.1 era; Android 16 + Zygisk since v30.3
   ```
2. Magisk app → **Settings → Zygisk** → enable → reboot [F: `docs/13` §2, `docs/10a` §6].
3. Install **LSPosed/Vector** (Android 16 needs the JingMatrix/Vector fork, LSPosed
   v1.10.2+; the project used Vector v2.2) as a Magisk module, then reboot [F:
   `docs/10a` §6, `docs/13` §2]:
   ```sh
   adb push Vector-*.zip /sdcard/
   adb shell su -c 'magisk --install-module /sdcard/Vector-*.zip'
   adb reboot
   ```
   The module manager only appears after the reboot; the header/keyguard APKs below are
   plain user apps enabled in it.

### 3.3 Build each artifact

Every script is idempotent and fails closed on a bad overlay/APK. Sizes (and the
per-file hash pointers) are in §4.3; each artifact's own evidence file is authoritative.

```sh
# framework-res RRO (target android; values-only; static)
repo/scripts/build-overlay-framework.sh --out /tmp/ztmetro-fw
#   flags: --out DIR | --unsigned | --no-regen
#   env:   ANDROID_SDK_ROOT, ANDROID_BUILD_TOOLS, JAVA_HOME
#   -> /tmp/ztmetro-fw/ZtMetroFramework.apk

# Settings RRO (target com.android.settings; not static)
repo/scripts/build-overlay-settings.sh
#   flags: --out DIR
#   env:   ANDROID_SDK, ANDROID_JDK, KEYSTORE, BUILD_HOST, REMOTE_WORK,
#          BUILD_TOOLS_VERSION, APK_NAME
#   -> evidence/settings-rro/ztmetro-settings-rro.apk

# SystemUI RRO (target com.android.systemui; not static)
repo/scripts/build-overlay-sysui.sh
#   same env/flag family as the Settings script; -> evidence/sysui-rro/...

# status-strip RRO pair (framework + SystemUI)
repo/scripts/build-overlay-status.sh
#   env: BUILD_LOCAL, BUILD_HOST, ANDROID_SDK, ANDROID_JDK, BUILD_TOOLS_VERSION,
#        OUT_DIR, REMOTE_WORK, KEYSTORE
#   -> evidence/status-strip/ztmetro-status-{framework,systemui}-rro.apk

# Magisk module that carries all five overlay APKs + the boot-time enable service
repo/scripts/build-overlay-module.sh
#   flag: --out DIR (default repo/artifacts)
#   -> artifacts/ztmetro-overlays-1.0.0.zip

# system font Magisk module (no SDK needed)
repo/scripts/build-font-module.sh --out /tmp/ztmetro-font.zip
#   flags: --out PATH | --allow-font-substitution (local licensed files only)
#   -> artifacts/ztmetro-font-1.1.0.zip by default

# Settings header LSPosed module (downloads the pinned Xposed API 82 once)
repo/scripts/build-header-module.sh
#   env: ANDROID_HOME, JAVA_HOME, HEADER_BUILD_DIR, HEADER_KEYSTORE_PASS
#   -> settings/header-module/build/dist/ztmetro-settings-header.apk

# Start launcher (SDK + JDK + python3 only)
repo/scripts/build-launcher.sh
#   env: ANDROID_HOME / ANDROID_SDK_ROOT, JAVA_HOME
#   -> evidence/launcher-v3/ztmetro-start.apk

# Metro Action Centre (javac + aapt2 + d8 + apksigner; no Gradle/AndroidX)
repo/scripts/build-actioncentre.sh
#   env: APP_DIR, BUILD_DIR, ACTIONCENTRE_REMOTE, ACTIONCENTRE_REMOTE_DIR
#   -> evidence/actioncentre/ztmetro-actioncentre.apk

# keyguard LSPosed module
repo/scripts/build-keyguard-module.sh
#   env: ANDROID_HOME, JAVA_HOME, KEYGUARD_BUILD_DIR, KEYGUARD_EVIDENCE_DIR,
#        KEYGUARD_KEYSTORE_PASS
#   -> evidence/keyguard-module/ztmetro-keyguard.apk
```

Note: the framework and Settings/SystemUI scripts re-sign with a throwaway key (the
Settings/SystemUI pair reuses `~/.ztmetro-overlay-debug.keystore`, so rebuilds are
upgrade-compatible). A clean rebuild on a new host generates a new key — that is fine
for preinstalled overlays, but do not try to "update" an installed user app across
keys [F: scripts, reports].

### 3.4 Install / apply each artifact

```sh
# --- overlays: one Magisk module maps five APKs into system/product/overlay/ ---
adb push repo/artifacts/ztmetro-overlays-1.0.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-overlays-1.0.0.zip'
adb reboot
# service.sh enables settings, systemui and status.systemui at boot
# (framework + status.framework are static; no cmd needed)

# explicit enable + apply (also the per-iteration path, no reboot)
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.settings'
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.systemui'
adb shell am force-stop com.android.settings
adb shell su -c 'killall com.android.systemui'

# --- font: Magisk module; needs a reboot ---
adb push repo/artifacts/ztmetro-font-1.1.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-font-1.1.0.zip'
adb reboot

# --- Settings header: normal APK + LSPosed scope ---
adb install -r repo/evidence/settings-header/ztmetro-settings-header.apk
# LSPosed Manager -> Modules -> ztMetro Settings header -> enable
# scope: com.android.settings ONLY (module ships xposedscope; do not add SystemUI)
adb shell am force-stop com.android.settings

# --- Start: normal APK, default HOME ---
adb install -r repo/evidence/launcher-v3/ztmetro-start.apk
adb shell cmd package set-home-activity org.ztmetro.start/.StartActivity

# --- Action Centre: normal APK + listener grant + WRITE_SETTINGS appop ---
adb install -r repo/evidence/actioncentre/ztmetro-actioncentre.apk
adb shell cmd notification allow_listener \
    org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener
adb shell appops set org.ztmetro.actioncentre WRITE_SETTINGS allow
adb shell am start -n org.ztmetro.actioncentre/.ActionCentreActivity

# --- keyguard: normal APK + LSPosed scope ---
adb install -r repo/evidence/keyguard-module/ztmetro-keyguard.apk
# LSPosed Manager -> ztMetro Keyguard -> enable, scope: com.android.systemui
adb shell su -c 'killall com.android.systemui'
```

The Magisk install command is the pattern recorded for the font module
([`font-module-opensans/REPORT.md`](../evidence/font-module-opensans/REPORT.md) §7);
the same module installer handles the overlay zip. `su -c` assumes Magisk's `su` is on
`PATH` for the shell user [E].

### 3.5 Verify each step

| # | Block | Command | Expected |
|---|---|---|---|
| 1 | overlays registered | `adb shell su -c 'cmd overlay list \| grep -E "ztmetro\|ztos"'` | `org.ztmetro.overlay.framework`, `org.ztos.overlay.systemui`, `org.ztos.overlay.settings`, `org.ztos.overlay.status.*` present |
| 2 | overlay state + idmap | `adb shell su -c 'cmd overlay dump org.ztos.overlay.settings'` | state `enabled`, no `idmap2 … not allowed` in `adb logcat` |
| 3 | boot-time enable log | `adb shell su -c 'cat /data/local/tmp/ztmetro-overlays.log'` | `enable` lines for the four enable-time packages |
| 4 | Settings chrome | open Settings, Wi-Fi | full-bleed rows, caps section labels, `0dp` panels, cobalt dashboard tiles (compare [`a0-settings.png`](../evidence/ux-compare/current/a0-settings.png)) |
| 5 | Settings header | open Settings, then Wi-Fi | 56 dp cobalt block above content, `Настройки`/`Интернет` + `ztMetro`, no duplicate toolbar title |
| 6 | header hook loaded | `adb logcat -s ztmetro-header` | `loading in Settings (xposed API 93)` + `module resources via …` |
| 7 | shade | pull down the shade | square tiles, black panel, white 20 sp header, square cards (currently **not** observed — see §6) |
| 7b | status strip | `adb shell su -c 'cmd overlay dump org.ztos.overlay.status.systemui'` | enabled; strip 28 dp, icons 13 dp at 70 % opacity |
| 8 | font | `adb shell su -c 'grep -c OpenSans- /system/etc/font_fallback.xml'` | `20`; also `ls /system/fonts/OpenSans-*.ttf` → 8 files |
| 9 | font applied | `adb logcat -d \| grep -i 'Loading font config'` | names `font_fallback.xml`; Settings text changes after force-stop |
| 10 | Start | `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` | `org.ztmetro.start/.StartActivity` |
| 11 | Action Centre | `adb shell am start -n org.ztmetro.actioncentre/.ActionCentreActivity` | panel opens; tiles toggle; notification list or the "access not granted" hint |
| 12 | keyguard | lock/unlock | 56 sp white light clock; LSPosed log has no `ztmetro-keyguard` errors |

---

## 4. Cost notes

### 4.1 One-time platform preparation vs per-change iteration

| Step | Class | Repeats? |
|---|---|---|
| Download + flash LineageOS 23.2, first boot, setup | **one-time platform** | only on a new device/ROM; destroys the previous install (PLAN §6) |
| Magisk boot patch + flash, Zygisk, LSPosed/Vector | **one-time platform** | survives module changes; lost only on a boot re-flash/OTA |
| First install of overlays/header/launcher/font | **one-time provisioning** | module zips and APKs persist across reboots |
| Rebuild one overlay + push + `cmd overlay enable`/force-stop | **per change** | seconds to minutes; no reboot for Settings/SystemUI (non-static) |
| Framework overlay change | **per change, heavier** | static → reboot required; rollback is module removal, not a toggle |
| Font module change | **per change, reboot** | config is rewritten at install time and mounted at boot |
| Header/keyguard hook change | **per change, process restart** | `adb install -r` + force-stop; no reboot |
| Launcher / Action Centre change | **per change, trivial** | `adb install -r` |

### 4.2 Measured times

Only a few numbers are actually measured in the evidence; the rest are estimates and
are labelled as such:

| Item | Value | Source |
|---|---|---|
| ROM download, 1 205 136 779 B | **90.2 s at 13.4 MB/s** | `evidence/device-flash/EVIDENCE.md` §3 [F] |
| Flash window (recovery, format, sideload, first boot, checks) | **30–45 min** | PLAN §3 [E] |
| First boot after sideload | **5–15 min** | PLAN §3 [E] |
| ROM zip / boot / recovery size | 1 205 136 779 / 20 967 424 / 32 790 528 B | PLAN §2 [F] |
| Settings/header iteration observed from mtimes: source or APK 12:01–12:02 → screenshot 12:03–12:04 | **≈2 min** for push + restart | file mtimes [E] |
| Header iteration observed: 11:29 (Settings RRO visible) → 11:31 (header visible) | **≈2 min** | screenshot clocks [E] |
| Build durations of the scripts | **not recorded** — no timings in any build log | absence [F] |

### 4.3 Artifact sizes (current snapshot)

Sizes are the bytes on disk in this snapshot; rebuilds during the same day changed some
of them (the Settings RRO and the overlays zip grew when the Settings accent block and
the status-strip APKs were folded in). The hash authority is always the small
`*.sha256` / `artifact-facts.txt` / build log in the same evidence directory — do not
copy a hash out of this table.

| Artifact | Bytes | Hash authority |
|---|---|---|
| `evidence/framework-rro/ZtMetroFramework.apk` | 16 728 | [`REPORT.md`](../evidence/framework-rro/REPORT.md) |
| `evidence/sysui-rro/ztmetro-systemui-rro.apk` | 12 719 | [`build-log.txt`](../evidence/sysui-rro/build-log.txt) |
| `evidence/settings-rro/ztmetro-settings-rro.apk` | 21 196 (12:13 rebuild) | current file; the 56-entry report describes the 17 100 B build — see §6.3 |
| `artifacts/ztmetro-overlays-1.0.0.zip` (module: 5 overlay APKs) | 29 095 (12:13 rebuild) | `shasum -a 256` on the zip; module tree in [`modules/ztmetro-overlays/`](../modules/ztmetro-overlays/) |
| `artifacts/ztmetro-font-1.0.0.zip` (Selawik, superseded) | 134 010 | [`evidence/font-module/REPORT.md`](../evidence/font-module/REPORT.md) |
| `artifacts/ztmetro-font-1.1.0.zip` (Open Sans) | 700 774 | [`REPORT.md`](../evidence/font-module-opensans/REPORT.md) `33d64346…` |
| `evidence/launcher/ztmetro-start.apk` (v1) | 37 142 | [`REPORT.md`](../evidence/launcher/REPORT.md) |
| `evidence/launcher-v2/ztmetro-start.apk` | 49 430 | [`REPORT.md`](../evidence/launcher-v2/REPORT.md) |
| `evidence/launcher-v3/ztmetro-start.apk` | 53 526 | [`artifact-facts.txt`](../evidence/launcher-v3/artifact-facts.txt) |
| `evidence/settings-header/ztmetro-settings-header.apk` | 25 268 (12:02 rebuild) | current file; the report's 21 172 B table is stale — see §6.4 |
| `evidence/actioncentre/ztmetro-actioncentre.apk` | 296 359 | [`artifact-facts.txt`](../evidence/actioncentre/artifact-facts.txt) |
| `evidence/keyguard-module/ztmetro-keyguard.apk` | 20 947 | [`REPORT.md`](../evidence/keyguard-module/REPORT.md) |
| `evidence/status-strip/ztmetro-status-*-rro.apk` (2) | 8 536 each | [`REPORT.md`](../evidence/status-strip/REPORT.md) |

Total installed bytes (five overlay APKs + header + launcher v3 + Action Centre +
keyguard APKs, plus the font module zip) ≈ **1.16 MB**. There is no meaningful
storage/RAM cost on this 2 GB device
[E]; the LSPosed modules add a per-process hook cost that was not measured [F absence].

---

## 5. Second target / another ROM

### 5.1 What is device-independent

| File / artifact | Why it ports |
|---|---|
| `design/tokens/ztmetro.tokens.json` + `gen_android.py` / `gen_overlay.py` | pure design data and code generation; no device constants |
| `scripts/*.sh` | parameterised by `ANDROID_SDK*`, `JAVA_HOME`, output dirs; no device paths |
| `overlay/framework/tools/check_names.py`, `overlay/settings/tools/check_names.py` | read a target tree + an APK dump; the check is reusable for any ROM |
| **framework** overlay values | AOSP resource names (`accent_device_default_*`, `TextAppearance.Material.*`, `config_*CornerRadius`, `system_accent1_*`) are stable across Android 16 devices |
| Start launcher APK | plain SDK app, minSdk 29, no ROM API, feature-detects notification access |
| Action Centre APK | plain SDK app, minSdk 29, standard `Settings.Panel`/`TorchMode` APIs |
| font module **scripts** (`rewrite-fonts.sh`, `verify-xml.awk`, `customize.sh`) | read the live `/system/etc/font_fallback.xml`; no hardcoded ROM values |
| header / keyguard module **structure** (guarded hooks, module-resource inflation) | the pattern ports; the hook targets do not (see 5.2) |

### 5.2 What is ROM-coupled

| File | Coupled to | Symptom when it drifts |
|---|---|---|
| `overlay/settings/res/values/*.xml` | Settings `lineage-23.2` resource **names** (`primary_device_default_settings_*`, `settingslib_expressive_radius_*`, `min_tap_target_size`, `homepage_*_{fg,bg}`, `wifi_item*`, `SearchBarStyle_v2`…), file:line map | a missing name is dropped **silently** by idmap2; the screen just keeps the stock value |
| `overlay/systemui/res/*` | SystemUI `lineage-23.2` names (`qs_corner_radius`, `qs_tile_height`, `TextAppearance.QS.TileLabel`, `notification_corner_radius`, `shade_panel_fallback`, `qs_tile_background_shape`) and the **Compose/2025 shade** behaviour | geometry/typography silently reverts; tile fill was never reachable |
| `overlay/framework/res/values/ztmetro_accent_roles.xml` | `primary_device_default_settings_light` / `…settings` (must exist in the framework of that Android version) | M3 switch accent falls back; no crash |
| `settings/header-module/src/…` | `com.android.settings.core.SettingsBaseActivity#setContentView(int)/setTitle`, `SettingsHomepageActivity#onCreate`, ids `content_frame`, `homepage_container`, `action_bar`, `collapsing_toolbar` | hooks skip/log; Settings stays stock (fail-safe) |
| `sysui/keyguard-module/src/…` | `AnimatableClockView`, `KeyguardClockViewBinder`, `KeyguardSliceView`, `KeyguardIndicationTextView`, `NotificationShadeWindowView` (not `KeyguardClockSwitch` — deleted at 23.2) | hooks skip; lock screen stays stock |
| `modules/ztmetro-font/fonts.fragment` + `rewrite-fonts.sh` | Android 15+ config shape (`font_fallback.xml` + legacy `fonts.xml`); a ROM that changes the `<family name="sans-serif">` shape aborts the install by design | install refuses, nothing half-applied |
| `modules/ztmetro-overlays/service.sh` | the four enable-time package names above | a renamed package is never enabled |
| all overlays | Android **16 / API 36** resource sets (minSdk 36 for framework; 34 for Settings/SystemUI overlays) | on an older ROM the overlay may install but map nothing |

### 5.3 Porting checklist for another LineageOS 23.2 device

1. Confirm the target is **Android 16 / API 36** (`ro.build.version.sdk=36`) and
   LineageOS-for-microG 23.2; if the API differs, do not install the framework overlay
   (minSdk 36) — rebuild it against that `android.jar`.
2. Fetch the matching target trees at the ROM's branch: `android_frameworks_base`
   (framework values + SystemUI) and `android_packages_apps_Settings` (Settings +
   SettingsLib, including `res/values/themes.xml` and `styles.xml`).
3. Run the name checks before building:
   ```sh
   python3 repo/overlay/settings/tools/check_names.py --tree <settings-src> \
       --dump <apk dump> --md … --csv …
   python3 repo/overlay/framework/tools/check_names.py --apk <apk> --aapt2 $AAPT2 \
       --aosp-values <aosp>/values --aosp-color <aosp>/color
   ```
   Do not proceed with a name the target does not define; idmap2 will drop it silently.
4. Rebuild every overlay (framework, Settings, SystemUI, status pair) and diff
   `aapt2 dump resources` against the target tree.
   Anything that disappeared is a ROM change, not a build failure.
5. Font module: confirm `/system/etc/font_fallback.xml` exists and the `sans-serif`
   family has the shape the rewrite expects; run the installer dry run first
   ([`evidence/font-module-opensans/scripts/dry-run-install.sh`](../evidence/font-module-opensans/scripts/dry-run-install.sh)).
6. Header/keyguard: check that the hook classes/methods in §5.2 still exist at the same
   signatures; the modules fail safe but then do nothing. Re-check the resource ids
   (`getIdentifier` is by name, so renamed ids only lose the header, not crash).
7. Launcher and Action Centre need no porting beyond `minSdk 29`; verify the HOME role
   and the notification-listener grant.
8. Install **one block at a time**, screenshot, and keep the rollback paths:
   `cmd overlay disable`, `am force-stop`, LSPosed disable, Magisk safe mode
   (Vol−), `magisk --remove-modules`, recovery removal of `/data/adb/modules/…`
   ([`docs/15`](15-boot-recovery.md)).
9. Never flash `super_empty.img` on a retrofit-super device; it wipes the logical
   partitions (PLAN §4).

A machine-readable split is maintained by the portability task (`docs/22`) if that
file is present in the checkout; otherwise this section is the checklist.

---

## 6. Contradictions and gaps found while cross-checking

These are the places where the evidence and the prose disagree. None was resolved by
inventing a fact; each is stated with what is actually known.

### 6.1 The reports say "not device-tested", the repo contains device screenshots

`framework-rro/REPORT.md` §7, `sysui-rro/REPORT.md` §4, `settings-rro/REPORT.md` §5.5,
`settings-header/REPORT.md` §7, `launcher-v2/REPORT.md` and
`font-module-opensans/REPORT.md` §8 all say the device was not touched. Yet
`evidence/device-bringup/` (11:11–11:42) and `evidence/ux-compare/current/`
(11:45–12:04) are real 720×1280 captures showing the stack applied. The reports are
**stale hand-offs**, not lies: each was written before the lead's device pass. This
document treats the screenshots as the verification source and the reports as the
host-verification source.

### 6.2 The flash plan says "preparation only" but the phone runs LineageOS

`device-flash/PLAN.md` and `EVIDENCE.md` both say nothing was flashed. The screenshots
show LineageOS 23.2 with the ztMetro stack, so the flash did happen later. There is
**no transcript** of that run (no `fastboot`/`sideload` log in `evidence/`). The
commands in §3.1 are therefore [F] as a recipe, [?] as an executed transcript.

### 6.3 Settings RRO report is one build behind its source

The report/CSV/dump describe **56 entries** and hash `6180aa…`; the current APK is
**21 196 B** with a different hash, rebuilt at 12:13 after
`overlay/settings/res/values/ztmetro_accent_and_shapes.xml` (11:40 / 12:01) added
Settings-local accent and SettingsLib radius entries. `verified-resources.csv` has not
been regenerated for those names, so their existence/consumer check is **[?]** until
the next `check_names.py` run. The visual result (blue switch at 12:01, full-bleed rows)
is real, but which of the new names produced it is **[E]**.

### 6.4 Settings header report/table is stale, and the mechanism is now two-part

`settings-header/REPORT.md` says 21 172 B / `4d9c801e…`; the current APK is
**25 268 B** / `48d7764b…` (12:02) and `MetroTinter.java` now exists (it tints
`CompoundButton`s cobalt at runtime). The report's "one view, no other change" is no
longer accurate. Also, the switch accent on screen may come from `MetroTinter` **or**
from the Settings RRO's `settingslib_switchbar_switch_{track,thumb}_tint`; no log
distinguishes them.

### 6.5 `colorPrimary` in Settings: two different claims

`framework-rro/REPORT.md` §3c traces `Theme.Settings`' `colorPrimary` to
`primary_device_default_settings_light` / `primary_device_default_settings` (framework
resources); `overlay/settings/res/values/ztmetro_accent_and_shapes.xml`'s comment says
Settings sets `colorPrimary=@color/primary_material_dark` (a Settings-local colour).
Both cannot be the complete truth. The device shows the switch turned cobalt only after
the Settings pass, which is consistent with *some* Settings-local resource and/or the
LSPosed tint, but the exact resource chain is **[?]**.

### 6.6 The SystemUI RRO is documented as effective for tiles; the Compose shade ignores the tile resources

`sysui-rro/REPORT.md` predicts square 0 dp tiles and 13 sp labels. A pixel measurement of
`93-shade.png` ([`qs-square/forensics/measure-shade.txt`](../evidence/qs-square/forensics/measure-shade.txt))
shows that the overlay **is at least partly live**: the horizontal gap between tiles is
7 px = **4.31 dp**, matching the overlay's `qs_tile_margin_horizontal` 4 dp, not the
stock 8 dp. But the tiles are **72 dp tall** (Compose `CommonTileDefaults.TileHeight`)
with ~24 dp active / ~36 dp inactive corners computed in Compose code, so
`qs_corner_radius`, `qs_tile_height`, `qs_tile_padding` and
`qs_tile_background_shape` cannot change them. The 12:20 rebuild in
[`evidence/qs-square/`](../evidence/qs-square/) is an attempt to find a reachable
resource; it is not device-verified. Treat the **square** QS pass as **not delivered**,
and the margins/notification radii as delivered-but-unmeasured on screen.

### 6.7 The framework RRO has no on-screen confirmation at all

No frame isolates a framework-inherited control. The strongest counter-evidence is the
teal QS tile fill, which the framework overlay's `customColorShadeActive` would have
made cobalt. This may be because the overlay is not installed/enabled, or because a
Lineage monet overlay outranks it (`framework-rro/REPORT.md` §7 warns exactly about
this). The distinction matters for the port: do not assume the framework layer works
until `cmd overlay list` shows it enabled and one stock control turns cobalt.

### 6.8 `docs/16` describes the stack as enabled, including pieces this evidence cannot confirm

`docs/16` §5 says the Selawik/Open Sans font and the QS square pass are "banked"; the
font has no readback and the QS pass has no square tiles on screen. The roadmap
([`docs/14`](14-ux-roadmap.md) §5) also lists the SystemUI RRO and Selawik as
"already banked". Read both as intent/status summaries, not as verification.

---

## Appendix — artifact ↔ evidence map

| Artifact | Built by | Evidence dir |
|---|---|---|
| framework RRO | `scripts/build-overlay-framework.sh` | `evidence/framework-rro/` |
| Settings RRO | `scripts/build-overlay-settings.sh` | `evidence/settings-rro/` |
| SystemUI RRO | `scripts/build-overlay-sysui.sh` | `evidence/sysui-rro/` |
| overlays Magisk module | `scripts/build-overlay-module.sh` | `artifacts/ztmetro-overlays-1.0.0.zip` + module tree |
| font module | `scripts/build-font-module.sh` | `evidence/font-module/` (Selawik), `evidence/font-module-opensans/` (Open Sans) |
| Settings header | `scripts/build-header-module.sh` | `evidence/settings-header/` |
| Start launcher | `scripts/build-launcher.sh` | `evidence/launcher/`, `-v2/`, `-v3/` |
| Action Centre | `scripts/build-actioncentre.sh` | `evidence/actioncentre/` |
| keyguard | `scripts/build-keyguard-module.sh` | `evidence/keyguard-module/` |
| status strip | `scripts/build-overlay-status.sh` | `evidence/status-strip/` |
| verification index | — | `evidence/coverage/` |
