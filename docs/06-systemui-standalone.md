# 06 — Can SystemUI be built separately and injected into an existing ROM?

The main question: *"200–300 GB of sources is scary; can we build only SystemUI and
put it into an existing LineageOS install?"*

Short answer:

> **Not as a standalone project, and not into an officially-signed ROM.** SystemUI is
> a platform-signed, privileged `system_ext` application compiled with hidden platform
> APIs, `framework-res` internals, `WindowManager-Shell`, feature-flag libraries and
> LineageOS-specific interfaces. Building it means having the full Android build tree
> and product configuration. Putting your build into a ROM is possible only if the ROM
> is **signed with a key you control** (i.e. your own LineageOS build) — official
> LineageOS builds are signed with the project's private keys, which you do not have.
> The only clean path to a modified SystemUI is: **build the whole ROM yourself with
> your own keys** (the device is officially supported, so this is bring-up-free), then
> iterate on SystemUI incrementally inside that tree.

Labels: `[F]` verified fact, `[E]` estimate, `[?]` must be tested.

## 1. What SystemUI actually depends on (from the build files)

From `packages/SystemUI/Android.bp` at AOSP `android-16.0.0_r3` `[F]`:

- **APK module `SystemUI`**: `platform_apis: true` (compiles against hidden
  `@hide`/`@SystemApi` framework APIs), `system_ext_specific: true`,
  `certificate: "platform"`, `privileged: true`, `required:
  ["privapp_whitelist_com.android.systemui"]`, `static_libs: ["SystemUI-core"]`.
- **`SystemUI-core`**: `android_library` with `static_libs` including
  `WindowManager-Shell`, `SystemUISharedLib`, `SystemUICustomizationLib`,
  `SettingsLib`, `SystemUI-res`, `monet`/`libmonet`, `SystemUIPluginLib`,
  `SystemUI-statsd`, `SystemUI-tags`, `SystemUI-proto`, `PlatformComposeCore`,
  AndroidX/Compose prebuilts, aconfig flag libraries (`com_android_systemui_flags`,
  `android.app.flags-aconfig`), Dagger/Room compiler plugins.
- **`SystemUI-res`**: resource dirs `res-product`, `res-keyguard`, `res`; depends on
  `SystemUISharedLib`, `SettingsLib`, `WindowManager-Shell`.
- **`wmshell_defaults`**: shared build defaults contributed by
  `frameworks/base/libs/WindowManager/Shell`.
- LineageOS's branch additionally carries `additional_manifests: ["LineageManifest.xml"]`
  and `vendor.lineage.powershare-V1-java` (a Lineage HIDL/interface) `[F]` — i.e. the
  Lineage SystemUI is **not** AOSP-only.

The resource side is equally binding: SystemUI uses `@*android:` (framework-res)
internal resources and system-only attributes. `framework-res` itself is built from
`frameworks/base/core/res` — there is no way to compile SystemUI's resources without
the framework resource package for the exact same API level.

**Therefore the minimum "source set" for SystemUI is effectively the full platform
build:** build system (Soong + `build/make`), `frameworks/base` (framework.jar +
framework-res + SystemUI + WMShell + SystemUI libs), `frameworks/libs/systemui`,
prebuilt SDKs/toolchains, and the product/device configuration for `Mi8917`.
AOSP's own requirements for that tree `[F]`:

- **400 GB free disk** (250 GB checkout + 150 GB build) `[F]`;
- **64 GB RAM minimum** for the documented build (a 6-core/64 GB machine takes ~1 h
  for a full build; less RAM/cores takes proportionally longer) `[F]`.

`[E]` Realistically, a LineageOS 23.x tree for one device with ccache is
~120–200 GB checkout and ~50–100 GB out, first full build 2–6 h on a 8-core/32 GB
Linux machine; incremental `SystemUI` rebuild + flash is 5–20 min.

### 1.1 Soong vs Gradle, hidden APIs, and why `adb install` is not an option

- **Soong, not Gradle.** SystemUI is an `android_app` module built by Soong with
  `platform_apis: true` `[F]`; there is no Gradle/Android-Studio build of AOSP
  SystemUI. `android_app` + platform API + `system_ext_specific` + platform
  certificate are all Soong/product concepts that have no Gradle equivalent.
- **"Hidden API bypass" does not apply.** Tools that defeat the hidden-API policy
  (reflection helpers, Xposed/LSPosed hooks, `hiddenapi` flag patching) exist for
  *ordinary apps at runtime*. SystemUI is part of the platform: it is compiled
  against the hidden API (`platform_apis: true`) and runs under a platform shared UID,
  so the compile-time problem is solved by the full tree, not by a bypass.
- **`adb install` cannot create a privileged system app.** It installs into
  `/data/app` as a normal app: no platform signature, no priv-app location, no
  privapp allowlist entry, different UID. Installing it over the existing
  `com.android.systemui` fails on signature/shared-user mismatch (or creates a
  duplicate that the system ignores). To be privileged, an APK must live on a system
  partition (`/system`, `/system_ext`, `/product`, …) **and** be signed with the
  platform key **and** be listed in a privapp allowlist (or run with enforcement
  relaxed — not shippable).
- **What a "standalone build" would need:** the platform `framework.jar` (with
  hidden APIs), `framework-res.apk` resources for the exact API level,
  `WindowManager-Shell` + `SystemUI-shared`/`SettingsLib`/`monet` libraries and the
  aconfig flag libraries — i.e. exactly the artifacts that only a full platform build
  produces. `[E]` A custom Soong/Gradle hybrid that consumes prebuilt platform jars
  is theoretically possible but is not a supported AOSP configuration, and it would
  still need a ROM signed with matching keys to install the result.


## 2. Building inside the full tree: the only supported way

Once the tree exists, the build itself is trivial:

```sh
# in the repo root of the LineageOS 23.x tree, after `source build/envsetup.sh`
breakfast Mi8917        # or: lunch lineage_Mi8917-userdebug
m SystemUI              # builds SystemUI + everything it depends on
# or, after one full build, from packages/SystemUI:  mma
```

Output: `$OUT/system_ext/priv-app/SystemUI/SystemUI.apk`. This is the *same* APK
that would be in the ROM; it is signed with whatever keys the tree is configured with
(test-keys by default for unofficial builds, your own keys if configured, the Lineage
release keys only on the official build servers) `[F]`.

## 3. Signing: the real blocker for injection

Android checks package signatures. SystemUI is platform-signed and declares
`android:sharedUserId="android.uid.systemui"` `[F]`; it holds signature|privileged
permissions and a privapp allowlist `[F]`. A modified SystemUI must therefore:

1. be signed with the **same platform key** as the rest of the ROM, or the ROM must
   have signature verification disabled (an unsupported, insecure modification);
2. keep the same package name/UID and, if it changes requested permissions, the
   privapp allowlist must be updated too (or `ro.control_privapp_permissions=log`
   used during bring-up — not for production).

Who has the key?

| ROM | Platform key | Replace SystemUI? |
|---|---|---|
| Official LineageOS / LineageOS-for-microG build | **LineageOS private release keys** — not distributed `[F]` | **No**, unless signature verification is patched (unsupported) |
| Unofficial build you compiled | **test-keys** (AOSP `build/target/product/security`) unless you configure your own `vendor/lineage-priv/keys` `[F]` | Yes, if you sign your SystemUI with the same test-keys/your keys |
| Build you compiled with your own keys | **yours** (`lineage-priv-template`, includes AVB key) `[F]` | Fully, and you control OTA/AVB |

LineageOS documents both the test-keys → official and official → test-keys migration
paths (`migration.sh official|unofficial`) `[F]` — a sign that signature identity is
the first-class constraint here.

Practical consequence: *"inject into an existing ROM"* is really *"make the ROM
exist in a version signed by me"*. If the goal is a modified SystemUI, plan a
**self-built LineageOS** from the start; the device (`Mi8917`) is officially supported
in LineageOS 23 (Android 16), so there is no device porting work `[F]`.

## 4. Three injection techniques, honestly assessed

### A. Build the full ROM and flash it (clean)

- Build `lineage_Mi8917-userdebug` with your keys, flash `boot`/`recovery`/`system`
  images the usual LineageOS way.
- Modify SystemUI in-tree; every iteration is `m SystemUI` + full OTA zip/reflash (or
  `adb root; adb remount; adb push` on a userdebug build — B).
- **Effort: L** (build infra 1–2 weeks, first UX iteration 2–6 weeks).
- **Risk: medium** (you own the ROM; no unknown-signature trickery; but every
  LineageOS point release is a rebase).
- **This is the recommended path if SystemUI-level UX is required.**

### B. `adb root` + `adb remount` on your own userdebug build

- Only on a ROM that accepts the new signature (your build with test-keys or your
  keys). `adb root` works on userdebug builds; `adb remount` remaps `/system`,
  `/system_ext`, `/product` read-write (it may require disabling verity/AVB first —
  `[?]` for the exact `Mi8917` LineageOS 23 configuration).
- Push `SystemUI.apk` to `/system_ext/priv-app/SystemUI/`, remove stale
  `oat/`/`vdex`/`odex` artifacts, reboot, verify with
  `adb shell dumpsys package com.android.systemui | grep -A2 signatures`.
- **Effort: S** once the build exists. **Risk: medium** (bootloop; keep a recovery
  OTA zip and know `adb shell cmd overlay` / `magisk --remove-modules` equivalents).

### C. Magisk module (systemless APK swap)

- A module places `system/system_ext/priv-app/SystemUI/SystemUI.apk` and Magisk
  bind-mounts it at boot. Magisk's `magic mount` handles dynamic partitions and
  `system_ext` on modern devices `[F]` (Magisk internal details).
- **Magisk does not re-sign APKs and does not disable PackageManager signature
  checks** `[E]`. If the replacement is signed with a different key than the rest of
  the platform, normal Android package/signature rules still apply — for a
  platform-signed shared-UID app this is a boot-risk, not a supported feature `[?]`.
  It *can* work only in the same trust situations as B (ROM built with test-keys/your
  keys) or with signature-verification patches.
- Additional hazards: SELinux labels/timestamps, `oat`/`vdex` mismatches, OTA
  overwriting the mount, Magisk's own detections, and the lack of a supported "reset"
  if SystemUI crashes before boot completes. Keep a second bootable slot/recovery and
  test the module on a sacrificial device first.
- **Effort: M. Risk: high.** Do not make this the primary plan.

### D. RRO-only "SystemUI theming" (no APK swap)

- Only overlayable resources can be changed; if SystemUI exposes no overlayables, a
  third-party overlay cannot legally change them (see 03) `[F]`.
- Can give palette/typography/limited dimens, not layout or behaviour.
- **Effort: S. Risk: low.** Good as a stage-1 complement, insufficient for a
  WP-like shade/keyguard.

## 5. What the modified SystemUI can change (and what it still cannot)

With a ROM build you can implement, inside SystemUI:

- a WP-like status bar (size, clock area, accent strip, notification counter);
- an "Action Centre" shade (layout, cards, background, tiles);
- a custom keyguard (clock typography, tile area, PIN pad);
- a WP-style navigation bar / gesture nav (SystemUI + WMShell);
- QS tiles as a 2-column Metro grid;
- power menu and volume dialog restyling;
- the starting-window splash and (with WMShell edits) transition animations.

Still not possible without touching every app: forcing third-party apps to use
WP-style in-app headers/controls; removing app toolbars; changing app layouts. Those
are app-level, or require framework patching with unknown side effects.

## 6. Sizes, times, effort (summary)

| Item | Value | Confidence |
|---|---|---|
| AOSP full tree requirement | 400 GB free (250 checkout + 150 build), 64 GB RAM | `[F]` |
| LineageOS 23.x tree for one device | ~120–200 GB checkout, ~50–100 GB out | `[E]` |
| First full build (8-core, 32 GB, ccache warm) | 2–6 h | `[E]` |
| Incremental `m SystemUI` | 5–20 min | `[E]` |
| Build infra + first bootable self-signed ROM | 1–2 weeks | `[E]` |
| First WP-like SystemUI iteration | 2–6 weeks | `[E]` |
| Magisk APK swap experiment (sacrificial device) | 1–3 days, uncertain | `[E]`/`[?]` |
| RRO-only theming | days | `[F]` mechanism |

## 7. Decision rules

- **If the requirement is "Start screen, tiles, app list, palette, fonts" → do not
  build SystemUI.** Use the launcher + RRO path (04/03). No 300 GB, no signing
  problem, no fork maintenance.
- **If the requirement is "the phone's system chrome is WP-like" → build the ROM.**
  There is no shortcut that survives contact with Android's signature model.
- **Never** base the project on swapping a platform-signed SystemUI into an
  officially-signed ROM; even if a trick boots once, every OTA and every crash
  recovery becomes a research project.

Continue with [`07-ztmetro-mapping.md`](07-ztmetro-mapping.md).
