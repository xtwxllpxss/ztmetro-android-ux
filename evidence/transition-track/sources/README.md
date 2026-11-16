# Source snapshots used by the transition track

`line-refs.txt` is a verbatim, line-numbered excerpt set. It was generated on
2026-11-16 from these revisions:

| repository | branch | head commit |
|---|---|---|
| `LineageOS/android_frameworks_base` | `lineage-23.2` | `797e43244b96c6a0dff5c81b21c4d4be665b082e` |
| `LineageOS/android_packages_apps_Launcher3` | `lineage-23.2` | `35ec64cec8f03580ae3b1b634ba137aaac4680af` |
| `LineageOS/android_build` (version check only) | `lineage-23.2` | not pinned; not used for a line claim |

Files and their source paths:

| local excerpt | upstream path |
|---|---|
| `QuickstepTransitionManager.java` | `packages/apps/Launcher3/quickstep/src/com/android/launcher3/QuickstepTransitionManager.java` |
| `QuickstepLauncher.java` | `packages/apps/Launcher3/quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java` |
| `Transitions.java` (Shell) | `libs/WindowManager/Shell/src/com/android/wm/shell/transition/Transitions.java` |
| `RemoteTransitionHandler.java` | `libs/WindowManager/Shell/src/com/android/wm/shell/transition/RemoteTransitionHandler.java` |
| `DefaultTransitionHandler.java` | `libs/WindowManager/Shell/src/com/android/wm/shell/transition/DefaultTransitionHandler.java` |
| `TransitionAnimationHelper.java` | `libs/WindowManager/Shell/src/com/android/wm/shell/transition/TransitionAnimationHelper.java` |
| `Transition.java` | `services/core/java/com/android/server/wm/Transition.java` |
| `Activity.java` | `core/java/android/app/Activity.java` |
| `ActivityRecord.java` | `services/core/java/com/android/server/wm/ActivityRecord.java` |
| `TransitionInfo.java` | `core/java/android/window/TransitionInfo.java` |
| `WindowManagerService.java` | `services/core/java/com/android/server/wm/WindowManagerService.java` |
| `ValueAnimator.java` | `core/java/android/animation/ValueAnimator.java` |
| `styles.xml` | `core/res/res/values/styles.xml` |
| `task_open_enter.xml` / `task_open_exit.xml` | `core/res/res/anim/task_open_{enter,exit}.xml` |
| `ResourceMapping.cpp` | `cmds/idmap2/libidmap2/ResourceMapping.cpp` |

`values-listing.txt` is the API listing of `core/res/res/values` at the pinned
framework commit. It has **no `overlayable.xml`**: framework-res declares no
`<overlayable>` groups, which is why the idmap2 policy path in `ResourceMapping.cpp`
(lines 55-75) governs framework overlays, and why a preinstalled `/product/overlay`
APK is allowed to override these anim names.

Public upstream URLs (browsable):

- `https://github.com/LineageOS/android_frameworks_base/blob/797e43244b96c6a0dff5c81b21c4d4be665b082e/libs/WindowManager/Shell/src/com/android/wm/shell/transition/Transitions.java`
- `https://github.com/LineageOS/android_frameworks_base/blob/797e43244b96c6a0dff5c81b21c4d4be665b082e/libs/WindowManager/Shell/src/com/android/wm/shell/transition/RemoteTransitionHandler.java`
- `https://github.com/LineageOS/android_frameworks_base/blob/797e43244b96c6a0dff5c81b21c4d4be665b082e/libs/WindowManager/Shell/src/com/android/wm/shell/transition/DefaultTransitionHandler.java`
- `https://github.com/LineageOS/android_frameworks_base/blob/797e43244b96c6a0dff5c81b21c4d4be665b082e/services/core/java/com/android/server/wm/Transition.java`
- `https://github.com/LineageOS/android_packages_apps_Launcher3/blob/35ec64cec8f03580ae3b1b634ba137aaac4680af/quickstep/src/com/android/launcher3/QuickstepTransitionManager.java`

Android 16 / API 36 identification: device verification report
`evidence/verify/20261116-142854/report.md` records `sdk 36`, and the build used
`platforms/android-36/android.jar`.
