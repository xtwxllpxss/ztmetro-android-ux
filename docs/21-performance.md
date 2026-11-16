# 21 — Performance on a 2 GB Android 16 phone: the ztmetro shell optimisation plan

Target: Xiaomi Redmi Note 5A Lite (`ugglite`, MSM8917, 4×Cortex-A53, Adreno 308,
**2 GB RAM**, 16 GB eMMC, 720×1280, 60 Hz) running LineageOS-for-microG **23.2**
(Android 16, API 36) with the ztmetro stack from [18-coverage-and-build.md](18-coverage-and-build.md).

Snapshot: 2026-11-16. **This document contains no device measurements.** Numbers marked
`[E]` are engineering estimates from the code in this repo and the cited AOSP sources; the
lead must replace them with the protocol in §5. `[F]` = read in the named source at the
named commit; `[?]` = open question that only the device can answer.

The one-sentence verdict: **on 2 GB the largest wins are in code we wrote (the Settings
view-tree walks and the launcher's first frame), not in build.prop folklore.** The useful
AOSP low-RAM knobs are real but secondary; several popular "tweaks" are placebos or
actively harmful on Android 16. Ranked plan in §4, measurement protocol in §5, do-not-do
list in §6.

---

## 0. The short list (ranked by expected effect / effort)

| # | Change | Effort | Expected on 2 GB / 720p `[E]` | Risk | Primary metric |
|---|---|---|---|---|---|
| **P1** | **Collapse the Settings LSPosed styling into one coalesced pass; cache ids/classes/reflection** | 0.5–1 d | up to **32 full tree walks → 2** per screen open; 200–600 ms less main-thread CPU in the first 1.6 s; 1–3 fewer dropped frames on Settings open; **no PSS change** | low (cosmetic only; guarded) | Settings `am start -W`, `dumpsys gfxinfo com.android.settings`, `logcat -s ztmetro-header` |
| **P2** | **Make Start's first frame cheap: build tile/app icons off the main thread, placeholder first, cap cache** | 0.5–1 d | 1–3 fewer dropped frames on HOME; **100–250 ms** faster first draw; 5–15 MB lower peak PSS | low (visual placeholders for ~1 frame) | `am start -W` HOME, `dumpsys gfxinfo org.ztmetro.start`, `dumpsys meminfo` |
| **P3** | **Memory config only if baseline says so: `ro.config.low_ram=true` and zram ≈ 50–75 % / lz4 / swappiness 100** | 0.5 d + reboot | fewer lmk kills (10+/day → 0–2), fewer app-switch reloads; idle PSS **−20…60 MB** if low-RAM features trim; no guaranteed FPS gain | **medium** — feature trims, thrash if zram too small/large; screenshot + PSI check | PSI `/proc/pressure/memory`, `zramctl`, lmk kills, `dumpsys meminfo` |
| **P4** | **Dexopt our own APKs correctly; keep `speed-profile` as the system default, never `everything`** | 0.25 d | 5–15 % faster cold start of Start/Action Centre/Settings; less disk and dirty code than `speed`/`everything` | low | `am start -W` median of 10; `dumpsys package dexopt` |
| **P5** | **Remove always-on shell work: one notification listener, 0.5× animation scales, disable window blur if enabled** | 0.25 d | **20–40 MB** PSS if the second listener process goes away; 50–100 ms perceived responsiveness; blur off saves GPU on Adreno 308 | low | `dumpsys meminfo`, `settings get global`, `wm disable-blur` (no arg) |

Do **not** start with P3. Start with the measurement in §5, then P1/P2 because they are
free wins with no ROM change and no risk to the boot path. P3 and P4 are gated on the
numbers.

---

## 1. What Android 16 actually exposes (and what applies here)

### 1.0 The LineageOS layer

LineageOS 23.2 is AOSP 16 plus the device tree, vendor blobs and a small set of Lineage
features; for this phone the `Mi8937`/`Mi8917` tree contains no zram or low-RAM overrides
(see below), and there is no Lineage "performance mode" daemon to tune. In practice the
"LineageOS optimisation track" is the AOSP platform track: ART/dexopt, lmkd/PSI, zram,
HWUI, and the framework's low-RAM switch. Device maintainers normally set the memory
properties in the device tree; because this tree does not, the live values are the
vendor/AOSP defaults and must be measured rather than assumed `[F:
[device tree](https://github.com/LineageOS/android_device_xiaomi_Mi8937/tree/lineage-23.2)]`.


### 1.1 `ro.config.low_ram` is a feature switch, not a RAM multiplier

`ActivityManager.isLowRamDeviceStatic()` reads `RoSystemProperties.CONFIG_LOW_RAM`,
which is backed by `ro.config.low_ram` `[F: [ActivityManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/app/ActivityManager.java)]`.
The same predicate feeds `isHighEndGfx()`, so turning it on also tells the framework and
SystemUI to avoid "high-end" graphics paths `[F: same file, `isHighEndGfx()`]`. The
public doc says "currently it generally means something with 1 GB or less" `[F: same
file, `isLowRamDevice()` javadoc]`, but Android Go itself supports 2 GB (the official
minimum-RAM table tops out at 2 GB for Android 13) `[F:
[Android Go](https://developer.android.com/guide/topics/androidgo)]`. On this device the
switch is therefore **plausible but not free**: it changes app feature choices and can
remove graphics features. Test it with screenshots and `gfxinfo`, not only meminfo.

What Android Go's own 2 GB configuration sets `[F:
[go_defaults_common.mk](https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/product/go_defaults_common.mk),
[go_defaults_common.prop](https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/board/go_defaults_common.prop)]`:

```make
PRODUCT_VENDOR_PROPERTIES += ro.config.low_ram=true
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed-profile
PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD := false
PRODUCT_MINIMIZE_JAVA_DEBUG_INFO := true
```

```properties
ro.lmk.critical_upgrade=true
ro.lmk.upgrade_pressure=40
ro.lmk.downgrade_pressure=60
ro.lmk.kill_heaviest_task=false
pm.dexopt.downgrade_after_inactive_days=10
pm.dexopt.shared=quicken
dalvik.vm.heapgrowthlimit=128m
dalvik.vm.heapsize=256m
```

Two cautions:

- **Do not copy `pm.dexopt.shared=quicken`** to a non-Go 2 GB phone. The AOSP comment says
  it trades clean pages for dirty pages, extra CPU and battery, because quicken files are
  JIT-ed in every process that loads the shared APK `[F: go_defaults_common.prop comment]`.
  That is a Go-specific RAM/space trade, not a general speed win.
- **Do not copy `dalvik.vm.heapgrowthlimit=128m`** blindly. A 128 MB per-app Java heap cap
  on a 2 GB phone can cause GC thrash in browser/Chromium; Go accepts that because its
  target apps are smaller. Measure GC and app crashes before touching heap sizes.

No generic LineageOS 2 GB tuning guide was found in the device tree; the `Mi8937` tree at
`lineage-23.2` (the parent of `Mi8917`) contains no zram or low-RAM overrides in
`vendor.prop`, `fstab.qcom` or `init.xiaomi.device.rc` `[F:
[device tree](https://github.com/LineageOS/android_device_xiaomi_Mi8937/tree/lineage-23.2)]`.
So the live values come from the vendor/AOSP defaults and **must be read on the device**
(`getprop`, `zramctl`) before changing anything.

### 1.2 lmkd, PSI and `ro.lmk.*`

lmkd is the userspace low-memory killer since Linux 4.12 `[F:
[lmkd README](https://android.googlesource.com/platform/system/memory/lmkd/+/3cc43abcbf1675ded0fd88d62bbc06901beb581e/README.md)]`.
Its documented knobs (same README) are:

| Property | Default | What it does |
|---|---|---|
| `ro.config.low_ram` | `false` | Choose low-memory vs high-performance defaults; also changes lmkd thresholds |
| `ro.lmk.use_minfree_levels` | `false` | Legacy free/file-cache thresholds instead of PSI |
| `ro.lmk.low` / `.medium` / `.critical` | `1001` / `800` / `0` | Minimum oom_adj score killable at each vmpressure level |
| `ro.lmk.critical_upgrade` | `false` | Allow upgrade to critical level |
| `ro.lmk.upgrade_pressure` / `.downgrade_pressure` | `100` / `100` | Swap-pressure thresholds for level changes (disabled at 100) |
| `ro.lmk.kill_heaviest_task` | `false` | Kill heaviest eligible task vs any eligible task |
| `ro.lmk.kill_timeout_ms` | `100` | No second kill within this window |
| `ro.lmk.thrashing_limit` | 30 low-RAM / 100 high-end | Workingset-refault threshold for "thrashing" |
| `ro.lmk.psi_partial_stall_ms` / `.psi_complete_stall_ms` | 200/700 low-RAM; 70/700 high-end | PSI stall thresholds for memory notifications |
| `ro.lmk.swap_free_low_percentage` | `10` | Swap-starved threshold |
| `ro.lmk.swap_compression_ratio` | `1` | Used to estimate swappable capacity |

The only defensible change on this phone is **`ro.config.low_ram=true`**, which lets lmkd
pick the low-RAM defaults above and makes `isLowRamDevice()` true. Hand-editing individual
`ro.lmk.*` values without a PSI/lmkd trace is how people get UI kills or thrash; the
`critical`/`low` scores decide which process dies, and a wrong value can kill SystemUI.
`ro.lmk.kill_heaviest_task=true` is not automatically better: it frees more memory but
kills the user's largest cached app; Go deliberately sets it `false` for the fast decision
`[F: go_defaults_common.prop]`.

Measure before/after with `/proc/pressure/memory`, `/proc/vmstat` (`pswpin`, `pswpout`,
`pgmajfault`), `dumpsys meminfo`, and lmkd kill events in `logcat -b events`. The
`ro.config.low_ram` change is a **read-only property**: `setprop` will fail. Put it in a
Magisk module (`system.prop` or `post-fs-data.sh` with `resetprop -n`) and reboot; verify
with `getprop ro.config.low_ram`.

### 1.3 ART compiler filters and dexopt

Android 16 officially supports four compiler filters `[F:
[ART configuration](https://source.android.com/docs/core/runtime/configure)]`:

| Filter | What it does | Use here |
|---|---|---|
| `verify` | Verify DEX, no AOT; interpreter/JIT at runtime | Fallback when no profile; not a performance target |
| `quicken` | Verify + interpreter optimisations (Android 11 or lower; retained internally) | Go/shared-APK trade only; not for our APKs |
| `speed` | Verify + AOT-compile **all** methods; no class-load optimisation | Small own APKs (Start, Action Centre) only |
| `speed-profile` | Verify + AOT-compile methods in the profile + class-load optimisation for profiled classes | Default for apps; keep it |

Key facts that decide the plan:

- `speed-profile` is the default for install (`pm.dexopt.install`) and background dexopt
  (`pm.dexopt.bg-dexopt`); if no profile is supplied, `speed-profile` is equivalent to
  `verify` `[F: [Package manager configuration](https://source.android.com/docs/core/runtime/configure/package-manager)]`.
  Side-loaded ztmetro APKs have no profile, so their first runs are JIT-heavy until the
  system collects one.
- `everything` is **not** one of the four officially supported filters. It exists inside
  dex2oat's filter enum, but it is not the supported app-level lever; it compiles more code
  and expands the odex/vdex footprint `[F: ART configuration lists exactly verify, quicken,
  speed, speed-profile; `[E]` for the size effect]`. `pm compile -m everything` is not a
  fix for jank; it mostly moves disk and dirty pages.
- The system server already uses `speed-profile` on Android 14+; product core apps may be
  built with `speed` `[F: ART configuration, "System server code" and
  `PRODUCT_DEXPREOPT_SPEED_APPS`]`. Do not rebuild the ROM to change this for a shell tweak.
- `cmd package compile` is the supported runtime interface; read the state before and
  after. The exact accepted filters and flags are device-build-specific, so `cmd package
  compile -h` on the phone is authoritative. `dumpsys package dexopt` is the readback.

**Plan:** force `speed-profile` for Settings/SystemUI after any update; allow `speed` for
the two tiny own APKs (57 KB and 296 KB); never `everything`; re-run after OTA because the
boot classpath changes invalidate dexpreopt `[F: ART configuration note on module updates]`.

### 1.4 zram, swappiness and PSI

zram is compressed swap in RAM; it caps, not reserves, memory, and is much faster than
eMMC swap `[F: [kernel zram documentation](https://docs.kernel.org/admin-guide/blockdev/zram.html)]`.
On a 2 GB phone, disabling it is exactly the wrong move: the kernel then has no compressed
overflow and lmkd kills processes earlier. The useful range is a **cap of about 50–75 % of
RAM** with a fast algorithm (`lz4` or `lzo-rle`, if the kernel exposes it) and **swappiness around 100**; these are
starting points, not truths `[E]`. A same-device LineageOS 23.2 write-up uses 75 %, `lz4`,
swappiness 100 and reports it as usable `[E:
[IMatvey, zram on ugglite](https://imatvey.xyz/software/android/nastrojka-zram-na-lineageos-s-pomoshhyu-modulya-magisk/)]`;
treat that as one anecdote, not a controlled result.

Never add a swap file on the 16 GB eMMC: it adds wear and latency and does not beat zram.
Do not judge zram by "free RAM"; judge it by swap used at the same workload, PSI
`full avg60`, `pswpin/pswpout` per second, and lmkd kills. A zram cap that is too large
lets the system compress until the A53 spends its time decompressing; a cap too small
causes early kills. Measure.

### 1.5 HWUI renderer, tracing and animation scale

`debug.hwui.renderer` is read per process in `Properties::peekRenderPipelineType()`:

```cpp
bool useVulkan = use_vulkan().value_or(false);
std::string rendererProperty = base::GetProperty(PROPERTY_RENDERER,
                                                 useVulkan ? "skiavk" : "skiagl");
if (rendererProperty == "skiavk") return RenderPipelineType::SkiaVulkan;
return RenderPipelineType::SkiaGL;
```

`[F: [Properties.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.cpp)]`

Consequences:

- On Android 16 the only selectable pipelines are **Skia Vulkan** (`skiavk`) and **Skia
  GL** (`skiagl`, the fallback for every other value). The old OpenGL pipeline is gone;
  `debug.hwui.renderer=opengl` does not select a faster legacy path, it just falls back to
  SkiaGL. This is a placebo, not a tweak.
- `skiavk` on Adreno 308 is a real experiment, not a default recommendation: the driver's
  Vulkan path may be slower or less stable than GL at 720p. Test by setting the property,
  force-stopping the app, and comparing `gfxinfo`; rollback by removing the property and
  rebooting. Do not ship it in a boot script without A/B numbers.
- `debug.hwui.skia_tracing_enabled` is documented in the same header as "broad recording
  of Skia drawing commands" and can be slow; `debug.hwui.trace_gpu_resources` is explicitly
  "slow"; `debug.hwui.capture_skp_enabled` writes a recording `[F:
  [Properties.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.h)]`.
  Use them only for a bounded trace and remove them afterwards. A permanent skia-tracing
  prop is a self-inflicted regression.

**Animation scale:** there is no supported `persist.sys.*` animation-scale property.
WindowManagerService reads the three `Settings.Global` values
`window_animation_scale`, `transition_animation_scale` and `animator_duration_scale`
`[F: [WindowManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerService.java)]`.
Set them with `settings put global … 0.5`; `0` removes feedback and is a user-preference
choice, not a performance benchmark. Use 0.5 for ztmetro: motion stays, latency drops.

**Blur:** `wm disable-blur` writes `Settings.Global.DISABLE_WINDOW_BLURS` and is persistent
`[F: [WindowManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerShellCommand.java)]`.
`wm disable-blur` with no argument prints whether blur is supported and enabled. Disable
it only if it is actually on; on a low-RAM configuration it may already be off.

### 1.6 `ro.hw_timeout_multiplier`: not a performance knob

`Build.HW_TIMEOUT_MULTIPLIER` is documented as a multiplier "for products targeting
software emulators"; on real devices and hardware-accelerated virtualized devices "this
should not be set" `[F:
[Build.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/os/Build.java)]`.
Setting it on the phone does not make anything faster; it makes timeouts longer, hides
ANRs and slows boot. Do not use it.

### 1.7 SystemUI / launcher feature trimming

There is no generic "disable unnecessary SystemUI features" property. The real levers are:

- `ro.config.low_ram=true` (SystemUI/framework choose low-end paths) — test visually.
- `wm disable-blur true` (if blur is on).
- `settings put secure doze_always_on 0` (always-on display off).
- 0.5× animation scales.
- Do not add live wallpaper, blur, or a second always-on overlay window.

Our SystemUI RRO changes resources only, so it adds **no code** and no per-frame cost; it
cannot remove a feature either. Removing SystemUI code requires a ROM fork and is out of
scope for this plan (see [06-systemui-standalone.md](06-systemui-standalone.md)).

---

## 2. LSPosed / Xposed cost, and specifically our view-tree walks

### 2.1 What a hook costs (and what it does not)

LSPosed uses LSPlant, an ART hook library with **inline deoptimisation**: it can replace a
method entry point and deoptimise methods so inlined calls are not missed `[F:
[LSPlant docs](https://lsposed.org/LSPlant/namespacelsplant.html),
[LSPosed](https://github.com/LSPosed/LSPosed)]`. The practical cost model:

- **Installation cost** is paid once per hooked method per process (find class, resolve
  method, hook, deoptimise). It is not a background daemon tax.
- **Invocation cost** is paid on every call to a hooked method: trampoline + bridge +
  callback. A hook on a method called once per screen is cheap; a hook on
  `onDraw`/`onLayout`/`getView` would be catastrophic because it also deoptimises a hot
  method. We do not hook those; keep it that way.
- **Zygisk/LSPosed injection** adds process-start work for scoped processes
  (Settings/SystemUI), not for every app. Measure it with `am start -W` on Settings with
  the module scope disabled vs enabled; do not guess.

Our Settings module installs exactly four hooks: `SettingsBaseActivity.setContentView(int)`,
`setTitle(CharSequence)`, `onResume()`, and `SettingsHomepageActivity.onCreate(Bundle)`
`[F: [SettingsHeaderHooks.java](../settings/header-module/src/org/ztmetro/settingsheader/SettingsHeaderHooks.java#L30-L110)]`.
Those are low-frequency lifecycle calls, so **the hook overhead itself is not the problem**.
The problem is what the callbacks do.

### 2.2 The actual cost: up to 32 tree traversals per Settings screen open

`HeaderBinder.attach()` and `HeaderBinder.refresh()` each call `MetroTinter.apply()` and
`ScreenStyler.apply()` `[F:
[HeaderBinder.java](../settings/header-module/src/org/ztmetro/settingsheader/HeaderBinder.java#L78-L134)]`.
Each of those schedules its own delayed chain:

| Component | Immediate | Delayed | Walks per kick |
|---|---|---|---|
| `MetroTinter.apply` | 1 full walk | 200 / 600 / 1500 ms | 4 |
| `ScreenStyler.pass` (home) | `RowIcons` + `FlatRows` + `RowType` | 200 / 700 / 1600 ms | 3 per pass × 4 = 12 |
| `ScreenStyler.pass` (sub-screen) | `FlatRows` + `RowType` | 200 / 700 / 1600 ms | 2 per pass × 4 = 8 |

`[F: [MetroTinter.java](../settings/header-module/src/org/ztmetro/settingsheader/MetroTinter.java#L42-L58),
[ScreenStyler.java](../settings/header-module/src/org/ztmetro/settingsheader/ScreenStyler.java#L72-L127)]`

So one `attach` + one `refresh` is **16–32 full walks** per screen, depending on whether
the screen is the homepage, and `setTitle` can trigger additional refreshes. `ScreenStyler`
coalesces its own chain per Activity with a generation counter, but `MetroTinter` does not:
each `apply()` posts another three Runnables that are never cancelled. On a screen with
~1,000 views this is tens of thousands of `getChildAt`/`instanceof`/`getResourceEntryName`
operations in the first 1.6 s — exactly when the user is looking at the screen `[E]`.

The walks are also allocation-heavy, and the same subtrees are searched repeatedly:

- `Views.idName()` calls `getResourceEntryName()` (string allocation) on every view that
  needs classification; `className()` lowercases a fresh string; callers lowercase again
  `[F: [Views.java](../settings/header-module/src/org/ztmetro/settingsheader/Views.java#L32-L47)]`.
- `Views.containsTitle()` recursively searches every child for a title; `FlatRows.walk()`
  calls it for every non-protected group, and `looksLikePreferenceRow()` calls it again
  `[F: [Views.java](../settings/header-module/src/org/ztmetro/settingsheader/Views.java#L97-L137),
  [FlatRows.java](../settings/header-module/src/org/ztmetro/settingsheader/FlatRows.java#L55-L95)]`.
  Each pass re-searches the same subtrees for titles; the depth bound keeps this from being quadratic, but it multiplies the per-view cost across every walk.
- `RowIcons.shouldHide()` allocates a new `String[]{"arrow", …}` for every `ImageView` and
  lowercases `id + " " + cls` each time `[F:
  [RowIcons.java](../settings/header-module/src/org/ztmetro/settingsheader/RowIcons.java#L126-L154)]`.
- `MetroTinter.invokeTint()` does `view.getClass().getMethod(...)` and
  `ColorStateList.valueOf(...)` for every enabled `CompoundButton` on every walk `[F:
  [MetroTinter.java](../settings/header-module/src/org/ztmetro/settingsheader/MetroTinter.java#L64-L92)]`.
  Method lookup is the expensive part, and the same two `ColorStateList` objects are
  recreated every pass.
- `RowType.walk()` allocates `int[] count` and `RowIcons` allocates `int[] count` per pass;
  small, but avoidable in a hot path.

**The fix is structural, not micro-optimisation:**

1. **One visitor, one walk.** Merge `MetroTinter`, `FlatRows`, `RowType`, and `RowIcons`
   into a single `styleOnce(View root, boolean homepage)` traversal. At each node, decide
   once whether it is a row, a switch, an icon, or a container; do not re-walk for each
   concern. `BottomBar.attach()` stays separate (it uses tags/findViewById, not a full
   walk).
2. **One schedule per screen, not four.** Run once after the first layout and once after
   the fragment's data arrives. Use `ViewTreeObserver.OnGlobalLayoutListener` (one-shot,
   removed after firing) or `root.postOnAnimation` with a dirty flag; cancel pending
   callbacks on `onPause`/`onDestroy`. Do not use fixed 200/600/1500 chains as the primary
   synchronisation mechanism.
3. **Style only rows that exist.** On a `RecyclerView`, attach a
   `ViewGroup.OnHierarchyChangeListener` (framework API, no AndroidX dependency) and style
   newly added children; do the initial pass over `getChildCount()` only. Skip protected
   containers (`Toolbar`, `AppBarLayout`, `NestedScrollView` chrome) and never descend into
   a row after styling it (the existing `return` after a row is correct — keep it).
4. **Cache classification.** Precompute the framework ids you care about
   (`android.R.id.title`, `android.R.id.summary`); compare `view.getId()` to those ints
   instead of `getResourceEntryName().toLowerCase()`. For names you must resolve, cache
   `SparseArray<String>` per process. Cache `className()` in a `WeakHashMap<Class<?>, String>`
   or compare `instanceof` where possible.
5. **Cache reflection and colours.** Static `Method` cache keyed by class
   (`ClassValue<Method[]>` or `HashMap<Class<?>, Method>`); static `ColorStateList`
   `TRACK`/`THUMB`; call `setTrackTintList(TRACK)` only if the current list is not already
   `TRACK` (API 21+ getters). Never call `getMethod` in a loop.
6. **No per-frame or per-row hooks.** Keep the four lifecycle hooks. If a future change
   needs per-row styling, use the listener in (3), not a hook on
   `RecyclerView.onBindViewHolder` — that method is hot and deoptimising it costs more than
   the styling.
7. **Instrument the pass in debug builds.** Log `views`, `rows`, `switches`, and
   `SystemClock.uptimeMillis()` delta in one line; `adb logcat -s ztmetro-header` then
   shows the real cost per screen. Remove the log or gate it behind a `debug` flag before
   shipping.

Expected: 32 walks → 2, main-thread time per Settings open down by **200–600 ms** `[E]`,
fewer dropped frames in the 0–1.6 s window, and no change in PSS (the views are already
there). Risk: low; every stage is guarded and cosmetic. Rollback: reinstall the previous
module APK. Measure: `am start -W`, `gfxinfo`, and the debug timing log.

### 2.3 What not to blame

- LSPosed itself is not a background CPU consumer when Settings is not running. If
  `top` shows Settings at 0 % idle, the hooks are not the idle problem.
- The module APK is small; its resources are loaded only in the Settings process. Do not
  chase module APK size as a RAM lever.
- Do not disable the module to "save RAM" unless A/B shows a real Settings PSS delta; the
  cost is CPU in the first 1.6 s, not resident memory.

---

## 3. What our artefacts actually cost

### 3.1 Five RRO overlays: negligible, do not optimise for RAM

The overlay module zip is **69,619 bytes**; its five APKs are **67,715 bytes**
unpacked (`ZtMetroFramework`, Settings, SystemUI, framework status, SystemUI status) `[F:
[ztmetro-overlays-1.0.0.zip](../artifacts/ztmetro-overlays-1.0.0.zip)]`. RROs are not
processes; `OverlayManagerService`/`idmap2` map their resource tables and the target loads
only resources it references. Expected steady-state PSS delta: **< 2–5 MB total**, mostly
package-manager metadata, not per-frame work `[E]`. They add a small package scan at boot
(five packages; tens of ms `[E]`), not a smoothness tax. Keep them; removing a working
overlay to "save RAM" is not worth the visual regression. If boot time is the metric, the
overlay count is a second-order term behind dexopt and SystemUI.

### 3.2 Font module: 8 TTFs, ~1.2 MB, no frame cost

The module ships 8 Open Sans faces (Light/Regular/SemiBold/Bold + italics), 1,233,578 bytes
total `[F: [ztmetro-font-1.1.0.zip](../artifacts/ztmetro-font-1.1.0.zip)]`. Each process
maps only the faces it uses; the system font cache and per-process glyph caches account
for the cost. Expected: **0–3 MB PSS** in processes that draw a lot of text, and no
per-frame CPU after the first layout `[E]`. Eight static faces are not a problem; adding
more weights (or a variable font plus fallbacks) would start to matter. The real risk is
layout/metrics change, which is already a design decision, not a performance one.

### 3.3 Start launcher: the first frame is the cost, not the APK

APK: 57,622 bytes, no AndroidX, targetSdk 36, HOME + optional notification listener `[F:
[launcher-v3/artifact-facts.txt](../evidence/launcher-v3/artifact-facts.txt)]`. Expected
PSS on this device: **55–95 MB** (single activity, no WebView, no image pipeline); hard
ceiling 150 MB `[E]`. The likely first-frame costs:

- `StartActivity.onCreate()` calls `buildUi()` and then `refreshData()` synchronously;
  `refreshData()` loads apps/tiles and calls `grid.setTiles()` / `appList.setApps()`
  `[F: [StartActivity.java](../launcher/app/src/main/java/org/ztmetro/start/StartActivity.java#L82-L90),
  [#L373-L383](../launcher/app/src/main/java/org/ztmetro/start/StartActivity.java#L373-L383)]`.
- `MetroTileView.buildArt()` calls `TileArt.art()` on the main thread; `TileArt` renders
  bitmaps on demand and caches up to 96 entries `[F:
  [MetroTileView.java](../launcher/app/src/main/java/org/ztmetro/start/MetroTileView.java#L192-L207),
  [TileArt.java](../launcher/app/src/main/java/org/ztmetro/start/TileArt.java#L56-L118)]`.
  On first layout that means ~15 visible tiles plus the app-list's visible rows; each
  adaptive-icon render is a `Drawable` load + bitmap draw + `PorterDuff` pass. At
  720p/44 % icon fraction this is tens of ms per icon on an A53, i.e. **100–400 ms** of
  main-thread work on the first frame `[E]`. The cache is also not thread-safe; if you
  move rendering off the main thread, guard it (synchronized or `LruCache`).
- `TileArt` key is `component.flattenToString() + "@" + size`, which allocates a string
  per tile lookup; minor compared with the render, but it is on the draw path. Cache the
  key per tile or use a small value class.

**Fix:** render icons on a single background executor; return a deterministic placeholder
(letter/`SystemGlyphs`/tile colour) for the first frame; post the finished `Art` to the
main thread and invalidate only that tile. Keep `CACHE_LIMIT` at 96 (≈5–6 MB at 720p
`[E]`), or lower to 64 if PSS is tight. Prewarm the first screen while the launcher is
idle, not in `onCreate`. This trades a few frames of placeholders for a first frame that
does not block on 15–30 icon renders.

### 3.4 Action Centre: the listener is the standing cost

APK: 296,359 bytes, targetSdk 36, notification listener + optional tile alias `[F:
[actioncentre/artifact-facts.txt](../evidence/actioncentre/artifact-facts.txt)]`. The
listener is only bound after the user grants notification access; without it the app is
just an activity and costs nothing at idle. With it granted:

- `ActionCentreListener` is a bound `NotificationListenerService` in the app process, so
  the process stays alive and pays **25–45 MB PSS** `[E]`.
- Every posted/removed notification calls `publishAll()`, which does a binder
  `getActiveNotifications()`, builds a new `List`, and calls `convert()` for every active
  notification `[F:
  [ActionCentreListener.java](../actioncentre/app/src/main/java/org/ztmetro/actioncentre/ActionCentreListener.java#L58-L95)]`.
  At tens of notifications this is cheap; at hundreds it is a main-thread rebuild per
  post/remove. Debounce 100–200 ms or maintain an incremental map keyed by `sbn.getKey()`.
- `ActionCentreActivity.onStart()` posts a **one-shot** `periodicRefresh` after 500 ms;
  it does not repost itself `[F:
  [ActionCentreActivity.java](../actioncentre/app/src/main/java/org/ztmetro/actioncentre/ActionCentreActivity.java#L95-L102)]`.
  So it is not a 500 ms poller; do not "fix" a loop that is not there. `StatusStripView`
  ticks every 15 s but only while attached `[F:
  [StatusStripView.java](../actioncentre/app/src/main/java/org/ztmetro/actioncentre/StatusStripView.java#L25-L70)]`.
- Two notification listeners (Start and Action Centre) mean two bound services and
  potentially two live processes. If both features are used, merge onto **one** listener
  (same app or a small provider/broadcast contract) and let the panel read the snapshot.
  If Action Centre is not used, do not grant its listener; that saves the whole process.
  This is the only artefact change in P5, and it is architectural, not a build.prop tweak.

### 3.5 Keyguard LSPosed module

The keyguard module adds seven guarded hooks in SystemUI `[F:
[18-coverage-and-build.md](18-coverage-and-build.md) §1 row 9]`. They are lifecycle/clock
hooks, not per-frame tree walks; the idle cost is the SystemUI process it already lives in.
Measure only if the lock screen is actually enabled and used; do not include it in the
first optimisation wave.

---

## 4. The plan, in order

Each item lists: change, why, effect on this phone, risk/rollback, how to measure.
Effort is engineer time, not elapsed calendar time.

### P1 — One Settings pass (expected effect: high, effort: low–medium, risk: low)

**Change:** replace the `MetroTinter` + `ScreenStyler` chains with one coalesced
`styleOnce` visitor per screen (see §2.2). Keep the four hooks; change only what they call.
Run the pass once after the first layout and once after the screen's data settles; remove
fixed 200/600/1500 and 200/700/1600 chains. Style new `RecyclerView` children via
`OnHierarchyChangeListener`. Cache ids, class names, `Method`s and `ColorStateList`s.

**Why:** 16–32 full traversals per screen open, with repeated title searches and per-view
string allocations, is the largest self-inflicted main-thread cost in the shell. It is
also the easiest to remove without touching the ROM.

**Effect `[E]`:** 200–600 ms less main-thread CPU per Settings open; 1–3 fewer dropped
frames in the first 1.6 s; no PSS change. On a 4×A53 at 1.4 GHz, that is visible when
opening Wi-Fi/Apps/Display.

**Risk/rollback:** low; all stages are `try/catch(Throwable)` and cosmetic. Reinstall the
previous `ztmetro-settings-header.apk` (`adb install -r`) to roll back. Do not change the
hook set in the same commit as the pass rewrite; keep the A/B clean.

**Measure:** `am start -W` 10× (median), `dumpsys gfxinfo com.android.settings framestats`
(janky %, p95), `logcat -s ztmetro-header` debug timing, `top` during a 60 s Settings
scroll. Success: ≥30 % lower pass time and ≥30 % fewer janky frames on Settings open.

### P2 — Start first frame (expected effect: high, effort: low–medium, risk: low)

**Change:** move `TileArt` rendering and app-icon loading to a background executor; show a
deterministic placeholder until ready; `post` the result and invalidate the tile. Prewarm
the first page on idle. Keep a thread-safe LRU cache (96 entries or 64 if PSS is tight).
Avoid `flattenToString()` on every draw lookup.

**Why:** HOME is the most-seen surface; the first frame currently does synchronous
`PackageManager`/`Drawable`/bitmap work for visible tiles and rows.

**Effect `[E]`:** 100–250 ms faster first draw; 1–3 fewer dropped frames; 5–15 MB lower
peak PSS if the placeholder avoids retaining duplicate bitmaps. It will not fix a slow
app launch (that is P4).

**Risk/rollback:** low; placeholders are visible for a frame or two. Revert the APK. Watch
for recycled-view races: always check the tile/app identity before applying a result.

**Measure:** `am start -W` HOME (force-stop first), `dumpsys gfxinfo org.ztmetro.start`,
`dumpsys meminfo org.ztmetro.start` after first frame and after a 300-app scroll.
Success: first-frame time −20 %, janky frames on HOME <5 %, PSS ≤95 MB.

### P3 — Memory configuration, gated on baseline (expected effect: high under load, effort: low, risk: medium)

**Change (only if §5 shows it is needed):**

1. Read the baseline: `getprop ro.config.low_ram`, `zramctl`, `/proc/swaps`,
   `/proc/sys/vm/swappiness`, PSI, lmkd kills.
2. If idle used RAM >1.2 GB or lmk kills >2/day, test `ro.config.low_ram=true` via a Magisk
   module; reboot; compare PSS, PSI, lmkd kills and screenshots (feature trims).
3. If zram is disabled or <40 % of RAM, test a cap of 50 % (1 GB) with `lz4`/`lzo-rle`
   and swappiness 100. Only go to 75 % if swap is exhausted *and* PSI is high *and* CPU is
   not saturated.
4. Do not touch individual `ro.lmk.*` values in the same experiment; change one thing at a
   time.

**Why:** 2 GB of RAM with a 1.1–1.25 GB Android base is tight; compressed swap and the
low-RAM feature profile are the two supported levers. zram is not optional on this class.

**Effect `[E]`:** if the baseline is bad, lmk kills 10+/day → 0–2/day, fewer app reloads
on switch, idle PSS −20…60 MB; if the baseline is already sane, **no effect** — do not
change it. There is no guaranteed FPS gain; this is a memory-pressure fix.

**Risk/rollback:** medium. `low_ram` can remove graphics features and change app
behaviour; zram too large can thrash and too small can OOM. Reboot after each change.
Rollback: remove the Magisk module / restore the previous `system.prop`, reboot; verify
`getprop` and `zramctl`. Never `swapoff` a heavily used zram under load; reboot instead.
Do not add eMMC swap.

**Measure:** PSI `full avg60`, `zramctl` used/ratio, `pswpin/pswpout`, lmkd kill events,
`dumpsys meminfo` after 30 min of the same workload, and a screenshot/`gfxinfo` check for
feature trims. Success: lmk kills ≤2/day and PSI `full avg60` at load <5, with no
regression in Settings/HOME jank.

### P4 — Dexopt the right things (expected effect: medium, effort: very low, risk: low)

**Change:** after installing/updating the stack:

```sh
# read state first (root may be required for system packages)
adb shell dumpsys package dexopt | grep -A4 -E 'org.ztmetro.start|org.ztmetro.actioncentre|com.android.settings|com.android.systemui'
# side-loaded own APKs: speed-profile after a profile exists; speed is acceptable for tiny APKs
adb shell cmd package compile -m speed-profile -f org.ztmetro.start
adb shell cmd package compile -m speed -f org.ztmetro.actioncentre
# system apps: keep speed-profile; do not use everything
adb shell su -c 'cmd package compile -m speed-profile -f com.android.settings'
```

`cmd package compile -h` on the device is authoritative for accepted filters/flags.
`dumpsys package dexopt` is the readback; `--reset <pkg>` reverts a package to its
installed state.

**Why:** side-loaded APKs with no profile are effectively `verify`; the first runs pay JIT.
A one-off compile (or letting background dexopt run while charging) removes that. The
system server is already `speed-profile`; don't rebuild the ROM for it.

**Effect `[E]`:** 5–15 % faster cold start of Start/Action Centre/Settings; less disk and
dirty code than `speed`/`everything`. Does not fix jank inside a frame; do not expect it
to.

**Risk/rollback:** low. `--reset` or reinstall reverts. Do not compile every app with
`speed`; code expansion costs disk and dirty pages. Never `everything`.

**Measure:** `am start -W` median of 10 cold starts before/after; `dumpsys package dexopt`;
`logcat -b events | grep am_activity_launch_time`; `dumpsys meminfo` for code-size/PSS
change (should be small).

### P5 — Remove standing shell work (expected effect: medium, effort: low, risk: low)

**Change:**

- If both Start and Action Centre listeners are granted, consolidate to one listener and a
  snapshot the panel reads; if Action Centre is unused, leave its listener ungranted.
- `settings put global window_animation_scale 0.5`,
  `transition_animation_scale 0.5`, `animator_duration_scale 0.5`.
- `wm disable-blur` (no arg) → if enabled, `wm disable-blur true`; if unsupported/already
  off, skip.
- `settings put secure doze_always_on 0` if AOD is on.
- Do **not** set `debug.hwui.renderer`, `ro.hw_timeout_multiplier`, or skia tracing.

**Why:** a second bound notification listener is a whole cached process (20–40 MB) and a
second copy of notification objects; animation scales and blur are cheap perceived wins
with no code risk.

**Effect `[E]`:** 20–40 MB PSS if the second listener/process goes away; 50–100 ms
perceived latency on transitions; blur off saves GPU time on Adreno 308 if blur was on.
No effect on measured frame time if the UI was already not blurring.

**Risk/rollback:** low. Restore the settings (`settings put global … 1`;
`wm disable-blur false`; re-grant the listener). Do not disable animations entirely for
the owner; 0.5 keeps the Metro motion language.

**Measure:** `dumpsys meminfo` for both packages, `settings get global …`, `wm
disable-blur` (no arg), `dumpsys gfxinfo` before/after a shade pull.

---

## 5. Measurement protocol for the lead

### 5.1 Preconditions

- Same build, same boot, same brightness (50 %), same thermal state; note
  `/sys/class/thermal/thermal_zone*/temp` before each run. A53 throttling can dominate a
  10 % difference.
- Fresh boot for the "after boot" baseline; then 30 min of normal use for the "settled"
  baseline; then a scripted load (launcher + browser + messaging + Settings scroll).
- Force-stop the target before every cold-start run. Run each measurement 10× and use the
  **median**, not the best.
- No charging during jank runs (charging raises thermal/power state); note if the device
  is charging anyway.
- Keep the same app set, wallpaper, and notification count; record them in the report.

### 5.2 The first three commands

```sh
# 1) Per-package PSS and the total RAM/ZRAM picture
adb shell 'for p in org.ztmetro.start com.android.systemui com.android.settings org.ztmetro.actioncentre; do echo "== $p"; dumpsys meminfo "$p" | sed -n '1,45p'; done'

# 2) Memory pressure, zram and swappiness (the "why" behind the PSS)
adb shell 'cat /proc/pressure/memory; echo ---; zramctl; echo ---; cat /proc/swaps; echo ---; cat /proc/sys/vm/swappiness; echo ---; cat /proc/vmstat | grep -E "pswp|pgmajfault|compact"'

# 3) Settings jank: reset counters, cold-open, scroll, read frame stats
adb shell dumpsys gfxinfo com.android.settings reset
adb shell am force-stop com.android.settings
adb shell am start -W -n com.android.settings/.Settings
# scroll the page 3x with: adb shell input swipe 360 1000 360 300 250
adb shell dumpsys gfxinfo com.android.settings framestats > settings-framestats.txt
adb shell dumpsys gfxinfo com.android.settings | sed -n '1,25p'
```

The `gfxinfo` aggregate block prints `Total frames rendered`, `Janky frames (%)`, `90th`,
`95th`, `99th` percentile, `Number Missed Vsync`, `Number Slow UI thread`, `Number Slow
draw`. `framestats` is the CSV for per-frame distribution; use
`FRAME_COMPLETED - INTENDED_VSYNC` and ignore rows with non-zero `FLAGS` (the Android
"Test UI performance" doc describes the columns `[F:
[developer.android.com/training/testing/performance](https://developer.android.com/training/testing/performance)]`).

### 5.3 Full command set

**Total memory and PSS**

```sh
adb shell dumpsys meminfo -a | sed -n '1,90p'          # Total/Free/Used/Lost + ZRAM
adb shell dumpsys meminfo com.android.systemui
adb shell dumpsys meminfo com.android.settings
adb shell dumpsys meminfo org.ztmetro.start
adb shell dumpsys meminfo org.ztmetro.actioncentre
adb shell dumpsys meminfo surfaceflinger
adb shell dumpsys activity lru | head -40
adb shell dumpsys activity processes | grep -E 'oom adj|ProcessRecord' | head -40
```

**Jank / frame timing**

```sh
# Settings: cold open + 3 swipes
adb shell dumpsys gfxinfo com.android.settings reset
adb shell am force-stop com.android.settings
adb shell am start -W -n com.android.settings/.Settings
adb shell input swipe 360 1000 360 300 250
adb shell input swipe 360 1000 360 300 250
adb shell input swipe 360 1000 360 300 250
adb shell dumpsys gfxinfo com.android.settings framestats
adb shell dumpsys gfxinfo com.android.settings | sed -n '1,25p'

# Launcher: HOME, one page swipe, app-list scroll
adb shell dumpsys gfxinfo org.ztmetro.start reset
adb shell am force-stop org.ztmetro.start
adb shell input keyevent KEYCODE_HOME
adb shell input swipe 600 700 100 700 250
adb shell dumpsys gfxinfo org.ztmetro.start framestats
adb shell dumpsys gfxinfo org.ztmetro.start | sed -n '1,25p'

# Which layer to measure, then its present latency (desired vs actual present)
adb shell dumpsys SurfaceFlinger --list | grep -iE 'ztmetro|settings|launcher'
adb shell dumpsys SurfaceFlinger --latency '<exact layer name from --list>'
```

For `--latency`, the three columns are desired present time, actual present time, and frame
ready time (ns). `actual - desired` is the lateness; a 60 Hz frame that lands within one
vsync is ~16.7 ms. Report p50/p90/p99 and the missed-vsync count, not one frame.

**Launch times**

```sh
# cold Settings
adb shell am force-stop com.android.settings
adb shell am start -W -n com.android.settings/.Settings
# warm Settings: repeat immediately, 10x, record TotalTime/WaitTime
# Start
adb shell am force-stop org.ztmetro.start
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
# Action Centre
adb shell am force-stop org.ztmetro.actioncentre
adb shell am start -W -n org.ztmetro.actioncentre/.ActionCentreActivity
```

`TotalTime` includes the activity's first frame; `WaitTime` includes the launch sequence.
Report both, median of 10, and keep the screen state identical (unlocked, same page).

**ART / GC / events**

```sh
adb shell logcat -b events -c
# run the launch/scroll sequence
adb shell logcat -b events -d > events.txt
grep -E 'am_activity_launch_time|am_proc_start|am_kill|am_low_memory|lmkd|dex2oat|am_pss' events.txt | tail -80
# ART GC lines, if this user build logs them
adb shell logcat -d -s art | grep -iE 'gc|heap' | tail -40
```

The event buffer reliably gives `am_activity_launch_time`, `am_proc_start`, `am_kill`,
`am_low_memory`, and `am_pss`; GC lines may be absent on a user build. If they are absent,
do not add a permanent debug prop to force them — use a Perfetto/`atrace` run or a
userdebug build for the GC question `[E]`.

**CPU / top**

```sh
adb shell top -b -n 1 | head -30
# during a 30 s Settings scroll, in another shell:
adb shell top -b -n 1 -d 5 | grep -E 'system_server|systemui|settings|ztmetro|surfaceflinger'
```

**PSI / zram / vmstat**

```sh
adb shell cat /proc/pressure/memory
adb shell cat /proc/pressure/cpu
adb shell cat /proc/pressure/io
adb shell zramctl
adb shell cat /proc/swaps
adb shell cat /proc/sys/vm/swappiness
adb shell cat /sys/block/zram0/disksize
adb shell cat /sys/block/zram0/comp_algorithm
adb shell cat /proc/vmstat | grep -E 'pswp|pgmajfault|compact'
adb shell getprop | grep -E 'ro.config.low_ram|ro.lmk|dalvik.vm.heap|pm.dexopt'
```

**Dexopt state**

```sh
adb shell dumpsys package dexopt | grep -A5 -E 'org.ztmetro.start|org.ztmetro.actioncentre|com.android.settings|com.android.systemui'
adb shell cmd package compile -h        # authoritative on this build
# after an update:
adb shell cmd package compile -m speed-profile -f org.ztmetro.start
adb shell cmd package compile -m speed -f org.ztmetro.actioncentre
adb shell cmd package compile --reset org.ztmetro.start   # rollback
```

### 5.4 Expected baselines for a 2 GB 720p phone `[E]`

| Metric | Expected / acceptable | Red flag | "Improved" means |
|---|---|---|---|
| `MemTotal` | 1.80–1.90 GB | — | — |
| Used RAM after boot | 1.05–1.25 GB | >1.35 GB | −100 MB or more |
| Free/cached after 30 min | 500–750 MB | <300 MB | +100 MB |
| `systemui` PSS | 80–140 MB | >180 MB | −20 MB |
| `settings` PSS | 60–100 MB | >140 MB | −10 MB |
| `org.ztmetro.start` PSS | 55–95 MB | >150 MB | −15 % or −10 MB |
| `org.ztmetro.actioncentre` PSS | 25–45 MB | >70 MB | −10 MB |
| Settings janky frames | 5–12 % | >15 % | −30 % relative, or <5 % absolute |
| Settings p95 frame | 16–24 ms | >33 ms | <16 ms |
| HOME janky frames | 3–8 % | >12 % | <5 % |
| SurfaceFlinger p90 lateness | 16.7–33 ms | >50 ms | −20 % |
| Settings cold `TotalTime` | 700–1,200 ms | >1,800 ms | −15 % median |
| Start cold `TotalTime` | 400–800 ms | >1,200 ms | −15 % median |
| Action Centre cold `TotalTime` | 250–500 ms | >800 ms | −15 % median |
| PSI `memory full avg60` idle | <0.5 | >2 | −50 % |
| PSI `memory full avg60` under load | <5 | >10 sustained | −50 % |
| zram used at idle | 100–400 MB | >700 MB | same workload, lower |
| zram compression ratio | ≥2.0 | <1.5 | — |
| lmkd kills / 24 h | 0–2 | >10 | 0–2 |
| `top` system_server idle | <25 % CPU | >40 % | −20 % |
| `top` Settings during scroll | <35 % CPU | >60 % | −30 % |

These are starting points from typical 2–4 GB AOSP-class devices and the memory arithmetic
in [05-ram-budget.md](05-ram-budget.md), **not** measurements from this phone. Replace them
after the first run; keep the table in the perf evidence file, not in this doc. PSS is
shared between processes: compare packages one at a time and use `dumpsys meminfo`'s total
`Used RAM`, not the sum of package PSS.

### 5.5 A/B rules

- **One change per A/B.** Baseline → change → reboot (for props) → same workload → 10
  runs. Do not combine P1 and P2 in one "before/after".
- **LSPosed A/B:** disable the module scope in LSPosed manager, reboot, measure; re-enable,
  reboot, measure. A module toggle is not a fair A/B if the process was not restarted.
- **Dexopt A/B:** measure before compile, compile, force-stop, measure; note that the first
  run after compile may be cold in a different way; discard the first run.
- **Thermal A/B:** if the phone is hot, stop. A 10 % difference on an A53 can be thermal,
  not code.
- **Report:** store raw `dumpsys`/`logcat`/CSV under `repo/evidence/perf/<date>/` if the
  lead wants them in-tree; do not paste raw logs into this doc. Include build fingerprint,
  boot time, ambient/thermal, app set, and the exact commands.

---

## 6. What NOT to do

| Tempting tweak | Why not on this phone | Source |
|---|---|---|
| `pm compile -m everything` for all apps | Not an officially supported filter; compiles more code, expands odex/vdex and dirty pages, does not fix main-thread jank | [ART configuration](https://source.android.com/docs/core/runtime/configure) lists only verify/quicken/speed/speed-profile |
| `speed` for every system app | Code expansion and disk cost; system server is already speed-profile on Android 14+ | ART configuration |
| Disabling zram / adding eMMC swap | Removes the only compressed overflow; eMMC swap adds wear and latency; 2 GB will OOM earlier | [zram docs](https://docs.kernel.org/admin-guide/blockdev/zram.html), [lmkd README](https://android.googlesource.com/platform/system/memory/lmkd/+/3cc43abcbf1675ded0fd88d62bbc06901beb581e/README.md) |
| Hand-editing `ro.lmk.*` without PSI/lmkd data | `low`/`critical` select which process dies; wrong values kill SystemUI or thrash | lmkd README |
| `ro.hw_timeout_multiplier` | Emulator-only; hides ANRs and slows boot on real hardware | [Build.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/os/Build.java) |
| `persist.sys.*` animation-scale props | No supported property; WMS reads `Settings.Global` only | [WindowManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerService.java) |
| `debug.hwui.renderer=opengl` | On Android 16 anything except `skiavk` falls back to SkiaGL; the legacy OpenGL pipeline is gone. Placebo | [Properties.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.cpp) |
| Forcing `skiavk` as a default | Adreno 308 Vulkan may be slower/less stable; only an A/B experiment, not a boot prop | Properties.cpp `[E]` for this GPU |
| Leaving `debug.hwui.skia_tracing_enabled` or `debug.hwui.trace_gpu_resources` on | Both are documented as slow; they are tracing tools, not performance modes | [Properties.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.h) |
| "Free RAM" cleaners, `drop_caches`, task killers | Evict page cache and warm code; next launch pays I/O; no net win | `[E]`; Android memory model |
| `ro.config.low_ram=true` without screenshots/A-B | It trims features; can remove graphics paths and change app behaviour; not a free RAM multiplier | [ActivityManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/app/ActivityManager.java) |
| Disabling animations to 0 as a "performance fix" | Removes feedback, does not improve frame time; use 0.5 if latency is the complaint | WMS/`Settings.Global` |
| Adding more font weights to "look better" | Each face adds disk and potential glyph-cache cost; 8 is already a full family | [font module](../artifacts/ztmetro-font-1.1.0.zip) |
| Removing RROs to "save RAM" | 5 overlays are 69 KB of resources, no process; visual regression for <5 MB | §3.1 |
| 60 Hz / refresh-rate "tricks" | The panel is 60 Hz; no supported higher rate; forcing modes can break composition | device spec |

---

## 7. Sources and commits

**LineageOS / device**

- `frameworks_base` `lineage-23.2` = `797e43244b96c6a0dff5c81b21c4d4be665b082e`:
  <https://github.com/LineageOS/android_frameworks_base/commit/797e43244b96c6a0dff5c81b21c4d4be665b082e>
- `packages/apps/Settings` `lineage-23.2` = `e1b8cd2dde2edd8c513362efc55a770270dce899`:
  <https://github.com/LineageOS/android_packages_apps_Settings/commit/e1b8cd2dde2edd8c513362efc55a770270dce899>
- `Mi8937` device tree (parent of `Mi8917`), `lineage-23.2` — fstab, vendor.prop,
  init.xiaomi.device.rc (no zram/low-RAM override):
  <https://github.com/LineageOS/android_device_xiaomi_Mi8937/tree/lineage-23.2>
- LineageOS wiki, `Mi8917` variants:
  <https://wiki.lineageos.org/devices/Mi8917/>
- LineageOS Changelog 30 (LineageOS 23 / Android 16):
  <https://lineageos.org/Changelog-30/>

**AOSP Android 16 (tag `android-16.0.0_r3`)**

- ART configuration (four compiler filters, dexpreopt categories):
  <https://source.android.com/docs/core/runtime/configure>
- ART Service configuration:
  <https://source.android.com/docs/core/runtime/configure/art-service>
- Package manager configuration (deprecated; `speed-profile` defaults, no-profile=verify):
  <https://source.android.com/docs/core/runtime/configure/package-manager>
- Go defaults (low_ram, speed-profile, lmkd, heap):
  <https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/product/go_defaults_common.mk>
  <https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/board/go_defaults_common.prop>
- `ActivityManager.isLowRamDeviceStatic` / `isHighEndGfx`:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/app/ActivityManager.java>
- `Build.HW_TIMEOUT_MULTIPLIER` ("should not be set" on real devices):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/os/Build.java>
- HWUI renderer selection and tracing props:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.cpp>
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/hwui/Properties.h>
- Animation scales read from `Settings.Global`:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerService.java>
- `wm disable-blur` writes `DISABLE_WINDOW_BLURS`:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerShellCommand.java>
- lmkd properties and defaults (README at commit `3cc43abc…`):
  <https://android.googlesource.com/platform/system/memory/lmkd/+/3cc43abcbf1675ded0fd88d62bbc06901beb581e/README.md>
- lmkd overview:
  <https://source.android.com/docs/core/perf/lmkd>
- Cached apps freezer:
  <https://source.android.com/docs/core/perf/cached-apps-freezer>
- Kernel zram:
  <https://docs.kernel.org/admin-guide/blockdev/zram.html>
- Android Go (2 GB class, disabled features):
  <https://developer.android.com/guide/topics/androidgo>
- UI performance / `gfxinfo` / `framestats`:
  <https://developer.android.com/training/testing/performance>

**Hooking**

- LSPlant API (hook/unhook, inline deoptimisation):
  <https://lsposed.org/LSPlant/namespacelsplant.html>
- LSPosed (Zygisk/Riru, LSPlant):
  <https://github.com/LSPosed/LSPosed>

**Same-device prior art (anecdote, not a controlled measurement)**

- IMatvey, "Настройка zRam на LineageOS с помощью модуля Magisk" — Redmi Note 5A
  (ugglite), LineageOS 23.2, 75 % zram, lz4, swappiness 100:
  <https://imatvey.xyz/software/android/nastrojka-zram-na-lineageos-s-pomoshhyu-modulya-magisk/>

---

## 8. Open questions for the device pass

1. What are the live `zram0` size, algorithm, swappiness, and `ro.config.low_ram`? `[?]`
2. Is the Settings jank concentrated in the first 1.6 s (the walk chains) or in the first
   frame (inflation)? `gfxinfo framestats` + debug pass timing answer this. `[?]`
3. What is Start's actual PSS and first-frame time? `[?]`
4. Is the Action Centre notification listener granted? If not, P5's 20–40 MB is not
   available until it is. `[?]`
5. Does `ro.config.low_ram=true` remove a visible feature on this build (blur, recents,
   graphics effects)? Screenshot before/after; if yes, weigh the RAM against the UX. `[?]`
6. Does `skiavk` help or hurt on Adreno 308? A/B on one app only; do not ship a boot prop
   without numbers. `[?]`

Report format for the lead: one line per metric with baseline → after → delta → verdict,
plus the raw command outputs in `repo/evidence/perf/<date>/`. Do not update this doc with
guesses; update it only when the numbers exist.

---

## 7. Pass 2 — code is in, numbers are not

Written 2026-11-16. This section supersedes the "fix" sketches in §2.2, §3.3, §3.4 and P1/P2/P5
where they differ; the reasoning in §1–§6 still stands. The executable protocol and the
artifact hashes are in [`../evidence/perf-pass2/`](../evidence/perf-pass2/README.md).
**Nothing in this section is a device measurement.** `[E]` stays an estimate; `[?]` is what
the phone must still answer.

### 7.1 What shipped

| Surface | Change | Expected `[E]` | Proof available now |
|---|---|---|---|
| Action Centre | **Not merged.** Both accessibility services already live in one process, so a merge saves no PSS and would break the boot re-grant, the static checks and the unverifiable strip z-order. Instead: two receivers (screen/user-present always, data only while screen-on), network+telephony callbacks released on screen off, `onCapabilitiesChanged` bursts coalesced over 400 ms with **one** active-network read shared by Wi-Fi and cellular, every strip setter rejects an unchanged state, panel clock minute-aligned and change-gated, panel `onDraw` allocation-free, keyguard safety net 5 s and skipped while the screen is off | no strip work while the screen is off; unchanged battery/Wi-Fi/signal ⇒ no frame; panel frames stop allocating strings | build-time checks + code |
| Start | `onResume` no longer reloads `PackageManager` or rebuilds tile views: package broadcasts mark the list dirty, and `tileSignature`/`appSignature` gate `setTiles`/`setApps`. Notification listener released 2 min after `onPause` (`requestUnbind`), rebound on resume (`requestRebind`) | one fewer hitch on HOME; 5–15 MB less retained Start footprint once the listener is released; tile views survive a resume | build-time checks + `StartPerf` log lines |
| Settings module | One one-shot `OnGlobalLayoutListener` row pass instead of the 250/700 ms chain, `OnHierarchyChangeListener` for rows attached later, `WeakReference` recyclers, per-view style tag (`metro_style`) + epoch so layout params are written at most once, reflection cached per class | 2 walks + 1 row pass per open, no repeated `setLayoutParams`; less main-thread layout work in the first 1.6 s | build-time checks + `ztmetro-header` pass log |

### 7.2 What the phone still has to answer `[?]`

1. PSS before/after for all four surfaces, and whether the Start listener release actually
   drops the process to cached importance within two minutes.
2. Settings `gfxinfo` 90th/95th and janky % after the single row pass.
3. That the strip still hides under the keyguard, passes touches through and repaints on a
   real state change — the reason the merge was refused.
4. That a released listener rebinds and repopulates live tiles on the next resume.

### 7.3 Protocol and gates

`repo/evidence/perf-pass2/protocol.sh` implements §5 as 3 runs per label with medians:
`run before` → install the pass-2 APKs → `run after` → `compare before after`. It records
Settings (cold open + 3 swipes) and HOME `gfxinfo`, PSS of SystemUI/Settings/Action
Centre/Start/SurfaceFlinger, idle PSI, cold starts and the module logcat.

Acceptance (after, median of 3): Settings 90th **< 40 ms**, p95 < 33 ms, janky < 8 %;
Start janky < 5 %; `zt_sum` (Start + Action Centre + Settings) **< 90 MB**; Start + Action
Centre < 61 MB; PSI `full avg60` < 5; Settings cold `TotalTime` < 1100 ms, Start < 800 ms,
Action Centre < 500 ms. One change per A/B; do not install two of the three APKs and call
it a pass result.

### 7.4 Corrections to earlier text

- §3.4 said `StatusStripView` ticks every 15 s while attached. That was the defect, not the
  design: it is now minute-aligned, change-gated and stopped when the panel is not visible.
- §2.2 item 2 suggested `OnGlobalLayoutListener` *or* `postOnAnimation`; pass 2 uses the
  one-shot layout listener plus the hierarchy listener, and no delayed chain at all.
- P5's "merge onto one listener" for notifications is unchanged in intent but is now
  implemented as a *lifecycle release* (the system service cannot be merged across two
  packages without a shared provider); see §2.2 of the evidence report.
