# 04 — Replacement layers: APK vs accessibility vs RRO vs privileged app vs framework

Labels: `[F]` fact (source), `[E]` estimate, `[?]` unverified / must be tested.

This document answers one question per UX element: **what is the shallowest layer
that can change it?** The layers, from cheapest to deepest:

| Layer | How it ships | Needs | Survives OTA | Reversible |
|---|---|---|---|---|
| **L0 — ordinary APK** (launcher, IME, notification listener, overlay window) | normal install from APK | user grants (default home, notification access, "draw over apps") | yes | yes |
| **L1 — accessibility service / device admin** | APK + special access | user grants; visible in settings | yes | yes |
| **L2 — user RRO overlay** | overlay APK + `cmd overlay` | target overlayable resources; root or shell to enable for non-`public` policies | lost on update | yes (disable) |
| **L3 — root / Magisk module** | files in `/data/adb/modules` mounted over system | unlocked bootloader, root | usually yes | yes (remove module) |
| **L4 — privileged system app** (preinstall/signature) | `/system_ext/priv-app`, platform signature, privapp allowlist | ROM build or systemless signed APK | no | with ROM |
| **L5 — framework/system image** | SystemUI, WMShell, system_server, framework-res | full ROM build | no | reflash |

Evidence for the boundaries:

- `TYPE_APPLICATION_OVERLAY` windows are *below critical system windows like the
  status bar or IME* `[F]`. So an APK can never cover the status bar, keyguard, IME
  or system dialogs.
- `SYSTEM_ALERT_WINDOW` is a special permission the user must grant for targetSdk ≥ 23
  `[F]`; Android Go **disables system alert windows by default** `[F]` — relevant if
  we ever run a Go configuration on 2 GB.
- SystemUI holds `STATUS_BAR_SERVICE`, `INJECT_EVENTS`, `INTERNAL_SYSTEM_WINDOW`,
  `MANAGE_ACTIVITY_TASKS`, `WRITE_SECURE_SETTINGS`, `CHANGE_OVERLAY_PACKAGES` via
  manifest + priv-app allowlist `[F]`. No L0/L1 app can acquire these.
- RRO cannot overlay a target that exposes no overlayable unless preinstalled on the
  system image or signed with the same key `[F]`.

## 1. Element-by-element matrix

| UX element (ztmetro / WP) | Best layer | What you get | What you cannot get |
|---|---|---|---|
| Start screen, live tiles, tile groups | **L0 launcher APK** | pixel-perfect, own animations, tile sizes, accent colours, widgets-as-tiles | true "live tile" background updates while not foreground are limited by Android background rules; content from `NotificationListenerService` `[F]` |
| All-apps list, alphabetical headers, search | **L0 launcher** | full control | — |
| Folders, app shortcuts | **L0 launcher** | full control | Android "shortcuts" are opt-in per app |
| Wallpaper/backdrop treatment | L0 launcher + wallpaper API | full control inside home | cannot repaint other apps |
| App launch animation / splash | L0 launcher can start activities; splash is **WMShell `startingsurface`** `[F]` | close-enough custom transition inside our app | system splash for cold starts is system-image |
| Status bar (content, size, transparency) | **L5 SystemUI** | WP-like status strip | L0 can only *hide* bars (immersive) and draw its own overlay **below** them `[F]` |
| Notification shade / Action Centre | **L5 SystemUI** | full redesign | L0 can show its own notification-list activity/overlay when user opens it; cannot replace the pull-down |
| Heads-up notifications | **L5 SystemUI** | WP toast/banner | L0 can post its own notifications and use a notif-listener to mirror, not replace |
| Quick Settings tiles | **L5 SystemUI** | Metro tile grid | L0 can only provide `TileService` entries (still rendered by SystemUI) |
| Lock screen / always-on | **L5 SystemUI** | full redesign (clock, tiles, PIN pad) | L0: a `showWhenLocked` activity can appear, but the keyguard remains; cannot replace it without disabling it |
| Recents / Overview | **L5 WMShell** (+ SystemUI/Launcher3) | custom task switcher | L0 launcher cannot own recents; only privileged Quickstep can `[F]` |
| Navigation bar / gestures | **L5 SystemUI** / WMShell | WP bottom bar (back/Start/search) | L0 can hide the nav bar and emulate buttons with an accessibility overlay `[?]`; gesture handling belongs to WMShell/Launcher3 `[F]` |
| Volume dialog | **L5 SystemUI** (`volume/`) | Metro-style volume | — |
| Power menu | **L5 SystemUI** (`globalactions/`) | Metro list with Restart/Power | — |
| System font | L2 RRO / L3 font files / L5 ROM | WP-like typeface system-wide | L0: only inside our own UI |
| System palette / accent | L2/L3 `android` RRO; L5 | global recolour | L0: only inside our own UI |
| App window "frame"/decorations | L5 (freeform/desktop only) | WP-like frames around tasks | normal fullscreen apps draw their own chrome; no system frame exists `[F]` |
| In-app page headers ("CALCULATOR → calculator") | app code or L6 framework patch | only for our own apps | third-party apps keep their own headers |
| Boot animation / splash | L5 ROM (`bootanimation.zip`) | WP-like boot | L0 cannot replace boot animation |

## 2. The "shell as apps" architecture (L0/L1 only)

This is the maximum reachable without system-image changes:

```
┌───────────────────────────────────────────────┐
│ launcher APK (HOME)                           │
│  Start: tiles, status strip drawn INSIDE app  │
│  All apps: list/search                        │
│  Live content: NotificationListenerService    │
│  Own "Action Centre" activity (slide-down UI) │
│  Own "lock-screen-like" activity              │
│    (showWhenLocked) — behind the real keyguard│
├───────────────────────────────────────────────┤
│ status bar / nav bar / shade / keyguard       │  ← stock SystemUI, only themed
│  (SystemUI)                                   │     via RRO where possible
├───────────────────────────────────────────────┤
│ apps                                          │
└───────────────────────────────────────────────┘
```

What this buys: a convincing Start screen + app list + own banners/"toast"
(`TYPE_APPLICATION_OVERLAY` cannot go over status bar `[F]`, but banners over app
content are fine), plus a custom drawer/notification-centre *activity* opened by a
gesture/tile from the launcher. It is exactly what Windows-Phone-style launchers do
(see [`09-existing-projects.md`](09-existing-projects.md)).

What it cannot buy: the phone still *is* Android — pull down from the top and you get
Material shade; press power/lock and you get Material keyguard; recents is Material
Overview; nav bar is Android. The illusion breaks at every system gesture.

## 3. Privileged / platform layer (L4)

An app can become a platform-signed privileged app only if it is signed with the
ROM's platform key and preinstalled (priv-app) with a privapp allowlist `[F]`. That
unlocks signature/privileged permissions and shared UIDs. Ways to get there:

1. **Self-built ROM** with your own keys: everything is possible; you control
   `platform.x509.pem` and every system app (see 06).
2. **Official ROM with its private keys**: impossible to platform-sign a third-party
   APK. Systemless replacement of an existing platform-signed APK is a signature
   hazard, not a supported path `[?]` (must be tested; see 06 §4).
3. **"Signature spoofing" patches** (used by microG) exist for LineageOS forks, but
   they spoof *Google* signatures for apps, not a general platform-key bypass; using
   them to sign SystemUI is not a supported route `[E]`.

## 4. Root/Magisk layer (L3) and its limits

- Magisk mounts files over partitions systemlessly; it does **not** re-sign APKs and
  does **not** disable PackageManager signature checks. Replacing a platform-signed
  app (SystemUI) with a differently-signed build is therefore governed by normal
  Android package/signature rules `[E]`; a mismatch is a bootloop risk (see 06).
- Static RROs in `/product/overlay` need either an `OverlayConfig` or
  `android:isStatic`; a system-image-preinstalled overlay can legally target
  non-overlayable resources `[F]`. This is the most promising root-only theming route.
- Root voids the security model and may break OTA/verified boot; acceptable for an
  experiment device, not for a "product".

## 5. Framework patch layer (L5+/L6)

Only in a self-built ROM, only when RRO cannot express it:

- force a global window flag/inset policy (e.g. all apps get a WP-like top frame);
- inject an app header/titlebar in `PhoneWindow` for all apps;
- replace recents with a WP task switcher;
- change power/lock behaviour globally;
- hide the nav bar permanently and route gestures through the shell.

Cost: every LineageOS point release needs a rebase; device bring-up risk; no OTA.
This is the "own OS" territory, which is what ztOS already is on the Linux side.

## 6. Recommendation (summary)

- **Stage 1 (no ROM build): L0 launcher + L1 notification listener + L2/L3 RRO
  palette/fonts.** Delivers the home experience and global colours; keeps LineageOS
  updatable and reversible. This is also the safest RAM story (see 05).
- **Stage 2 (only if the "system chrome" is a hard requirement): L5 with a self-built
  LineageOS and own keys.** Fork SystemUI (+ optionally a privileged launcher) and
  accept a fork maintenance commitment.
- **Do not** build the plan on L3 SystemUI APK swaps or on "signature spoofing" —
  they are the highest-risk, lowest-predictability options.

Continue with [`05-ram-budget.md`](05-ram-budget.md).
