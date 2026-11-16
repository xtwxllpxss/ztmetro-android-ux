# Perf pass 2 — measured pass, before/after protocol

Target: Redmi Note 5A (`ugglite`, 4×A53, Adreno 308, 2 GB, 720×1280, 60 Hz),
LineageOS-for-microG 23.2. Written 2026-11-16 as the pass-2 counterpart of
[`docs/21-performance.md`](../../docs/21-performance.md).

**Status: code + static proof + APKs are in this tree; the numbers are not.**
There is no adb in this session, so nothing below the "measured" line has been
run on the phone. The expected effects are engineering estimates `[E]`; the
protocol in [`protocol.sh`](protocol.sh) is what turns them into numbers.

Baseline the pass was designed against (owner's report, not re-measured here):
SystemUI 118–150 MB, Settings 42–80 MB, Action Centre 25–29 MB, Start 26–32 MB,
Magisk 56 MB, LSPosed 55 MB; Settings gfxinfo 12–15 % janky, p50 21–25 ms.

---

## 1. Artifacts (built on `hermes-ubuntu`, `~/android-sdk`, `~/android-jdk`)

| APK | before (baseline build) | after (pass 2) |
|---|---|---|
| Action Centre `org.ztmetro.actioncentre` | 349 900 B · `dbc360072b27c39a832a222f9c62d9d155d6a8ea5895af503204e17df3e595af` | 353 996 B · `a2306ba97886267aa1ba64f0f7c7b8a01d0430d3ff54231d742b1b1274778525` |
| Start `org.ztmetro.start` | 74 155 B · `db656516d87172a2a9ab5a05d52d50984fe27715eab386241a2a539355ce9af3` | 78 251 B · `c32062fa9394dfd789fef0f22d79a11c5afc0f32f458c914c5df3479ca60bcc1` |
| Settings header module | 41 968 B · `66fb9eaa017108377b4721516f50d54f2ae0d4ef8bdefd07fb983f9a04afea63` | 41 968 B · `857f48b24661aadb57cb0a2266d26493f15c9d47219ba4eb55cddbd5c08836ee` |

Copies: `baseline/<name>/` and `pass2/<name>/` next to this file; per-APK static
verification logs are in the same directories (`verify.txt`, `aapt2-*`,
`dexdump-classes.txt`, `logs/build-verify.txt`). The header module APK hash is not reproducible
byte-for-byte: its build script does not pin the zip entry timestamp (the Action
Centre and launcher scripts do), so two identical builds differ in the signature
block only.

Install the "before" set, run the protocol, then install the "after" set:

```sh
adb install -r repo/evidence/perf-pass2/baseline/ztmetro-actioncentre.apk
adb install -r repo/evidence/perf-pass2/baseline/ztmetro-start.apk
adb install -r repo/evidence/perf-pass2/baseline/ztmetro-settings-header.apk
# ... protocol run before ...
adb install -r repo/evidence/perf-pass2/pass2/ztmetro-actioncentre.apk
adb install -r repo/evidence/perf-pass2/pass2/ztmetro-start.apk
adb install -r repo/evidence/perf-pass2/pass2/ztmetro-settings-header.apk
adb shell am force-stop com.android.settings   # the module reloads with the process
```

Disclosure: the Action Centre APK also carries the **already uncommitted**
`LockOverlayView` auth-panel fallback edit that was in the working tree when the
pass-2 build ran. It is not part of this pass; it is why the APK is 4 KB larger
than the arithmetic of these changes alone suggests.

---

## 2. What changed

### 2.1 Action Centre — the two accessibility services stay separate

They were **not merged**, and the reason is one sentence: both services already
run in the same `org.ztmetro.actioncentre` process, so merging saves no process
and no PSS — it only removes one binder registration — while it would break the
boot re-grant (`repo/scripts/ztmetro-apply-settings.sh` enables the two
components by name), the build's static verification, and the strip's z-order /
touch pass-through, none of which can be re-verified without the phone.

Instead the per-minute redraws and the standing binder calls were cut:

| | before | after |
|---|---|---|
| `StatusStripService` receivers | one receiver for battery + Wi-Fi + clock + airplane + screen + user-present, registered for the whole life of the service | **two**: screen/user-present always; battery/clock/Wi-Fi/airplane only while the screen is on and the strip is drawn |
| network + telephony callbacks | registered at service connect, live while the screen is off | registered only with the strip, released on screen off / panel / keyguard |
| `onCapabilitiesChanged` | 1 read of `getActiveNetwork` + `getNetworkCapabilities` + `isWifiEnabled` + `getSimState` + `getSignalStrength` per callback, then a redraw | burst coalesced over **400 ms**, one active-network read (2 binder calls) shared by Wi-Fi and cellular, redraw only if a glyph actually changes |
| unchanged values | each setter posted a frame unconditionally | `StatusStripOverlayView` setters reject an identical state; the service caches `battery/charging/wifi/sim/signal/cellular/airplane` and does not even call the view |
| clock | `ACTION_TIME_TICK` (strip) and a **15 s** `Handler` tick in the panel that also invalidated unconditionally | strip: `ACTION_TIME_TICK`, screen-on only; panel: **minute-aligned** tick, invalidates only when a drawn value changed, stops when the window is not visible |
| panel `onDraw` | 3–4 `SimpleDateFormat.format` allocations + 2 `measureText` per frame | clock/date strings and the date width cached, measured once per minute |
| keyguard safety net (`ShadeBridgeService`) | `isKeyguardLocked()` on accessibility events, throttled to 500 ms, screen off included | 5 s throttle, skipped while the screen is off (screen state from the existing receiver, no getter) |

`LockOverlayController` already had the correct shape (minute-aligned clock,
poll stopped on `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON` restarts it), so it was
left alone.

Effect `[E]`: strip work goes from ~4 broadcasts/min + a capability read per
event to zero while the screen is off; while the screen is on, unchanged battery
/ Wi-Fi / signal state produces no frame at all. On an A53 that is CPU/thermal
headroom and a few wakeups per minute, not a PSS change; the panel's per-frame
allocations are the part most likely to show in `gfxinfo`.

### 2.2 Start — the listener is capped, the tile field is not rebuilt

| | before | after |
|---|---|---|
| `onResume` | `refreshData()`: `PackageManager` query + `seedIfNeeded` + `loadTiles` + `grid.setTiles()` (removeAllViews + new `MetroTileView` per tile + `buildArt()` per tile) | `refreshIfNeeded()`: reloads only when a package broadcast marked the app list dirty; otherwise nothing |
| new surface in the tile/app list | rebuilt on every resume | `tileSignature()` / `appSignature()` compare the ordered layout (component, size, appearance, label); `setTiles`/`setApps` only run on a change |
| notification listener | stays bound for the life of the process (the system keeps the launcher at perceptible importance) | released by `ListenerWindow` **2 min after `onPause`** via `requestUnbind()`; `requestRebind()` on `onResume`, then `onListenerConnected()` rebuilds the live map from the active notifications |

The 2-minute window is what keeps a normal app switch, the Action Centre panel or
a dialog from ever releasing the binding; only a real absence does.

Evidence on the device: `adb logcat -d -s StartPerf` prints
`notification listener released: Start hidden for 120 s` and
`notification listener rebound on resume`.

Effect `[E]`: the launcher's standing PSS in the background is the listener
binding (the process becomes an ordinary cached process, killable under
pressure); a return to HOME no longer pays the package query and 15–30 icon
renders. Expected: one fewer visible hitch on HOME, 5–15 MB lower retained
footprint once the listener is released, no first-frame change.

### 2.3 Settings header module — one traversal, one post-layout row pass, no layout storm

`ScreenPass` was already one visitor, but it still did layout work repeatedly:

| | before | after |
|---|---|---|
| post-layout row passes | two `postDelayed` passes per screen open (250 ms + 700 ms), never cancelled | **one** one-shot `ViewTreeObserver.OnGlobalLayoutListener`, removed when it fires |
| rows attached later (scroll, slow fragment) | caught by the 700 ms pass only | per-recycler `OnHierarchyChangeListener` (framework API) posts `styleRow()` for each added child — outside the layout pass, so it cannot be a `requestLayout()` inside layout |
| recycler list | `ArrayList<View>` — retained every RecyclerView of every screen opened in the Settings process | `ArrayList<WeakReference<View>>` |
| row layout params (card margins, icon-cell inset, chevron slot) | re-walked and re-applied on every pass; a re-applied stock inset meant `setLayoutParams()` again | cached per view under the `metro_style` tag id (`res/values/ids.xml`), with an epoch that changes when the page margin/row padding change; a styled row is skipped |
| `flattenCard` | `getClass().getMethod(...)` per card per pass | `Method` pair cached per class; card flattened once per view (tag bit) |

`ScreenStyler` still runs pass 1 (attach) and pass 2 (post-layout) and still
coalesces `setTitle`/`onResume` kicks, so the count is 2 walks + 1 row pass per
screen instead of 2 walks + 2 row passes + repeated layout writes.

Effect `[E]`: fewer main-thread layout traversals in the first 1.6 s of a
Settings screen; the `setLayoutParams()` writes that used to repeat per pass are
gone. No PSS change is claimed for the module itself.

Evidence on the device: `adb logcat -d -s ztmetro-header` shows exactly
`pass 1/2 attach`, `pass 2/2 post-layout` and one `row pass:` line per screen
open, with the `views/applies/ms` counters (before: two `row pass:` lines).

---

## 3. Protocol

`protocol.sh` is the executable form of §5 of `docs/21-performance.md`; it does
3 runs per label and reports medians, never the best run.

```sh
cd repo/evidence/perf-pass2
ADB_PREFIX="ssh hermes-ubuntu /home/denis/android-sdk/platform-tools/adb" ./protocol.sh run before
# install the pass-2 APKs (section 1), then 30 min of normal use, same workload
ADB_PREFIX="…" ./protocol.sh run after
./protocol.sh compare before after        # PASS/FAIL against the thresholds
```

Each run records, into `<label>/run<N>/`:

- idle `/proc/pressure/memory`, thermal zone, `dumpsys meminfo -a` head;
- Settings: `gfxinfo reset`, force-stop, cold `am start -W -n com.android.settings/.Settings`
  (`TotalTime`), 3× `input swipe 360 1000 360 300 250`, then `gfxinfo` +
  `gfxinfo framestats`;
- HOME: force-stop, `KEYCODE_HOME`, one horizontal swipe, `gfxinfo`;
- cold starts of Action Centre and Start (`am start -W`);
- PSS for SystemUI, Settings, Action Centre, Start, SurfaceFlinger;
- PSI memory/cpu, `zramctl`, `/proc/swaps`, `dumpsys activity oom`,
  `logcat -d -s StartPerf -s ztmetro-header -s StatusStrip -s ShadeBridge`.

Metric definitions: PSS is the `TOTAL` row of `dumpsys meminfo <pkg>` (kB);
jank/percentiles are the aggregate block of `dumpsys gfxinfo <pkg>`;
`zt_sum` = Start + Action Centre + Settings PSS (the three surfaces this pass
touches — SystemUI, Magisk and LSPosed are reported but not gated, because no
code in this pass runs in them).

---

## 4. Acceptance thresholds (`compare` enforces these)

| Metric (after, median of 3) | Threshold | Why |
|---|---|---|
| Settings 90th percentile frame | **< 40 ms** | the owner's complaint; p90 catches the visible stalls |
| Settings p95 | < 33 ms | two vsyncs |
| Settings janky frames | < 8 % (from 12–15 %) | ~30–40 % relative |
| Start janky frames | < 5 % | HOME is the most-seen surface |
| `zt_sum` (Start + Action Centre + Settings) | **< 90 MB** | the brief; baseline is ~93–141 MB |
| Start + Action Centre PSS | < 61 MB (62 464 kB) | our own two processes; baseline 51–61 MB, must not grow |
| PSI memory `full avg60` under load | < 5 | memory-pressure gate |
| Settings cold `TotalTime` | < 1100 ms | 700–1200 ms is the healthy band for this class |
| Start cold `TotalTime` | < 800 ms | ditto |
| Action Centre cold `TotalTime` | < 500 ms | ditto |

A change is only accepted if the metric it targets moves **and** no gated metric
regresses. One change per A/B: do not install two of the three APKs and call it a
pass result.

---

## 5. What still needs the phone

1. **PSS**: nothing here was measured. In particular the 5–15 MB listener
   release is a hypothesis until `dumpsys meminfo org.ztmetro.start` is compared
   2 min after leaving HOME.
2. **Jank**: Settings p90/p95 and the `ztmetro-header` pass timings need the real
   view trees (the pass log prints view counts and ms).
3. **Strip behaviour**: the status strip must still hide under the keyguard, still
   pass touches through, and still repaint on a real battery/Wi-Fi/signal change.
   The merge was rejected partly because this cannot be checked without the phone.
4. **Listener cap**: `StartPerf` lines + `dumpsys activity oom` must show the
   launcher at cached importance after 2 min hidden and a live tile face within
   one reconnect after resume.
5. **No regression**: animations/motion, the header layout and the row typography
   are unchanged by this pass, but the pass still has to be looked at once.

## 6. Rollback

Reinstall the `baseline/` APKs (same commands as section 1 with the other
directory); for the module, force-stop Settings afterwards. No prop, no Magisk
module and no overlay is touched by this pass, so a reboot is not required.
