# ztmetro-android-ux

Research notes for a strategic fork: **replace the Android UX/UI of a LineageOS-based
phone with a ztmetro-style (Lumia / Windows Phone Metro) shell**, instead of running
Android apps through a native translation layer on top of a Linux/Wayland OS.

Target hardware class: Xiaomi Redmi Note 5A Lite (`ugglite`, Qualcomm MSM8917,
Adreno 308, **2 GB RAM**, 16 GB eMMC, 720×1280). LineageOS for microG publishes
`lineage-23.2` builds for it (Android 16), so the Android 16 + microG base is real,
not hypothetical.

> **Status (2026-11-16): the stack is installed on a real device; screen verification is
> partial.**
> LineageOS-for-microG 23.2 on `ugglite` with Magisk + Zygisk + LSPosed/Vector: a
> framework RRO, a SystemUI RRO, a Settings RRO, a system font module (Selawik 1.0.0 →
> Open Sans 1.1.0), the Start launcher, a Settings header module, a keyguard module, a
> status-strip RRO pair and a Metro Action Centre APK. **3 of 10 blocks are verified on
> screen, 1 is partial and 6 are host-verified only** — the coverage table, the exact
> resource names, every command and the artifact sizes are in
> [`docs/18-coverage-and-build.md`](docs/18-coverage-and-build.md).
> Labels: `[F]` = verified fact with a source link, `[E]` = estimate or engineering
> judgement, `[?]` = not verified / needs an on-device test. Sources are collected in
> [`sources.md`](sources.md).

---

## Quickstart — rebuild and reapply the stack

One-time base: flash **LineageOS-for-microG 23.2 for `Mi8917`** (recovery → format data
→ `adb -d sideload`), then install **Magisk**, enable **Zygisk**, install
**LSPosed/Vector** (v1.10.2+; Vector v2.2 was used). The gated command list, including
the fastboot checks and the `super_empty.img` warning, is
[`evidence/device-flash/PLAN.md`](evidence/device-flash/PLAN.md) §3; the stack order is
[`docs/18`](docs/18-coverage-and-build.md) §3.

```sh
# build (SDK 36 / build-tools 36.0.0 + JDK 17; scripts SSH to hermes-ubuntu if no SDK)
repo/scripts/build-overlay-framework.sh --out /tmp/ztmetro-fw   # android RRO
repo/scripts/build-overlay-settings.sh                          # Settings RRO
repo/scripts/build-overlay-sysui.sh                             # SystemUI RRO
repo/scripts/build-overlay-status.sh                            # status-strip RRO pair
repo/scripts/build-overlay-module.sh                            # all 5 overlay APKs in one zip
repo/scripts/build-font-module.sh                               # Open Sans Magisk module
repo/scripts/build-header-module.sh                             # Settings header (LSPosed)
repo/scripts/build-launcher.sh                                  # Start
repo/scripts/build-actioncentre.sh                              # Action Centre
repo/scripts/build-keyguard-module.sh                           # lock screen (LSPosed)

# install the two Magisk modules, then reboot
adb push repo/artifacts/ztmetro-overlays-1.0.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-overlays-1.0.0.zip'
adb push repo/artifacts/ztmetro-font-1.1.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-font-1.1.0.zip'
adb reboot

# install the APKs (enable both LSPosed modules in the manager, scopes below)
adb install -r repo/evidence/launcher-v3/ztmetro-start.apk
adb shell cmd package set-home-activity org.ztmetro.start/.StartActivity
adb install -r repo/evidence/settings-header/ztmetro-settings-header.apk  # scope: Settings
adb install -r repo/evidence/actioncentre/ztmetro-actioncentre.apk
adb install -r repo/evidence/keyguard-module/ztmetro-keyguard.apk         # scope: SystemUI

# apply the runtime overlays without a reboot
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.settings'
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.systemui'
adb shell am force-stop com.android.settings
adb shell su -c 'killall com.android.systemui'
```

Verify each step with the table in [`docs/18`](docs/18-coverage-and-build.md) §3.5, and
read §6 before trusting any status claim: several hand-off reports predate the device
pass, and the SystemUI RRO's **square-tile** pass is not visible on screen (the 2025
Compose shade ignores the tile-radius resources; only the 4 dp gaps took effect).

**What each piece is, and what it does not reach:** framework accent/typography and
control styles are inherited by stock apps; Settings and SystemUI override their own
theme resources and need their own overlays; a new Settings header or lock-screen clock
needs an LSPosed hook; Start and the Action Centre are ordinary APKs. The per-block
table with evidence links is [`docs/18`](docs/18-coverage-and-build.md) §1, the
inheritance/override split is §2.

---

## TL;DR (research findings)

1. **The "200–300 GB of AOSP sources" fear is justified.** AOSP's own build
   requirements are 400 GB free disk (250 GB checkout + 150 GB build) and 64 GB RAM
   `[F]`. You cannot build `SystemUI` from "just the SystemUI folder": it is a
   platform-signed, privileged `system_ext` app that compiles against hidden platform
   APIs, `framework-res` internals and `WindowManager-Shell` `[F]`. See
   [`docs/06-systemui-standalone.md`](docs/06-systemui-standalone.md).
2. **A very large part of the ztmetro look is reachable without touching the system
   image at all**: a home-screen launcher APK (Start screen, tiles, app list), an RRO
   theme overlay (palette/fonts/dimensions where resources are overlayable), plus a
   small set of accessibility/overlay helper apps. See
   [`docs/04-replacement-layers.md`](docs/04-replacement-layers.md) and
   [`docs/03-theming-rro.md`](docs/03-theming-rro.md).
3. **The parts that make a phone feel like Windows Phone — notification shade/Action
   Centre, lock screen, navigation bar, recents, QS tiles, power menu — are owned by
   `com.android.systemui`, i.e. by the system image.** They cannot be replaced by an
   APK. Replacing them requires either (a) a self-built LineageOS with your own
   signing keys and a SystemUI fork, or (b) a systemless (Magisk) RRO/hook path whose
   exact reach is measured in [`docs/10a`](docs/10a-systemless-injection.md),
   [`docs/10b`](docs/10b-overlay-and-hook-facts.md) and
   [`docs/18`](docs/18-coverage-and-build.md).
4. **RAM verdict for 2 GB:** Android 16 is designed to fit in 2 GB only in its
   Android Go configuration (Go's minimum RAM for Android 13 is 2 GB `[F]`), and Go
   *disables* split-screen, PiP, system alert windows, live wallpapers and shortcuts
   `[F]`. A realistic budget for a ztmetro-like shell on top of a normal (non-Go)
   LineageOS 23.2 is **~200–350 MB PSS for the shell**, with ~600–900 MB left for
   apps after Android base + microG `[E]`. See
   [`docs/05-ram-budget.md`](docs/05-ram-budget.md).
5. **Recommendation (staged, and now partly executed):**
   - **Stage 0** — install `lineage-23.2` (microG) for `Mi8917`, measure. **Done.**
   - **Stage 1 (2–4 weeks):** ship the UX as **launcher APK + RRO theme + helper
     apps** — the only path that gives a WP-like home screen *and* working Android
     apps without a ROM build. **This is the delivered stack.**
   - **Stage 2 (2–4 months, only if Stage 1 proves the need):** self-build
     LineageOS 23.2 with **your own platform keys** and fork SystemUI for the
     shade/keyguard/nav/QS. Still not started; a Magisk SystemUI swap remains a
     non-option ([`docs/06`](docs/06-systemui-standalone.md) §3).
   - Keep ztOS + ATL/Waydroid as a fallback branch, not as the main line
     ([`docs/08-decision.md`](docs/08-decision.md)).
6. **Nobody has replaced Android's full system UX without owning the ROM.** The prior
   art splits into APK-level home screens (including convincing Windows-Phone cosplay
   launchers such as MetroWinLauncher, GPL-3.0) and ROM-level products
   (MIUI/One UI/Light Phone/Punkt/Mudita) that ship their own system image. Details:
   [`docs/09-existing-projects.md`](docs/09-existing-projects.md).

---

## What is in this repository

| Path | Contents |
|---|---|
| [`docs/01-android-ux-anatomy.md`](docs/01-android-ux-anatomy.md) | What Android's UX is made of: SystemUI packages, WMShell, Launcher3/Quickstep, framework windows, permissions and UIDs |
| [`docs/02-window-shell-decorations.md`](docs/02-window-shell-decorations.md) | WindowManager/Shell, insets, edge-to-edge (Android 15/16), app decorations, the "top strip", global immersive |
| [`docs/03-theming-rro.md`](docs/03-theming-rro.md) | RRO/overlayable, `cmd overlay`, Material You/Monet dynamic colour, fonts, icon packs, what a third party can theme |
| [`docs/04-replacement-layers.md`](docs/04-replacement-layers.md) | Which UX element can be changed by APK / accessibility / overlay / RRO / privileged app / system image / framework patch |
| [`docs/05-ram-budget.md`](docs/05-ram-budget.md) | 2 GB RAM budget: Android base, low-RAM/Go properties, lmkd, ART heap, freezer, zram, measurements |
| [`docs/06-systemui-standalone.md`](docs/06-systemui-standalone.md) | Can SystemUI be built and injected into an existing ROM? Dependencies, signing, options A/B/C, effort/risk |
| [`docs/07-ztmetro-mapping.md`](docs/07-ztmetro-mapping.md) | ztmetro elements mapped onto Android components, and what does not transfer |
| [`docs/08-decision.md`](docs/08-decision.md) | LineageOS + ztmetro UX vs ztOS + ATL vs Waydroid: decision matrix and recommendation |
| [`docs/09-existing-projects.md`](docs/09-existing-projects.md) | Prior art: launchers, theming layers, minimalist ROMs, alternative OSes, WP cosplay |
| [`docs/10a-systemless-injection.md`](docs/10a-systemless-injection.md) | Magisk/Zygisk/RRO mechanics, `cmd overlay` policy, fonts, verified boot/OTA, LSPosed on Android 16 |
| [`docs/10b-overlay-and-hook-facts.md`](docs/10b-overlay-and-hook-facts.md) | Resource-by-resource overlay facts for stock Settings/SystemUI, with file:line |
| [`docs/13-root-and-injection-setup.md`](docs/13-root-and-injection-setup.md) | Working root/injection notes for this device |
| [`docs/14-ux-roadmap.md`](docs/14-ux-roadmap.md) | Pattern → mechanism → risk ledger, four waves, acceptance test per item |
| [`docs/15-boot-recovery.md`](docs/15-boot-recovery.md) | Recovering the phone when a module/overlay breaks boot |
| [`docs/16-ux-gap-analysis.md`](docs/16-ux-gap-analysis.md) | Screenshot-based gap analysis against WP 8.1 / ztmetro references |
| [`docs/18-coverage-and-build.md`](docs/18-coverage-and-build.md) | **Delivery document:** mechanism table, inheritance vs overrides, exact rebuild/reapply commands, costs, porting checklist, contradictions |
| [`evidence/`](evidence/) | Per-artifact evidence: reports, build logs, `aapt2` dumps, screenshots, `evidence/coverage/` index |
| [`overlay/`](overlay/), [`modules/`](modules/), [`scripts/`](scripts/) | RRO sources, Magisk modules, build scripts |
| [`launcher/`](launcher/), [`actioncentre/`](actioncentre/), [`settings/`](settings/), [`sysui/`](sysui/) | Start, Action Centre, Settings header and keyguard module sources |
| [`sources.md`](sources.md) | Every link used, grouped by topic, with verification status |
| [`artifacts/ztmetro-tokens.css`](artifacts/ztmetro-tokens.css) | Design tokens (palette, type ramp, metrics) transcribed for Android/Launcher use |
| [`artifacts/launcher-skeleton/`](artifacts/launcher-skeleton/README.md) | Non-building Kotlin skeleton of a Metro-style home app |

## Reading order

1. [`docs/18`](docs/18-coverage-and-build.md) — what shipped, what was verified, how to
   rebuild it.
2. [`docs/06-systemui-standalone.md`](docs/06-systemui-standalone.md) — the question
   that decides the strategy.
3. [`docs/04-replacement-layers.md`](docs/04-replacement-layers.md) — what is possible
   without a ROM build.
4. [`docs/05-ram-budget.md`](docs/05-ram-budget.md) — whether 2 GB survives the plan.
5. [`docs/09-existing-projects.md`](docs/09-existing-projects.md) — who already tried,
   and with what depth.
6. [`docs/08-decision.md`](docs/08-decision.md) — the recommendation and its costs.

## Scope and non-goals

- In scope: Android 16 (LineageOS 23.x / LineageOS for microG) on `Mi8917`
  (`ugglite`), 2 GB RAM; UX replacement depth; RAM budget; SystemUI build/inject
  options; prior art; and the **delivered stack** with its rebuild/reapply commands.
- Out of scope: kernel/device-tree work, Play Services licensing, per-device secrets.
  No device identifiers, no absolute host paths.
- The flash/root recipe is documented from the recorded plan
  ([`evidence/device-flash/PLAN.md`](evidence/device-flash/PLAN.md)); it is destructive
  and is not a supported updater.

## Confidence and freshness

Started 2026-11 (research snapshot); the delivery and verification pass is 2026-11-16.
AOSP sources were read at tag `android-16.0.0_r3`; LineageOS facts were taken from
`lineage-23.2` branches and the LineageOS-for-microG download server. Several evidence
reports were written before the device pass and are stale — the authoritative current
state, including the known contradictions, is
[`docs/18`](docs/18-coverage-and-build.md) §6.

## Licence

Documentation: **CC BY 4.0**. Code snippets and the artefacts: **MIT**.
See [`LICENSE`](LICENSE).
