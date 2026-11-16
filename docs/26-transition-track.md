# 26 — App → HOME transition track (LineageOS 23.2 / Android 16)

Research-only track for one boundary: opening an app from ztmetro Start already
uses our own `ActivityOptions` animation, but the return from an app to the HOME
screen is played by the platform. This document answers who plays it, what can be
changed without a ROM, and what a ROM change would cost. It does not claim a
device acceptance: no adb was available to this track, so every runtime statement
is either source-level or an explicit measurement step for the lead.

Evidence bundle: [`evidence/transition-track/`](../evidence/transition-track/REPORT.md)
(experiments, APKs, dumps, verbatim source excerpts), with the device procedure in
[`measurement-plan.md`](../evidence/transition-track/measurement-plan.md). Versions used throughout:

| component | revision |
|---|---|
| `android_frameworks_base` | `lineage-23.2` @ `797e43244b96c6a0dff5c81b21c4d4be665b082e` |
| `android_packages_apps_Launcher3` | `lineage-23.2` @ `35ec64cec8f03580ae3b1b634ba137aaac4680af` |
| device | SDK 36 (Android 16), 720×1280, per `evidence/verify/20261116-142854/report.md` |

## 0. Verdict

- **A (no root): impossible to replace.** The three animation-scale settings can
  make the stock animation faster or instant. They cannot change what it is. There
  is no public API by which an app — including the HOME app — can animate the
  outgoing app's surface.
- **B (Magisk/LSPosed): possible, with two different ceilings.** A preinstalled
  static RRO that replaces the framework anim XMLs (`task_*` for the opaque
  Start, `wallpaper_open_*` for a wallpaper-showing home) is a real, cheap replacement **if WMShell's `DefaultTransitionHandler` owns the transition**. If
  Launcher3/Quickstep owns it, the RRO is dead code and only a LSPosed hook into
  Launcher3 (or the Shell remote-transition path) can retarget it. Both B routes
  are **2D only**: Android's window/surface animation is an affine 2D transform,
  so a literal WP 3D turnstile is not reachable this way.
- **C (ROM build): a clean 2D WP-style transition is one file plus one class. A
  literal 3D turnstile is not one file.** It needs the app surface captured into a
  texture and rendered with a perspective transform, i.e. new compositor/renderer
  work, not a tweak to `QuickstepTransitionManager`.

## 1. Who exactly plays app → HOME on this build (Q1)

### 1.1 The generic Android 16 path

App → HOME is a **Shell transition**. `system_server`'s
`Transition.calculateTransitionInfo()` builds the `TransitionInfo` from the
visibility changes (`services/core/java/com/android/server/wm/Transition.java:3155-3165`).
The HOME task is normally already alive, so its change is `TRANSIT_TO_FRONT`; the
app's change is `TRANSIT_TO_BACK` (`Transition.java:3895-3913` decides visible
changes). A cold home start can instead produce `TRANSIT_OPEN`/`TRANSIT_CLOSE`.
The exact `TransitionInfo.getType()` for the phone's HOME key path is recorded by
`dumpsys window transitions`; both `TO_FRONT` and `OPEN` resolve to the same
`task_open_*` XML names (§2), so the resource recommendation does not depend on
that distinction.

WMShell (in the SystemUI process) dispatches the transition to handlers in reverse
registration order. The order is fixed in `Transitions`:

- `DefaultTransitionHandler` is added first (`Transitions.java:353-361`);
- `RemoteTransitionHandler` is added second (`:362-364`);
- `dispatchTransition()` walks the list from the end, so **Remote runs first and
  Default is the last resort** (`Transitions.java:1059-1085`).

`RemoteTransitionHandler.startAnimation()` searches its filters; if none matches it
returns `false` and Default gets the transition
(`RemoteTransitionHandler.java:117-153`).

### 1.2 The Launcher3QuickStep remote transition

`QuickstepTransitionManager.registerRemoteTransitions()` registers a
`RemoteTransition` named `QuickstepLaunchHome`
(`quickstep/src/com/android/launcher3/QuickstepTransitionManager.java:1308-1345`),
called from `QuickstepLauncher` (`.../uioverrides/QuickstepLauncher.java:365`).
Its `TransitionFilter` requires all of:

| requirement | value | source |
|---|---|---|
| home change | `ACTIVITY_TYPE_HOME`, `mTopActivity == mLauncher.getComponentName()`, mode `OPEN` or `TO_FRONT` | `QuickstepTransitionManager.java:1329-1334` |
| app change | `ACTIVITY_TYPE_STANDARD`, mode `CLOSE` or `TO_BACK` | `:1336-1337` |
| exclusion | not keyguard-going-away | `:1321-1322` |
| exclusion | no independent **task** change with a custom animation that overrides the task transition | `:1339-1342` + `core/java/android/window/TransitionFilter.java:262-277` |

If it matches, the whole transition is delegated to Launcher. The closing
animation is programmatic, not an XML resource:

- `WallpaperOpenLauncherAnimationRunner.onAnimationStart()` →
  `createWallpaperOpenAnimations()` (`QuickstepTransitionManager.java:1956-1996`,
  `1800-1830`);
- the normal case is `getClosingWindowAnimators()`: a `RectFSpringAnim` plus
  launcher workspace reveal (`:1546-1588`);
- the fallback is a 250 ms translate/fade `ValueAnimator`
  (`CLOSING_TRANSITION_DURATION_MS = 250` at `:226`, fallback at `:1665-1760`).

**The filter's `mTopActivity` is the decisive fact.** It is
`mLauncher.getComponentName()` — `com.android.launcher3/...` — not "whatever is
HOME". ztmetro Start is a different component (`org.ztmetro.start/.StartActivity`),
and the documented install sets it as the default HOME
(`docs/18-coverage-and-build.md:276-278`, `evidence/launcher/REPORT.md:4-5`).
Therefore, while Start is HOME, **Quickstep's filter cannot match**: requirement 0
fails before the app-close requirement is even considered.

### 1.3 The answer for the documented configuration

On the documented configuration — **ztmetro Start is default HOME and the device
has no on-screen nav bar, so HOME is a key event** — the owner is:

> **`com.android.wm.shell.transition.DefaultTransitionHandler`**,
> reached through WMShell's `Transitions` dispatcher after
> `RemoteTransitionHandler` found no matching filter.

For a `TRANSIT_TO_FRONT` transition there are two possible stock pairs, chosen by
`DefaultTransitionHandler.getWallpaperTransitType()` (`DefaultTransitionHandler.java:1031-1059`):

1. **If any change in the transition reports `FLAG_SHOW_WALLPAPER`** — the normal
   case for a translucent launcher over the wallpaper, e.g. stock Launcher3 — the
   helper returns `WALLPAPER_TRANSITION_OPEN` and the earlier wallpaper branch
   wins (`TransitionAnimationHelper.java:93-109`). The outgoing app gets
   `wallpaperOpenExitAnimation` = `@anim/wallpaper_open_exit` (a **225 ms vertical
   slide down by 110 %**, `core/res/res/anim/wallpaper_open_exit.xml:19-31`) and
   HOME gets `wallpaperOpenEnterAnimation` = `wallpaper_open_enter` (alpha,
   `:19-31`).
2. **If no change shows wallpaper** — the expected case for the current ztmetro
   Start, whose theme uses an opaque `metro_black` `windowBackground` and which
   sets the window background to `Color.BLACK` at runtime, with no
   `windowShowWallpaper` (`launcher/app/src/main/res/values/styles.xml:9-10`,
   `launcher/app/src/main/java/org/ztmetro/start/StartActivity.java:109`) — the
   transition has no wallpaper change, `wallpaperTransit` is `NONE`, and the
   `TRANSIT_TO_FRONT` branch loads `taskToFrontEnterAnimation` /
   `taskToFrontExitAnimation` (`TransitionAnimationHelper.java:144-147`), i.e.
   `@anim/task_open_enter` / `task_open_exit`
   (`core/res/res/values/styles.xml:110-111`), a **500 ms horizontal slide by
   105 %** (`core/res/res/anim/task_open_enter.xml:19-31`,
   `task_open_exit.xml:19-31`).

So the return currently ends as either a downward slide or a horizontal slide,
never a turnstile. The 5 s capture in the measurement plan distinguishes them: a
downward app slide means pair 1; a horizontal push means pair 2. The RRO in §2
overrides both pairs, so it does not depend on that distinction.

Two conditional cases must be kept explicit:

1. **Launcher3QuickStep is set back as HOME** → its filter matches (its own
   component is top and HOME), and Quickstep owns the return via
   `QuickstepLaunchHome`.
2. **Gesture navigation is active** → Launcher3 is still the recents component;
   the swipe/home gesture path (`TouchInteractionService` / recents animation) and
   the home intent's explicit `RemoteTransition`
   (`RecentsActivity.java:489-521`) can make Launcher3 animate the return even when
   another app is HOME. This is the case in which the owner's
   "Launcher3QuickStep" description is correct.

This build contains both mechanisms; which one fires is determined by the live
HOME component and the navigation mode, not by the source tree alone. Settle it on
the device with:

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME
adb shell settings get secure navigation_mode
adb shell dumpsys window transitions        # look for "animated by <handler>"
# or, immediately after pressing HOME from an app:
adb logcat -d -s WindowManager ShellTransitions QuickstepTransitionManager | tail -80
```

`dumpsys window transitions` / the Shell trace names the handler that consumed the
transition (`Transitions.java:1076-1083` logs `"animated by <handler>"`). This is
the only way to turn the conditional answer into a single fact on the phone.

## 2. Can a runtime resource overlay reach the animation? (Q2)

### 2.1 Framework overlays are allowed

Android 16 framework-res has **no `<overlayable>` declarations** — the API listing
of `core/res/res/values` at the pinned commit contains no `overlayable.xml`
([`sources/values-listing.txt`](../evidence/transition-track/sources/values-listing.txt)).
When the target defines no overlayable, idmap2 permits an overlay only if it
fulfils one of the default policies: ODM/OEM/system/vendor/product partition, or
signature (`cmds/idmap2/libidmap2/ResourceMapping.cpp:51-86`). A **preinstalled**
static APK under `/product/overlay` fulfils `PRODUCT_PARTITION`. This is the same
policy that made the proven `ZtMetroFramework.apk` work
(`evidence/framework-rro/REPORT.md` §1); a normal data-app RRO cannot do it.

### 2.2 Which names are reachable and which are consumed

| resource / parameter | reachable by RRO? | consumed by the app→HOME path? | verdict |
|---|---|---|---|
| `@android:anim/wallpaper_open_enter` / `wallpaper_open_exit` | yes (preinstalled static) | when a change carries `FLAG_SHOW_WALLPAPER` (stock/translucent launcher over wallpaper): `getWallpaperTransitType()` returns `WALLPAPER_TRANSITION_OPEN` and `TransitionAnimationHelper` selects this pair before the task branch (`:93-109`) | primary for a wallpaper-showing HOME |
| `@android:anim/task_open_enter` / `task_open_exit` | yes | `DefaultTransitionHandler`'s `TRANSIT_TO_FRONT` / `TRANSIT_OPEN` branch when there is no wallpaper transition (`WALLPAPER_TRANSITION_NONE`, likely the opaque ztmetro Start) | primary for an opaque HOME; fallback otherwise |
| `@android:anim/task_close_enter` / `task_close_exit` | yes | only if the transition resolves to `TRANSIT_CLOSE`/`TO_BACK` instead of `TO_FRONT` | cheap insurance |
| `@android:anim/wallpaper_close_enter` / `wallpaper_close_exit` | yes | the opposite direction (leaving HOME); `wallpaper_close_exit` can be bypassed by the code-created `AlphaAnimation` when `polishCloseWallpaperIncludesOpenChange` is on (`TransitionAnimationHelper.java:168-179`) | not needed for app→HOME |
| `@android:dimen/config_appTransitionAnimationDurationScaleDefault` | yes | only the default value of `Settings.Global.TRANSITION_ANIMATION_SCALE` when that setting is unset (`Transitions.java:402-405`) | duration only, no shape |
| Launcher3 `R.dimen.closing_window_trans_y`, `closing_freeform_window_trans_y`, `max_shadow_radius` | only with a Launcher3-targeted preinstalled overlay, not framework-res | read by `QuickstepTransitionManager`'s constructor (`:306-309`), used inside the programmatic spring/fallback | distance only, never the curve |
| `config_recentsComponentName` (framework string) | build/RRO-able but not a live knob | selects who may act as recents; does not change an animation | not useful |

These six names were actually packaged and verified in the experiment:
[`evidence/transition-track/rro/`](../evidence/transition-track/rro) contains a
13 075-byte static RRO with the six resources and no dex
(`rro/artifacts/aapt2-dump-resources.txt`, `rro/artifacts/BUILD.txt`). `aapt2` refuses
`android:hasRoundedCorners` because it is a private framework attribute, so the
overlay omits it; a public-SDK RRO cannot set private attrs.

**Crucial limit:** overriding these names affects the Default handler only. It
cannot alter Quickstep's programmatic spring/AnimatorSet, which uses no framework
animation resource. If the phone is in the Launcher3/gesture case of §1.3, an RRO
that overrides these XMLs changes nothing visible.

## 3. Can LSPosed hook the transition path? (Q3)

Yes, but there is no single safe hook. The table lists the actual classes and
methods in this build, in decreasing order of leverage.

| # | class / method (exact) | process / scope | what it can do | risk |
|---|---|---|---|---|
| 1 | `com.android.wm.shell.transition.RemoteTransitionHandler#startAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction, SurfaceControl.Transaction, Transitions.TransitionFinishCallback)` (`RemoteTransitionHandler.java:117-153`) | `com.android.systemui` | replace the matched `RemoteTransition` or run a custom animator instead of delegating to Launcher | **highest**: SystemUI process, must call `finishCallback` exactly once, leaked transition = frozen screen; crash = SystemUI restart/possible boot loop |
| 2 | `com.android.wm.shell.transition.DefaultTransitionHandler#startAnimation(...)` (5-arg overload at `DefaultTransitionHandler.java:336-358`; loader at `:874-947`) | `com.android.systemui` | wrap/replace the fallback animation; can return a different `Animation` or build a custom `Animator` on the leashes | high, same process/contract; easiest for a 2D WP-style anim because the leashes are already prepared |
| 3 | `com.android.wm.shell.transition.TransitionAnimationHelper#loadAttributeAnimation(...)` (static; `TransitionAnimationHelper.java:68-206`) | `com.android.systemui` | swap only the resource-loaded `Animation` (e.g. return our `Animation` for `taskToFrontExit`) | medium-high; only covers the Default path; `Animation` is 2D |
| 4 | `com.android.wm.shell.transition.Transitions#dispatchTransition(...)` (`Transitions.java:1059-1085`) or `#addHandler(...)` (`:436-444`) | `com.android.systemui` | install a new handler with higher priority than Remote/Default | high: ordering and transition lifecycle changes affect every transition, not just home |
| 5 | `com.android.launcher3.QuickstepTransitionManager$WallpaperOpenLauncherAnimationRunner#onAnimationStart(int, RemoteAnimationTarget[], RemoteAnimationTarget[], RemoteAnimationTarget[], LauncherAnimationRunner.AnimationResult)` (`QuickstepTransitionManager.java:1956-1996`) | `com.android.launcher3` | replace the `AnimationResult` with a custom AnimatorSet on the app's `RemoteAnimationTarget` leashes | medium-high; only fires when Quickstep owns; must coordinate launcher content visibility; launcher crash kills HOME/recents |
| 6 | `com.android.launcher3.QuickstepTransitionManager#createWallpaperOpenAnimations(...)` (`:1800-1830`) | `com.android.launcher3` | return a different `BackAnimState`; needs a valid spring/animator and matching `onAnimationFinished` | high: `BackAnimState` lifecycle is internal and easy to leak |
| 7 | `com.android.quickstep.AbsSwipeUpHandler` / `TouchInteractionService` internals | `com.android.launcher3` | gesture-only path; fully custom swipe/home animation | highest maintenance: private state machine, version-specific |

Hook 1 or 2 is where a real retarget lives. A module cannot simply "change the
duration" of Launcher's spring by hooking `setAnimScaleSetting` — the
`RemoteTransitionHandler` does not implement that method. To replace the visual it
must own the animation and call the finish callback.

**Risk summary.** All Shell hooks run inside SystemUI; a bad hook is a system-UI
crash loop, not an app crash. Launcher hooks are less fatal but kill HOME/recents.
Hooks on hidden methods are not a stable API: Lineage 23.2 → next quarterly drop
can change signatures. A LSPosed module is viable for a personal ROM, not for a
shippable feature.

The stub module in [`evidence/transition-track/lsposed/`](../evidence/transition-track/lsposed)
contains exactly the hooks 1, 2 and 5. It **built successfully** (12 767-byte
signed APK, one `classes.dex`, module and hook methods present in
`lsposed/artifacts/dex-classes.txt`) but was **not installed** — no adb. Its log lines
would immediately tell which handler consumed a real transition.

## 4. Can a third-party launcher set as HOME influence the return? (Q4)

**No, not the return.** Two independent reasons:

1. `Activity#overrideActivityTransition()` is documented as applying only while the
   activity is at the top of its task, and explicitly “cannot be used to customize
   cross-task transitions” since Android 11
   (`core/java/android/app/Activity.java:6990-7004`). An app → HOME return is a
   cross-task transition: the outgoing app's task and the HOME task are different
   tasks. The API is ignored for it.
2. Even where the options are built, they become `AnimationOptions(ANIM_FROM_STYLE)`
   (`core/java/android/window/TransitionInfo.java:1181-1187`) and are attached to
   the **activity** change (`Transition.java:3286-3301`). The Default handler only
   uses them when `overrideType == ANIM_FROM_STYLE && !isTask`
   (`TransitionAnimationHelper.java:181-192`). The HOME change in a Shell
   transition is a **task** change (`getTaskInfo() != null`), so the custom
   activity animation is skipped and the task attr is loaded instead. The
   `ANIM_CUSTOM` branch that can override a task requires
   `overrideTaskTransition` (`DefaultTransitionHandler.java:908-911`), and
   `overrideActivityTransition` does not set that flag.

A third-party HOME also cannot register a Shell remote transition. The
`IShellTransitions` binder is provided through the recents-component plumbing:
`SystemUiProxy.setProxy()` receives it (`quickstep/src/com/android/quickstep/SystemUiProxy.kt:300-323`)
and `registerRemoteTransition()` calls `shellTransitions.registerRemote()`
(`:1011-1014`); `QuickstepLauncher` then calls `registerRemoteTransitions()`
(`QuickstepLauncher.java:365`). A normal APK installed as HOME has no `TouchInteractionService`, no
`IShellTransitions` binder and no privileged permissions; it owns only its own
window. If it replaces Launcher3 as HOME, the Launcher3 filter **stops matching**
and the system falls back to `DefaultTransitionHandler` — i.e. the third-party
launcher makes the return *more* stock, not more custom.

What a third-party HOME can do: draw its own window, set its own theme/window
animation for transitions it actually participates in as the top activity, and
pretend a WP scene inside its own content. It cannot move the outgoing app's
surface. This matches the project's earlier anatomy conclusion
(`docs/01-android-ux-anatomy.md:118-150`).

## 5. Do the animation scales replace anything? (Q5)

No. They scale durations; they do not select a different animation.

| setting | who reads it | effect on app → HOME | can it replace? |
|---|---|---|---|
| `Settings.Global.WINDOW_ANIMATION_SCALE` | WMS window-animation settings (`WindowManagerService.java:828-829, 1569-1571, 3869`) | legacy window animations; Shell transitions do not read it for this path | no |
| `Settings.Global.TRANSITION_ANIMATION_SCALE` | Shell `Transitions` (`Transitions.java:402-405`), then `setAnimScaleSetting` on handlers (`:426-430`) | `DefaultTransitionHandler` calls `a.scaleCurrentDuration(scale)` on its loaded `Animation` (`DefaultTransitionHandler.java:937-946`); `RemoteTransitionHandler` has no override, so Launcher's AnimatorSet/spring is **not** scaled by this | no, duration only |
| `Settings.Global.ANIMATOR_DURATION_SCALE` | WMS `ValueAnimator.setDurationScale` (`WindowManagerService.java:3855-3857`) and the per-session notification at `:6220-6233` | scales every `ValueAnimator`, including Launcher's fallback closing animator; `0` makes it end immediately | no, duration/zero only |

So the maximum no-root result is "the stock slide at 0.5× or 0×". It cannot become
a turnstile. On this device the scales are also inconsistent: the Default path is
driven by `TRANSITION_ANIMATION_SCALE`, while Launcher's programmatic path is
driven by `ANIMATOR_DURATION_SCALE`; setting only one may leave the other path at
stock speed.

## 6. What would a ROM-side change look like? (Q6)

### 6.1 If Quickstep owns (Launcher3 HOME or gesture path)

One file is the natural insertion point:

`packages/apps/Launcher3/quickstep/src/com/android/launcher3/QuickstepTransitionManager.java`

- replace the body of `getClosingWindowAnimators()` (`:1546-1588`) or the result of
  `createWallpaperOpenAnimations()` (`:1800-1830`) with a WP-style animator;
- or hook at `WallpaperOpenLauncherAnimationRunner.onAnimationStart()`
  (`:1956-1996`) and set a custom `AnimationResult` on the app targets' leashes;
- the module to rebuild is `Launcher3QuickStep` only (smaller and lower risk than
  SystemUI). A new class of roughly 300-500 lines plus one call-site change is a
  realistic 2D "page push / card fade" transition. Build and signature are the
  only integration costs.

### 6.2 If the Default handler owns (current documented configuration)

Add a new `Transitions.TransitionHandler` in
`libs/WindowManager/Shell/src/com/android/wm/shell/transition/` and register it in
`Transitions`'s constructor (`Transitions.java:353-364`), or patch
`DefaultTransitionHandler.loadAnimation()` (`:874-947`) / the
`TransitionAnimationHelper` attr selection (`:144-165`). This rebuilds **SystemUI**
(`WindowManager-Shell` is statically linked into it), which is a bigger and more
risk-bearing artifact than Launcher3. A 2D WP-style animator here is roughly
200-400 lines plus the registration; the same "true 3D" caveat applies.

### 6.3 The 3D question

A literal WP turnstile requires perspective rotation of the outgoing app's live
surface. Android's layer animation is an affine 2D transform: `Animation` exposes
translate/scale/rotate/alpha, and `SurfaceControl.Transaction#setMatrix` unpacks a
3×3 affine `Matrix` into scale/skew/translate only
(`core/java/android/view/SurfaceControl.java:3665-3680`). There is no
`rotationY`/perspective for a SurfaceControl leash.
Achieving a true turn would require capturing the app into a texture (screenshot
or GPU copy) and rendering that texture with a 3D transform in an overlay — new
renderer/compositor code, not a `QuickstepTransitionManager` tweak. That is weeks
of work with real jank and lifecycle risk on a 2 GB Adreno 308 device, and it
would still be a snapshot, not the live app. **Do not budget it as a one-file ROM
change.**

## 7. Ranked options

Effort is hands-on time on the existing build; risk is the chance of breaking the
phone or the transition contract.

| rank | option | class | effort | risk | works where | ceiling |
|---|---|---|---|---|---|---|
| 1 | **B1. Magisk static RRO over `@android:anim/wallpaper_open_enter`, `wallpaper_open_exit`, `task_open_enter`, `task_open_exit`, `task_close_enter`, `task_close_exit`** | B | 1-2 h build/install + reboot | medium: global task animations, static overlay, no per-app control; wrong names = no visible change, not a crash | only Default-handler path (§1.3 case 1) | 2D WP-style push / fade / scale; no 3D; no effect under Launcher3/gesture |
| 2 | **B2. LSPosed hook `RemoteTransitionHandler#startAnimation` (or `DefaultTransitionHandler#startAnimation`)** | B | 1-2 days logger + 2D animator; 1-2 weeks for a polished custom path | high: SystemUI crash loop, transition finish contract, hidden-API drift | both owners, because it intercepts before the remote/default split | 2D only unless it captures a texture; fragile across Lineage drops |
| 3 | **C1. ROM patch of `QuickstepTransitionManager` (or a WMShell handler)** | C | 1-3 days + module build | medium: one system app or SystemUI rebuild, ROM signing | whichever owner the build has | clean 2D WP transition; survives as part of the ROM, still not 3D |
| 4 | B3. LSPosed hook specifically in Launcher3 (`WallpaperOpenLauncherAnimationRunner#onAnimationStart`) | B | 1-2 days | medium-high: HOME/recents process, only Quickstep path | Launcher3 HOME / gesture only | 2D; less leverage than B2 |
| 5 | A. `ANIMATOR_DURATION_SCALE` / `TRANSITION_ANIMATION_SCALE` 0.5 or 0 | A | 5 min | none | always | faster/instant stock animation, never WP |
| 6 | C2. True 3D turnstile on the live app surface | C | weeks, uncertain | very high: new renderer/compositor, 2 GB GPU, snapshot vs live | ROM only | not realistically achievable with the current layer model |

**Recommendation.** If the goal is a WP *feel* and not a literal 3D turn, build
**B1** first: it is a two-hour experiment against the actual current owner, and the
APK already exists in `evidence/transition-track/rro/`. If B1 has no visible effect,
the phone is in the Launcher3/gesture case; then either accept **B2** (with a
SystemUI-scoped LSPosed module and a short, reversible rollout) or go straight to
**C1** if a rebuild is acceptable. Do not start with C2.

## 8. Blunt impossibilities

- **No public/runtime API replaces app → HOME.** `overrideActivityTransition` is
  explicitly cross-task-limited (`Activity.java:7003`), and the custom activity
  options are skipped for task changes (`TransitionAnimationHelper.java:181-192`).
- **A third-party HOME cannot influence the outgoing app's animation.** It cannot
  get the Shell binder; if it replaces Launcher3 it removes the only component that
  could animate the return and leaves the stock Default handler.
- **An RRO cannot change a programmatic animation.** Launcher's closing spring and
  fallback animator are code (`QuickstepTransitionManager.java:1546-1588`); only a
  hook or a ROM patch reaches them.
- **The scales cannot replace the animation.** They can only scale/zero it
  (`Transitions.java:402-405`, `DefaultTransitionHandler.java:937-946`).
- **A true turnstile is not a resource or a 2D transform.** The live app surface
  has no perspective transform; without a texture/compositor path it is
  impossible, and that path is a project, not a patch.

## 9. Experiment summary

| experiment | result | evidence |
|---|---|---|
| Static framework RRO with six `wallpaper_open_*` + `task_*` anims | **built and verified**: 13 075-byte signed APK, 6 anim entries, 0 dex; names exist and are mapped by `Animation.Activity`; consumed only by `DefaultTransitionHandler` | [`rro/`](../evidence/transition-track/rro), [`REPORT.md`](../evidence/transition-track/REPORT.md) |
| Stub LSPosed logger for the three hook sites | **built and verified**: 12 767-byte signed APK, one dex, module + hook methods present; not installed/run | [`lsposed/`](../evidence/transition-track/lsposed) |

Neither experiment was run on the phone (no adb). The next device step is in §1.3:
resolve HOME, read `navigation_mode`, press HOME from an app, and record
`dumpsys window transitions` / logcat to name the handler. That single measurement
decides whether B1 or B2/C1 is the right branch.
