# ztMetro Android UX — acceptance

Snapshot 2026-11-16 16:50 MSK · device `4f2b50b2`, Xiaomi Redmi Note 5A (`ugglite`,
MSM8917, 2 GB, 720×1280) · LineageOS-for-microG 23.2, Android 16 / API 36 · live density
260 dpi = 1.625 px/dp ([verify report](evidence/verify/20261116-162636/report.md#L26-L35)).

States: **device-verified** = measured in a captured run on this phone; **host-verified** =
built + statically checked, never run; **partial** = some checks/surfaces pass; **boundary**
= owned by the platform, not deliverable by an APK — the fix option is named in §5.

This acceptance pass re-read the evidence; it did not run adb. Every number below is
from the dated run cited next to it.

## 1. Verdict

The phone is no longer a stock LineageOS shell. Start is HOME; the app list, Action
Centre, lock overlay, IME and Settings chrome are ztMetro; 13 ztMetro/ztos RROs are
enabled at once ([report](evidence/verify/20261116-162636/report.md#L35)). Android still
owns the shade, Quick Settings tiles, recents, volume/power panels and the app→HOME
animation — platform boundaries, not missing APK work (§5). The latest **full** run is
**21 PASS / 5 FAIL / 2 SKIP of 28**: four failures come from one capture miss (the
Action Centre never reached the foreground) and one is a real teal Wi-Fi switch. The same suite
measured **25/2/1** three minutes earlier, **26/2/0** at 15:29 and **26/1/1** at 15:18
([16:23](evidence/verify/20261116-162345/report.json),
[15:29](evidence/verify/20261116-152908/report.md#L9-L12),
[15:18](evidence/verify/20261116-151804/report.md#L9-L13)); 26 is the best observed
state, not a guarantee. The README's 26/1/1 ([README](README.md#L21-L24)) matches 15:18
but is superseded; docs/18's "3 yes / 1 partial / 6 no"
([docs/18](docs/18-coverage-and-build.md#L36)) is a 12:15 snapshot. This document
supersedes both.

## 2. Surfaces

| Surface | State | Evidence | Notes |
|---|---|---|---|
| Start (HOME) | device-verified | [checks](evidence/verify/20261116-152908/report.md#L54-L58), [frame](evidence/verify/20261116-152908/raw/start.png) | 5/5: 12 tiles, gutter 6.77 dp, margins 14.15/12.92 dp, 6 palette fills, 11 white-glyph tiles, 12/12 square corners. Deployed launcher is versionName 4.0 ([manifest](evidence/conductor/20261116-155813/ztmetro-manifest.json)). |
| App list | device-verified | [checks](evidence/verify/20261116-162636/report.md#L73-L77), [frame](evidence/verify/20261116-162636/raw/app-list.png) | 5/5: icon x=80.0 dp, 44.92 dp icon, label x=140.31 dp, 9 rows, contrast 17.62:1; `ZTMETRO` header + A–Z rail. |
| Action Centre | device-verified (last good 16:23) | [checks](evidence/verify/20261116-152908/report.md#L112-L116), [frame](evidence/verify/20261116-152908/raw/action-centre.png), [16:23 focus](evidence/verify/20261116-162345/report.json) | 5/5: our activity focused, 8 tiles 4×2 at 105.8×70.2 dp (ratio 1.509), 4 cobalt fills. At 16:26 the panel did not open; the frame is Calendar ([report](evidence/verify/20261116-162636/report.md#L115-L119)), and `ac.status_row` passing there measures Calendar's toolbar, not the panel. |
| Status strip | partial | [Start frame](evidence/verify/20261116-152908/raw/start.png), [strip evidence](evidence/strip-rework/README.md#L4-L12) | 28 dp row visible on Start (glyphs left, clock right, stock green battery gone), but no `ux-verify` check measures it; docs/18's "no device run" ([row 10](docs/18-coverage-and-build.md#L34)) is stale. |
| Lock overlay | partial | [2/2 checks](evidence/verify/20261116-162636/report.md#L131-L132), [frame](evidence/verify/20261116-162636/raw/lock.png) | Clock 109 px / 67.1 dp, one block, no status text; count row + `↑ СВАЙП ВВЕРХ` visible. Unlock/PIN flow is static-only ([README](evidence/lock-overlay/README.md#L4-L6)). README says "pure black" ([L117-L127](evidence/lock-overlay/README.md#L117-L127)) but source draws `#002878 → black` ([LockOverlayView.java#L82-L84](actioncentre/app/src/main/java/org/ztmetro/actioncentre/LockOverlayView.java#L82-L84)); the frame matches the source. |
| IME | device-verified | [frame](evidence/ux-compare/current/u1-ime.png), [evidence](evidence/ime/README.md#L1-L20) | ztMetro keyboard rendered and typed a URL at 13:42 (RU 11/11/9, 56 dp keys, black). No automated check; predictions are out of scope ([README](evidence/ime/README.md#L147)). |
| Settings home | device-verified | [checks](evidence/verify/20261116-162636/report.md#L89-L93), [frame](evidence/verify/20261116-162636/raw/settings-home.png) | 5/5: 95 % black, no cobalt banner, 52 px title band (60.4 sp implied), white rows 87 %, one title. Flat rows replaced the older cobalt dashboard tiles. |
| Settings sub-screens | partial | [checks](evidence/verify/20261116-162636/report.md#L105-L106), [frame](evidence/verify/20261116-162636/raw/settings-wifi.png) | Header/rows/square cards verified (0 radius violations). Wi-Fi switch is **teal** at 16:27 (FAIL) but was **cobalt** at 15:30 ([PASS](evidence/verify/20261116-152908/report.md#L102)); the check's `on=no` is wrong — the frame shows it ON. |
| Volume / power / toast | boundary | [volume SKIP](evidence/verify/20261116-162636/report.md#L141-L142), [stock frame](evidence/ux-compare/current/y0-vol.png), [RRO report](evidence/volume-power-toast/REPORT.md#L26-L32) | 13:55 frame is the stock rounded right-side panel. RROs exist (black/0-corner outer shell, 11 entries) but were never visually accepted; the slider is Compose. |
| Recents | boundary | [evidence](evidence/transitions-recents/README.md#L95-L99), [capability table](evidence/transitions-recents/README.md#L153-L158) | Stock `com.android.launcher3/.quickstep.RecentsActivity`; a normal app cannot list-and-remove tasks or replace the gesture/3-button UI. |
| App → HOME transition | boundary | [docs/26](docs/26-transition-track.md#L105-L107), [options](docs/26-transition-track.md#L341-L346) | Played by `DefaultTransitionHandler` (no wallpaper) or Quickstep's spring; Start as HOME leaves it on the Default path. Current motion is the stock slide (500 ms task pair or 225 ms wallpaper pair), never a turnstile. |
| Theme factory | partial | [factory](evidence/conductor/20261116-155813/report.md#L44-L47) | 4/4 RROs built + enabled + target force-stopped; 73.8–77.1 % of the colour resources each app actually uses. No screenshot acceptance. |
| Font (Open Sans 1.1.0) | host-verified | [row 5](docs/18-coverage-and-build.md#L29), [hash](docs/18-coverage-and-build.md#L363) | Module built/signed (`33d64346…`) but no `font_fallback.xml` readback and no before/after pair, so the font cannot be attributed on screen. |
| Overlays (13 enabled) | device-verified | [device block](evidence/verify/20261116-162636/report.md#L35), [QS limit](docs/18-coverage-and-build.md#L498-L510) | Settings effects visible; QS square tiles not; the framework overlay has no isolating on-screen proof. |
| Boot self-heal | host-verified | [docs/27](docs/27-boot-apply.md#L3-L6), [23/23](evidence/boot-apply/report.md#L38) | Applies overlays, a11y, listeners, HOME, IME, appops after `sys.boot_completed`, idempotent when unchanged. No device boot log captured. |

## 3. The owner's scenario: Start → app → shade → panel → close → Settings

Commands are those of the recorded runs ([docs/24](docs/24-auto-verify.md#L32-L40));
the transport was `ssh hermes-ubuntu '~/android-sdk/platform-tools/adb …'`
([report](evidence/verify/20261116-162636/report.md#L24)). `adb` is shorthand.

```sh
adb root && adb shell id                    # uid=0, u:r:su:s0 (conductor requirement)
adb shell input keyevent KEYCODE_HOME        # 1 Start
adb shell am start -a android.intent.action.MAIN -c android.intent.category.APP_CALENDAR
adb shell input swipe 360 6 360 400 250      # 3 shade gesture from the top edge
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb shell input keyevent KEYCODE_BACK        # 5 close
adb shell am start -a android.settings.SETTINGS   # 6
```

1. **Start.** Focus `org.ztmetro.start/.StartActivity`, 5/5 checks ([frame](evidence/verify/20261116-152908/raw/start.png)).
2. **Third-party app.** Focus Calendar; the 16:26 frame proves it was foreground ([frame](evidence/verify/20261116-162636/raw/action-centre.png)) and that the next step failed in that run.
3. **Shade gesture.** The swipe starts inside the top 24–32 dp accessibility strip, which starts the panel on `DOWN` ([design](evidence/strip-rework/README.md#L20-L40)). At 15:29 and 16:23 focus became `org.ztmetro.actioncentre/.ActionCentreActivity` ([15:29](evidence/verify/20261116-152908/report.md#L112), [16:23](evidence/verify/20261116-162345/report.json)).
4. **Panel.** 8 tiles in 4×2, each 105.8×70.2 dp, ratio 1.509, four cobalt fills, own status strip ([checks](evidence/verify/20261116-152908/report.md#L113-L116), [frame](evidence/verify/20261116-152908/raw/action-centre.png)).
5. **Close.** `adb shell input keyevent KEYCODE_BACK` (or handle/swipe-up): documented 220 ms slide-down ([report](evidence/actioncentre/REPORT.md#L122-L144)). No post-close frame exists; this step is code + log-line evidence, not a measured frame.
6. **Settings.** `am start -a android.settings.SETTINGS`, then `…WIFI_SETTINGS`. Black home, `SETTINGS` caps, 58 sp lowercase title, flat rows ([frame](evidence/verify/20261116-162636/raw/settings-home.png), [5/5](evidence/verify/20261116-162636/report.md#L89-L93)); Wi-Fi sub-screen with header/square cards ([frame](evidence/verify/20261116-162636/raw/settings-wifi.png)).

The 16:26 run repeated 2–6 and the panel did not open; the focus field and the Calendar
frame say so ([report](evidence/verify/20261116-162636/report.md#L47)) — it is a capture
miss, not a panel regression.

## 4. Numbers

**`ux-verify.py`** (full = 28 checks, quick = 17):

| Run | Mode | PASS | FAIL | SKIP | Failures |
|---|---|---:|---:|---:|---|
| 16:26:36 ([report](evidence/verify/20261116-162636/report.md#L9-L12)) | full | 21 | 5 | 2 | AC not foreground (4); Wi-Fi switch teal; volume absent (2 SKIP) |
| 16:23:45 ([json](evidence/verify/20261116-162345/report.json)) | full | 25 | 2 | 1 | Wi-Fi switch teal; volume absent |
| 15:29:08 ([report](evidence/verify/20261116-152908/report.md#L9-L12)) | full | 26 | 2 | 0 | Settings title checks — header was scrolled off |
| 15:18:04 ([report](evidence/verify/20261116-151804/report.md#L9-L13)) | full | 26 | 1 | 1 | volume panel absent |
| 15:59:56 ([conductor](evidence/conductor/20261116-155813/report.md#L35)) | quick | 13 | 4 | 0 | all `settings.*` — LSPosed header disabled after a cert-change reinstall |

**PSS**, same boot as the conductor verify (`dumpsys meminfo`, TOTAL PSS KB; shared pages
are not additive): SystemUI **150 043**, Settings **41 792**, Action Centre **29 210**,
Start **25 745** ([table](evidence/conductor/20261116-155813/report.md#L53-L58),
[raw](evidence/conductor/20261116-155813/perf/meminfo.tsv)).

**Settings jank** (`dumpsys gfxinfo com.android.settings`): 8 janky frames **12.31 %**
(legacy 28 / 43.08 %); p50 25 ms, p90 38 ms, p95 61 ms, p99 450 ms
([report](evidence/conductor/20261116-155813/report.md#L63-L68)). PSI `some avg60=1.03`,
`full avg60=0.23` ([report](evidence/conductor/20261116-155813/report.md#L78-L79)).
These are a baseline, not a target; docs/21 has no device numbers
([docs/21](docs/21-performance.md#L7-L9)).

**Factory coverage** of colour resources each app actually uses: deskclock **75.0 %**,
contacts **77.1 %**, dialer **73.8 %**, aperture **74.9 %**, all `ok`
([report](evidence/conductor/20261116-155813/report.md#L44-L47)).

**Artifact hashes.** Left = what the 15:58 conductor deployed (the only recorded install);
right = bytes now in the tree, built after that run and **not** proven on the phone. Same
source can hash differently because the conductor signs with a stable keystore
([docs/25](docs/25-conductor.md#L145-L162)); different sizes mean different code.

| Artifact | Deployed 15:58 (bytes / sha256) | In-tree now (bytes / sha256) |
|---|---|---|
| overlays module | 78 142 / `ea89a8941457363e7d0342046a7f96c730c6668b39db498325e040e1959fa5e5` ([report](evidence/conductor/20261116-155813/report.md#L26)) | 82 989 / `2728e18d01dd1cd0e7df62e8e93b2674a7fcb5832933c6bf78a1e5f557f28ea5` ([zip](artifacts/ztmetro-overlays-1.0.0.zip)) |
| launcher | 74 155 / `00247799ab4a19a5c57f63129c3d4943ea015c9d030ec57a2e0ffbe5f3ce1443` ([report](evidence/conductor/20261116-155813/report.md#L27)) | 57 622 / `5f4b08b113ac8823938323815ee2292641687a5f4598a228aa651f0d1911ea3c` ([APK](evidence/launcher-v3/ztmetro-start.apk)) |
| Action Centre | 345 804 / `91db93d7c074438112ed18f05987ab59a0e2ddbb147e5f56b373c7bbb5338010` ([report](evidence/conductor/20261116-155813/report.md#L28)) | 349 900 / `01bd51b89c7a181a4ae3f179622496c77968938dc7293500cf06e3d7e8a8c147` ([strip-rework](evidence/strip-rework/actioncentre/ztmetro-actioncentre.apk)) |
| IME | 20 817 / `f22250442df52fd14a857a80dce88a513bec9486cab056150e08c4463ff58df4` ([report](evidence/conductor/20261116-155813/report.md#L29)) | 20 817 / `a7c2281ff6df22bb48c37df28c5b8b817cc64e49da393aeb41aba5358bd6745d` ([APK](evidence/ime/ztmetro-ime.apk)) |
| Settings header | 41 968 / `adcab3556e9b908654c8489a13f55242039af2335797aba1f7da69629fea6d6f` ([report](evidence/conductor/20261116-155813/report.md#L30)) | 41 968 / `ca04b6b52ebc8dba514dc660f5dbcf624cf92db6ebdd675ea5ea3cb4c73b8a36` ([settings-rows](evidence/settings-rows/ztmetro-settings-header.apk)) |
| Font module | not installed by the conductor | 700 774 / `33d643465ea5dc7c76f838bb598e9104b12838944a02a25132867f6cacab92fa` ([zip](artifacts/ztmetro-font-1.1.0.zip), [docs/18](docs/18-coverage-and-build.md#L363)) |

## 5. Known gaps, exact reason, and the option that fixes each

| Gap | Exact reason | Fix option |
|---|---|---|
| Stock shade flashes on the fallback path | The 24–32 dp strip owns only gestures that start at the very top; a swipe below it, adb or the keyguard lets SystemUI draw the shade first. The APK starts the panel under it and sends at most one BACK at +120 ms, so the stock ~260 ms close is still visible ([§1.1.3](evidence/actioncentre/REPORT.md#L154-L180)). | SystemUI hook. The LSPosed module is built (16 851 B, scope `com.android.systemui`) but **not installed** ([shade-hook](evidence/shade-hook/REPORT.md#L1-L20)); it collapses the shade before drawing and starts our panel. A framework RRO lowering the top-swipe thresholds is the alternative ([strip-rework](evidence/strip-rework/README.md#L145-L152)). |
| Recents are stock | `config_recentsComponentName` resolves to `com.android.launcher3/.quickstep.RecentsActivity`; `REMOVE_TASKS`/`MANAGE_ACTIVITY_TASKS` are `signature\|recents` and PMS grants them only to a factory package. An RRO can redirect the component so a data app *lists* tasks, but it cannot remove them or replace the gesture UI ([table](evidence/transitions-recents/README.md#L153-L158)). | Install the recents app as a **priv-app in the system image** and wire it into **WMShell/QuickStep**. The RRO prototype exists but was never installed ([README](evidence/transitions-recents/README.md#L160-L180)). |
| App → HOME is the stock slide | The transition is a Shell transition owned by `DefaultTransitionHandler` when Start is HOME; an app cannot animate the outgoing task, the scales only change duration, and a true 3D turnstile needs a new compositor path ([docs/26](docs/26-transition-track.md#L355-L370)). | (a) static RRO over `task_open_*`/`wallpaper_open_*` — built, 13 075 B, not installed ([docs/26](docs/26-transition-track.md#L341)); (b) LSPosed hook into `Remote/DefaultTransitionHandler` — built as a logger, not installed, SystemUI crash risk; (c) ROM patch of `QuickstepTransitionManager` or a WMShell handler ([docs/26](docs/26-transition-track.md#L291-L318)). |
| QS tiles are not square | The 2025 shade is Compose: tiles are 72 dp tall with 24/36 dp corners computed in `CommonTileDefaults`; `qs_corner_radius`, `qs_tile_height` and `qs_tile_background_shape` never reach them. Only the 4 dp gaps applied (measured 4.31 dp) ([docs/18](docs/18-coverage-and-build.md#L498-L510)). | **Fork SystemUI** in a ROM build — the only clean route; a SystemUI hook on the tile defaults is the root-only shortcut. Not an RRO deliverable. |
| Volume panel is still stock | The shipped dialog is a View shell around a **Compose** slider; an RRO can blacken the outer panel but not the slider/labels, and it has not been visually accepted ([RRO](evidence/volume-power-toast/REPORT.md#L26-L32)). | SystemUI fork/hook for the Compose slider, or accept the partial outer-shell theming. Same class of limit as QS. |
| Wi-Fi switch accent is unstable | The cobalt tint comes from the Settings RRO and/or the header module's `MetroTinter`; the header was re-enabled by 16:23 but the switch is teal again ([16:27 frame](evidence/verify/20261116-162636/raw/settings-wifi.png)). The check's `on=no` also cannot distinguish "off" from "wrong colour". | Rebuild the Settings RRO tint block, keep the header module enabled, and fix the ON/OFF detector; the exact resource chain was never settled ([docs/18 §6.5](docs/18-coverage-and-build.md#L488-L496)). |

## 6. Reproduce from a clean LineageOS install

One-time platform work is manual (recovery + GUI); everything after is one command.

```sh
# 1. Fetch + verify the ROM (90.2 s / 13.4 MB/s measured).
mkdir -p ~/lineage-flash && cd ~/lineage-flash
curl -O https://download.lineage.microg.org/Mi8917/lineage-23.2-20260812-microG-Mi8917{,-boot.img,-recovery.img,-super_empty.img}{,.sha256sum}
sha256sum -c *.sha256sum                      # all four OK

# 2. Gate + flash recovery + sideload.  Never flash super_empty.img (retrofit super).
fastboot getvar product                       # MUST print: ugglite
fastboot flash recovery lineage-23.2-20260812-microG-Mi8917-recovery.img
fastboot boot          lineage-23.2-20260812-microG-Mi8917-recovery.img
# recovery UI: Factory reset -> Format data, then Apply update -> Apply from ADB
adb -d sideload lineage-23.2-20260812-microG-Mi8917.zip
# recovery UI: Reboot system now
adb shell getprop ro.build.version.sdk         # 36
adb shell getprop ro.lineage.version           # 23.2

# 3. Magisk: patch the ROM boot.img in the app, flash it, enable Zygisk.
fastboot flash boot magisk_patched-*.img && fastboot reboot
adb shell su -c 'magisk -v'

# 4. LSPosed/Vector as a Magisk module, then reboot.
adb push Vector-*.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/Vector-*.zip' && adb reboot

# 5. Build, publish, apply, verify, factory — the whole stack (421 s recorded).
cd <workspace>/repo
adb root && adb shell id                       # uid=0, u:r:su:s0 required
./scripts/ztmetro-conductor.sh --with-factory

# 6. GUI-only, once: LSPosed Manager -> enable "ztMetro Settings header"
#    (scope: com.android.settings ONLY) and "ztMetro Keyguard"
#    (scope: com.android.systemui); restart the scoped processes.

# 7. Full 28-check verification (the conductor runs only --quick / 17).
python3 scripts/ux-verify.py                   # exit 0 iff no FAIL
# report: evidence/verify/<timestamp>/report.md + raw/ + annotated/

# 8. Optional font module (not installed by the conductor), then read it back.
adb push artifacts/ztmetro-font-1.1.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-font-1.1.0.zip' && adb reboot
adb shell su -c 'grep -c OpenSans- /system/etc/font_fallback.xml'    # expect 20

# 9. Boot self-heal proof after the first boot (docs/27 §5).
adb shell su -c 'tail -n 40 /data/local/tmp/ztmetro-apply.log'
# first boot: "apply: overlays=10 enabled ... accessibility=2/2"
# later boots: "apply: done changes=0 warnings=0"
```

Stages/logs: [docs/25](docs/25-conductor.md#L242-L266). Flash gates: [PLAN](evidence/device-flash/PLAN.md).
Rollback: [docs/15](docs/15-boot-recovery.md). `--skip-build` is gated by `--allow-deploy`
because it may deploy another commit's artifacts ([docs/25](docs/25-conductor.md#L77-L79)).

## 7. What was NOT done (blunt)

- **No recents replacement.** Stock Quickstep remains; the RRO prototype was never installed, and task removal/replacement needs priv-app + WMShell ([README](evidence/transitions-recents/README.md#L95-L99)).
- **No app → HOME change on the phone.** The RRO and LSPosed logger were built and never installed; the return is the stock Default-handler slide ([docs/26](docs/26-transition-track.md#L379-L382)).
- **No shade replacement.** The shade hook is built but not installed; swipes below the strip still show the stock shade ([shade-hook](evidence/shade-hook/REPORT.md#L1-L6)).
- **No square QS.** The square pass is not delivered; Compose owns the tiles ([docs/18](docs/18-coverage-and-build.md#L498-L510)).
- **No volume/power/toast acceptance.** RROs are built, but there is no power-menu or toast frame, and the volume frame is stock ([y0-vol.png](evidence/ux-compare/current/y0-vol.png)).
- **No font readback.** No `font_fallback.xml` check and no isolated before/after pair; the font may be installed, but this repo cannot prove it ([docs/18](docs/18-coverage-and-build.md#L29)).
- **No boot self-heal device log.** `/data/local/tmp/ztmetro-apply.log` was never read back; only the host harness (23/23) is evidence ([docs/27](docs/27-boot-apply.md#L190-L209)).
- **No clean-install transcript.** The recipe is documented, but no `fastboot`/`sideload` log exists in `evidence/` ([docs/18 §6.1-6.2](docs/18-coverage-and-build.md#L451-L467)).
- **No deployed-artifact consistency.** The tree was rebuilt after 15:58 (overlays 16:44, AC 349 900 B); those bytes were not installed or verified (§4). The 40 %-window strip rework is not the build that produced the working panel.
- **No long-run data.** One PSS/jank/PSI sample on one boot; no lmkd-kill, battery, thermal or multi-hour test. PSS is per-process TOTAL, not additive.
- **No git commit/push.** This pass only added `repo/ACCEPTANCE.md`; nothing was published.
