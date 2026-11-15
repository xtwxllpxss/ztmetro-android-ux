# 07 — ztmetro → Android mapping: what transfers, what does not

Source of the design side: the ztmetro Wayland compositor used on the project's Linux
phone (`upstream/ztOS/ztmetro` in the private tree; public design notes live with this
repo's artefacts). ztmetro is a Lumia/Windows-Phone-style shell: it draws *everything*
— Start screen with tiles, status bar, navigation bar, shade, lock screen, on-screen
keyboard, splash; apps are tiled into the rectangle between the status bar and the nav
bar, and the shell draws app chrome (a WP page header) itself. Windows-Phone design
guidance (Segoe WP family, page-title style, bottom app bar, all-caps app title,
accent-only-for-interaction) is documented in the project's WP UX research.

Labels: `[F]` fact about Android (source in [`sources.md`](../sources.md)); `[E]`
design/engineering estimate; `[?]` needs a prototype.

## 1. Why this mapping is not 1:1

ztmetro is a **compositor**: it owns the screen, lays out every window, draws chrome
around apps and receives input before apps do. Android is a **multi-window manager
with distributed drawing**: every app draws its own content and most of its own
chrome; SystemUI draws a few system windows; the app framework decides insets. The
same visual result therefore requires different owners in the two systems:

| ztmetro owner | Android owner |
|---|---|
| compositor draws Start | launcher app (HOME) |
| compositor draws status bar/nav bar | SystemUI (system image) |
| compositor draws shade/lock/splash | SystemUI / WMShell (system image) |
| compositor draws app title/page header | each app itself (`ActionBar`/`Toolbar`/Compose) — not system |
| compositor forces window rect between bars | framework/insets; apps opt into edge-to-edge and apply insets |
| compositor draws OSK | the active IME app (replaceable APK!) |
| compositor's global accent/palette | RRO/dynamic colour, but apps must consume theme colours |

## 2. Element-by-element map

| ztmetro element | Android equivalent | Best layer | Fidelity | Notes |
|---|---|---|---|---|
| **Start screen** (tiles, always underneath) | HOME activity + wallpaper | **launcher APK** | high | Start is just the home activity; "always underneath" maps to the launcher staying behind tasks — Android shows it when you go home |
| **Live tiles** | launcher tile widgets + `NotificationListenerService` data | launcher APK + user-granted notification access `[F]` | medium-high | counts/content from notifications; Android background limits prevent arbitrary live updates `[E]` |
| **Tile groups / sizes / accent colour** | launcher layout | launcher APK | high | fully in our control |
| **All apps list (alphabetical, search)** | launcher drawer / search activity | launcher APK | high | — |
| **Search** | in-launcher search; system search provider is optional | launcher APK | high | can also expose a global search intent |
| **Status bar** (28 px strip, clock/battery/accent) | SystemUI status bar window | **SystemUI / ROM** | low at stage 1, high with fork | stage 1: theme via RRO (if overlayable) + hide via immersive; the real bar remains |
| **Notification shade / Action Centre** | SystemUI shade | **SystemUI / ROM** | low at stage 1, high with fork | stage 1: own notification-centre *activity* opened from a launcher tile |
| **Heads-up / toast banner** | SystemUI heads-up; app toasts | **SystemUI / ROM** | low-medium | own overlay banners are possible below the status bar `[F]`; system heads-up stays |
| **Lock screen (clock, notes, PIN)** | SystemUI keyguard | **SystemUI / ROM** | low at stage 1 | a `showWhenLocked` activity can overlay content, not replace keyguard; keyguard can be disabled only at ROM/device-policy level `[E]` |
| **Navigation bar (back/Start/search)** | SystemUI nav bar / gesture nav | **SystemUI + WMShell** | low at stage 1, high with fork | APK cannot make a system nav bar; can hide + emulate with accessibility overlay `[?]` |
| **Power menu** | SystemUI `globalactions` | **SystemUI / ROM** | low/high | — |
| **Volume dialog** | SystemUI `volume` | **SystemUI / ROM** | low/high | — |
| **QS tiles** | SystemUI QS | **SystemUI / ROM** | low/high | stage 1 can add `TileService` tiles, still drawn by SystemUI |
| **Bottom app bar (WP action bar)** | app UI / toolbar | **app code** | high for our apps, none for third-party | could be emulated by a framework patch, not by a shell app |
| **Page header: CAPS app name + page title** | app `ActionBar`/`TopAppBar` | **app code** (our apps) or framework patch | high for our apps | the ztmetro shell drew it for foreign apps; Android cannot force it `[E]` |
| **Pivot headers** | app tabs/pager UI | app code | high for our apps | — |
| **Segoe-like typography** | system font / app font | RRO font, `fonts_customization.xml`, launcher-bundled font | medium | system-wide needs overlay/ROM; in our UI trivial |
| **Flat surfaces, 12 px margins, accent-only-for-interaction** | design tokens | launcher + theme overlay | high | a design-system job, not a platform job |
| **Accent colour global** | dynamic colour (Monet) / RRO | RRO + apps opting into dynamic colour | medium | third-party apps that aren't Material You-aware ignore it `[F]` |
| **Splash "Loading…"** | boot animation + starting window | ROM + app theme | low at stage 1 | SystemUI/WMShell `startingsurface` `[F]`; launcher can fake a splash for its own launch |
| **OSK** | active IME | **IME APK** | high | replaceable like any keyboard; ztmetro's built-in OSK has no exact Android twin but an IME can be themed WP-like |
| **Global back gesture/semantics** | predictive back + system back | framework/ROM | medium | back semantics stay Android's; WMShell handles cross-task animations `[F]` |
| **Window frames / per-window title** | freeform/desktop decorations only | WMShell | only in freeform | fullscreen apps have no system frame at all `[F]` |
| **Fade/lift animations** | WMShell transitions | **WMShell / ROM** | with fork | stage 1 limited to launcher-internal animations |

## 3. What does not transfer at all

1. **"The shell draws the app's page header."** In Android, the header is part of the
   app (`Toolbar`/Compose `TopAppBar`). Only our own apps can be made to look like
   ztmetro pages; third-party apps keep their own chrome.
2. **"Window fits between status and nav bar because the compositor says so."**
   Android apps draw edge-to-edge by default on Android 15/16 `[F]`; insets are
   advisory, and an app that ignores them will draw under the bars. The shell cannot
   force a frame around an app.
3. **A single global "Start always underneath".** Android task stack + launcher
   semantics; the home activity is not a persistent compositor layer.
4. **Live tiles with arbitrary background rendering.** Android background execution
   limits and notification-based content restrict this `[E]`.
5. **Lock screen as an app.** The keyguard is a SystemUI window with security
   semantics; "replace it" means a ROM-level change or disabling it.
6. **OSK drawn by the shell.** The IME is a separate app; it can be themed/replaced,
   but it is not owned by the launcher/shell.

## 4. Recommended staged scope

**v1 — "ztmetro at home" (APK-only, 2–4 weeks `[E]`):**
- Start screen with tiles (sizes, groups, accent, no-blur flat background);
- All-apps list with alphabetical sections + search;
- WP-like type ramp and palette inside the shell (bundled fonts, CSS-like tokens);
- optional live tiles via notification listener;
- own "Action Centre" activity (notification list + toggles) surfaced by a tile;
- theme RRO for system accent/font where possible (root/self-build later).

**v2 — "ztmetro system chrome" (ROM build, 2–6 months `[E]`):**
- SystemUI fork: status bar, shade, keyguard, nav bar, QS, power/volume;
- WMShell: transition/splash tuning;
- preinstalled launcher + IME + font + overlay in the ROM.

**Not worth attempting:**
- forcing WP headers into third-party apps (framework patch with broad regressions);
- replacing the keyguard by an ordinary app;
- a full "new SystemUI from scratch" (Android's framework expects a SystemUI with
  specific services; reimplementation risk is extreme) `[E]`.

Continue with [`08-decision.md`](08-decision.md).
