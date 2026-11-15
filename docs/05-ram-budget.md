# 05 — RAM budget on a 2 GB device: Android 16, low-RAM knobs, and the ztmetro shell

Target: `Mi8917` (`ugglite`) — MSM8917, 2 GB RAM, 16 GB eMMC, 720×1280, Android 16
(LineageOS 23.x / LineageOS for microG `lineage-23.2`) `[F]` for the ROM availability.
Labels: `[F]` fact, `[E]` estimate, `[?]` unverified / measure on device.

> All numbers below marked `[E]` are engineering estimates from typical Android
> behaviour, not measurements from this device. The measurement protocol in §6 is the
> deliverable that must replace them before any go/no-go decision.

## 1. The arithmetic

- 2 GB nominal → `MemTotal` is typically ~1.8 GiB because kernel/reserved memory is
  not available to userspace `[F]` (it is `MemTotal` in `/proc/meminfo`; the exact
  value is device-specific `[?]`).
- Android's own memory accounting (`dumpsys meminfo`) reports: Total RAM, Free RAM
  (cached PSS + cached kernel + free), Used RAM, Lost RAM, and a ZRAM section.
  "Free RAM" includes page cache, so it overstates what an app can actually get;
  watch **PSI** (`/proc/pressure/memory`) and lmkd kills instead `[F]` (`dumpsys
  meminfo` format is documented in AOSP/`dumpsys` output `[E]`).
- With ZRAM (compressed swap) the device gets ~1.5–2.5× the swapped pages as
  effective capacity, at a CPU/latency cost. On a 4×Cortex-A53 this is the difference
  between "browser tab survives" and "OOM kill", but it does not make 2 GB a 3 GB
  device `[E]`.

## 2. What Android 16 itself costs (`[E]`, typical 2–4 GB AOSP-class device)

| Process | Typical PSS | Notes |
|---|---|---|
| `system_server` | 180–320 MB | AMS/WMS/PMS + all system services; grows with users/accounts |
| `surfaceflinger` | 40–80 MB | compositor + buffers accounting |
| `zygote` / `zygote64` | 15–30 MB | shared pages dominate |
| `com.android.systemui` | 70–140 MB | status bar + shade + keyguard + QS + WMShell code |
| `com.android.launcher3` (QuickStep) | 90–180 MB | launcher + recents + gesture integration |
| telephony/phone, media, audioserver, HALs | 60–150 MB | device-dependent |
| kernel + vendor reserved | 150–300 MB | not visible as process PSS |
| **idle "Android base"** | **~0.9–1.2 GB** | leaves ~0.6–0.9 GB of 1.8 GiB for apps |

`[E]` A **ztmetro-style launcher** replacing `Launcher3QuickStep` should be cheaper
than the stock QuickStep launcher because it does not host recents/gesture code; a
Compose/View launcher with tiles typically sits at **80–150 MB PSS**, plus a live-tile
`NotificationListenerService` in the same process (~10–20 MB). Target budget for the
whole Stage-1 shell: **≤ 250 MB PSS on top of Android base**, i.e. the phone should
still show ~500–700 MB free after boot.

### 2.1 ROM flavours on 2 GB: microG, /e/OS, LMODroid

| Base | What it adds/removes vs LineageOS | RAM delta vs stock GMS | Availability for `Mi8917` |
|---|---|---|---|
| **LineageOS for microG** | LineageOS + microG GmsCore instead of Google Play Services/Play Store (no Google apps by default); includes the signature-spoofing patch microG needs `[F]` | GMS typically runs several heavyweight processes; microG is 1 process and ~40–90 MB PSS when used `[E]`; expect a **100–250 MB saving** vs a GApps build `[E]` | **Yes** — `lineage-23.2` builds exist `[F]` |
| **/e/OS** | LineageOS-for-microG derivative + eFoundation services/apps (cloud, launcher, store) | roughly microG-level for Google services, plus vendor apps `[E]` | No official `Mi8917` build found `[?]` |
| **LMODroid** | LineageOS-based "lightweight" Android project (LMODroid 6.x is Android 14/15 generation) `[E]` | no verified RAM measurements for this device class; project positioning is "lightweight" but the framework is LineageOS `[E]` | No `Mi8917` build found `[?]` |
| **GApps LineageOS** | LineageOS + Google Play Services/Play Store | highest baseline: Play Services, Play Store, Google account services `[E]` | build-your-own (MindTheGapps etc.) `[E]` |

`[E]` For a 2 GB device, **microG is the only sensible Google-services choice**; a full
GApps stack plus a WP-like shell is unlikely to be a pleasant daily phone.

### 2.2 What a lighter launcher/SystemUI saves

- **Launcher:** stock `Launcher3QuickStep` includes recents/gesture code and a large
  icon/theme pipeline; a bespoke launcher without Quickstep can save **50–100 MB PSS**
  `[E]` and removes the stock launcher's background work.
- **SystemUI:** a custom/forked SystemUI can save memory only by removing features
  (blur, media projection, extra overlays, big Compose hierarchies). Realistic win for
  a *simpler* SystemUI: **20–80 MB PSS** `[E]`, at the cost of a ROM fork; a heavier
  WP-like UI can also cost *more* than stock. Do not count on SystemUI as the RAM
  lever; the launcher and microG choices matter more.
- Removing stock `Launcher3QuickStep` entirely is possible on a self-built ROM; on a
  stock LineageOS you can only disable it per user (`pm disable-user`) `[E]`.

## 3. Android Go: the official 2 GB configuration

Android (Go edition) is "part of the main Android platform" and is optimised for
entry-level RAM; the official minimum-RAM table tops out at **2 GB for Android 13
(API 33)** `[F]`. Features disabled by default on Go edition `[F]`:

- picture-in-picture;
- **system alert windows (draw over other apps)**;
- **split-screen / multi-window**;
- live wallpapers; multi-display; launcher shortcuts/deep shortcuts; VR mode.

That is a direct conflict with a "shell as overlay apps + Action Centre" design and
with multitasking UX, so **do not blindly turn the device into a Go build**. The
useful part of Go is its property/profile set, which can be applied selectively.

### 3.1 AOSP Go defaults (verified source)

`build/make/target/product/go_defaults_common.mk` `[F]`:

```make
PRODUCT_VENDOR_PROPERTIES += ro.config.low_ram=true
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed-profile   # less RAM/space than speed
PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD := false
PRODUCT_MINIMIZE_JAVA_DEBUG_INFO := true
```

`build/make/target/board/go_defaults_common.prop` `[F]`:

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

`ro.config.low_ram` is also the documented lmkd switch ("Specify if the device is a
low-RAM or high-performance device", default `false`) `[F]`. In framework code it is
what `ActivityManager.isLowRamDeviceStatic()` returns; apps use
`ActivityManager.isLowRamDevice()` to reduce their own memory use `[F]`.

## 4. The knobs that matter, and what they actually do

| Knob | Where | Effect | Risk |
|---|---|---|---|
| `ro.config.low_ram=true` | `/vendor` or `/system` prop (build.prop / vendor.prop) | marks device low-RAM; affects lmkd strategy and app behaviour (`isLowRamDevice`) `[F]` | makes apps trim features; do not expect Go's full behaviour without the Go product config |
| `ro.lmk.*` | vendor prop | lmkd kill strategy: `critical_upgrade`, `upgrade_pressure`, `downgrade_pressure`, `kill_heaviest_task`, `kill_timeout_ms`, `use_minfree_levels`, `use_psi` `[F]` | wrong values → thrash or kills of the UI |
| `dalvik.vm.heapgrowthlimit` / `heapsize` | system/vendor prop | per-app Java heap cap / max heap; AOSP defaults parsed in `AndroidRuntime.cpp` (`heapstartsize` default 4m, `heapsize` default 16m; product overrides are expected) `[F]` | too low → apps OOM/GC-thrash; too high → each app eats RAM |
| `dalvik.vm.heapminfree`, `heapmaxfree`, `heaptargetutilization`, `foreground-heap-growth-multiplier` | props | GC/heap growth tuning `[F]` | needs measurement, not guesswork |
| `pm.dexopt.*` | props / `device_config` | compiler filter and dexopt policy; `speed-profile` + `quicken` trade CPU/disk for RAM `[F]` | slower cold starts if overdone |
| Cached apps freezer | `device_config activity_manager_native_boot use_freezer true` (Android 11+, cgroup v2) `[F]` | keeps cached apps in RAM off CPU; Android 14+ freezes 10 s after cached, performs GC/compaction, may swap anon pages to ZRAM `[F]` | frozen apps can't do work; must be enabled/tested per device |
| max cached processes | `device_config activity_manager max_cached_processes N` (default is high; Android 16 `ActivityManagerConstants` default `1024` with device-config override `[F]`) | lower = fewer cached apps kept | lower = more cold starts/app kills |
| ZRAM | kernel + init (device-specific) | compressed swap; typical Android low-RAM setup ≈ 50% of RAM with lz4/lzo-rle `[E]`; kernel interface documented in `Documentation/admin-guide/blockdev/zram.rst` `[F]` | too large → thrash; see §5 |
| `vm.swappiness` | sysctl | how aggressively anon pages go to ZRAM; Android commonly uses 100 for ZRAM `[E]` — verify on device | depends on ZRAM size |
| Doze / App Standby | system defaults; `cmd deviceidle`, `am set-standby-bucket` `[F]` | background CPU/wakeup reduction, not an anon-RAM saver | — |
| `ro.control_privapp_permissions` | prop | `log` instead of `enforce` helps bring-up of modified system apps; **security-degrading** `[F]` | do not ship |

Measurement helpers are in §6. Android Go's disabled features (PiP/split-screen) are
**not** implied by `ro.config.low_ram` alone; they come from the Go product
configuration `[E]`. So: apply the memory properties listed above **without** adopting
a full Go product, unless multitasking is expendable.

## 5. ZRAM: how to think about it on 2 GB

- ZRAM is compressed swap in RAM; it does not reserve memory (only caps it), and it
  is much faster than eMMC swap. Kernel documentation:
  `Documentation/admin-guide/blockdev/zram.rst` `[F]`.
- A sane starting point for a 1.8 GiB device is **50–60% of RAM as ZRAM cap with
  lz4/lzo-rle**, `swappiness≈100`, and no writeback to eMMC `[E]`.
- Do not judge ZRAM by "free RAM"; judge it by: swap used at the same workload, PSI
  `memory full avg60`, `pswpin/pswpout` per second, `lmkd` kill count, and the user's
  perception of app switches `[E]`.
- Too small ZRAM = OOM kills; too large = thrashing (A53 decompression latency).
  Measure, don't guess.

## 6. Measurement protocol (read-only, ~1 hour, the real deliverable)

Run after a fresh boot, then after 30 min of normal use, then under a scripted load
(launcher + browser + messaging):

```sh
adb shell cat /proc/meminfo
adb shell dumpsys meminfo -a | sed -n '1,120p'
adb shell dumpsys meminfo com.android.systemui
adb shell dumpsys meminfo com.android.launcher3
adb shell cat /proc/pressure/memory
adb shell zramctl
adb shell cat /proc/swaps
adb shell cat /proc/sys/vm/swappiness
adb shell cat /proc/sys/vm/page-cluster
adb shell getprop dalvik.vm.heapgrowthlimit
adb shell getprop dalvik.vm.heapsize
adb shell getprop ro.config.low_ram
adb shell getprop ro.lmk.kill_heaviest_task
adb shell device_config get activity_manager_native_boot use_freezer
adb shell device_config get activity_manager max_cached_processes
adb shell dumpsys activity lru
adb shell dumpsys activity processes | grep -E 'oom adj|ProcessRecord' | head -40
adb shell logcat -d | grep -iE 'lmkd|lowmemorykiller|killing'   # or: dmesg | grep -i kill
adb shell cat /proc/vmstat | grep -E 'pswp|pgmajfault|compact'
```

`procrank` is available on userdebug builds for a process-level RSS/PSS ranking `[E]`.
A boot log with `dumpsys meminfo` before/after installing the ztmetro shell is the
minimum evidence for the RAM claim.

### 6.1 Expected numbers to check against (replace with measurements)

| Metric | Acceptable for Stage 1 `[E]` | Red flag |
|---|---|---|
| Idle Used RAM after boot | ≤ 1.15 GB of ~1.83 GB | > 1.35 GB |
| ZRAM swap used at idle | ≤ 250 MB | > 500 MB at idle |
| PSI `memory full avg60` at idle | < 1–2 | > 5 sustained |
| lmkd kills in 24 h normal use | 0–2 | > 10 / day |
| SystemUI PSS | 70–140 MB | > 200 MB |
| Launcher PSS (ours) | 80–150 MB | > 250 MB |
| Free RAM (dumpsys "Free RAM") after 30 min | ≥ 550 MB | < 300 MB |
| Cold app start (calculator/settings) | ≤ 1.5 s | > 3 s |

## 7. What the ztmetro shell can afford

Stage 1 (launcher APK + notification listener + optional RRO overlay):

- launcher process: target ≤ 150 MB PSS, hard ceiling 200 MB (single activity, no
  WebView, no heavy image pipeline; use `RecyclerView`/Compose with lazy lists,
  downscaled icons, no full-screen blur).
- notification listener: keep it in the launcher process or a tiny separate app
  (≤ 25 MB), cache only counts/titles, not bitmaps.
- overlay helper (banners/status mimic): ≤ 15 MB, no permanent foreground service.
- **Do not** ship a WebView-based shell: WebView adds 100–200 MB `[E]`.
- Avoid live wallpaper, blur (`disable-blur` exists as a `wm` command for experiments
  `[F]`), and multiple always-on overlay windows.

Stage 2 (SystemUI fork) can *reduce* RAM (remove stock features) but adds the
maintenance cost of a ROM fork; it is not needed for the Stage-1 decision.

## 8. Bottom line

- Android 16 on 2 GB is **possible but tight**: Go's own cap is 2 GB, and non-Go
  LineageOS carries ~0.9–1.2 GB of base plus a 90–180 MB stock launcher `[E]`.
- The ztmetro look must be **cheap by construction**: a 100–150 MB launcher plus
  colour/font overlays, not a second compositor.
- The stock QuickStep launcher can be replaced by a lighter home app; the freed
  50–100 MB is the shell's budget `[E]`.
- Everything must be measured on the real `lineage-23.2` build; numbers above are
  placeholders until the §6 protocol is run.

Continue with [`06-systemui-standalone.md`](06-systemui-standalone.md).
