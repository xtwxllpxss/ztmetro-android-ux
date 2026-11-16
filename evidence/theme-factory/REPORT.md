# theme-factory — Calendar run report

What was actually executed for the resource-level prototype, on which host, with
which results, and what remains unverified.

## Environment

| | |
|---|---|
| build host | `hermes-ubuntu` (Ubuntu 24.04, 8 cores), workdir `~/ztux/factory` |
| SDK | `~/android-sdk`, build-tools **36.0.0** (`aapt2` 2.20-13193326), `platforms/android-36/android.jar` |
| JDK | `~/android-jdk` (Temurin **17.0.21**), `apksigner` verified the output |
| signing key | `~/.ztmetro-overlay-debug.keystore` — the same throwaway key as the other ztmetro overlays |
| factory | `repo/tools/theme-factory` (Python 3.12, stdlib only) |
| device | **not touched** — no adb in this session; the lead owns the phone |

## Input APK

The ROM zip on the host (`~/lineage-flash/lineage-23.2-20260812-microG-Mi8917.zip`)
carries only `*.new.dat.br` partition images — there is no standalone Calendar APK
to pull out of it without unpacking a 500 MB brotli image, which this host does not
have the tools or the free space for. No Calendar APK was in the lead's `/tmp`
mirror either (only Settings and SystemUI).

So the analysed APK is the F-Droid build of the **same LineageOS 23.2 Etar branch**:

| | |
|---|---|
| source | `https://f-droid.org/repo/ws.xsoh.etar_57.apk` |
| package | `ws.xsoh.etar` 1.0.57 (57), minSdk 23, targetSdk 37, label “Etar” |
| launchable | `com.android.calendar.AllInOneActivity` |
| sha256 | `dae01d93c8920aa63845868db2190bcc4aa6ff8410b1289de1ce27b1bb89c599` |

Note: the task text says `org.lineageos.etar`; current LineageOS/F-Droid Etar uses
`ws.xsoh.etar`. The tool reads the package from `aapt2 dump badging`, and
`registry.json` lists both ids, so an older pull still works.

## analyse

`bash repo/scripts/theme-factory.sh analyse --apk etar-fdroid.apk --out app-profile.json`
(9.3 s wall clock, 8 threads for the layout scan)

* 7865 resources parsed, **1231 colour** resources, 193 layouts scanned with `aapt2 dump xmltree`
* **423 mappings** — text 173, surface 117, accent 71, radius 29, destructive 15,
  type 13, surface_key 4, success 1
* **74.1 %** of the colour resources the app actually uses are mapped (234 / 316);
  1231 includes library colours the app never references
* 14 config-qualified output sets: `default, night, night-v31, v31, v34, v35, land,
  h360dp-land, h480dp-land, sw600dp, w360dp-port, w400dp-port, w600dp-h879dp, w960dp-h527dp`
* contrast: **166 checks, 1 escalation** (`color/colorAccent` → `#3D7BFF` because the
  theme also uses it as text)
* theme: `style/Theme.Etar` → `BaseTheme` → `Theme.Material3.DayNight.NoActionBar`;
  **no `<overlayable>`**; **3 Monet styles** detected
  (`CalendarAppTheme{Black,Dark,Light}Monet`) whose `(v31)` bags point straight at
  `@android:color/system_*` → the documented partial-theme caveat
* `unmanaged`: 14 hardcoded layout colours, 4 hardcoded style colours, 6 widget
  “radius” names that are really sizes, 1 type size too far from the ramp, plus the
  style/drawable/selector classes the prototype does not touch

## generate

`bash repo/scripts/theme-factory.sh generate --profile app-profile.json --out overlay`

* overlay package `org.ztos.overlay.factory.ws_xsoh_etar`, target `ws.xsoh.etar`,
  priority 10, `android:hasCode="false"`
* **522 values** in **15 `values-*` directories** (423 names, config-qualified ones
  counted per config); `aapt2 link` took < 1 s
* APK: `overlay/ztmetro-ws_xsoh_etar-rro.apk`, **49 496 bytes**
* **sha256 `80e3ef1a23564b49bf21b4f4783f02aeb1176271c90b4ccf8ac2261cd0fe1ea8`**
* self-check: no `classes*.dex`, `targetPackage` present, `hasCode=false`,
  0 missing entries — `build.json.verify.ok = true`

Examples from `overlay/mapping-table.md`:

| resource | before | after |
|---|---|---|
| `color/background_color` | `#fffafafa` | `#000000` |
| `color/colorPrimary` | `#ff41c3b1` | `#0050EF` |
| `color/colorAccent` (also used as text) | `#ff1dc1ab` | `#3D7BFF` |
| `color/calendar_date_banner_background` | `#ffeeeeee` | `#000000` |
| `color/m3_sys_color_light_surface` | `#fef7ff` | `#000000` |
| `color/m3_sys_color_dark_on_surface` | `#e6e0e9` | `#FFFFFF` |
| `dimen/m3_sys_shape_corner_value_medium` | `12dp` | `0dp` |
| `dimen/date_header_text_size` | `16sp` | `17sp` |
| `dimen/chip_text_size` | `14sp` | `13sp` |

## verify

`overlay/verify.sh etar-fdroid.apk` → **`522 values OK, 0 mismatched, 0 missing` — PASS**
(each expected name is also checked to exist in the target, so a typo cannot pass).

The screenshot half of the same script was exercised on two existing Settings
captures (`/tmp/90-settings.png` vs `/tmp/a0-settings.png`):
`changed pixels: 1233 / 921600 = 0.13 %`, diff PNG written. So the recipe works;
it was **not** run on Calendar before/after because there is no device here.

## Safety checks exercised

* package allow list: a profile edited to `com.example.unlisted` is refused with
  exit 1 and the exact registry path (`policy-refusal.log`) — default deny works.
* overlay self-check: `build.json.verify.ok = true` (no dex, `hasCode=false`,
  `targetPackage` present, 0 missing entries).
* deterministic build: the same command twice produces the same sha256
  `80e3ef1a…1ea8` (the throwaway key and resource order are stable).
* a second full run from a deleted `overlay/` directory reproduces 522/0/0 in
  `verify.log`.

## Generality

Same tool, unmodified, on the Settings APK (`/tmp/settings.apk`, 86 MB,
`com.android.settings`, sha256 `738994e9…`), `--no-layouts`:
20836 resources / 1661 colours → 260 of 337 used colours mapped (77.2 %),
accent 182, surface 210, text 236, radius 83, type 65, 237 contrast checks.
The factory is not Calendar-specific; see `extra-settings.log`.

## What is NOT verified (honest list)

1. **Nothing was applied to hardware.** No adb here. The overlay APK is built and
   value-verified, but nobody has looked at a themed Calendar screen yet.
2. **Monet.** If the device runs Etar with dynamic colour enabled, the three
   `*Monet` themes bypass colour resources (`§5.4` of `docs/23-theme-factory.md`).
   Expected result there: partially themed. Turning Monet off in Etar's settings is
   the cheap way to see the factory's full effect.
3. **Visual risk of the type/radius half.** 29 radii → 0dp and 13 sp sizes are
   metric changes inside fixed rows; a per-screen screenshot diff is required
   before shipping (recipe is in `verify.sh`).
4. **A new preinstalled overlay needs one reboot** before `cmd overlay` can see it
   (PackageManager scans `/product/overlay` at boot). `apply` and the watchdog say
   so explicitly and refuse to pretend otherwise.
5. The APK analysed is the F-Droid build of the branch, not a `pm pull` from this
   exact phone. Resource names match the branch; a device pull can be analysed with
   the same one-line command and will only change the sha256 pin.

## Reproduce

```sh
ssh hermes-ubuntu
cd ~/ztux/factory
bash repo/scripts/theme-factory.sh analyse  --apk etar-fdroid.apk \
     --out repo/evidence/theme-factory/app-profile.json
bash repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json --dry-run
bash repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json \
     --out repo/evidence/theme-factory/overlay
repo/evidence/theme-factory/overlay/verify.sh etar-fdroid.apk
bash repo/scripts/theme-factory.sh apply --profile repo/evidence/theme-factory/app-profile.json \
     --out repo/evidence/theme-factory/overlay --serial <serial>
```
