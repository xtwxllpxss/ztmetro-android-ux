# WP transitions + Metro recents prototype — evidence

Date: 2026-11-16. Target: LineageOS 23.2 / Android 16 (SDK 36), Redmi Note 5A
`ugglite`. No adb and no device in this task: everything below is built and
statically verified; the on-device visual check is left to the lead.

## Artifacts

| artifact | sha256 |
|---|---|
| `evidence/transitions-recents/launcher/ztmetro-start.apk` | `9af7ebb79d19dba5053158b510e6f9889b8e08984e83e8ddccdb864882ce230f` |
| `evidence/transitions-recents/actioncentre/ztmetro-actioncentre.apk` | `812fcae3b67a0b5b43c606c41b3941ea9b2455303b2decc60ac0fac97ca3d8bd` |
| `evidence/transitions-recents/ztmetro-recents-rro.apk` (throwaway, do **not** ship) | `edd78f0ce30a0c642411dafcb39d2eadf5221f7faf604b7f812c10530f126695` |

Copies used by the hashes above are also in
`evidence/transitions-recents/{launcher,actioncentre}/`.

## Part A — launcher-initiated launches

### Mechanism

`StartActivity.launchApp()` / `launchIntent()` now call

```java
startActivity(intent, ActivityOptions.makeCustomAnimation(
        this, R.anim.zt_turnstile_in, R.anim.zt_turnstile_out).toBundle());
```

`ActivityOptions.makeCustomAnimation` is the only public hook an ordinary app
has into the WMShell app-launch transition. The bundle is passed on the
`startActivity` call, so it wins over the target app's own
`windowAnimationStyle`. WMShell's `DefaultTransitionHandler` lists
`ANIM_CUSTOM` in `isSupportedOverrideAnimation()`, so the request is a
supported override rather than a silently dropped option.

The three launch paths all funnel through `launchApp(ComponentName)`:

* tile tap — `onTileTap()` → `launchApp(tile.component)`;
* app-list row — `onAppTap()` → `launchApp(app.component)`;
* long-press menus — both `onTileLongPress()` and `onAppLongPress()` sheet
  `OPEN` items → `launchApp(pressed.component)`;
* the appearance-sheet Settings shortcuts also go through
  `launchIntent(...)`, so no `startActivity` path in Start is left on the
  stock transition (only the Action Centre panel uses its own window
  animation).

### Numbers

| side | motion | duration | source |
|---|---|---|---|
| outgoing Start | `translateX 0 → -100%p`, `alpha 1 → 0` | **128 ms** | `core/zrnmetrocore.c:50` ROW_OUT_MS (UIXMobile TurnstileForwardOut 0.128 s) |
| incoming app | `translateX +100%p → 0`, `alpha 0 → 1` | **180 ms**, `startOffset` **33 ms** | `core/zrnmetrocore.c:51` ROW_IN_MS, `:52` ROW_STAGGER_MS |
| curves | out `accelerate_cubic`; in `decelerate_cubic` | — | WP out `e=t²`, in `e=1-(1-t)³` (`core/zrnmetrocore.c:1424-1475`); no overshoot |

The 33 ms offset is the same row stagger the in-launcher turnstile uses; it
lets the Start slab start moving before the app window appears, which is the
timing relationship the C turnstile has.

### Action Centre window animations

`Theme.ActionCentre` now sets
`android:windowAnimationStyle=@style/Animation.ActionCentre`:

* open: `ac_slide_up` = 260 ms bottom-up (`ztmetro.c:138` SHADE_MS);
* close: `ac_slide_down` = 220 ms top-down;
* open exit / close enter are `ac_stay` (the panel is translucent, Start stays
  visible behind it).

`ActionCentreActivity` no longer suppresses the window transition. Open is a
real window animation; close is a real window animation unless the user
dragged the panel partway down, in which case the drag travel finishes the
move and the window close animation is suppressed so it does not double.
`EXTRA_INSTANT` (launch from `ShadeBridgeService` behind the closing shade)
still explicitly suppresses open/close. This replaces the old in-content
`translationY` open animation; drag and snap-back still use `Motion.WP_LOG`.

`Theme.ZtmetroStart` also carries the same `zt_turnstile_*` pair as its
`windowAnimationStyle`, so a Start window that is the incoming/outgoing
window (for example another app starting HOME) does not fall back to a
Material transition.

### What this cannot change

The reverse transition — app → Start on Back/Home — is composed by WMShell
(`DefaultTransitionHandler` / Launcher3 `TouchInteractionService`), not by the
app. `startActivity` has no API to supply an animation for a task that another
process starts. The RRO/system-image route for that is out of scope here and
is listed in the roadmap as a WMShell change. A launcher APK alone cannot make
the return-to-home turnstile.

## Part B — recents

### Verdict first

**Short answer: a full Metro recents is not possible from an ordinary APK on
stock LineageOS 23.2.** Reading the task list is possible *only* after a
system-side change (framework RRO + reboot, and the app must be a factory
system package for the recents-flagged permissions). Removing another app's
task and replacing the gesture/3-button recents UI both require system-image
work. The prototype therefore implements both states and states the boundary
in the UI.

### What LineageOS 23.2 actually contains

Extracted from the real `lineage-23.2-20260812-microG-Mi8917.zip` system
image (`system.img` → `framework-res.apk`, `services.jar`;
`system_ext.img` → `Launcher3QuickStep.apk`, `SystemUI.apk`):

* `framework-res.apk` (`6e51f5ed…`) still declares
  `string/config_recentsComponentName` at framework id **`0x010402cb`**, value
  **`com.android.launcher3/com.android.quickstep.RecentsActivity`**.
* `services.jar` contains `RecentTasks.loadRecentsComponent`,
  `RecentTasks.isCallerRecents`, `ActivityTaskManagerService.isGetTasksAllowed`
  and `getRecentsComponent`, so the string is read at boot on this build.
* `Launcher3QuickStep.apk` declares the privileged task permissions:
  `START_TASKS_FROM_RECENTS`, `REMOVE_TASKS`, `MANAGE_ACTIVITY_TASKS`,
  `READ_FRAME_BUFFER`, plus `QUERY_ALL_PACKAGES`; its
  `com.android.quickstep.RecentsActivity` is the configured component.
* Neither `Launcher3QuickStep.apk` nor `SystemUI.apk` contains the string
  `config_recentsComponentName` in any of its dex files. WMShell/Launcher do
  not read the resource directly.

Raw output: `framework/recents-evidence.txt` and
`framework/rom-metadata.txt`. AOSP 16 source excerpt: `aosp16-source-excerpts.txt`.

### Who reads it, and what an ordinary app gets

From AOSP `android16-release`:

* `RecentTasks.onSystemReadyLocked()` → `loadRecentsComponent(Resources)` reads
  `config_recentsComponentName`, resolves the package with
  `MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS`, and records
  `mRecentsUid` + `mRecentsComponent`. **There is no `isSystemApp()` or
  privileged check in this method.**
* `ActivityTaskManagerService.isGetTasksAllowed()` returns `true` immediately
  when `isCallerRecents(callingUid)`, before `REAL_GET_TASKS`. So the UID of
  the configured component can see all recent tasks through the *public*
  `ActivityManager.getRunningTasks(int)` (`getTasks` in ATMS).
* `PackageManagerService` also reads the same string into `mRecentsPackage`,
  but only through `ensureSystemPackageName()`, which requires
  `MATCH_FACTORY_ONLY` — a data-installed APK is rejected.
  `PermissionManagerServiceImpl` grants the `signature|recents` permissions
  (`REMOVE_TASKS`, `MANAGE_ACTIVITY_TASKS`, `START_TASKS_FROM_RECENTS`) only to
  a package in `KnownPackages.PACKAGE_RECENTS`, i.e. that factory package.
* `GET_TASKS` is deprecated; in `isGetTasksAllowed` it only helps a
  **privileged** system image (`isUidPrivileged`). `REAL_GET_TASKS` is
  `signature|privileged`. `ActivityManager.getAppTasks()` lists only the
  caller's own tasks. `ActivityManager.getRecentTasks()` is filtered by the
  same `getTasksAllowed` flag; it is not a bypass.

Consequences for `org.ztmetro.actioncentre`:

| capability | ordinary app | after RRO-only (recents component, still data app) | needs factory/priv-app |
|---|---|---|---|
| list other apps' recent tasks | no (own + HOME only) | **yes** via `getRunningTasks` | — |
| tap task → `moveTaskToFront` | own tasks only | yes (`REORDER_TASKS` is normal) | — |
| remove another app's task | no | no (`REMOVE_TASKS` not granted) | yes |
| replace gesture/3-button recents | no | no | yes (WMShell/QuickStep) |
| app is `PACKAGE_RECENTS` | no | no (PMS factory check) | yes |

### The throwaway RRO

`rro/` contains a code-less dynamic RRO:

* package `org.ztmetro.overlay.recents`, `targetPackage="android"`,
  `isStatic=false`;
* one resource: `string/config_recentsComponentName` =
  `org.ztmetro.actioncentre/org.ztmetro.actioncentre.RecentsActivity`.

It was built and signed (`ztmetro-recents-rro.apk`,
sha256 `edd78f0c…`); the compiled table and manifest are in
`rro-resources.txt` / `rro-manifest.txt`. Static result: the resource name and
value are correct and the manifest targets `android`. **It was not installed
or enabled: this task has no adb and no device.** Therefore "what happens" at
runtime is not measured here. The exact test the lead should run is:

```sh
adb install -r evidence/transitions-recents/ztmetro-recents-rro.apk
adb shell cmd overlay enable --user 0 org.ztmetro.overlay.recents
adb shell cmd overlay list | grep ztmetro.overlay.recents
adb reboot
# after boot, open the recents activity:
adb shell am start -n org.ztmetro.actioncentre/.RecentsActivity
```

Expected, from the AOSP code above:

1. if OMS accepts the overlay (framework resources have no `<overlayable>`
   restriction on this string), `RecentTasks` picks up the new component on
   the next boot;
2. `getRunningTasks` starts returning *all* tasks to the Action Centre UID, and
   the prototype renders the WP card strip;
3. swipe-up dismissal still fails with `SecurityException` because
   `REMOVE_TASKS` is not granted to a data-installed `PACKAGE_RECENTS`;
4. the system gesture/3-button recents keeps opening the stock
   Launcher3QuickStep overview — the RRO changes a system_server read, not the
   WMShell/QuickStep UI path;
5. **the mismatch may also misclassify the stock overview transition.**
   `RecentsAnimation` (system_server) sets its target activity type to
   `ACTIVITY_TYPE_RECENTS` only when the client-supplied intent component
   equals the configured recents component, otherwise `ACTIVITY_TYPE_HOME`.
   Launcher3QuickStep still supplies its own `RecentsActivity` intent, so after
   the RRO that comparison fails. This is not measured on device here, but it
   is a concrete reason the RRO must stay throwaway and must not be shipped;
6. if OMS rejects a third-party overlay of `android`, the enable step fails
   and nothing changes. Either outcome is the experiment; it must be observed
   on the device, not assumed.

The RRO is evidence only. It is **not** referenced by either shipped APK and
must not be added to the launcher/action-centre build outputs.

### The prototype activity

`org.ztmetro.actioncentre.RecentsActivity` (exported; action
`org.ztmetro.actioncentre.RECENTS`; no LAUNCHER icon; `excludeFromRecents`):

* WP frame: black, 58 sp Light lower-case **`recent`**, caps `RECENT APPS`,
  24 dp page margin, flat cards 248×320 dp with 8 dp gaps;
* card: app icon, `TaskDescription` label (fallback: package label), flat
  `TaskDescription.getPrimaryColor()` or `#1F1F1F`, `TASK #id` + package;
* data: `ActivityManager.getRunningTasks(30)`, excluding this app and Start;
* cross-app task mode: tap → `moveTaskToFront(taskId, 0)`; swipe up → attempt
  hidden `ActivityManager.removeTask(taskId)` via reflection; on denial the
  card animates back and the UI says dismiss needs `REMOVE_TASKS`; no fake
  success;
* no-access mode: the WP frame plus an explicit statement of why and the exact
  system-side change (RRO + priv-app + WMShell/QuickStep), and, if the user
  grants Usage Access, a `RECENTLY USED APPS · USAGE ACCESS` list (tapping
  launches the app). That list is **not** a task list and does not claim to
  be; it is the only useful ordinary-app fallback.

### Exact system-side change required for a real Metro recents

1. Add `config_recentsComponentName` override to a framework RRO (or set it in
   `frameworks/base/core/res/res/values/config.xml` in a ROM build) pointing
   at `org.ztmetro.actioncentre/.RecentsActivity`. `ActivityTaskManagerService`
   reads it once in `onSystemReadyLocked()`, so a reboot is required.
2. Put the Action Centre APK in `/system_ext/priv-app` (factory package) so
   `PackageManagerService.ensureSystemPackageName()` accepts it as
   `PACKAGE_RECENTS`, which is what grants `REMOVE_TASKS`,
   `MANAGE_ACTIVITY_TASKS` and `START_TASKS_FROM_RECENTS`. Without this the
   prototype can list but not dismiss.
3. Replace the recents gesture/3-button UI: patch WMShell overview / ship a
   QuickStep replacement, or make Launcher3QuickStep's recents activity route
   to this one. `config_recentsComponentName` alone does not redirect the
   gesture or the recents key.

### Build / verify (no adb)

```sh
cd repo
EVIDENCE_DIR="$PWD/evidence/transitions-recents/launcher" \
  ./scripts/build-launcher.sh
ACTIONCENTRE_REMOTE_DIR=ztux/transitions/actioncentre-recents \
EVIDENCE_DIR="$PWD/evidence/transitions-recents/actioncentre" \
STATUS_STRIP_EVIDENCE_DIR="$PWD/evidence/transitions-recents/actioncentre" \
  ./scripts/build-actioncentre.sh
./evidence/transitions-recents/build-rro.sh
```

Both build scripts stage to `hermes-ubuntu` when the local host has no
android-36, build there, copy the APK back and compare the sha256 with the
remote one. The evidence copies above are the canonical artifacts for this
task. `actioncentre/build/out` is shared with parallel lock-overlay work and
can be rebuilt independently; deploy the evidence APK if a byte-for-byte
match with the reported hash matters.

Static checks in the build logs:

* launcher: `ActivityOptions.makeCustomAnimation` in `classes.dex`,
  `zt_turnstile_in/out` and `Animation.ZtmetroStart` in the resource table,
  compiled durations 180/33/128, no `uses-permission`, no AndroidX;
* Action Centre: `RecentsActivity` in the manifest and dex, `Theme.Recents`,
  `ac_recents_in/out`, `getRunningTasks`, `removeTask`, Usage/Reorder
  permissions, 2 MB size cap;
* RRO: target package/name/value and signed APK.

### Deploy / visual check for the lead

```sh
adb install -r repo/evidence/transitions-recents/launcher/ztmetro-start.apk
adb install -r repo/evidence/transitions-recents/actioncentre/ztmetro-actioncentre.apk
# tap a tile / an app-list row / long-press OPEN:
#   Start must slide left+fade in 128 ms, app enters from the right in 180 ms
#   after 33 ms. Press Home: the return is WMShell's, not ours.
adb shell am start -n org.ztmetro.actioncentre/.RecentsActivity
# expect the "No task list on stock LineageOS 23.2" frame plus the exact
# system-side change; grant Usage Access to see the fallback app list.
```

If `settings put global animator_duration_scale 0` is set, both window
animations become instant (system behaviour), which is the intended
WP-style "shorten, don't invent" model. Drag-close of the panel remains
scale-aware through `Motion.ms()`.

### Files touched

* launcher: `app/src/main/res/anim/zt_turnstile_{in,out}.xml`,
  `res/values/styles.xml`, `java/.../StartActivity.java`;
* Action Centre: `RecentsActivity.java`, `res/anim/ac_recents_{in,out}.xml`,
  `res/values/styles.xml`, `res/values/strings.xml`, `AndroidManifest.xml`,
  `ActionCentreActivity.java` (window open/close);
* `scripts/build-launcher.sh` (remote host support + transition checks),
  `scripts/build-actioncentre.sh` (recents resources/class checks);
* this directory.
