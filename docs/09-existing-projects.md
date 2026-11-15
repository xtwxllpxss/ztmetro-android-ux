# 09 — Prior art: projects that deeply change Android's UX (and WP cosplay)

Question behind this document: *"Do projects already exist that strongly change
Android's UX/UI — up to Windows-Phone cosplay — and how deep do they go?"*

Method: each entry was checked against its live page/repository where the network
allowed; licence and last-activity data come from the repository API / store listing.
Status legend: `active` (commits/releases in the last ~12 months), `dormant`
(older, but usable), `dead` (abandoned/removed). Depth legend matches
[`04-replacement-layers.md`](04-replacement-layers.md): **L0** ordinary APK,
**L2** RRO, **L3** root/Magisk, **L4** privileged system app, **L5** ROM/system image.

Verification notes are in [`sources.md`](../sources.md); a few vendor sites block
automated fetching from this host, which is marked there.

Confidence: licence and last-activity data are `[F]` (repository API/store listing);
the depth classification (L0–L5) and "reusable" judgements are `[E]`; entries whose
page could only be seen through a search snippet are marked `SEARCH` in
[`sources.md`](../sources.md) and are `[?]` if a specific claim matters for a decision.

---

## 1. Launchers / app-layer shells (L0 — ordinary APK)

These change the *home experience* only; the system bars, shade, lock screen, recents
and navigation bar remain the ROM's.

| Project | What it changes | Depth | Licence | Status | Reusable for us |
|---|---|---|---|---|---|
| **MetroWinLauncher** (GitHub) — "Windows Phone Start screen on Android: live tiles, Action Center, Metro lock screen" | Start screen with live tiles, an in-app "Action Center" screen, a Metro-style lock-screen-looking screen; no Google services | L0 | **GPL-3.0** | active (2026-11) | **Closest prior art to our Stage 1.** Great reference for tile layout/live-tile data via notification access. GPL-3 means code reuse would force GPL on our launcher |
| **Launcher 10** (Play Store) | Windows-Mobile-styled home screen: live tiles, tile badges, folders, all-apps list, widgets-in-tiles, icon-pack support, in-app purchases | L0 | proprietary | active (store listing updated 2025) | Design reference and a "what users expect" benchmark; no code |
| **Factor Launcher Reboot** (GitHub) | "Modern Android launcher inspired by Windows Phone 7": tile start screen, app list | L0 | none declared (all rights reserved) | dormant (last push 2024-03) | Design/UX reference only |
| **MetroLauncherAndroid** (GitHub) | Windows 10 Mobile-style Metro UI: resizable coloured tiles, live notification previews, media | L0 | none declared | active 2026 | Design reference |
| **Vanta Launcher** (GitHub) | "Brutalist Windows Phone Metro-inspired" launcher | L0 | Apache-2.0 | active 2026 | Code reusable with attribution (Apache-2.0) — small project |
| **mosaik_home** (GitHub) | Launcher "inspired by Microsoft's Windows Phone" | L0 | AGPL-3.0 | active 2026 | AGPL makes reuse impractical for a permissive public repo; design only |
| **Olauncher** (GitHub, "Minimal AF Launcher") | Light-Phone-like text-first home screen: 8 shortcuts, text app list, screen-time counter, no icons | L0 | GPL-3.0 | active (2026-11), 3.9k stars | **Proof that an app can reproduce a "minimal phone" UX.** Useful UX study for an ultra-minimal ztmetro mode |
| **Niagara Launcher** (commercial) | List-first home screen with a typing app list, minimal chrome | L0 | proprietary | active | Reference for "one-hand/all-apps" patterns; no code |

**Takeaway:** launchers can recreate a *Start screen and app list* convincingly and
are the only zero-risk layer. None of them replaces system chrome — that is the
structural limit, not a missing feature.

## 2. Theming layers (L2/L3 — RRO, Xposed, Magisk)

| Project | What it changes | Depth | Licence | Status | Reusable |
|---|---|---|---|---|---|
| **Substratum** (theme manager, RRO/OMS) | System-wide resource overlays: colours, styles, some SystemUI strings/dimens; needs ROM OMS support or root on modern Android | L2 (+L3 via root) | GPL-3.0 | **archived/dead** (last push 2022-01; repo archived) | Historical reference for overlay packaging; the mechanism (RRO) is what we would use directly via `cmd overlay` |
| **Iconify** | Root/LSPosed module customising Android 12+ SystemUI: icons, colours, shapes, **notification panel**, QS, status bar | L3 + Xposed hooks into SystemUI | GPL-3.0 | active (2026-11), ~3.2k stars | **Proof that SystemUI can be restyled deeply — but only by hooking/altering SystemUI inside a rooted/modified system.** Not applicable to a clean LineageOS daily phone `[E]` |
| **ColorBlendr** | Material You palette override on Android 12+: accent/background saturation, individual tonal colours | L2/L3 (root or Shizuku-class access) | GPL-3.0 | active | Reference for palette controls; we can do the same with framework overlays on a self-built ROM |
| **Repainter** (kdrag0n) | Dynamic-colour theming beyond stock palettes on Android 12+ | L2/L3 | proprietary | **discontinued** (community reports; alternatives now exist) `[E]` | UX reference only |
| **LSPosed** | Xposed-compatible hooking framework (Zygisk) | L3 | GPL-3.0 | active, ~25k stars | Not a product path: hooking SystemUI on a user ROM is fragile/unshippable |
| **Magisk** | Systemless mount of files over system partitions; module ecosystem | L3 | GPL-3.0 | active | The mechanism for overlay/font/SystemUI experiments (see 06); never assume it can bypass signatures |
| **"Windows Phone 10" Xperia theme** (2015, XDA) | Sony theme engine: blue palette, Windows nav-bar icons, wallpaper, some system UI colours | L2-era theme engine | unknown | dead | Historical evidence that WP *cosplay* at theme level is shallow |

**Takeaway:** deep SystemUI restyling on Android exists in the *rooted/modding*
world (Iconify, Substratum) — always coupled to root or custom ROMs. There is no
mainstream "install an APK and get a new shade/keyguard" product, because the
platform forbids it.

## 3. ROMs / firmware with strongly reworked UX (L5 — system image)

| Project / product | What it changes | Depth | Status | Relevant to us? |
|---|---|---|---|---|
| **MIUI / HyperOS (Xiaomi), One UI (Samsung)** | Whole UX: launcher, shade, keyguard, nav, settings, animations, fonts, always-on — proprietary skins with deep framework changes | L5 (+ framework) | active | Existence proof that a phone *can* look completely different — but it is a multi-thousand-engineer proprietary fork, not a hobby option. Cannot be ported to `Mi8917` legally/practically |
| **Light Phone (LightOS)** | Minimal phone UX built **on top of Android**, hiding Android behind a small set of apps; the "Android layer" is present but not exposed to the user | vendor ROM + **app-layer LightOS** | active (Light Phone III) | **Architectural analogue:** LightOS is an app/launcher experience on Android, not a replacement of the Android framework. Proves the "Android underneath, UX on top" model ships commercially |
| **Punkt MP02** | Minimal phone: calls, SMS, Signal-protocol messenger ("Pigeon"); **no app installation/sideloading** | vendor ROM (AOSP 8.1 per device databases) | active product, niche | Shows the "UX replaces app ecosystem" extreme; unusable for a project that needs Android apps |
| **Mudita Kompakt** | E-ink minimalist phone; "based on Android", Google-free, only a few bespoke apps | vendor ROM (Android-based) | active (2025–2026) | Same lesson: full UX control requires owning the ROM |
| **Dumbphone launchers** (Olauncher, "Minimalist Phone" etc.) | Restrict the phone to a handful of apps; remove icons/notifications | L0 | active | Could be combined with ztmetro as a "focus mode"; not a base |
| **E-ink vendor launchers** (Onyx Boox etc.) | E-ink-optimised home/reading UX on Android | vendor ROM | active | Not applicable (LCD device) |

**Takeaway:** every product with a genuinely different UX either owns its ROM
(MIUI/One UI/Punkt/Mudita) or ships a launcher-app UX on top of an Android ROM it
controls (Light). None of them is "install this APK on stock LineageOS and get a new
system".

## 4. Separate OSes with their own UX and Android-app support

These are architectural analogues: **shell-first OS + Android compatibility layer**.

| Project | Architecture | Own UX | Android apps | Depth for us | Status |
|---|---|---|---|---|---|
| **Sailfish OS** (Jolla) | Mer/Linux core + **Sailfish Silica/QML** shell; Android support via proprietary **Alien Dalvik / AppSupport** | yes, complete | yes — but only on officially licensed devices; **community ports do not include Alien Dalvik** | would be a "third path": own shell, Android apps as guests — but proprietary app layer and no `Mi8917` port `[?]` | active commercially; community ports limited |
| **Ubuntu Touch (UBports)** | **Halium** (Android kernel/HAL) + **Lomiri** shell; Android apps via **Waydroid** | yes, complete | via Waydroid (container) | same class as our ztOS + Waydroid option (RAM/container costs); Halium port for `Mi8917` not in scope `[?]` | active community |
| **postmarketOS** | Alpine Linux on Android hardware; shells: Phosh, Plasma Mobile, GNOME Mobile, …; Android apps via Waydroid | depends on shell | via Waydroid | this is the family the project's ztOS already belongs to (Arch/glibc variant with ztmetro) | active |
| **KaiOS** | Firefox OS (B2G) fork; HTML5 apps; feature-phone UX | yes | no Android apps | shows a non-Android app model can succeed on low-end hardware; not compatible with our app needs | active in feature phones |
| **LuneOS** | webOS open-source continuation | yes (webOS UX) | no | historical interest; effectively inactive `[?]` | dormant |

**Takeaway:** the "own shell over Android-compatible hardware" architecture is real
and battle-tested (Sailfish, Ubuntu Touch, postmarketOS). Its recurring cost is the
Android-app layer: proprietary (Sailfish) or container-based (Waydroid, RAM-heavy).
On a 2 GB device the container path does not close for daily use `[E]`.

## 5. Windows Phone / Metro cosplay projects specifically

| Project | Layer | What exactly is "WP" | Licence | Status | Can we take code? |
|---|---|---|---|---|---|
| **MetroWinLauncher** | L0 APK | Start screen with resizable tiles/live tiles, Action Center screen, Metro lock-screen screen, WP typography/colours | GPL-3.0 | active | Code yes (GPL-3 obligations: our launcher must be GPL-3 if derived). Best reference implementation |
| **Launcher 10** | L0 APK | Windows-Mobile home: live tiles/badges/folders/all-apps, icon packs | proprietary | active | No code; design benchmark |
| **Factor Launcher Reboot** | L0 APK | WP7-inspired tiles | none | dormant | No (no licence ⇒ no reuse) |
| **MetroLauncherAndroid** | L0 APK | Windows 10 Mobile tiles + live notification previews | none | active | No (no licence) |
| **Vanta Launcher** | L0 APK | Metro/brutalist tiles | Apache-2.0 | active | Yes with attribution |
| **mosaik_home** | L0 APK | WP-inspired home | AGPL-3.0 | active | Design only (AGPL) |
| **Windows Phone 10 Xperia theme** | L2 theme engine | blue palette, Windows nav-bar icons | unknown | dead (2015) | No |
| **WP lock-screen/Action-Center clones** (various store apps) | L0 APK | A lock-screen-*looking activity* or notification-list screen — never the real keyguard/shade | mostly proprietary | mixed | UX reference only |

**What none of them do:** replace the actual notification shade, keyguard, navigation
bar, recents, QS tiles or power menu. They emulate screens *inside* apps and rely on
the user staying in them.

## 6. Answers to the lead's questions

**(a) Has anyone replaced Android's UX completely *without* rebuilding the ROM?**
No — if "UX" includes system chrome (we found no counter-example). The complete replacements (MIUI/One UI/Light/
Punkt/Mudita/Sailfish/UT) all own the system image or the whole OS. What *is*
achievable APK-only is the home-screen UX: dozens of launchers, including convincing
WP ones (MetroWinLauncher, Launcher 10). That is the existing precedent we can build
on.

**(b) What is the deepest UX achievable without building a system image?**
`L0 launcher` + `NotificationListenerService` live tiles + `TYPE_APPLICATION_OVERLAY`
banners (below status bar) + `L1 accessibility` gestures + `L2/L3 RRO` palette/fonts
(where overlayable, root or preinstall needed). Result: a WP-like Start/app-list,
own Action-Centre *activity*, own banners, global palette/font. Not achievable:
status bar replacement, shade, keyguard, nav bar, recents, QS, power/volume menus.
See [`04-replacement-layers.md`](04-replacement-layers.md).

**(c) What does a Sailfish/Ubuntu-Touch-style architecture give us?**
A shell that owns *everything* (that is what ztOS + ztmetro already is) — at the cost
of an Android-app compatibility layer: proprietary Alien Dalvik (Sailfish) or a
container (Waydroid) with a RAM floor that 2 GB cannot carry together with a browser
and a shell `[E]`. It is the "third path" only if Linux-native ztmetro beats Android
apps in priority; it does not solve both at once on this hardware.

**(d) Recommendation.**
- Reuse **design and UX patterns** from MetroWinLauncher/Launcher 10 (tile grid, live
  tiles via notifications, Action-Centre screen) — and, if GPL-3 is acceptable for
  our launcher, reuse MetroWinLauncher code as a starting point; otherwise implement
  clean-room with the same patterns.
- Do **not** plan on Substratum/Iconify-class SystemUI mods for a daily ROM: they are
  root/Xposed-dependent and fragile.
- Treat Light Phone as the architectural proof: **Android underneath + our UX on top
  is a shippable model**; if we want its depth of control we must own the ROM
  (Stage 2 in [`08-decision.md`](08-decision.md)).
- A Sailfish/UT-style third path is only justified if the product goal changes from
  "Android apps with WP look" to "Linux phone with WP look".

Back to [`08-decision.md`](08-decision.md) or [`README.md`](../README.md).
