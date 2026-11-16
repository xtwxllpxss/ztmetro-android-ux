# 22 — Prior art, read as engineering: MetroWinLauncher and GridLauncher

Two still-maintained open-source Windows-Phone launchers are the closest prior art to
our Stage 1 (`L0` APK + accessibility + RRO). This document reads their code, not their
screenshots, and answers one question per feature: **how exactly do they do it, and what
does that mean for us?**

Snapshot and method:

- **MetroWinLauncher** — `developerkenshi-coder/MetroWinLauncher`, GPL-3.0-or-later,
  Kotlin + Compose, `minSdk 26` / `targetSdk 36` / `versionName 26.9.2S`
  `[F: app/build.gradle.kts:20-28]`. Read at commit **`720b4f6`**, 2026-11-16 09:31 UTC
  ("README: the tile mosaic as rows of images"), i.e. the tree the owner pointed at.
- **GridLauncher** — `Tgo1014/GridLauncher`, Apache-2.0, 110 stars. The **`main`
  branch contains no code** — only `README.md`, `LICENSE`, `.gitignore` and a CI
  workflow `[F: git/trees/main]`. The sources are on **`develop`**, 59 Kotlin files
  (95 blobs), Compose + `@Inject` + `eu.wewox.lazytable` + `dev.chrisbanes.haze`
  `[F: git/trees/develop]`.
  Everything below about GridLauncher is from `develop`.
- Method: targeted `raw.githubusercontent.com` fetches + `gh api …/git/trees?recursive=1`;
  **no clone**. Files actually read are listed in Appendix A. No device was touched, no
  adb, nothing installed.
- Licence verdicts are from the GitHub licence API (`spdx_id`), not from prose.
- Our own state is quoted from this repo at the same day, **working tree was dirty**
  (other agents were editing `actioncentre/` and `launcher/` while this was written), so
  for our side I cite class + method names and this repo's documents, not line numbers.
- Labels: `[F]` read in the cited source, `[E]` engineering estimate, `[?]` open question
  that only `ugglite` can answer.

---

## 0. Verdict first

1. **MetroWinLauncher is the best available spec for L0 ztmetro, and unusable as source.**
   GPL-3.0-or-later, a single 118 KB `LauncherApp.kt`, and a 929-blob tree. We take
   *mechanisms and numbers*, never code (§11).
2. **The three genuinely transferable mechanisms** are not the visible ones:
   a) the Action Center is **not** an activity and **not** the system shade — it is a
   `TYPE_ACCESSIBILITY_OVERLAY` window of the accessibility service, opened by a 20 dp
   transparent strip over the status bar (`OverlayActionCenter`);
   b) notification reads are **paced and gated** on "some launcher screen is visible"
   (`NotificationPacer`, 600 ms floor), which is why a launcher with live tiles is not a
   battery drain;
   c) the tile flip **swaps the face at the edge-on instant, in the same frame**
   (`snapTo(-90f)` right after the index change), which is what removes the classic
   "wrong face on the far side"/one-frame-stale-content jank.
3. **Their Metro lock screen is an overlay on top of Android's keyguard, not a
   replacement** — and their own documentation admits the supported form is a
   `DreamService` screensaver `[F: docs/DOCUMENTAZIONE.md:219-231]`. The overlay variant
   needs a hole in the picture for an under-screen fingerprint sensor and pre-building the
   window at `ACTION_SCREEN_OFF`. On `ugglite` (rear sensor) the hole is unnecessary —
   that whole class of hacks collapses to "add window on screen-off, remove on
   user-present".
4. **GridLauncher contributes a negative result**, worth recording: its "open the shade"
   is `Runtime.exec("service call statusbar 1")` `[F: OpenNotificationShadeUseCase.kt:1-10]`,
   which a normal app cannot do on modern Android. Its grid is also weaker than ours
   (fixed columns, no wide tiles, resize without re-packing ⇒ overlaps).
5. **The one feature they have that we entirely lack and that costs us least to add** is
   the tile publishing API: a `ContentProvider.call()` contract plus a
   `getCallingPackage()` identity rule (§7). Ideas-only, ~1 day, and it makes third-party
   apps feed live tiles.

---

## 1. Live tile model, and how content gets into it

**Model.** Two layers, both immutable data:

- `LiveKind` — one enum value per tile kind (Calendar, Photos, Album, People, Group,
  Phone, Mail, Weather, Battery, Storage, Alarm, Birthdays, News, Finance, Reminders,
  Clock…). This is the unit of *permission* and of *data loading*.
- `LiveTileData` — one immutable snapshot class holding a field per kind: `photos`,
  `people`, `nextEvent`, `events`, `missedCalls`, `unreadMail`, `torchOn`, `battery`,
  `weather`, `nowPlaying`, `storage`, `nextAlarm`, `birthdays`, `albums`, `groups`,
  `news`, `finance`, `reminders`, `extras` `[F: data/LiveTiles.kt:63-95]`.
  `@Immutable` + Compose means a tile recomposes only when its own field identity changes.

**Loading is kind-driven, never "load everything".** `LiveTileRepository.load(wanted =
LiveSources.all, albumIds, groupIds, …)` takes the set of kinds that are *pinned to Start*
and reads only those `[F: data/LiveTiles.kt:132-170]`. The two companions enforce the same
rule on updates and on unpinning:

- `LiveTileData.keeping(kinds)` drops the data of every kind that is no longer pinned, and
  prunes `albums`/`groups` to the ids still pinned `[F: :101-116]`;
- `LiveTileData.updatedWith(fresh, kinds)` copies only the kinds just re-read
  `[F: :118-127]`.

**Where the data comes from** — no widgets, no polling loop, no background service:

| Tile | Source `[F]` |
|---|---|
| Photos / Albums | `MediaStore.Images` with `QUERY_ARG_LIMIT` + `SORT_COLUMNS=DATE_ADDED DESC` + `BUCKET_ID IN (…)`; `ImageDecoder` decoded **already scaled** (`setTargetSize`) because the MediaStore thumbnail is ≤512 px and blurs in a wide tile; fallback `loadThumbnail`; LRU cache of 8 `[F: :173-333]` |
| People / Groups | `ContactsContract.Contacts` (`PHOTO_THUMBNAIL_URI IS NOT NULL`, starred first) and `ContactsContract.Data` group membership; face cache 16, cleared when exceeded `[F: :242-303]` |
| Calendar | `CalendarSearch(context).upcoming()` / `.next()` — the launcher's own query helper `[F: :156-160]` |
| Phone | `CallLog.Calls` where `TYPE=MISSED AND NEW=1 AND DATE > seenAt` — a "seen" watermark, not a count of everything `[F: :304-319]` |
| Mail | `MailSync.unread(context)` (their own IMAP store) `[F: :155]` |
| Battery / Storage / Alarm | `StorageInfo.read()`, `NextAlarm.read(context)` `[F: :157-158]` |
| Torch | `CameraManager.registerTorchCallback`, i.e. a callback, not a poll `[F: data/QuickActions.kt:173-181]` |
| Music | `NowPlaying` from the media session, held by `NotificationHub` `[F: :70-71]` |
| Other apps' live tiles | **their notifications**, via `NotificationListenerService` → `TilePeek(title, text)` per package `[F: notifications/NotificationHub.kt; data/ThirdPartyTiles.kt:59-63]` |
| Third-party apps | the publishing provider (§7) `[F: data/TilePublishProvider.kt, data/ThirdPartyTiles.kt]` |

**Update path.** `NotificationService` is a `NotificationListenerService` (declared with
`BIND_NOTIFICATION_LISTENER_SERVICE` `[F: AndroidManifest.xml:660-666]`). On any post it
does *not* re-read everything: it asks `NotificationPacer.changed(now, watched)` and
either reads now, reads after a delay, or idles `[F: notifications/NotificationPacer.kt:33-57]`.
`refresh()` then calls `getActiveNotifications()` once and rebuilds only entries whose
content changed (pictures/icons decoded lazily) `[F: notifications/NotificationService.kt:308-392]`.
The notification state is published to the UI through `StateFlow`s in the `NotificationHub`
object, because the service has its own lifecycle `[F: notifications/NotificationHub.kt:74-92]`.

**Comparison to us.** `LiveTiles.java` keeps a process-wide `HashMap<pkg, Entry(count,
title, text)>` with a `signature()` change detector `[F: launcher/…/LiveTiles.java:17-91,
164-230]`; `StartNotificationListener.onNotificationPosted` re-reads
`getActiveNotifications()` and republishes the whole map on *every* post
`[F: launcher/…/StartNotificationListener.java:41-56, 78, 137-149]`. So: same data source,
no pacing, no per-kind gating, no pictures, no reply actions, no per-app grouping, and the
tile face does not flip (see §2). We are strictly behind on everything except startup cost.

---

## 2. Flip animation: numbers, and the three tricks that stop jank

All timing is in one object, `Motion` `[F: ui/anim/Motion.kt:47-96]`:

| Quantity | Value | Note |
|---|---|---|
| `FLIP_HALF_MS` | **330** | one half-turn; a full flip is 660 ms |
| `SLIDE_MS` | **600** | the "slide" transition, 0→180° translation |
| `FlipAway` | `CubicBezierEasing(0.55, 0.085, 0.68, 0.53)` | face turning away: accelerates |
| `FlipIn` | `CubicBezierEasing(0.25, 0.46, 0.45, 0.94)` | next face settling: decelerates |
| first flip after | `1 500 ms + (seed % 6) × 800 ms` | per-tile stagger |
| hold on a face | `5 000 ms + (seed % 4) × 900 ms` | per-tile stagger |
| camera | `14 × density` | perspective without distortion |
| `metroTween(...)` | duration **and** delay divided by `MotionSpeed.factor` | one global speed knob `[F: :174-177]` |

The animation loop `[F: ui/anim/Motion.kt:284-328]`:

```kotlin
LaunchedWhileStarted(listOf(faces, reduced || saving, slide, resetKey)) {
    if (faces <= 1 || reduced || saving) return@LaunchedWhileStarted      // 1
    ...
    angle.animateTo(90f, metroTween(FLIP_HALF_MS, FlipAway))
    index = (index + 1) % faces                                          // 2
    angle.snapTo(-90f)                                                   // 2
    angle.animateTo(0f, metroTween(FLIP_HALF_MS, FlipIn))
    delay(5_000L + (seed.mod(4)) * 900L)
}
```

Why this does not jank, in order of importance:

1. **One `Animatable<Float>` drives `rotationX` only**, inside
   `Modifier.graphicsLayer { rotationX = …; cameraDistance = … }` `[F: :340-346]`. A
   render-thread property: no relayout, no remeasure, no allocation per frame, and the
   tile's Compose tree is not recomposed while it turns.
2. **The face changes at the edge-on instant, in the same frame** (`index = …` then
   `snapTo(-90f)`). Comment in the source: *"the old face is never drawn on the far side,
   and the new one never waits a frame"* `[F: :321-323]`. The alternative — animating to
   90°, then swapping — shows the old face mirrored for one frame or the new one late.
3. **Only one face exists in the tree**: `content(visible)` — a single child, not two
   stacked children with alpha cross-fade `[F: :337-349]`. The two-child form is only used
   for the *slide* transition, and only while moving (`moving` is a `derivedStateOf`
   `[F: :296, :330-336]`).
4. **Staggered, seeded schedule** (`seed % 6`, `seed % 4`): a Start full of tiles never
   flips in lockstep, which is what makes a 2 GB GPU hitch once instead of once per tile.
5. **Nothing animates off screen.** `LaunchedWhileStarted` is a coroutine bound to the
   lifecycle's *started* state: with another app in front the loop is not merely paused,
   it is cancelled and restarted `[F: :306]` (their docs make the same claim for media
   callbacks `[F: docs/DOCUMENTAZIONE.md:478]`).
6. **Reduced motion and battery saver disable the flip entirely** (`reduced || saving →
   return`) `[F: :306-308]`. The face keeps its content; nothing blinks.
7. A tile that returns from off screen mid-slide is settled onto the incoming face
   (`if (slide && angle.value >= 90f) index = …`) `[F: :309-311]`, so a reappearing tile
   never shows a half-scrolled frame.

Entry animation for a freshly pinned tile is a different, cheaper thing:
`Modifier.flipIn(index, stepMs = 55)` — a one-shot `rotationX` from −85° with `index × 55 ms`
stagger `[F: :145-162]`.

**Against our WP reference:** WP8.1's own tile flip is roughly 300–400 ms per half with a
quadratic-ish ease `[E, no frame-accurate measurement in docs/17-wp-design-reference.md]`.
MWL's 330 ms / `FlipAway`+`FlipIn` pair is a defensible approximation of that; our
`Motion.WP_LOG` ease `(1-0.1^t)/0.9` `[F: launcher/…/Motion.java:38-56]` is for presses and
transitions only. **We have no tile flip at all** — `MetroTileView` animates a press tilt
(`animate()` on rotationX/scale, `pressDelayMs`/`pressInMs`/`tiltDeg`
`[F: launcher/…/MetroTileView.java:126-150, 314-340]`) and nothing else.

---

## 3. Action Center: an accessibility-service overlay, not an activity, not the shade

**Two hosts, one panel.** The panel content is a Compose composable
(`ui/actioncenter/ActionCenter`). It is shown either inside the launcher window, or — over
any app — by `OverlayActionCenter` `[F: notifications/OverlayActionCenter.kt:64-70]`.

**How the swipe reaches it.** A **20 dp transparent strip** at the very top edge of the
screen, added to the *accessibility service's* `WindowManager`:

```kotlin
WindowManager.LayoutParams(
    MATCH_PARENT, (STRIP_DP * density).toInt(),          // STRIP_DP = 20
    TYPE_ACCESSIBILITY_OVERLAY,                          // 2032
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or
    FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSPARENT,
).apply { gravity = Gravity.TOP }.overBars()
```
`[F: :110-119]`; the touch listener opens the panel once `rawY - startY > 2 × touchSlop`
`[F: :95-110]`.

Why the *accessibility* window type matters (their comment and ours agree): an app window
(`TYPE_APPLICATION_OVERLAY`) sits **below** the status bar, so the system steals a swipe
that starts at the top edge; only `TYPE_ACCESSIBILITY_OVERLAY` (2032) is above
`TYPE_STATUS_BAR`. This single fact is the whole trick, and it is why our current design
(§ below) is the hard way round.

**Opening.** `open()` removes the strip, calls `NotificationHub.startWatching()`, and adds
a full-screen `TYPE_ACCESSIBILITY_OVERLAY` window (translucent, `LAYOUT_IN_SCREEN or
LAYOUT_NO_LIMITS`, `softInputMode = ADJUST_RESIZE` for the inline reply box) whose root view
overrides `dispatchKeyEvent` to close on `KEYCODE_BACK` `[F: :130-171]`. There is a
**watchdog**: if the panel window has not been dismissed after 2.5 s (it failed to draw),
it is removed anyway `[F: :169, 407]`, and closing is `animate up → removeViewImmediate`
after 1.5 s, again with a fallback timer `[F: :175-186, 410]`.

**No task, no recents entry, no activity transition** — that is the design win over our
`ActionCentreActivity`. Consequences they accept: the panel is not a "screen" for
`ActivityManager`, so they hand-manage lifecycle (`OverlayWindowOwner` implements
`LifecycleOwner` + `SavedStateRegistryOwner` for the Compose view `[F: :136-146]`), Back,
Ime resize and dismissal.

**Does it fight the system shade?** No: the strip swallows the down-swipe at the top edge,
so SystemUI's shade mostly never opens. It does not *replace* the shade — the stock shade
still exists and is still reachable (e.g. from inside the panel via
`GLOBAL_ACTION_QUICK_SETTINGS`, and the strip is deliberately shorter than the status bar
so the launcher's own pull-down keeps working `[F: :64-70]`). In games, the strip yields to
the status-bar-recall strip (`gameBarsAway`) `[F: LockAccessibilityService.kt:186-188]`.

**Quick toggles without system privileges** — four routes, tried in this order
`[F: data/QuickActions.kt:262-372; data/ShizukuButtons.kt:32-55; data/ShizukuRadio.kt:14-52]`:

| Route | Used for | Mechanism | Permission needed |
|---|---|---|---|
| **Shizuku (shell)** | WiFi, Bluetooth, airplane, mobile data, location, DND, rotation, auto-brightness, screen timeout, brightness, recents, screenshot | `rikka.shizuku` → `Shizuku.newProcess` (via reflection) running `svc wifi enable`, `svc bluetooth disable`, `cmd connectivity airplane-mode enable`, `svc data …`, `cmd location set-location-enabled true`, `cmd notification set_dnd …`, `settings put system accelerometer_rotation 0`, `input keyevent KEYCODE_SYSRQ` | Shizuku installed + running + granted; **optional, per button** |
| **System panels** | WiFi, internet/mobile data, Bluetooth, airplane | one-tap system sheet over the app: `Settings.Panel.ACTION_WIFI`, `ACTION_INTERNET_CONNECTIVITY`, `BluetoothAdapter.ACTION_REQUEST_ENABLE` | none |
| **Settings.System write** | rotation lock, auto-brightness, screen timeout | `Settings.System.putInt`, gated on `Settings.System.canWrite()` else `ACTION_MANAGE_WRITE_SETTINGS` | user-granted special access |
| **DND policy access** | quiet hours (incl. timed quiet hours + `Alarms` to turn it off) | `NotificationManager.isNotificationPolicyAccessGranted` + `setInterruptionFilter(PRIORITY/ALL)`; with Shizuku it skips the grant via `cmd notification` | special access |
| **Hardware APIs** | torch (incl. strength on 13+), media volume, ringer | `CameraManager.setTorchMode` / `turnOnTorchWithStrengthLevel`, `AudioManager` | none |

Everything falls through to "open the right system screen" (`hold()` opens the per-button
settings page, long-press on WiFi/Bluetooth opens the network/device list)
`[F: QuickActions.kt:86-105, 374-378]`. **They never ask for root, never for
`WRITE_SECURE_SETTINGS`, never for a system signature.** The four visible slots are
user-chosen; eight more sit behind them `[F: QuickActions.kt:69-78]`.

**Notification list inside the panel**: one row per notification or one group row per app
(`NotificationGroups.build(entries, grouped)`, newest-first, group placed at the app's
newest entry) `[F: notifications/NotificationGroups.kt:27-41]`; rows carry reply
(`RemoteInput` + the action's `PendingIntent`), up to three actions, long text, picture,
small icon, and a "dismissed lately" history `[F: NotificationHub.kt:34-72]`.
Optional: **hide the system copy** of a notification so only the Metro panel has it
(`cancelNotification` + a held local copy; ongoing/clearable/full-screen/group-summary
notifications are exempt) `[F: NotificationService.kt:173-207]` — powerful, and the thing
I would *not* copy (§12).

**Our current state** (`repo/actioncentre`, dirty tree): a real full-screen
`ActionCentreActivity` reached by `ShadeBridgeService`, an accessibility service that
watches `TYPE_WINDOW_STATE_CHANGED`, identifies the *system* shade window **by geometry**
(≥ 85 % of the display, because on this LineageOS build the shade reports as
`android.widget.FrameLayout`), sends `GLOBAL_ACTION_BACK`, and 80 ms later starts our
activity with an `EXTRA_INSTANT` flag; a recovery probe sends BACK at ~300 ms only if the
shade is still up `[F: actioncentre/…/ShadeBridgeService.java:28-39, 77-122, 196-205]`.
Visual result matches WP well (see `evidence/prior-art/mwl-4.png`), but the mechanism is:
wait for SystemUI to draw a shade → ask it to close → race it with our activity. That is
two animations the user did not ask for, and it depends on a geometry heuristic that a
LineageOS/SystemUI update can break. Quick toggles: `openPanel` for WiFi/internet, settings
intents for BT/airplane, `Settings.System` + `canWrite` for rotation/brightness, torch via
camera — **no Shizuku route** `[F: ActionCentreActivity.openPanel/toggleRotation/toggleBrightness,
DeviceState.java:17-105]`.

---

## 4. Metro lock screen: which accessibility APIs, and what Android 14+ does to them

**The honest framing first.** Their README says it plainly: *"Android does not let a
launcher replace its lock screen, so it works as the screensaver and has a preview in the
launcher"* `[F: README.md §lock screen]`, and the documentation lists three supported
surfaces: a `DreamService` screensaver, a preview inside the launcher, and the lock
wallpaper `[F: docs/DOCUMENTAZIONE.md:219-231]`. On top of that, the code also ships an
**optional overlay** (`lockOverlayOn`), which is what actually looks like a lock screen
`[F: gestures/LockOverlay.kt:58-337]`. Both exist; the overlay is the interesting
engineering.

**Service and its config.** One accessibility service does everything
(`LockAccessibilityService`): lock, recents, screenshot, power menu, split screen, the nav
bar, the status bar, the Action Center strip and the lock overlay
`[F: gestures/LockAccessibilityService.kt:24-35]`. Its config is deliberately minimal:

```xml
android:accessibilityEventTypes="typeWindowStateChanged"
android:accessibilityFeedbackType="feedbackGeneric"
android:canRetrieveWindowContent="false"
android:notificationTimeout="0"
```
`[F: app/src/main/res/xml/lock_accessibility_service.xml]`

So: **window-content access off, one event type, and that event used only to learn *which
package* is in front** `[F: LockAccessibilityService.kt:110-140]`. The APIs it calls:

| API | Use |
|---|---|
| `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` | lock (API 28+) `[F: :258-264]` |
| `GLOBAL_ACTION_RECENTS`, `…_DISMISS_NOTIFICATION_SHADE`, `…_TAKE_SCREENSHOT`, `…_POWER_DIALOG` (guarded by `SystemAction.minSdk`), `…_NOTIFICATIONS`, `…_QUICK_SETTINGS` | gestures/buttons `[F: :265-311, 326-340]` |
| `AccessibilityService.disableSelf()` | "turn it off for now" (banks) `[F: :311-317]` |
| `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | is it on? (pure parser, unit-tested) `[F: :309-322]` |
| **its own `WindowManager`** + `TYPE_ACCESSIBILITY_OVERLAY` | every window it draws over other apps, keyguard included |
| `KeyguardManager.isKeyguardLocked/isDeviceSecure` | is Android still locked `[F: LockOverlay.kt:104-116; LockAssist.kt:35-50]` |
| `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` | is a fingerprint enrolled `[F: LockOverlay.kt:287-300]` |
| `BiometricManager.getLastAuthenticationTime(DEVICE_CREDENTIAL)` (**API 35+)**, `SystemClock.elapsedRealtime()` | how long ago the PIN was entered → does Android still accept a fingerprint (72 h rule) `[F: LockOverlay.kt:120-136; LockAssist.kt:11-30]` |
| `TelephonyManager.getSimState` | SIM asks PIN/PUK ⇒ *do not* cover it `[F: LockOverlay.kt:139-147]` |
| `WindowManager.currentWindowMetrics` (API 30+) | screen size without `DisplayMetrics` `[F: LockOverlay.kt:265-274]` |
| `NotificationListenerService` (same app) | counts per app on the lock screen; reads only while a screen shows them `[F: LockOverlay.kt:258-260; NotificationPacer]` |

**How it draws over the keyguard — the lifecycle is the trick.** A window added *after*
the screen wakes appears late, so Android's own clock flashes first. So:

1. `ACTION_SCREEN_OFF` → **build** the overlay while the screen is already dark
   `[F: LockOverlay.kt:66-79, 181-263]`;
2. `ACTION_SCREEN_ON` → re-decide (PIN entered 72 h ago? SIM now locked?) and rebuild if the
   verdict changed `[F: :118-128]`;
3. `ACTION_USER_PRESENT` → lift (swipe-up animation, `LIFT_MS = 300`) and remove
   `[F: :159-167, 337]`;
4. phones that unlock *without* `USER_PRESENT` (fingerprint read before the screen came on,
   trusted place, 1-minute lock delay) → poll `isKeyguardLocked` every 80 ms, and remove
   after 4 consecutive "not locked" answers `[F: :104-116, 331-335]`;
5. another app's window comes over the keyguard (alarm, camera, incoming call of another
   dialer) → remove immediately `[F: :282-285]`; a ringing call of *their own* phone app
   removes it too `[F: :210-212]`.

Window parameters `[F: :227-241]`:

```
TYPE_ACCESSIBILITY_OVERLAY
FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS
| FLAG_HARDWARE_ACCELERATED  (+ FLAG_NOT_TOUCHABLE when the fingerprint hole is used)
PixelFormat.TRANSLUCENT      // so lifting it reveals Android's keyguard, not leftovers
```

**The swipe, and the fingerprint sensor.** If our full-screen window takes touches, an
under-screen fingerprint sensor gets no light and no touch — the phone cannot be unlocked.
Their fix is empirical and device-specific: a *hole* in the drawn picture at the sensor's
position (`lockSensorY`, radius `SENSOR_R`), the main window `FLAG_NOT_TOUCHABLE`, and two
sibling **`SwipeCatcher` windows above and below the hole** that forward touches into the
lock view with an offset (`TOUCH_GAP = 2.4 × radius`) `[F: :195-203, 243-262, 318-330]`.
Only when upright and only when the setting says the sensor is under the screen; otherwise
no hole. **Swiping up does not unlock anything**: it removes *our* window and leaves
Android's keyguard with its PIN/biometric untouched — deliberately, *"nothing is asked of
Android: no screen is opened to take its lock away, as that could leave the two fighting
over the screen"* `[F: :276-279]`.

**What Android 14+ actually does to this** (facts separated from guesses):

| Constraint | Effect here |
|---|---|
| No public API to replace or unlock the keyguard; `showWhenLocked` activities are *over* it only while that activity is up | the overlay is the only APK-level shape that looks like a lock screen `[F: their docs quote the same limit]` |
| `TYPE_ACCESSIBILITY_OVERLAY` (2032) is above `TYPE_STATUS_BAR`/keyguard | draws over both — this is why the whole stack works `[F: behaviour they rely on; same in our docs/04]` |
| Accessibility service enablement for a sideloaded APK | user must first allow *restricted settings* in App info → the "turn on" flow needs an explicit step in our onboarding `[E, Android 13+ behaviour]` |
| `android:isAccessibilityTool` | **not declared** by MWL `[F: xml above]` ⇒ Android 14+ shows the stronger "can view and control the screen" warning even though the service reads nothing; declaring `true`/`false` is now expected of us regardless |
| `FLAG_SECURE`/`isKeyguardLocked` state changes do not broadcast | hence the 80 ms poll; on API 35+ `getLastAuthenticationTime` is the only *supported* way to know the PIN-vs-fingerprint state, and MWL degrades to "Unsure" below 35 `[F: LockAssist.kt:16-32]` |
| Partial photo access (API 34) | lock/start wallpaper read is unaffected (own files), but photo tiles need `READ_MEDIA_VISUAL_USER_SELECTED` — they handle it `[F: data/LiveTiles.kt:26-47]` |
| Under-screen sensor | hole + `FLAG_NOT_TOUCHABLE` + forwarding windows; **`ugglite` has a rear sensor**, so none of this is needed for us `[E]` |
| Only device tested | Vivo X300 Pro; everything above is *observed* on one OEM skin `[F: docs/DOCUMENTAZIONE.md:877-899]` ⇒ on LineageOS 23.2/API 36 our results may differ `[?]` |

**Our current state:** an LSPosed keyguard module (`org.ztmetro.keyguard`) that restyles
SystemUI's clock/date/indication via seven guarded hooks exists but is **unverified on the
device** `[F: docs/18-coverage-and-build.md item 9; evidence/keyguard-module/REPORT.md]`,
and `docs/14-ux-roadmap.md` P8 rates a real lock-screen replacement as ROM-level (`D`)
only. We have **no overlay lock screen and no DreamService screen**. MWL shows that the
overlay is shippable *without* touching SystemUI — with a hard, honest caveat: it is a
picture on top of the keyguard, not a keyguard.

---

## 5. Tile grid: packing, resize, free mode

**Geometry.** The grid is made of **2×2 blocks**, `DEFAULT_COLUMNS = 4` small units (two
blocks across), `MORE_COLUMNS = 6`, hard ceiling `MAX_COLUMNS = 12`
`[F: data/TileGrid.kt:28-31]`. Sizes: `Small(1,1)`, `Medium(2,2)`, `Wide(4,2)`, `Large(4,4)`
`[F: model/Models.kt:10-16; TileGrid.kt:170-205]`.
A small unit is 79.5 dp at 360 dp width with 6 dp gaps and 24 dp sides, and a *custom*
column count is refused below 44 dp per unit `[F: :37-45, 49-56]`. Column count is
computed, not fixed: whole blocks that fit (`columnsFor`, landscape phone ⇒ 6)
`[F: :59-67]`.

**Packing** (`place`) `[F: :165-262]`: tiles flow in **pinning order**; for each tile the
first free spot is found block by block; `Wide`/`Large` need two adjacent blocks in the
same row; `Medium` one free block; `Small` any free cell of the current block. A tile that
is a group *lead* (`breaks`) starts on a fresh row so a title can sit above it. A tile can
also be **fixed** to a cell (`fixed`, "free mode"), with a defensive cap
`MAX_FREE_ROW = 200` so a corrupt saved file cannot allocate an enormous grid
`[F: :32-34, 234-241]`. Result is a `GridLayout(placements, rows)` — pure function, no
Android types, unit-tested (`app/src/test/…/TileGridTest.kt`).

**Resize** = change `TileSize` and re-run `place`; the tile keeps its position in the
order, not on the screen — the same contract our `TileStore.pin(component, size)` has.

**Drag & drop without 2-D collision code** (`dropTargets`) `[F: :92-160]`: candidate drop
places are computed by *simulating* the layout. For a small tile: free cells of every
non-full block. For any tile: take the tile out, put it back after each preceding tile,
re-run `place`, and keep the resulting placement **if it is exactly where the tile ends
up** — *"so a place is always exactly where the tile ends up… what the user sees is what
happens"*. That is a much better pattern than hit-testing a rectangle against tiles, and it
also gives free ghost-position previews for the drag UI.

**Our current state:** `MetroGridView` has ztmetro's own first-fit packer — six logical
units across, 1×1 / 2×2 / 4×2, medium and wide may start only on even cells (their C
source's rule), smalls fill the corners of a block, and the right-centre block is reserved
for the circled Start arrow `[F: launcher/…/MetroGridView.java:11-20, 138-200]`. Sizes come
from `TileStore` (`SIZE_MEDIUM/WIDE/SMALL`, seeded mix) and are changed through a
long-press dialog (`StartActivity.onTileLongPress` → size targets), **not by drag**. No
free placement, no folders, no drop previews, no multi-select.

Judgement: our packer is already equivalent in *output* for the WP look; what MWL has and
we do not is the **drag-edit interaction** and the **free mode**. Both are
interaction-layer reimplementations for us — the algorithm is the easy half.

---

## 6. App list and search

**App list**: all apps of every profile in alphabetical order, section headers, a right-edge
jump list, plus "recent (★)", "most used", "new", categories, rename/hide, and per-app lock
`[F: README.md §app list; ui/apps/…]`. Rows and tiles are TalkBack-labelled.

**Search** is the part worth studying `[F: data/AppSearch.kt; data/SearchIndex.kt]`:

- one `SearchIndex<T>` built from `SearchEntry(value, title, keywords, boost)`
  `[F: SearchIndex.kt:12-24, 106-139]`;
- **word folding** (case/diacritics) and word splitting so `Wi-Fi` → `wi`, `fi`
  `[F: :27-31]`;
- **initials**: `Google Maps` → `gm` `[F: :58-65]`;
- a **sorted vocabulary** (`TreeMap`) with postings, so a *prefix* is a range lookup, not a
  scan of every name `[F: :97-104, 123-139]`;
- **typo tolerance** with a capped **optimal string alignment** distance (bounded, so it
  stops early and never does O(n·m) full DP) `[F: :66-95, 202-204]`;
- keywords: per-glyph aliases in two languages, package words (`com.google.android.apps.maps`
  → `google`, `maps`), category label, profile words `[F: AppSearch.kt:21-58, 85-137]`;
- keyword (not title) matches only count from 4 letters, so `cal` finds Calendar/Calculator
  and not *Phone* for `call` `[F: AppSearch.kt:50-58]`;
- ranking = weighted match + usage boost, so frequently-opened apps come first among
  equals `[F: AppSearch.kt:26-47; SearchIndex.kt:176-210]`;
- all of it is pure Kotlin, JVM-tested (`TileSearchTest`, `AppSearchTest`-style).

The same index is reused for settings entries, Start content, contacts, Android settings
pages and the web, and for "quick answers" (calculator, units, dates, timers) — i.e. one
fuzzy index, many corpora `[F: README.md §app list]`.

**Our current state:** `AppListView` is a letterboard `ListView` with A–Z strip and a
**substring filter on the label** (`app.searchKey.contains(query)`, lower-cased)
`[F: launcher/…/AppListView.java:18-20, 79-120]`. No initials, no typo tolerance, no
keywords, no ranking, no search over settings/contacts. Our `StartActivity` also searches
via the same plain filter.

---

## 7. The tile publishing API (third-party app → its own tile)

The whole contract, end to end:

```
other app ──ContentProvider.call("publish", extras{title,text,count})
             authority `${applicationId}.tiles`, permission `${applicationId}.permission.PUBLISH_TILE`
        → TilePublishProvider.call()                     [data/TilePublishProvider.kt:18-22]
        → TileFeed.handle(context, enabled, callingPackage, method, extras)
        → ThirdPartyTiles.handle(...)                    [data/ThirdPartyTiles.kt:124-145]
        → ThirdPartyTiles.publish(...)  (sanitise, dedupe, cap, persist)
        → StateFlow<Map<String,ThirdPartyTile>>          [data/ThirdPartyTiles.kt:87-122]
        → tile flip face: TilePeek(title, text) + count as the tile number
```

Details that matter:

- **Identity is not trusted from the caller.** The provider passes
  `ContentProvider.getCallingPackage()` — filled in by the system — and
  `ThirdPartyTiles.handle` refuses a null/blank caller; a tile is only ever written for
  the caller's own package, *"whatever the call says"* `[F: ThirdPartyTiles.kt:120-129]`.
- **Permission**: `PUBLISH_TILE`, `protectionLevel="normal"`, declared by the launcher and
  required on the exported provider `[F: AndroidManifest.xml:41-46, 597-601]`. Normal =
  any app can request it at install; the real gate is the caller-identity rule plus the
  user's global switch (`thirdPartyTilesOn()`, checked before anything is stored).
- **Only `call` is implemented**; `query/insert/update/delete/getType` return null/0 —
  there is no data to read back `[F: TilePublishProvider.kt:24-29]`.
- **Untrusted text is cleaned**: control characters and odd spaces flattened, whitespace
  collapsed, trimmed, `MAX_TITLE = 60`, `MAX_TEXT = 160`, `MAX_COUNT = 99_999`, at most
  `MAX_TILES = 64` publishers kept, oldest evicted `[F: ThirdPartyTiles.kt:23-27, 39-53]`.
- **Idempotence to protect the disk and the UI**: a publish whose title/text/count equal
  the stored one returns the *same map instance*, so no write and no recomposition
  `[F: :43-52]`.
- State is JSON in `SharedPreferences` (`Prefs.TILES`) `[F: :65-84]`, exposed as a
  `StateFlow`.
- The published content surfaces as the tile's **flip face** and the tile's **number**;
  a publisher with neither title nor text only gets a number
  `[F: :59-63]`.
- Developer-facing docs describe it as "your app writes to its own tile" `[F: README.md
  §for app developers]` and they unit-test the contract (`TilePublishContractTest.kt`).

**Our current state: none.** No provider, no permission, no published-tile face. If we
adopt this we design **our own** authority/method/extras names (§11) — the *shape* is an
idea, the literal strings are theirs.

---

## 8. GridLauncher (Apache-2.0): small, and a useful negative result

| Aspect | What it does `[F]` | Worth adopting? |
|---|---|---|
| Distribution | `main` = README only; code on `develop`; APKs on Releases | — |
| Architecture | Compose + `@Inject`-constructor use cases and repositories + `injection/` modules (`domain/usecases/*`) | no (our launcher is plain Java, no AndroidX) |
| Grid model | `GridItem(id, app, x, y, width, height)` persisted; `Constants.gridColumns` fixed | no |
| Placement | `AddToGridUseCase`: scan `y` 0…max, `x` 0…columns−1 with `Rect.overlaps` for a **2×2 only** tile, else append below | no — weaker than our first-fit, no small/wide mix |
| Resize | `ItemGridSizeChangeUseCase`: copy `width/height` (Small 1×1, Medium 2×2, Large 4×2), remove and re-append **without re-packing** | **no — this is a bug source** (overlaps) |
| Move | `MoveGridItemUseCase`: ×1 arrow steps with clamp, not drag | no |
| App list | `AppListScreen`: `LazyColumn` + `filterString` substring filter; floating search FAB | no |
| Shade | `OpenNotificationShadeUseCase`: `Runtime.getRuntime().exec("service call statusbar 1")` (+ `EXPAND_STATUS_BAR`) | **no** — requires system privileges; dead for a normal app on modern Android `[E]` |
| Live tiles / notifications / lock screen | none (no `NotificationListenerService`; `BIND_APPWIDGET` commented out) | — |
| Rendering | `eu.wewox.lazytable` + `dev.chrisbanes.haze` (real blur) | no — two extra deps and GPU blur on Adreno 308 |

Verdict: **Apache-2.0 is the licence we would want, but there is nothing here we need.**
The only reusable *idea* is "grid items with explicit x/y/w/h", which we already have in
`TileStore`. Its `service call statusbar` also documents the trap we must avoid: shell
commands are not a UI integration strategy.

---

## 9. Comparison table: MetroWinLauncher vs ours

Effort = my estimate for *us* to reach their level of the feature, clean-room, on
`ugglite` (S ≈ ≤1 d, M ≈ 2–4 d, L ≈ 1–2 weeks, XL = ROM-level). "Our state" is at
2026-11-16, dirty tree.

| # | Feature | Their approach (class / API) | Our current state | Effort | Licence note |
|---|---|---|---|---|---|
| 1 | Live tile data model | `LiveKind` + immutable `LiveTileData`; `LiveTileRepository.load(pinned kinds)`; providers only, no widgets `[LiveTiles.kt:63-170]` | `LiveTiles.Entry(count,title,text)` per package, process-wide map `[LiveTiles.java]` | M | **GPL — ideas only.** Our data classes are ours (already exist) |
| 2 | Live content updates | `NotificationListenerService` + `NotificationPacer` (600 ms floor, read only while watched) + `getActiveNotifications()` diff `[NotificationPacer.kt, NotificationService.kt]` | re-read + republish **every** post; no pacing | S–M | GPL — ideas only (concept is platform API) |
| 3 | Flip animation | `FlipContainer`: 330 ms halves, `FlipAway`/`FlipIn`, edge-on face swap, `graphicsLayer(rotationX)`, seeded stagger, stops when not started `[Motion.kt:284-349]` | **none**; press tilt only `[MetroTileView.java]` | S | GPL — ideas + numbers only; our `Motion.WP_LOG` easing may differ |
| 4 | Action Center hosting | `TYPE_ACCESSIBILITY_OVERLAY` window + 20 dp top strip in the a11y service; same Compose panel in and out of launcher `[OverlayActionCenter.kt:90-186]` | own `ActionCentreActivity`; `ShadeBridgeService` detects SystemUI shade by geometry, BACK, then starts activity `[ShadeBridgeService.java]` | M (Java rewrite of the window host) | GPL — ideas only; window types/flags are platform facts |
| 5 | Quick toggles | 4 routes: Shizuku `svc/cmd` (per-button opt-in), system panels, `Settings.System` + `canWrite`, DND policy access `[QuickActions.kt:262-372; ShizukuButtons.kt:32-55]` | panels + `Settings.System`; no Shizuku route | M | GPL — ideas only; Shizuku is Apache-2.0 (rikka) → that dependency itself is reusable |
| 6 | Metro lock screen | overlay on keyguard: `SCREEN_OFF` prebuild, `USER_PRESENT` remove, keyguard poll, `TRANSLUCENT`, sensor hole + `FLAG_NOT_TOUCHABLE` + swipe catchers; separate `DreamService` screensaver `[LockOverlay.kt]` | LSPosed keyguard module (clock/date only), unverified; no overlay, no DreamService `[docs/18 item 9; docs/14 P8]` | L | GPL — ideas only. `DreamService` + overlay mechanics are platform APIs |
| 7 | Tile grid packing | 2×2 block grid, first-fit in pin order, `dropTargets` by re-simulating `place`, free mode, folders, `MAX_FREE_ROW` guard `[TileGrid.kt:92-262]` | first-fit, 6 units, 3 sizes, even-cell rule, reserved arrow cell; no drag/free mode/folders `[MetroGridView.java]` | M for drag; L with folders/free mode | GPL — ideas only. Our packer already exists; do **not** port theirs |
| 8 | App list & search | prefix index (sorted vocabulary + postings), initials, bounded OSA typo distance, keyword aliases, usage boost `[AppSearch.kt, SearchIndex.kt]` | alphabetical letterboard + A–Z strip + substring filter `[AppListView.java]` | M | GPL — ideas only; a search index is textbook, write our own |
| 9 | Tile publishing API | `ContentProvider.call("publish"/"clear")`, `PUBLISH_TILE` normal permission, `getCallingPackage()` identity, sanitise/cap/dedupe, JSON in prefs `[TilePublishProvider.kt, ThirdPartyTiles.kt]` | **none** | S | GPL — ideas only. **Define our own authority/method/extras names.** |
| 10 | Notification rows | reply via `RemoteInput`+`PendingIntent`, up to 3 actions, long text, picture, small icon, group rows, "dismissed lately", snooze/quiet `[NotificationHub.kt, NotificationGroups.kt]` | rows with title/text/open/cancel (`Notif.java`, `NotificationRowView.java`) | M (reply is the hard part) | GPL — ideas only |
| 11 | Hide system-shade copies | `cancelNotification` + held local copy, with exemptions `[NotificationService.kt:173-207]` | not done | S | GPL — ideas only. **Recommend not doing it** (§12) |
| 12 | Widgets in tiles | `AppWidgetHost` as a tile type | none | L | GPL — ideas only; platform API |
| 13 | Backup / profiles / folders / themes | JSON backup + AES-GCM sync, work profile + private space, 3 themes `[README]` | no backup, no profiles, 1 look + RRO | XL | GPL — ideas only |
| 14 | Whole app shape | one `ComponentActivity`, Compose navigation, 929-blob tree, `LauncherViewModel` 134 KB | plain Java `Activity`/`View` + ~6.5 kLOC, no AndroidX | — | GPL — **do not merge, do not vendor, do not port files** |

---

## 10. Ranked adopt list (10 items, with the concrete first step)

Order = value to the owner's "feels like Windows Phone, still a daily phone" ÷ risk.
"First step" is a single action that can start today, not a plan.

**A1 · Action Center as an accessibility overlay, not an activity.** *(M)*
Replace the shade-BACK race with: a 20 dp `TYPE_ACCESSIBILITY_OVERLAY` strip added by
`ShadeBridgeService` (it already is an accessibility service — no new permission), and on
swipe-down a full-screen `TYPE_ACCESSIBILITY_OVERLAY` window hosting the *existing*
`ActionCentreActivity` view hierarchy as a plain `View` (build it with a
`LayoutInflater`/`attachToWindow` path, not `setContentView`).
*First step:* add a debug-only strip window to `ShadeBridgeService`, verify on `ugglite`
that a top-edge swipe reaches us while an app is in front (`logcat` line + screenshot).
Licence: ideas only.

**A2 · The tile flip, with WP-credible timing.** *(S)*
`330 ms` halves, `FlipAway`/`FlipIn` cubic-beziers, face swap at `rotationX = ±90` in one
frame, seeded stagger (`1.5 s + seed%6 × 800 ms`, hold `5 s + seed%4 × 900 ms`), animation
cancelled when `Activity` is not started, disabled under "remove animations" and battery
saver, `cameraDistance = 14 × density`.
*First step:* wire `LiveTiles.Entry(title/text)` into `MetroTileView` as a second face and
animate `rotationX` (it already owns `setCameraDistance`); measure with
`dumpsys gfxinfo org.ztmetro.start` before/after.
Licence: numbers and mechanism are unprotectable; our easing stays our own.

**A3 · Read pacing for the notification listener.** *(S)*
One `Pacer` class (600 ms floor, "read only while a launcher screen is watching",
`stale` flag read on resume) in front of our current full `getActiveNotifications()`
rebuild. Cuts wakeups on apps that post constantly (downloads, music, step counters).
*First step:* count `onNotificationPosted` calls/min on the device for 10 min, then gate.
Licence: ideas only.

**A4 · Tile publishing API for third-party apps.** *(S)*
`ContentProvider.call()` + `getCallingPackage()` identity + a `PUBLISH_TILE`-style normal
permission + our own sanitising/capping/dedupe + the published text as flip face and count
as the tile number.
*First step:* write the contract in our own words as `docs/23-tile-publish-contract.md`
(ours, not theirs), then implement the provider in ~150 lines of Java. **Choose our own
authority string and extras.** Licence: ideas only.

**A5 · Shizuku route for the four radio toggles.** *(M)*
`svc wifi|bluetooth enable|disable`, `cmd connectivity airplane-mode`, `svc data`, per
button opt-in, graceful fall-through to the system panel when Shizuku is absent — exactly
their routing table, re-expressed for Java, with the rikka Shizuku **client library**
(Apache-2.0, reusable with attribution).
*First step:* confirm on `ugglite` that Shizuku can be installed and that
`Shizuku.newProcess("svc","wifi","enable")` works from our app; if not, drop the item.

**A6 · Search index with initials + bounded typo distance.** *(M)*
Folded words, initials map, sorted-vocabulary prefix postings, capped OSA distance,
keyword aliases (glyph names/package words RU+EN), usage boost.
*First step:* extract our app list into a `List<Row(label, pkg, usage)>` and add an
`initials()` match beside `contains()`; ship that alone.

**A7 · Notification row model upgrade (reply + actions + pictures).** *(M)*
`RemoteInput` reply through the action's `PendingIntent`, ≤3 actions, long text, picture,
group rows (`NotificationGroups`-style build: newest-first, one row per app ≥2).
*First step:* implement reply for one app family (SMS/Telegram) and test on the device;
it is the only row feature that users notice daily.

**A8 · Lock-screen overlay, ugglite-shaped.** *(L)*
Overlay window added on `ACTION_SCREEN_OFF`, removed on `USER_PRESENT` / keyguard-not-locked
(4 × 80 ms poll) / another app's window; `TRANSLUCENT` so the keyguard shows through when it
lifts; **no fingerprint hole needed** (rear sensor on RN5A); keep our LSPosed clock as the
under-layer. Plus a `DreamService` screensaver as the no-risk first surface.
*First step:* ship the `DreamService` screensaver (zero overlay risk, previewable from
Settings), then evaluate the overlay only after A1 proves the a11y window host works.
Licence: ideas only; our LSPosed module is separate and stays.

**A9 · Drag-edit with simulated drop targets.** *(M after A2)*
`dropTargets` idea: candidate positions by re-running `place()` for candidate orders, so the
ghost position *is* the result. Needs a drag model in `MetroGridView` (currently
`onInterceptTouchEvent`-free).
*First step:* add long-press drag of a tile with a snapped ghost and "drop = reorder in
`TileStore`" only (no free placement, no cross-page), gated behind a hidden setting.

**A10 · Live tile data discipline.** *(M)*
Load only pinned kinds, drop data when unpinned, decode images already-scaled on a worker,
LRU caches with caps, equality that ignores bitmap identity.
*First step:* make `LiveTiles` keep *per-kind* data and add `keeping(pinned)` equivalent;
measure `dumpsys meminfo org.ztmetro.start` before/after.

**Not adopted, deliberately:** hiding system-shade notification copies (A10 in their
design), in-tile `AppWidgetHost`, work-profile/private-space handholding, their theme
engine, and any file-level port of their code.

---

## 11. Licence playbook: what "ideas only" means in practice

**MetroWinLauncher is GPL-3.0-or-later.** Any file-level reuse — Kotlin or translated to
Java — makes our launcher a derivative and therefore GPL-3. Our repo is MIT for artefacts
and CC-BY-4.0 for prose, and the owner has not agreed to relicense the launcher. So:
**read for facts, write our own code.** Allowed and done here:

- reading source to learn *which platform API* is used, *which window type/flags*, *which
  timing constants*, *which failure modes* — these are functional facts;
- re-deriving the same numbers (330 ms, 600 ms, 72 h) — facts are not protected;
- re-implementing an *interface shape* (a provider with `publish`/`clear`) — interfaces and
  ideas are outside copyright in the jurisdictions we care about; **still design our own
  names and semantics**, so no one can argue text copying.

Not allowed / avoided:

- copying Kotlin files, comments, doc text (`DOCUMENTAZIONE.md`), string resources, drawables
  or the Selawik font from their tree (font is OFL, but take it from Microsoft/SIL, not from
  their repo);
- translating their functions statement-by-statement ("clean room" means spec-first:
  we write down behaviour in our own doc, then implement from that doc — this is why A4's
  first step is to write *our* contract doc);
- the README screenshots as shipped assets. The three fetched screenshots live in
  `evidence/prior-art/` as **quoted evidence with attribution**, not in any APK.

**GridLauncher is Apache-2.0 — code reuse is permitted** with: keep the licence text,
keep/attach `NOTICE`-style attribution, state changes. But: `main` has no code, the code
lives on `develop`, it is Compose + Haze + LazyTable, and nothing in it beats our
implementation. **Conclusion: no code taken; the `service call statusbar` dead end and the
resize-without-repack bug are recorded as prior art, not as technique.**

**Third-party dependencies that *are* reusable** (independent of either launcher):
rikka **Shizuku** client (Apache-2.0) for shell-privileged toggles; nothing else is needed.

---

## 12. Risks, and things I would not copy even if licensed

1. **Cancelling other apps' notifications** so only our panel shows them
   (`NotificationService.hideFromAndroid`) builds a parallel shade that fights SystemUI:
   a listener cancel is visible in the app (`REASON_LISTENER_CANCEL`), some apps repost
   immediately, and on a LineageOS build a missed exemption makes notifications vanish
   from the stock shade *and* our panel. Keep Android's shade as the source of truth.
2. **Full-screen overlay on the keyguard** with `FLAG_NOT_TOUCHABLE` + forwarding windows:
   on a 2 GB phone a second full-screen hardware-accelerated window is real GPU cost, and
   any mistake in the keyguard poll leaves a dead screen over a locked phone. Ship the
   `DreamService` first.
3. **Their 20 dp strip swallows the top-edge swipe** system-wide. On our build the status
   bar is already Motif-wrapped by LSPosed; two owners of the top edge must be sequenced
   (strip only when the stock shade is not wanted), or the user loses the stock shade.
4. **Their app shape (929-blob tree / 118 KB `LauncherApp.kt`)** as a design template: their architecture
   is a product, ours is a shell. Do not let the adopt list grow into "port their app".
5. **Android-version drift:** they test on one OEM skin (Vivo) `[F: their limits section]`.
   Every overlay/flip claim needs a check on `ugglite` (LineageOS 23.2, API 36) before it
   is "adopted"; that is why every A-item above names a device observation.

---

## 13. Open questions for `ugglite` `[?]`

1. Does a `TYPE_ACCESSIBILITY_OVERLAY` strip receive a top-edge down-swipe on our
   LineageOS build while another app is in front, and does the stock shade stay closed?
2. Does the same window type draw over our keyguard on API 36 (MWL only proves Vivo), and
   does lifting it (300 ms) flash Android's clock?
3. Cost of a full-screen `TYPE_ACCESSIBILITY_OVERLAY` Compose/View window on 2 GB: one
   frame budget measurement with `dumpsys gfxinfo` while the panel is up.
4. Can `Shizuku` be installed/permitted on this ROM, and does `svc wifi enable` work from
   our UID via Shizuku?
5. Does our `StartActivity` stay resident enough for a seeded flip loop to be worth it, or
   does HOME recreate it often enough that the flip must start from a stored phase?
6. Rear-fingerprint `ugglite`: any `FLAG_NOT_TOUCHABLE` trickery needed at all for
   unlocking through our lock overlay? (Expect: no.)

---

## Appendix A — files read

MetroWinLauncher `@720b4f6`: `README.md`; `app/build.gradle.kts`; `app/src/main/AndroidManifest.xml`;
`app/src/main/res/xml/lock_accessibility_service.xml`; `docs/DOCUMENTAZIONE.md` (§lock screen,
§limits); and
`MainActivity.kt`, `data/LiveTiles.kt`, `data/TileGrid.kt`, `model/TileState.kt`,
`data/QuickActions.kt`, `data/Shizuku*.kt`, `data/TileStore.kt`, `data/TilePublishProvider.kt`,
`data/ThirdPartyTiles.kt`, `data/AppSearch.kt`, `data/SearchIndex.kt`,
`notifications/NotificationHub.kt`, `NotificationGroups.kt`, `NotificationService.kt`,
`NotificationPacer.kt`, `OverlayActionCenter.kt`, `gestures/LockAccessibilityService.kt`,
`LockOverlay.kt`, `LockAssist.kt`, `ui/anim/Motion.kt`, `ui/start/TileFaces.kt` (skimmed).

GridLauncher `@develop`: `README.md`, `AndroidManifest.xml`, `ui/MainActivity.kt`,
`ui/home/GridScreen.kt`, `ui/home/AppListScreen.kt`, `ui/composables/TileLayout.kt`,
`ui/composables/GridTile.kt`, `domain/usecases/{AddToGrid,MoveGridItem,ItemGridSizeChange,OpenNotificationShade}UseCase.kt`,
`domain/models/{TileSize,TileSettings}.kt`, `data/AppIconManagerImpl.kt` (skimmed).

Our side: `launcher/…/{StartActivity,MetroGridView,MetroTileView,TileStore,LiveTiles,StartNotificationListener,AppListView,Motion}.java`;
`actioncentre/…/{ActionCentreActivity,ShadeBridgeService,DeviceState,QuickGrid,QuickTile,NotificationRowView}.java`;
`docs/14-ux-roadmap.md` (P8), `docs/16-ux-gap-analysis.md`, `docs/18-coverage-and-build.md`
(item 9), `docs/21-performance.md`.

## Appendix B — evidence

| File | What it shows |
|---|---|
| [`../evidence/prior-art/mwl-4.png`](../evidence/prior-art/mwl-4.png) | MWL Action Center over another app: 4 square quick tiles with labels under the glyph, active = accent, volume/brightness as thin sliders, sound/vibration/silent row, `all settings` / `clear all` text links, lowercase `notifications` header, grouped row ("2 notifications"), "dismissed lately (7)" history. Fetched from their `fastlane` screenshots; quoted as evidence, GPL project. |
| [`../evidence/prior-art/mwl-7.png`](../evidence/prior-art/mwl-7.png) | MWL Metro lock screen: photo, big light clock bottom-left with `PM`, date under it, per-app icon+count row, bell count and weather line. Same caveat. |
| [`../evidence/prior-art/mwl-1.png`](../evidence/prior-art/mwl-1.png) | MWL Start: no header, tiles flush from the top with ~2 px black gutters, white silhouette glyphs, label bottom-left on medium tiles, count top-right (`3` on Phone), one wide photo tile, calendar tile carrying a big `27` + `Tuesday`. Same caveat. |

## Appendix C — how to re-verify this document

```sh
# MetroWinLauncher at the commit this doc describes
curl -s https://api.github.com/repos/developerkenshi-coder/MetroWinLauncher/commits/HEAD | jq -r .sha
# one file, no clone
curl -s https://raw.githubusercontent.com/developerkenshi-coder/MetroWinLauncher/<sha>/app/src/main/java/com/metrowin/launcher/data/TileGrid.kt | sed -n '92,160p'
# GridLauncher: code is NOT on main
curl -s https://api.github.com/repos/Tgo1014/GridLauncher/contents/app?ref=develop
```

Read next: [`17-wp-design-reference.md`](17-wp-design-reference.md) for the frame-accurate
WP numbers we are matching, [`16-ux-gap-analysis.md`](16-ux-gap-analysis.md) for our current
gap, [`21-performance.md`](21-performance.md) for the 2 GB frame budget these animations
have to fit into, and [`09-existing-projects.md`](09-existing-projects.md) for the wider
prior-art landscape (this document supersedes its MetroWinLauncher/GridLauncher rows with
source-level detail).
