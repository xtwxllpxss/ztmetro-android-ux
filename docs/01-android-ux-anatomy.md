# 01 — Android UX/UI anatomy: what the "interface" is actually made of

Snapshot: AOSP tag `android-16.0.0_r3` (Android 16), LineageOS `lineage-23.x`.
Labels: `[F]` fact (source link), `[E]` estimate, `[?]` unverified.

## 1. The stack in one picture

```
apps (Zygote app processes, per-app UID)
        │  windows, insets, activity lifecycle
framework  system_server  ── ActivityManager / WindowManager / PackageManager / ...
        │                   (wms, ams, pms, policy; runs as uid 1000 "system")
        │  Binder + SurfaceControl
SystemUI (com.android.systemui, shared UID android.uid.systemui, platform-signed,
          /system_ext/priv-app/SystemUI)   ── status bar, shade, keyguard, nav bar,
        │                                     QS, volume, power menu, recents host
        │  WMShell library (com.android.wm.shell) linked *into* SystemUI
        │  Launcher3 / Launcher3QuickStep (com.android.launcher3, HOME + recents)
        ▼
SurfaceFlinger + window manager Shell (system_server side) → display
```

Three independent projects cooperate, and each is replaceable at a different depth:

| Project | Source | Installed as | Runs as | Replace depth |
|---|---|---|---|---|
| `frameworks/base/packages/SystemUI` | AOSP/Lineage | `/system_ext/priv-app/SystemUI` (`system_ext_specific: true`) `[F]` | shared UID `android.uid.systemui`, platform cert `[F]` | system image only |
| `frameworks/base/libs/WindowManager/Shell` (WMShell) | AOSP | **library linked into SystemUI**: `SystemUI-core` has `static_libs: ["WindowManager-Shell", ...]` `[F]` | inside SystemUI / shell process | system image only |
| `packages/apps/Launcher3` (`Launcher3`, `Launcher3QuickStep`) | AOSP/Lineage | `/system_ext/priv-app/Launcher3QuickStep`, overrides `Home`, `Launcher2`, `Launcher3` `[F]` | its own app UID, `privileged: true` | **APK is enough for HOME**; recents/gesture half needs system integration |
| `system_server` (WMS/AMS/PMS) | AOSP | `/system/framework/services.jar` | uid 1000 | framework patch / ROM |

## 2. SystemUI: the package that *is* the phone's chrome

`packages/SystemUI/AndroidManifest.xml` declares `package="com.android.systemui"`,
`android:sharedUserId="android.uid.systemui"`, `coreApp="true"` `[F]`. Its permission
list is the most instructive part of the anatomy — these are permissions that no
ordinary app can hold (excerpt, all from the manifest `[F]`):

- `STATUS_BAR_SERVICE`, `STATUS_BAR`, `EXPAND_STATUS_BAR` — own the status bar/shade;
- `INTERNAL_SYSTEM_WINDOW`, `INJECT_EVENTS` — create system windows and inject input;
- `GET_TOP_ACTIVITY_INFO`, `MANAGE_ACTIVITY_TASKS`, `REORDER_TASKS`, `REMOVE_TASKS`,
  `START_TASKS_FROM_RECENTS`, `STOP_APP_SWITCHES` — task/recents control;
- `WRITE_SECURE_SETTINGS`, `MANAGE_USERS`, `DEVICE_POWER`, `MASTER_CLEAR`,
  `CONTROL_DISPLAY_BRIGHTNESS`;
- `BROADCAST_CLOSE_SYSTEM_DIALOGS` (manifest) and
  `CONTROL_KEYGUARD_SECURE_NOTIFICATIONS` (priv-app allowlist).

The APK module in `packages/SystemUI/Android.bp` `[F]`:

```
android_app {
    name: "SystemUI",
    defaults: ["platform_app_defaults", "SystemUI_optimized_defaults", "wmshell_defaults"],
    static_libs: ["SystemUI-core"],
    platform_apis: true,          // hidden @SystemApi/@hide framework APIs
    system_ext_specific: true,    // lands in /system_ext
    certificate: "platform",      // signed with the ROM's platform key
    privileged: true,             // /system_ext/priv-app
    required: ["privapp_whitelist_com.android.systemui"],
}
```

`SystemUI-core` is a huge `android_library`: `static_libs` include
`WindowManager-Shell`, `SystemUISharedLib`, `SystemUICustomizationLib`,
`SettingsLib`, `monet`, `libmonet`, `SystemUIPluginLib`, `SystemUI-statsd`,
Compose/AndroidX/material libraries, `SystemUI-res` (which itself depends on
`SystemUISharedLib`, `SettingsLib`, `WindowManager-Shell`, `monet`…), plus
`aconfig` feature-flag libraries and Dagger/Room plugins `[F]`. **There is no
"small SystemUI" build.** The dependency direction is from SystemUI *into* the
platform, never the reverse.

The privilege allowlist `frameworks/base/data/etc/com.android.systemui.xml`
(`privapp_whitelist_com.android.systemui`) grants exactly what the manifest asks for
plus elevated capabilities such as `CAPTURE_AUDIO_OUTPUT`, `BATTERY_STATS`,
`CHANGE_OVERLAY_PACKAGES`, `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`,
`MANAGE_GAME_MODE`, `MANAGE_SENSOR_PRIVACY`, `MANAGE_USB`, `MANAGE_USERS`,
`MASTER_CLEAR` `[F]`.

### 2.1 Component map (`packages/SystemUI/src/com/android/systemui/…`) `[F]`

Directories in Android 16 include: `statusbar`, `shade`, `qs`, `keyguard`,
`navigationbar`, `volume`, `globalactions`, `notifications`, `media`, `recents`,
`power`, `doze`, `dreams`, `brightness`, `biometrics`, `bouncer`, `deviceentry`,
`controls`, `people`, `smartspace`, `wallpapers`, `screenrecord`, `screenshot`,
`wmshell`, `window`, `theme`, `monet`, `compose`, `dagger`, `flags`, … The
user-visible pieces map like this:

| UX element | SystemUI package(s) | Notes |
|---|---|---|
| Status bar | `statusbar/`, `statusbar/systemstatusicons`, `statusbar/phone` | clock/battery/icons/notification icons; window type `TYPE_STATUS_BAR` |
| Notification shade, notification list | `shade/`, `notifications/`, `statusbar/notification` | Window `TYPE_NOTIFICATION_SHADE`; expands from status bar |
| Heads-up notifications | `statusbar/headsup` | floating window above apps |
| Lock screen / keyguard | `keyguard/`, `bouncer/`, `deviceentry/`, `biometrics/` | keyguard windows, PIN/pattern bouncer; `TYPE_KEYGUARD`/`TYPE_KEYGUARD_DIALOG` |
| Recents/Overview | `recents/` (legacy glue) + WMShell `overview`/`recents` packages | real implementation lives in WMShell, hosted by SystemUI/Launcher3 process |
| Navigation bar / gesture nav | `navigationbar/`, `statusbar/phone` (InsetsPolicy controllers) | `TYPE_NAVIGATION_BAR`; gesture nav handled by WMShell + Launcher3 `TouchInteractionService` |
| Volume dialog | `volume/` | system dialog window |
| Power menu (long-press power) | `globalactions/` | `TYPE_SYSTEM_DIALOG`-class window; global actions service in system_server triggers it |
| Quick Settings tiles | `qs/` | tile definitions, `QsTile` publishing via `StatusBarManagerService` |
| Media controls, smart space, privacy chips | `media/`, `smartspace/`, `privacy/` | inside shade/status bar |
| Screen decorations (cutout/rounded corners) | `decor/`, `ScreenDecorations` | `TYPE_NAVIGATION_BAR_PANEL`-style overlay windows |
| Theme/Monet application | `theme/ThemeOverlayController`, `monet/` | applies RRO overlays from a Secure setting (see `03-theming-rro.md`) |

### 2.2 WMShell: the part that is *not* an app

`frameworks/base/libs/WindowManager/Shell` builds the `WindowManager-Shell` library.
Its `src/com/android/wm/shell/` packages (Android 16) include `transition`,
`splitscreen`, `pip`, `pip2`, `recents`, `taskview`, `draganddrop`, `onehanded`,
`bubbles`, `freeform`, `desktopmode`, `windowdecor`, `keyguard`, `back`,
`displayareahelper`, `startingsurface`, `appzoomout`, `apptoweb`, `activityembedding`,
`splashscreen`-related code, `sysui`, `unfold` `[F]`. It is **linked statically into
SystemUI** and talks to `system_server` through `ShellTaskOrganizer`,
`WindowManagerShellWrapper`, `ShellTransitions`, `RootDisplayAreaOrganizer` etc. `[F]`.

Consequences for a custom UX:

- Shared-element/task animations, split screen, PiP, desktop/freeform window
  decorations, one-handed mode, drag & drop, predictive back *between tasks* and the
  starting-window splash are WMShell code, i.e. **system image**, not an app.
- An ordinary launcher APK can call the *public* activity-management APIs and can be
  the HOME app, but it cannot become the Shell. Launcher3's recents/gesture half
  (`Launcher3QuickStep`) needs `platform_apis: true`, `privileged: true`, many
  signature/privileged permissions (`MANAGE_ACTIVITY_TASKS`,
  `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, `READ_FRAME_BUFFER`,
  `MONITOR_INPUT`, `INTERNAL_SYSTEM_WINDOW`, …) and a `TouchInteractionService`;
  it is a system app `[F]`.

## 3. Launcher3 / Quickstep: replaceable, but only half-replaceable

AOSP builds several launcher variants from one tree `[F]`:

| Module | SDK | privileged | Installed | Purpose |
|---|---|---|---|---|
| `Launcher3` | `sdk_version: "current"` | yes | `/system_ext/priv-app/Launcher3` | plain HOME, **public SDK build** |
| `Launcher3Go` | public | yes | Go variant | low-RAM configuration |
| `Launcher3QuickStep` | `platform_apis: true` | yes | `/system_ext/priv-app/Launcher3QuickStep` | HOME + recents + gesture nav integration |
| `Launcher3QuickStepGo` | platform | yes | Go variant | same, Go |

`Launcher3QuickStep` `overrides: ["Home", "Launcher2", "Launcher3"]` and is
`system_ext_specific` `[F]`. Its manifest is still `package="com.android.launcher3"`,
it declares `TouchInteractionService` and `RecentsActivity`, and requests permissions
such as `START_TASKS_FROM_RECENTS`, `MANAGE_ACTIVITY_TASKS`,
`CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, `READ_FRAME_BUFFER`, `MONITOR_INPUT`,
`STATUS_BAR`, `SYSTEM_APPLICATION_OVERLAY` `[F]`.

Practical meaning:

- **HOME can be replaced by a normal third-party APK** (the user picks it as default
  home; visible role). The launcher then owns Start screen, app drawer, folders,
  widgets, wallpapers and its own animations.
- **Recents/Overview and the gesture-nav transition cannot be replaced by a normal
  APK.** The Quickstep launcher is a privileged system app bound to WMShell; without
  platform signature/permissions it cannot provide recents. A third-party home app
  gets the stock recents (or no recents if the OEM launcher is removed).
- Removing/keeping the OEM launcher is an important decision: on LineageOS 23 both
  `Launcher3` and `Launcher3QuickStep` are preinstalled system apps; a new home app
  must be installed as a normal APK and selected as default. The stock one can be
  disabled per-user; its privileged pieces remain in the system image.

## 4. Permissions and identity: why "just install an APK" has a ceiling

| Capability needed for a WP-like shell | Permission / mechanism | Ordinary APK | Root/Magisk | System image |
|---|---|---|---|---|
| Home screen, app list, folders, widgets | `Intent.CATEGORY_HOME`, `BIND_APPWIDGET` (user-granted) | yes | yes | yes |
| Draw over other apps (banners, tiles over apps) | `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`; drawn **below** status bar/IME `[F]` | yes (user toggle) | yes | yes |
| Replace status bar content | requires the status bar window (`STATUS_BAR_SERVICE`) | no | partial (hide it, draw your own) | yes |
| Replace shade / QS / volume / power menu | SystemUI component | no | only by replacing SystemUI | yes |
| Replace lock screen | keyguard (`CONTROL_KEYGUARD*`, SystemUI) | no (a *lock-screen-adjacent* activity is possible: `showWhenLocked`, but the real keyguard stays or must be disabled) | no | yes |
| Read notifications to draw "live tiles"/Action Centre | `NotificationListenerService` (user-granted special access) | yes | yes | yes |
| Inject touches/gestures globally | `INJECT_EVENTS`, accessibility service | accessibility only (user-granted; visible, restricted) | yes | yes |
| Change system colours/fonts | RRO overlay on overlayable resources; `CHANGE_OVERLAY_PACKAGES` | limited (see 03) | with root/`cmd overlay` | yes |
| Change task animations, splitscreen, PiP, recents | WMShell / system_server | no | no | yes |

`SYSTEM_ALERT_WINDOW` overlays are explicitly *below critical system windows like the
status bar or IME* `[F]` — this single sentence defines the ceiling of the
"shell as an app" approach.

## 5. Boot-to-home lifecycle (where UX time is spent)

`[E]` from AOSP structure; timings are device-specific `[?]`:

1. bootloader/kernel → `init` → `zygote` → `system_server`.
2. `system_server` starts `ActivityManagerService`, `WindowManagerService`,
   `PackageManagerService`, `StatusBarManagerService`; starts SystemUI
   (`SystemUIService`), the home activity (Launcher3QuickStep) and keyguard.
3. SystemUI creates status bar/nav bar windows and registers insets sources; WMS lays
   the windows out.
4. Launcher draws Start screen and requests the wallpaper; WMShell runs the app-launch
   transition and starting-window splash.
5. Keyguard shows/hides; shade and QS are available immediately from SystemUI.

A "ztmetro boot" therefore competes for: boot animation (ROM), starting-window splash
(WMShell/SystemUI + app theme), first frame of the launcher (our APK), and the
keyguard (SystemUI). Only the third is ours without a ROM build.

## 6. What this means for a ztmetro-style shell

- The *home experience* (Start, tiles, all-apps list, search, folders, live tiles from
  notification listener) is 100% APK-replaceable — this is where most of the visible
  "WP feel" can be delivered.
- The *system chrome* (status bar, shade, keyguard, nav bar, QS, volume, power menu,
  recents) is SystemUI/WMShell — reachable only through the system image (self-built
  ROM) or a fragile systemless replacement.
- The *app chrome* (title bars, action bars, "top strip") is drawn by each app; the
  system only decides insets and window decorations. A shell cannot force a WP-style
  header into third-party apps without framework patching (see
  [`02-window-shell-decorations.md`](02-window-shell-decorations.md)).

Continue with [`02-window-shell-decorations.md`](02-window-shell-decorations.md).
