# 02 — WindowManager, Shell, decorations and insets: where the "top strip" comes from

Snapshot: AOSP `android-16.0.0_r3`; Android 15/16 behaviour-change docs.
Labels: `[F]` fact, `[E]` estimate, `[?]` unverified.

## 1. Window ownership and z-order

Android windows are ordered by *type*, and the constants are public API
(`WindowManager.LayoutParams`) `[F]`:

| Type | Owner | Content |
|---|---|---|
| `TYPE_APPLICATION` (activities) | apps | normal app windows |
| `TYPE_APPLICATION_OVERLAY` | apps with `SYSTEM_ALERT_WINDOW` | "above all activity windows … but **below critical system windows like the status bar or IME**" `[F]` |
| `TYPE_STATUS_BAR` | SystemUI | status bar window |
| `TYPE_NAVIGATION_BAR` | SystemUI | nav bar window |
| `TYPE_NOTIFICATION_SHADE` | SystemUI | the whole shade surface |
| `TYPE_KEYGUARD` / `TYPE_KEYGUARD_DIALOG` | SystemUI | lock screen / bouncer |
| `TYPE_SYSTEM_DIALOG`, `TYPE_SYSTEM_ERROR` | system | system dialogs (volume, power menu, crashes) |
| `TYPE_BOOT_PROGRESS`, `TYPE_SCREENSHOT` | system | boot/screenshot |

The **layout** of the bars is done by `system_server`: the window manager's
`DisplayPolicy` reads the requested system-bar attributes and `InsetsPolicy` turns
them into insets (`services/core/java/com/android/server/wm/DisplayPolicy.java`,
`InsetsPolicy.java` exist in Android 16 `[F]`). SystemUI owns the windows and draws
their content; WMShell (`frameworks/base/libs/WindowManager/Shell`) handles
transitions between tasks and orchestrates the shell surfaces (`ShellTaskOrganizer`,
`ShellController`, `ShellTransitions`; packages `transition`, `splitscreen`, `pip`,
`recents`, `desktopmode`, `windowdecor`, `draganddrop`, `onehanded` … `[F]`).

## 2. Insets: the contract between chrome and content

- `WindowInsets.Type.systemBars()`, `.displayCutout()`, `.ime()` describe the space
  taken by the bars/cutout/keyboard.
- An app opts out of automatic fitting with
  `Window.setDecorFitsSystemWindows(false)` / `WindowCompat.setDecorFitsSystemWindows`
  and then must apply `WindowInsets` itself (Compose `WindowInsets.safeDrawing`,
  Views `ViewCompat.setOnApplyWindowInsetsListener`).
- Hiding bars is per-window: `WindowInsetsController.hide(WindowInsets.Type.systemBars())`
  with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.

**Edge-to-edge is now the default, not a trick:**

- Android 15 (targetSdk 35): edge-to-edge is enforced; the status bar is transparent,
  its top offset is disabled, `setStatusBarColor` is deprecated and has no effect;
  gesture nav bar is transparent, 3-button nav gets an 80% contrast scrim; non-floating
  windows get `layoutInDisplayCutoutMode = ALWAYS` `[F]`
  (Android 15 behaviour changes).
- Android 16 (targetSdk 36): the opt-out attribute
  `R.attr#windowOptOutEdgeToEdgeEnforcement` is deprecated and **disabled**, so an app
  cannot opt out of edge-to-edge on Android 16 `[F]` (Android 16 behaviour changes).
- Android 16 also removes orientation/resizability/aspect-ratio restrictions on
  displays with smallest width ≥ 600 dp `[F]` — irrelevant for a 720×1280 phone in
  portrait, but relevant if a desktop/dock mode is ever used.

Consequence: on a 720×1280 Android 16 device, **apps are expected to draw behind the
status bar and navigation bar**. A "WP-like top strip" therefore has three possible
sources, and they must not be confused:

1. **System bars** — SystemUI windows (status bar, nav bar); global look is a
   SystemUI theme change.
2. **App-drawn chrome** — `Toolbar`, `ActionBar`, Compose `TopAppBar`, custom headers.
   This is app code; the system can only change dimensions/colours through theme
   resources (`RRO`, see 03) — not remove the widget.
3. **Window decorations** — legacy title bar (see §3) or freeform/desktop caption.

## 3. App decorations: `screen_simple.xml` vs `screen_title.xml`

`frameworks/base/core/res/res/layout/` contains the two window layouts used by
`PhoneWindow`:

- `screen_simple.xml` — `LinearLayout` with `fitsSystemWindows="true"`, an
  `action_mode_bar` `ViewStub`, and the content `FrameLayout`
  (`@android:id/content`). **No title bar** `[F]`.
- `screen_title.xml` — same, but with a `FrameLayout` of height
  `?android:attr/windowTitleSize` containing the title `TextView`
  (`@android:id/title`, style `?android:attr/windowTitleStyle`) above the content `[F]`.

The title-bar layout is chosen from theme attributes (`windowNoTitle`,
`windowActionBar`) by `PhoneWindow.generateLayout()`; modern Material themes use
`NoActionBar`, so the "top strip" the user sees in most apps is an
`ActionBar`/`Toolbar` **drawn by the app itself**, not a system decoration.

`[E]` Therefore "remove the top strip globally" is **not a supported operation**:
- for legacy title-bar windows, an RRO/theme change can restyle it, and
  `android:windowNoTitle` (or an RRO changing the theme) can remove it *if* it is
  still the system decor title;
- for app-drawn `Toolbar`/`TopAppBar` there is no system switch; only framework
  patching (forcing a window flag/merging the action bar into an overlay) or a
  per-app rewrite can remove it.

Freeform/desktop windows are the exception: their caption/decorations are drawn by
WMShell (`windowdecor`, `desktopmode` packages `[F]`), so a system-image build can
change them.

## 4. Can the shell make everything "immersive"?

Per-app (public API, works on any Android 16 ROM): `WindowInsetsController.hide()`
from inside the app; system-wide forcing is not public API.

A **global** switch exists as a hidden framework setting:
`Settings.Global.POLICY_CONTROL = "policy_control"` `[F]` (AOSP `Settings.java`,
`@hide`). Historically it accepts values like:

```sh
adb shell settings put global policy_control immersive.full=*
adb shell settings put global policy_control immersive.status=*      # status bar only
adb shell settings put global policy_control immersive.navigation=*
adb shell settings put global policy_control ""                      # reset
```

The Android 16 `WindowManagerService` still registers a `ContentObserver` for
`Settings.Global.POLICY_CONTROL` `[F]`, so the setting is still consumed; the exact
Android 16 parsing class was not located in this pass `[?]`. Treat it as an
**unsupported, ROM-dependent escape hatch** to test on the real LineageOS 23.2 build,
not as a design foundation.

Other `wm` shell controls that matter for UX experiments (Android 16
`WindowManagerShellCommand` `[F]`): `size`, `density`, `scaling`, `dismiss-keyguard`,
`user-rotation`, `fixed-to-user-rotation`, `set-letterbox-style`,
`set-multi-window-config`, `set-display-windowing-mode`, `reset-freeze-recent-tasks`.
`wm overscan` no longer exists (removed in Android 11) `[E]`.

## 5. What a SystemUI/WMShell fork could actually change

Only reachable in a **self-built system image** (see
[`06-systemui-standalone.md`](06-systemui-standalone.md)):

- status bar window content/size/behaviour (WP-ish transparent strip with large clock,
  accent-coloured notification count, etc.);
- shade as an "Action Centre": layout, gesture, blur/opacity, per-notification cards;
- lock screen: clock typography, tile/live-tile area, PIN pad, wallpaper treatment;
- navigation bar: WP-style bottom bar (back/Start/search) instead of gesture pill,
  drawn by SystemUI;
- QS tiles: 2-column Metro tiles instead of the stock grid;
- power menu: Metro-styled list;
- recents/overview: WMShell overview layout (expensive: it is the most intricate
  part of the Shell);
- starting window / splash: WMShell `startingsurface` and app theme;
- window decorations in freeform/desktop.

Not achievable even then: replacing the *app's own* in-app chrome without app
changes; forcing third-party apps to use WP controls.

## 6. Practical conclusions

1. The "strip on top" on a stock LineageOS 23.2 screen is: SystemUI status bar
   (system) + app-drawn `Toolbar` (app) + possibly legacy decor title (theme).
2. Without a ROM build you can: hide system bars globally-ish (`policy_control`,
   unsupported), theme them (RRO where overlayable), and draw your own overlay below
   them (not over them).
3. With a self-built ROM you can own the status bar, nav bar, shade, keyguard and
   splash — everything a WP-like phone needs *around* apps. The apps themselves stay
   Material/Android inside.
4. If the visual target is "ztmetro compositor where the shell draws the frame and
   apps live in a rectangle", Android's equivalent is **a SystemUI fork plus a
   launcher**, not a single app. Plan the ROM build before promising that look.

Continue with [`03-theming-rro.md`](03-theming-rro.md).
