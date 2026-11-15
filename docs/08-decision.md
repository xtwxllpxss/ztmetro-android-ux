# 08 — Decision: LineageOS+ztmetro UX vs ztOS+ATL vs Waydroid+ztmetro shell

Labels: `[F]` verified fact, `[E]` estimate, `[?]` must be tested/decided.

## 1. The options

| # | Option | What the user sees | Where Android apps run | Room for ztmetro look |
|---|---|---|---|---|
| **A** | **LineageOS 23.2 (microG) + ztmetro UX layer** (launcher APK, theme RRO, helper apps; no ROM build) | ztmetro Start screen / tiles / app list / palette; Android's status bar, shade, keyguard, recents remain | native Android (highest compatibility) | home screen and colours/fonts; not system chrome |
| **B** | **Self-built LineageOS 23.2 (own keys) + ztmetro SystemUI fork + privileged launcher/IME/font/overlay** | ztmetro home **and** WP-like status bar/shade/keyguard/nav/QS | native Android | nearly all of the phone's UI around apps |
| **C** | **ztOS (Arch + Wayland ztmetro) + ATL** (Android Translation Layer, native per-app) | the original ztmetro UI | one ART process per APK, no Android system_server | maximal (the shell *is* the OS) |
| **D** | **ztOS + Waydroid container + ztmetro shell** around container windows | ztmetro shell; Android in a window | full Android in LXC container | maximal shell, Android apps look Android inside the container |
| **E** | Own shell over Android kernel, Sailfish/Ubuntu-Touch-style (Halium/Lomiri/Alien-Dalvik analogue) | custom | depends on an Android-app compatibility layer | maximal | 

## 2. Facts that drive the comparison

- **Device support (A/B):** `Mi8917` (Redmi 4A / 5A / Note 5A Lite / Y1 Lite) is in
  LineageOS 23.0's "moved from 22.2" device list — i.e. officially supported on
  Android 16 at the 23.0 launch `[F]` (LineageOS Changelog 30). LineageOS-for-microG
  publishes `lineage-23.2` builds for `Mi8917` (last seen 2026-08-12; 1.1 GiB ZIP,
  20 MiB boot, 31 MiB recovery, 3.5 GiB total folder) `[F]`. LineageOS 23.x is
  "based on Android 16 QPR0" `[F]`.
- **Android 16 requirements:** Android 16 QPR0 requires Linux kernel 5.4+ for new
  devices `[F]`; the `Mi8917` LineageOS port already exists, so this is the ROM's
  business, not ours.
- **SystemUI is system-image-only:** platform-signed, privileged `system_ext` app with
  hidden-API and WMShell dependencies `[F]` (see 01/06). Official builds are signed
  with Lineage's private keys; a third party cannot platform-sign a replacement `[F]`.
- **2 GB reality:** Android Go's minimum-RAM table tops out at 2 GB (Android 13) and Go
  disables PiP, system alert windows, split-screen, live wallpapers `[F]`. A normal
  LineageOS 23.2 costs ~0.9–1.2 GB base + 0.09–0.18 GB stock launcher `[E]`; the
  ztmetro-style Stage-1 shell should cost ≤ 0.15–0.25 GB `[E]`.
- **ATL (C):** no Android `system_server`; apps run against a reimplemented Java
  framework + bionic shims; camera/sensors/telephony/push/Play Services are stubs `[F]`
  (project feasibility notes); packaging for glibc/Arch is not upstream `[F]`.
- **Waydroid (D):** full Android in a container (images ~2.4 GiB on disk, container
  idle ~37 MiB but a session is ~0.9–1.4 GB `[F/E]`), which on a 1.8 GiB device does
  not coexist with a browser + shell `[E]`.
- **Sailfish/UT (E):** both build a custom shell over Android-compatible hardware;
  Sailfish's Android support (Alien Dalvik/AppSupport) is proprietary and absent from
  community ports `[F]`; Ubuntu Touch uses Halium + Lomiri and runs Android apps via
  Waydroid `[F]`. There is no Halium/Sailfish port for `Mi8917` in this project's
  scope `[?]`, and even if there were, the Android-app layer would be Waydroid-class
  (RAM) or proprietary.
- **Prior art:** we found no Android project that replaced the full system UI without a
  ROM build; the market splits into "launcher/theme" (APK) and "ROM with custom
  framework/SystemUI" (Graphene/Lineage forks, OEM skins). See
  [`09-existing-projects.md`](09-existing-projects.md) for the evidence table.

## 3. Score matrix

Weights reflect the project's stated goal: working daily phone + ztmetro look + 2 GB.

| Criterion (weight) | A | B | C | D |
|---|---|---|---|---|
| App compatibility (25%) | 5 (native Android) | 5 | 2 (ATL stubs) | 4 (full Android, integration gaps) |
| ztmetro look fidelity (25%) | 3 (home only) | 4.5 (home + chrome) | 5 (the original UI) | 4.5 (shell), 2 for app windows |
| RAM fit on 2 GB (20%) | 4 (base heavy, shell light) | 3.5 (fork can trim, but base heavy) | 3.5 | 1.5 (container + shell + browser doesn't fit) |
| Time to first usable phone (15%) | 5 (days–weeks) | 2 (months) | 2 (ongoing) | 2.5 |
| Maintenance risk (10%) | 4 (stock updates + our APKs) | 2.5 (own ROM rebase per release) | 2 (two stacks to maintain) | 2 (container + shell + hw quirks) |
| Reversibility (5%) | 5 | 3 | 3 | 3 |
| **Weighted (out of 5)** | **4.2** | **3.7** | **3.2** | **2.8** |

`[E]` The numbers are judgement, not measurements; they exist so the trade-offs are
explicit and can be re-scored after Stage 0 measurements.

## 4. What each option costs

| Option | Effort | Calendar `[E]` | Recurring cost |
|---|---|---|---|
| A | S–M: launcher app (Kotlin/Compose, ~1–2 kLOC), RRO, notification listener, testing | 2–4 weeks to a usable daily phone | app updates only; LineageOS-for-microG updates stay available while the device is supported `[F]` |
| B | L: build infra (200 GB, 64 GB RAM host `[F]`), own keys, SystemUI fork, privileged launcher preinstall | 2–4 months to a convincing demo | rebase per LineageOS release; build/CI; signing key custody `[F]` |
| C | L–XL: ATL compatibility matrix, Arch packaging, HAL gaps | months, open-ended | two OS stacks; app compatibility regressions |
| D | M–L: Waydroid session integration into ztmetro, hw quirks | weeks–months | container updates + RAM ceiling; not a daily driver on 2 GB `[E]` |

## 5. Recommendation

**Run A now, gate B on evidence, keep C as the Linux-native fallback.**

1. **Stage 0 — measure before promising (days).**
   Install LineageOS-for-microG `lineage-23.2` on `Mi8917` (keeping the current ztOS
   install recoverable), then run the RAM protocol in
   [`05-ram-budget.md`](05-ram-budget.md). Decide with numbers, not guesses.
2. **Stage 1 — ztmetro UX as APKs (2–4 weeks).**
   Launcher (Start/tiles/app list/search/own Action Centre), palette/typography
   tokens, notification-listener live tiles, optional root RRO for system accent/font.
   Success = the user can live with it daily; measure launcher PSS and time-to-first-
   frame; keep stock SystemUI untouched.
3. **Stage 2 — decide on SystemUI.**
   Only if the user says "the Material shade/keyguard/nav bar breaks the experience"
   after living with Stage 1: self-build LineageOS 23.2 with your own keys and fork
   SystemUI (see [`06-systemui-standalone.md`](06-systemui-standalone.md)). Budget
   months and treat it as a product fork, not a mod.
4. **Keep C alive as a branch.**
   ztOS + ATL remains the right answer only if the priority is "a Linux phone with
   ztmetro and just a few Android apps". Its app compatibility and ecosystem cost is
   real `[F]`.
5. **Waydroid (D) is a lab tool on this hardware, not the daily base** `[E]` — the RAM
   math does not close for shell + browser + container on 1.8 GiB.

## 6. What would flip the decision

- Stage 0 measures **> 1.35 GB used at idle / > 10 lmkd kills a day** on LineageOS
  23.2 microG → Android base is too heavy → B is also in trouble; reconsider a Go-style
  config or stay on ztOS.
- The user needs **Play Integrity / banking apps / Google apps** → only a real
  Android base (A/B) is viable; microG may be insufficient for some apps `[?]`.
- The user actually wants **the original ztmetro experience on Linux with
  convergence** → C is the answer; accept the app gap.
- A **working systemless SystemUI swap** turns out to be reliable on this device `[?]`
  → it would shortcut B's build cost, but it is not a plan until demonstrated on a
  sacrificial unit.
- SystemUI's real RAM cost on LineageOS 23.2 turns out to be **> 200 MB PSS** `[?]` →
  a custom SystemUI could be a RAM win, making B more attractive.

## 7. One-paragraph answer

There is no way to get "ztmetro around apps" on Android without owning the system
image, and no way to keep 2 GB comfortable while running a second Android stack
(Waydroid) or a translation layer (ATL) as the daily base. The cheapest path to a
*working* phone with a strong WP-like identity is **LineageOS 23.2 + microG + a
ztmetro-style launcher/theme layer**; the only path to full WP-like system chrome is
a **self-built LineageOS with your own keys and a SystemUI fork**. ztOS + ATL is the
right product if and only if Linux-native ztmetro matters more than Android apps.

Continue with [`09-existing-projects.md`](09-existing-projects.md).
