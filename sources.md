# Sources

Every link used in this repository, grouped by topic. Verification column:

- **OK** — fetched successfully (HTTP 200/30x) while writing (2026-11);
- **SRC** — read directly from the AOSP tree at the stated tag (source of truth);
- **SEARCH** — seen in a web-search result snippet and/or the page blocked automated
  fetching from this host (bot protection / TLS), but the resource exists;
- **HINT** — referenced as a lead, not independently verified.

LineageOS facts were cross-checked against `lineage-23.x` branches and the
LineageOS-for-microG download server.

## 1. Android platform sources (AOSP tag `android-16.0.0_r3`)

SystemUI:

- `packages/SystemUI/AndroidManifest.xml` — package, shared UID, permissions:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/packages/SystemUI/AndroidManifest.xml> — SRC
- `packages/SystemUI/Android.bp` — `SystemUI` app module, `SystemUI-core` deps,
  WMShell/`monet`/SettingsLib, `platform_apis`, `certificate: "platform"`,
  `system_ext_specific`, privapp requirement:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/packages/SystemUI/Android.bp> — SRC
- SystemUI source package tree (statusbar, shade, qs, keyguard, navigationbar, volume,
  globalactions, recents, notifications, theme, monet, wmshell, …):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/packages/SystemUI/src/com/android/systemui/> — SRC
- `ThemeOverlayController.java` (dynamic colour overlay application, categories,
  `THEME_CUSTOMIZATION_OVERLAY_PACKAGES`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java> — SRC
- `frameworks/base/data/etc/com.android.systemui.xml` (priv-app allowlist):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/data/etc/com.android.systemui.xml> — SRC
- `frameworks/base/data/etc/Android.bp` (`privapp_whitelist_com.android.systemui`,
  `system_ext_specific`, `sub_dir: "permissions"`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/data/etc/Android.bp> — SRC

WMShell / window manager:

- `frameworks/base/libs/WindowManager/Shell/Android.bp` (module `WindowManager-Shell`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/WindowManager/Shell/Android.bp> — SRC
- WMShell source tree (transition, splitscreen, pip, pip2, recents, desktopmode,
  windowdecor, draganddrop, onehanded, …):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/libs/WindowManager/Shell/src/com/android/wm/shell/> — SRC
- `WindowManagerService.java` (observes `Settings.Global.POLICY_CONTROL`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerService.java> — SRC
- WMS package listing (`DisplayPolicy`, `InsetsPolicy`, `WindowManagerShellCommand`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/> — SRC
- `core/res/res/layout/screen_simple.xml` and `screen_title.xml` (app decor layouts):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/res/res/layout/> — SRC
- `core/java/android/provider/Settings.java` (`POLICY_CONTROL = "policy_control"`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/provider/Settings.java> — SRC
- `services/core/java/com/android/server/om/OverlayManagerShellCommand.java`
  (`cmd overlay` commands; root required for `fabricate`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/om/OverlayManagerShellCommand.java> — SRC

Launcher3 / Quickstep:

- `packages/apps/Launcher3/Android.bp` — `Launcher3`, `Launcher3QuickStep`,
  `Launcher3Go`, `overrides`, `privileged`, `system_ext_specific`, `platform_apis`:
  <https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-16.0.0_r3/Android.bp> — SRC
- `packages/apps/Launcher3/quickstep/AndroidManifest.xml` — `TouchInteractionService`,
  `RecentsActivity`, permissions:
  <https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-16.0.0_r3/quickstep/AndroidManifest.xml> — SRC

Memory / runtime:

- `core/jni/AndroidRuntime.cpp` — `dalvik.vm.heapstartsize` (default 4m),
  `heapsize` (default 16m), `heapgrowthlimit`, `heapminfree`, `heapmaxfree`,
  `heaptargetutilization`, `foreground-heap-growth-multiplier`:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/jni/AndroidRuntime.cpp> — SRC
- `build/make/target/product/go_defaults_common.mk` — `ro.config.low_ram=true`,
  `speed-profile`, minimize debug info:
  <https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/product/go_defaults_common.mk> — SRC
- `build/make/target/board/go_defaults_common.prop` — lmkd Go values,
  `dalvik.vm.heapgrowthlimit=128m`, `heapsize=256m`, `pm.dexopt.shared=quicken`:
  <https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r3/target/board/go_defaults_common.prop> — SRC
- `core/java/android/app/ActivityManager.java` (`isLowRamDeviceStatic` reads
  `ro.config.low_ram`):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/core/java/android/app/ActivityManager.java> — SRC
- `services/core/java/com/android/server/am/ActivityManagerConstants.java`
  (max cached processes / device-config overrides):
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/am/ActivityManagerConstants.java> — SRC

## 2. Android documentation

- Build requirements — 400 GB free (250 checkout + 150 build), 64 GB RAM:
  <https://source.android.com/docs/setup/start/requirements> — OK
- Building Android (targets, module builds, `m`):
  <https://source.android.com/docs/setup/build/building> — OK
- Low memory killer daemon (`ro.lmk.*`, `ro.config.low_ram`, thresholds):
  <https://source.android.com/docs/core/perf/lmkd> — OK
- Cached apps freezer (Android 11+, cgroup v2, `activity_manager_native_boot use_freezer`):
  <https://source.android.com/docs/core/perf/cached-apps-freezer> — OK
- ART runtime configuration (`dalvik.vm.*`, dex2oat options):
  <https://source.android.com/docs/core/runtime/configure> — OK
- Runtime resource overlays / overlayable / OverlayConfig:
  <https://source.android.com/docs/core/runtime/rros> — OK
- Android (Go edition), minimum RAM table (2 GB for Android 13), disabled features:
  <https://developer.android.com/guide/topics/androidgo> — OK
- Android 15 behaviour changes (edge-to-edge enforcement, transparent bars):
  <https://developer.android.com/about/versions/15/behavior-changes-15> — OK
- Android 16 behaviour changes (edge-to-edge opt-out removed, orientation/resizability
  on ≥600 dp):
  <https://developer.android.com/about/versions/16/behavior-changes-16> — OK
- Dynamic Color / Material You mechanics (wallpaper → 5 keys → 13 tones):
  <https://developer.android.com/develop/ui/views/theming/dynamic-colors> — OK
- `WindowManager.LayoutParams` — `TYPE_APPLICATION_OVERLAY` is below status bar/IME;
  window types:
  <https://developer.android.com/reference/android/view/WindowManager.LayoutParams> — OK
- `Manifest.permission#SYSTEM_ALERT_WINDOW`:
  <https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW> — OK
- Doze and App Standby:
  <https://developer.android.com/training/monitoring-device-state/doze-standby> — OK
- App Standby Buckets:
  <https://developer.android.com/topic/performance/appstandby> — OK
- Kernel ZRAM documentation:
  <https://docs.kernel.org/admin-guide/blockdev/zram.html> — OK
- Verified boot / verity (context for system-partition replacement):
  <https://source.android.com/docs/security/features/verifiedboot/verified-boot> — OK
- Magisk internal details (magic mount, module layout, dynamic partitions):
  <https://topjohnwu.github.io/Magisk/details.html> — OK

## 3. LineageOS, microG, the device

- LineageOS Changelog 30 — LineageOS 23 based on Android 16 QPR0; `Mi8917` in the
  "moved from 22.2" device list:
  <https://lineageos.org/Changelog-30/> — OK
- LineageOS device wiki (`Mi8917` variants: Redmi 4A / 5A / Note 5A Lite / Y1 Lite):
  <https://wiki.lineageos.org/devices/Mi8917/> — OK
- LineageOS signing builds — test-keys vs official keys, `vendor/lineage-priv/keys`,
  APEX re-signing, key migration:
  <https://wiki.lineageos.org/signing_builds> — OK
- LineageOS for microG — project site/FAQ:
  <https://lineage.microg.org/> — OK
- LineageOS for microG downloads for `Mi8917` — `lineage-23.2` builds (Android 16),
  ZIP ~1.1 GiB, folder ~3.5 GiB, latest seen 2026-08-12:
  <https://download.lineage.microg.org/Mi8917/> — OK
- microG project:
  <https://microg.org/> — OK
- microG GmsCore sources:
  <https://github.com/microg/GmsCore> — OK

## 4. Prior-art projects (docs/09)

Launchers / app-layer shells:

- MetroWinLauncher (GPL-3.0, live tiles + Action Center + Metro lock screen):
  <https://github.com/developerkenshi-coder/MetroWinLauncher> — OK
- Launcher 10 (Play Store, proprietary, Windows-Mobile style):
  <https://play.google.com/store/apps/details?id=com.nfwebdev.launcher10> — SEARCH
- Factor Launcher Reboot (WP7-inspired, no licence):
  <https://github.com/valkriaine/Factor_Launcher_Reboot> — OK
- MetroLauncherAndroid (Windows 10 Mobile Metro UI, no licence):
  <https://github.com/lorddie/MetroLauncherAndroid> — OK
- Vanta Launcher (Apache-2.0, Metro-inspired):
  <https://github.com/ftwsourav/vanta-launcher-public> — OK
- mosaik_home (AGPL-3.0, WP-inspired):
  <https://github.com/MTRX8/mosaik_home> — OK
- Olauncher (GPL-3.0, minimal AF / Light-Phone-like):
  <https://github.com/tanujnotes/Olauncher> — OK
- Niagara Launcher (commercial minimal launcher):
  <https://niagaralauncher.com/> — OK

Theming / SystemUI modification:

- Substratum (RRO/OMS theme manager, GPL-3.0, repo archived 2022):
  <https://github.com/substratum/substratum> — OK
- Iconify (GPL-3.0, Android 12+ SystemUI customisation via root/LSPosed):
  <https://github.com/Mahmud0808/Iconify> — OK
- ColorBlendr (GPL-3.0, Material You palette overrides):
  <https://github.com/Mahmud0808/ColorBlendr> — OK
- LSPosed (GPL-3.0, Xposed-compatible hooking framework):
  <https://github.com/LSPosed/LSPosed> — OK
- "Windows Phone 10" Xperia theme (2015, dead, theme-engine era):
  <https://juggly.cn/archives/166886.html> — SEARCH
- Repainter (dynamic-colour theming app, discontinued) — community discussion:
  <https://android.stackexchange.com/questions/265500/does-the-colorblendr-application-have-the-same-feature-as-repainter> — SEARCH (page blocks this host; content seen via search snippet)

Minimalist ROMs / products:

- Light Phone — Light Phone III prototypes (LightOS runs on top of Android; prototype
  "LightOS app"):
  <https://www.thelightphone.com/blog/light-iii-first-prototypes> — SEARCH
  (direct fetch blocked from this host; page content seen via search)
- Light Phone product site:
  <https://thelightphone.com/> — OK (30x)
- Punkt MP02 device database entry — AOSP 8.1, no app installation:
  <https://www.esper.io/devices/mp02-4g-mobile-phone> — OK
- Mudita Kompakt — Android-based, Google-free, e-ink:
  <https://www.notebookcheck.net/Minimalist-e-ink-phone-These-updates-could-make-the-Mudita-Kompakt-even-better.1059787.0.html> — OK
- Mudita (vendor):
  <https://mudita.com/> — OK

Alternative OSes:

- Sailfish OS (Wikipedia) — own UX, Alien Dalvik proprietary/absent from community
  ports:
  <https://en.wikipedia.org/wiki/Sailfish_OS> — OK
- Sailfish OS (vendor):
  <https://sailfishos.org/> — OK
- Ubuntu Touch core UT OS — Halium + Lomiri + Android apps via Waydroid:
  <https://ubports.com/projects/core-ut-os-54> — OK
- postmarketOS:
  <https://postmarketos.org/> — OK
- postmarketOS generic MSM89x7 device page (the SoC family of the target device):
  <https://wiki.postmarketos.org/wiki/Generic_MSM89x7_(qcom-msm89x7)> — OK
- LMODroid (lightweight LineageOS-based ROM project):
  <https://github.com/LMODroid> — OK
- KaiOS (Wikipedia) — Firefox OS fork, HTML5 apps:
  <https://en.wikipedia.org/wiki/KaiOS> — OK
- KaiOS Technologies:
  <https://www.kaiostech.com/> — OK

## 5. Notes on confidence

- All AOSP statements in the docs are backed by the source links above at
  `android-16.0.0_r3`; LineageOS-specific build details (`LineageManifest.xml`,
  `vendor.lineage.powershare`) come from the `lineage-23.2` branch of
  `LineageOS/android_frameworks_base`.
- Estimates (`RAM` figures, build times, effort) are explicitly labelled `[E]` in the
  documents and must be replaced by on-device measurements (protocol in
  `docs/05-ram-budget.md`).
- Items marked `[?]` in the documents (Magisk SystemUI signature acceptance,
  `policy_control` behaviour on LineageOS 23.2, SystemUI overlayables, zram defaults)
  are the open questions this research could not close without the device.
