# Transition-track experiments

Two host experiments for [`docs/26-transition-track.md`](../../docs/26-transition-track.md).
No adb and no device: the ugglite is owned by the lead, so neither artifact was
installed or run. Everything below is a build and a source-level consumption check,
not a visual acceptance.

## Environment / revisions

| item | value |
|---|---|
| device target | LineageOS 23.2 / Android 16 (device report: SDK 36), Redmi Note 5A ugglite |
| `android_frameworks_base` | `lineage-23.2`, head `797e43244b96c6a0dff5c81b21c4d4be665b082e` (2026-11-16) |
| `android_packages_apps_Launcher3` | `lineage-23.2`, head `35ec64cec8f03580ae3b1b634ba137aaac4680af` (2026-11-01) |
| build host | `hermes-ubuntu`, Android SDK build-tools 36.0.0, `android.jar` API 36, JDK 17 |
| tools | `aapt2` 2.20-13193326, `apksigner` 36.0.0, `d8`, `dexdump` |

Line references used by the report are collected verbatim in
[`sources/line-refs.txt`](sources/line-refs.txt). The excerpts were cut from the
revisions above. The device-side procedure that turns the conditional "who owns
it" into a fact is in [`measurement-plan.md`](measurement-plan.md).

## Experiment 1 — framework `anim` RRO (build + name-consumption check)

**Question.** Can a Magisk-installed static RRO replace the task animation that the
app -> HOME transition uses, and are the names actually consumed by the code path?

**What was built.** A minimal `targetPackage="android"`, `hasCode="false"`,
`isStatic="true"` overlay defining six framework anim resources:

```
res/anim/wallpaper_open_enter.xml
res/anim/wallpaper_open_exit.xml
res/anim/task_open_enter.xml
res/anim/task_open_exit.xml
res/anim/task_close_enter.xml
res/anim/task_close_exit.xml
```

Sources are in [`rro/`](rro); the build script is [`rro/build.sh`](rro/build.sh).
It compiles with `aapt2`, links against `android.jar` API 36, signs with a
throwaway key, then dumps and verifies the result.

**Result (build succeeded).**

```
apk=13075 bytes
sha256=87651515abfd596dc374394ea6ceaf6838cf79f95a01b7fa004d373386dda3de
entries=12    # grep line count; the resource dump itself says entryCount=6
dex=0
```

- [`rro/artifacts/aapt2-dump-resources.txt`](rro/artifacts/aapt2-dump-resources.txt) reports
  `type anim ... entryCount=6` and lists the six `anim/` entries in the overlay:
  the two `wallpaper_open_*` names (selected when a change carries
  `FLAG_SHOW_WALLPAPER` and `DefaultTransitionHandler` reports
  `WALLPAPER_TRANSITION_OPEN`) and the four `task_*` names (expected for the
  opaque ztmetro Start, where the transition has no wallpaper change).
- [`rro/artifacts/apk-contents.txt`](rro/artifacts/apk-contents.txt) shows the six XML files
  plus `resources.arsc` and no `classes*.dex`.
- [`rro/artifacts/framework-anim-entries.txt`](rro/artifacts/framework-anim-entries.txt)
  shows the target framework's public anim entries and the style attrs pointing
  at them, including the `wallpaper_open_*` pair.
- [`rro/artifacts/framework-target-names.txt`](rro/artifacts/framework-target-names.txt) shows
  that the target framework (API 36) exposes the matching style attributes and
  points them at these exact names:

```
taskOpenEnterAnimation  = @anim/task_open_enter
taskOpenExitAnimation   = @anim/task_open_exit
taskCloseEnterAnimation = @anim/task_close_enter
taskCloseExitAnimation  = @anim/task_close_exit
taskToFrontEnterAnimation = @anim/task_open_enter
taskToFrontExitAnimation  = @anim/task_open_exit
taskToBackEnterAnimation  = @anim/task_close_enter
taskToBackExitAnimation   = @anim/task_close_exit
```

**One build finding.** `android:hasRoundedCorners` is a private framework attribute.
`aapt2` rejects it when linking against the public `android.jar` ("attribute
android:hasRoundedCorners is private"), so the overlay anims omit it. A resource
overlay cannot set that private flag through the public SDK; it is not needed for
the experiment.

**Consumption verdict (source-level, no device).**

- These names are **not dead**: `core/res/res/values/styles.xml:96-113` maps
  `Animation.Activity` attrs to them, and
  `TransitionAnimationHelper.loadAttributeAnimation()` selects
  `wallpaperOpenEnter/Exit` when `getWallpaperTransitType()` reports
  `WALLPAPER_TRANSITION_OPEN` (`:93-109`, `DefaultTransitionHandler.java:1031-1059`);
  otherwise it selects `taskToFrontEnter/Exit` for `TRANSIT_TO_FRONT` and
  `taskOpen*` for `TRANSIT_OPEN` (`:144-159`).
- They are consumed **only when `DefaultTransitionHandler` plays the transition**.
  If Launcher3's `RemoteTransition` filter matches, `RemoteTransitionHandler`
  consumes the whole transition first and never reaches that code
  (`RemoteTransitionHandler.java:117-153`, `Transitions.java:1059-1085`).
- Therefore the RRO is a real option only where `DefaultTransitionHandler` owns
  app -> HOME (see the report for the HOME/nav-mode condition). It cannot affect
  Launcher's programmatic closing animation (`QuickstepTransitionManager.java:1546-1588`,
  `1800-1830`), which uses no framework anim resources.

**Iteration that mattered.** The first build overrode only the four `task_*`
names. Reading `DefaultTransitionHandler.getWallpaperTransitType()` and
`TransitionAnimationHelper` together showed that a HOME change carrying
`FLAG_SHOW_WALLPAPER` selects the `wallpaper_open_*` pair *before* the task
branch, so the first overlay could be invisible on a wallpaper-showing home.
The shipped experimental APK was rebuilt with `wallpaper_open_enter/exit` plus the
four task names (6 entries), covering both the opaque-Start and
wallpaper-showing-HOME cases.

**Not done:** install and visual check. Required next step:
`cmd overlay list | grep transitions`, reboot, press HOME from an app, and record
before/after screen capture. The overlay is static, so it must be placed in
`/product/overlay/` (same policy as the proven `ZtMetroFramework.apk` in
`evidence/framework-rro/`); a user-installed data overlay will not be permitted for
framework-res because line 69-75 of `cmds/idmap2/libidmap2/ResourceMapping.cpp`
requires a preinstalled/signature policy when the target defines no `<overlayable>`.

## Experiment 2 — stub LSPosed transition logger (build only)

**Question.** Which exact classes can a LSPosed module hook to see/retarget the
transition, and does the module compile and package?

**What was built.** [`lsposed/`](lsposed) contains a stub Xposed module that:

- installs hooks in the `com.android.systemui` process on
  `com.android.wm.shell.transition.RemoteTransitionHandler#startAnimation` and
  `com.android.wm.shell.transition.DefaultTransitionHandler#startAnimation`;
- installs a hook in the `com.android.launcher3` process on
  `com.android.launcher3.QuickstepTransitionManager$WallpaperOpenLauncherAnimationRunner#onAnimationStart`;
- logs transition type, change count, modes, task ids and activity components.

The module is compiled against a minimal compile-only Xposed API stub and hidden
framework stubs (`TransitionInfo`, `RemoteAnimationTarget`); those stubs are not
packaged into the dex. Runtime still uses LSPosed's real classes and the hidden
framework classes.

**Result (build succeeded).**

```
apk_bytes=12767
sha256=5cfa24f99b1e0e18081401a4e1916e6ad9df2c5d81e32fda9c2d5846d240354e
dex_files=1
```

- [`lsposed/artifacts/apk-contents.txt`](lsposed/artifacts/apk-contents.txt): manifest,
  `assets/xposed_init`, `classes.dex`.
- [`lsposed/artifacts/dex-classes.txt`](lsposed/artifacts/dex-classes.txt): the module classes
  and both hook methods are present in the dex.
- [`lsposed/artifacts/apksigner-verify.txt`](lsposed/artifacts/apksigner-verify.txt): signed
  with a throwaway key.

**Not done:** install, LSPosed scope, logcat. The class/method names are taken
from the `lineage-23.2` sources; a name mismatch on the device would be caught by
the module's own `XposedBridge.log("... hook failed: ...")` path, not by the build.

## Reproduction

```sh
# RRO
scp -r rro hermes-ubuntu:~/ztux/transition-ro
ssh hermes-ubuntu 'cd ~/ztux/transition-ro && ANDROID_SDK_ROOT=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk bash build.sh'

# LSPosed stub
scp -r lsposed hermes-ubuntu:~/ztux/transition-lsposed
ssh hermes-ubuntu 'cd ~/ztux/transition-lsposed && ANDROID_SDK_ROOT=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk bash build.sh'
```

Both scripts are idempotent and create the throwaway signing key outside the APK
directory tree only in `artifacts/`; no key was copied into this repository.
