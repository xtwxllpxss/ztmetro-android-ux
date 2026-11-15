# ztmetro-style launcher — non-building skeleton

**Status: sketch only. Not compiled, not tested, no Gradle project, no APK.**
It exists to pin down *which Android APIs the Stage-1 launcher would use* and to make
the research concrete. Treat it as pseudo-code with a correct API surface.

## What it demonstrates

| File | Shows |
|---|---|
| `LauncherHomeActivity.kt` | HOME activity with a tile grid (WP Start screen), all-apps entry point, insets handling for edge-to-edge Android 15/16 |
| `TileRepository.kt` | how apps and pinned tiles are enumerated (`LauncherApps`, `PackageManager`) and merged with live-tile counts |
| `LiveTileListenerService.kt` | `NotificationListenerService` for unread counts / live-tile content |
| `AndroidManifest.xml` | the manifest pieces that matter: HOME intent filter, notification listener binding, overlay permission |
| `build.gradle.kts` | the dependency/target sketch (compileSdk 36 / Android 16) |

## Android facts this skeleton is based on

- `android.intent.category.HOME` + `android.intent.action.MAIN` is how a third-party
  app becomes the default home app; no system privileges required.
- `NotificationListenerService` requires the user to grant notification access
  (`BIND_NOTIFICATION_LISTENER_SERVICE` in the manifest); counts/content come from
  `onNotificationPosted`/`onNotificationRemoved`.
- `TYPE_APPLICATION_OVERLAY` (`SYSTEM_ALERT_WINDOW`) is **below the status bar and
  IME** — the skeleton uses an overlay only for optional in-app banners, never to
  simulate a system status bar.
- Android 15/16 force edge-to-edge for targetSdk 35/36; the skeleton consumes
  `WindowInsets.safeDrawing` instead of drawing under the bars blindly.
- The system status bar, shade, keyguard, recents, nav bar and QS are **not**
  reachable from this app; see `docs/04-replacement-layers.md`.

## Not in the skeleton

- No tile animations, no drag & drop reordering, no widgets, no folders — Stage-1
  product work.
- No `WebView`, no blur, no live wallpaper: RAM budget (docs/05) forbids them.
- No accessibility-service gestures: a separate design decision.

## If this becomes real

1. Create a normal Android project (`minSdk 31`+, `targetSdk 36` to match Android 16;
   verify against the actual LineageOS 23.2 build).
2. Start with the HOME activity + grid + app list; install it on the device alongside
   the stock launcher and switch default home — reversible at any time.
3. Add the notification listener behind a settings screen with an explicit permission
   flow (Android shows a system warning for notification access).
4. Keep the process lean: one activity, `LazyVerticalGrid`, downscaled icons, no
   background services. Measure with `dumpsys meminfo <package>` (docs/05 §6).
