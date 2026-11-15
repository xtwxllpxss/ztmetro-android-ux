# ztmetro-android-ux

Research notes for a strategic fork: **replace the Android UX/UI of a LineageOS-based
phone with a ztmetro-style (Lumia / Windows Phone Metro) shell**, instead of running
Android apps through a native translation layer on top of a Linux/Wayland OS.

Target hardware class: Xiaomi Redmi Note 5A Lite (`ugglite`, Qualcomm MSM8917,
Adreno 308, **2 GB RAM**, 16 GB eMMC, 720×1280). LineageOS for microG publishes
`lineage-23.2` builds for it (Android 16), so the Android 16 + microG base is real,
not hypothetical.

> Status: **research only, nothing implemented, no device touched.**
> All statements are labelled:
> `[F]` = verified fact with a source link, `[E]` = estimate or engineering judgement,
> `[?]` = not verified / needs an on-device test. Sources are collected in
> [`sources.md`](sources.md).

---

## TL;DR

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
   signing keys and a SystemUI fork, or (b) a fragile systemless (Magisk) APK swap
   whose signature/AVB implications are not verified for `lineage-23.2` `[?]`.
4. **RAM verdict for 2 GB:** Android 16 is designed to fit in 2 GB only in its
   Android Go configuration (Go's minimum RAM for Android 13 is 2 GB `[F]`), and Go
   *disables* split-screen, PiP, system alert windows, live wallpapers and shortcuts
   `[F]`. A realistic budget for a ztmetro-like shell on top of a normal (non-Go)
   LineageOS 23.2 is **~200–350 MB PSS for the shell**, with ~600–900 MB left for
   apps after Android base + microG `[E]`. See
   [`docs/05-ram-budget.md`](docs/05-ram-budget.md).
5. **Recommendation (staged):**
   - **Stage 0 (days):** install `lineage-23.2` (microG) for `Mi8917`, measure with
     `dumpsys meminfo` / PSI / zram before writing any UI code.
   - **Stage 1 (2–4 weeks):** ship the UX as **launcher APK + RRO theme + helper
     apps**. This is the only path that gives a WP-like home screen *and* working
     Android apps without a ROM build.
   - **Stage 2 (2–4 months, only if Stage 1 proves the need):** self-build
     LineageOS 23.2 with **your own platform keys** and fork SystemUI for the
     shade/keyguard/nav/QS. Do not attempt a Magisk SystemUI swap as the primary plan.
   - Keep ztOS + ATL/Waydroid as a fallback branch, not as the main line
     ([`docs/08-decision.md`](docs/08-decision.md)).
6. **Nobody has replaced Android's full system UX without owning the ROM.** The prior
   art splits into APK-level home screens (including convincing Windows-Phone cosplay
   launchers such as MetroWinLauncher, GPL-3.0) and ROM-level products
   (MIUI/One UI/Light Phone/Punkt/Mudita) that ship their own system image. The
   "own shell + Android app layer" architecture exists (Sailfish, Ubuntu Touch,
   postmarketOS) but its Android-app layer is proprietary or container-based — and a
   container does not fit the 2 GB budget together with a shell. Details:
   [`docs/09-existing-projects.md`](docs/09-existing-projects.md).

---

## What is in this repository

| Path | Contents |
|---|---|
| [`docs/01-android-ux-anatomy.md`](docs/01-android-ux-anatomy.md) | What Android's UX is made of: SystemUI packages, WMShell, Launcher3/Quickstep, framework windows, permissions and UIDs |
| [`docs/02-window-shell-decorations.md`](docs/02-window-shell-decorations.md) | WindowManager/Shell, insets, edge-to-edge (Android 15/16), app decorations, how the "top strip" appears, global immersive |
| [`docs/03-theming-rro.md`](docs/03-theming-rro.md) | RRO/overlayable, `cmd overlay`, Material You/Monet dynamic colour, fonts, icon packs, what a third party can actually theme |
| [`docs/04-replacement-layers.md`](docs/04-replacement-layers.md) | Which UX element can be changed by APK / accessibility / overlay / RRO / privileged app / system image / framework patch |
| [`docs/05-ram-budget.md`](docs/05-ram-budget.md) | 2 GB RAM budget: Android base, low-RAM/Go properties, lmkd, ART heap, freezer, zram, measurements and expected numbers |
| [`docs/06-systemui-standalone.md`](docs/06-systemui-standalone.md) | **Main answer:** can SystemUI be built and injected into an existing ROM? Dependencies, signing, options A/B/C, effort/risk |
| [`docs/07-ztmetro-mapping.md`](docs/07-ztmetro-mapping.md) | ztmetro elements (Start, tiles, status bar, shade, lock, pivot headers, fonts) mapped onto Android components, and what does not transfer |
| [`docs/08-decision.md`](docs/08-decision.md) | LineageOS + ztmetro UX vs ztOS + ATL vs Waydroid + ztmetro shell: decision matrix and recommendation |
| [`docs/09-existing-projects.md`](docs/09-existing-projects.md) | Prior art: launchers, theming layers, minimalist ROMs, alternative OSes, Windows Phone cosplay projects — depth, licence, status, reusability |
| [`sources.md`](sources.md) | Every link used, grouped by topic, with verification status |
| [`artifacts/ztmetro-tokens.css`](artifacts/ztmetro-tokens.css) | Design tokens (palette, type ramp, metrics) transcribed for Android/Launcher use — a proof artefact, not a build |
| [`artifacts/launcher-skeleton/`](artifacts/launcher-skeleton/README.md) | Non-building Kotlin skeleton of a Metro-style home app, showing which Android APIs the Stage 1 launcher would use |

## Reading order

1. `docs/06-systemui-standalone.md` — the question that decides the strategy.
2. `docs/04-replacement-layers.md` — what is possible without a ROM build.
3. `docs/05-ram-budget.md` — whether 2 GB survives the plan.
4. `docs/09-existing-projects.md` — who already tried, and with what depth.
5. `docs/08-decision.md` — the recommendation and its costs.

## Scope and non-goals

- In scope: Android 16 (LineageOS 23.x / LineageOS for microG) on `Mi8917`
  (`ugglite`), 2 GB RAM; UX replacement depth; RAM budget; SystemUI build/inject
  options; prior art.
- Out of scope: kernel/device-tree work, flashing instructions, Play Services
  licensing, per-device secrets. No device identifiers, no absolute host paths.
- The current project device runs a Linux/Wayland OS (ztOS) with a native
  ztmetro compositor; this repository deliberately describes the *Android* branch
  without publishing that device's private state.

## Confidence and freshness

Written 2026-11 (research snapshot). AOSP sources were read at tag
`android-16.0.0_r3`; LineageOS facts were taken from `lineage-23.x` branches and the
LineageOS-for-microG download server. Android moves: re-check
[`sources.md`](sources.md) before committing to a plan.

## Licence

Documentation: **CC BY 4.0**. Code snippets and the artefacts: **MIT**.
See [`LICENSE`](LICENSE).
