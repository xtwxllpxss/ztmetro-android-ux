# ztMetro shade hook — LSPosed module evidence

Date: 2026-11-16 · Device target: Redmi Note 5A (ugglite), LineageOS 23.2 /
Android 16, 720×1280 @ 320 dpi · Package: `org.ztmetro.shadehook`
· Scope: **static only** — the lead owns adb, so nothing here was installed or
run on the phone.

Goal: the user's swipe-down gesture opens the Metro Action Centre
(`org.ztmetro.actioncentre/.ActionCentreActivity`, screenshot
`repo/evidence/ux-compare/current/d2-action.png`) over the current app instead
of the stock shade. If anything fails, the stock shade must behave normally.

## 1. Artifact

| field | value |
|---|---|
| APK | `repo/evidence/shade-hook/ztmetro-shade-hook.apk` (build output: `repo/sysui/shade-hook/build/dist/ztmetro-shade-hook.apk`) |
| size | **16 851 bytes (16.5 KiB)** |
| sha256 | `180fa551929961eefbe0264bb80363ddedeb7636d788896c7ea00e144fcd3063` |
| package / version | `org.ztmetro.shadehook`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 signature only, throwaway RSA-2048 dev key in `repo/sysui/shade-hook/build/keystore/` (gitignored) |
| Xposed API jar | `api-82.jar`, sha256 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25` (**same pin as the settings-header and keyguard modules**), compile-only, not packaged |
| components / permissions | **none** — no activity, service, receiver, provider, permission or feature request |
| dex | `Lorg/ztmetro/shadehook/{ModuleEntry,ShadeHooks,ShadeRouter,SLog};` |
| build | `repo/scripts/build-shade-hook.sh`: rsync to `hermes-ubuntu:~/ztux/shadehook`, plain `aapt2` + `javac --release 11` (JDK 17) + `d8 --min-api 29` + `zipalign` + `apksigner`. No Gradle, no network after the pinned download. |

Static checks that passed (full log: `repo/evidence/shade-hook/logs/build-verify.txt`):

- package `org.ztmetro.shadehook`, targetSdk 36, no `uses-permission`, no
  `launchable-activity`;
- manifest metadata: `xposedmodule`, `xposeddescription`, `xposedminversion=93`,
  `xposedscope` → array `["com.android.systemui"]`;
- `assets/xposed_init` = `org.ztmetro.shadehook.ModuleEntry`;
- dex strings for `NotificationPanelViewController`, `ShadeControllerImpl`,
  `onPanelStateChanged`, `makeExpandedVisible`, `instantCollapseShade`,
  `makeExpandedInvisible`, `org.ztmetro.actioncentre(.ActionCentreActivity)`,
  `ztmetro_shade_hook`;
- `apksigner verify`: v3, one signer.

The checked-in `repo/sysui/shade-hook` tree checksum-matches the source tree the
APK was built from (`rsync --checksum` dry-run after the build: no differences).

## 2. Chosen hook

Source of truth: `LineageOS/android_frameworks_base` branch `lineage-23.2`,
commit **`797e43244b96c6a0dff5c81b21c4d4be665b082e`** (same tree pin as
`repo/docs/10b-overlay-and-hook-facts.md`; `git ls-remote` confirmed the branch
still pointed there when the files were fetched, 2026-11-16 12:31 MSK). Files
fetched from `raw.githubusercontent.com` and read in full on the build host:

| file | sha256 |
|---|---|
| `packages/SystemUI/src/com/android/systemui/shade/NotificationPanelViewController.java` | `0f0e4ace34fc98fa9decf9e26fef6912e4db78df49abc635340fb66967ff833d` |
| `…/shade/ShadeControllerImpl.java` | `1401bb54d80be1fed3b4df2b225e6198f9a916de73053c510cabff2c60b4125c` |
| `…/shade/ShadeController.java` | `19031e00a1c745180819c9f9fb8786c796a31ee0c5ef256066693f208827d749` |
| `…/shade/ShadeExpansionStateManager.kt` | `1bdeba711613491f1b09e7689e0334aba5a957d1587b9927f2d953b631121372` |
| `…/shade/BaseShadeControllerImpl.kt` | `1cc041d6e582d45786d03dbe8bf996231aa3d89963d1edfa7e05e4cace061026` |
| `…/statusbar/StatusBarState.java` | `2263f725ff64fdca50c4e116f73ea57ff60594b4a2ebe71e57272498aae01e9b` |
| `…/statusbar/CommandQueue.java` | `1a32e75de2e05a1040c533abd69c0c905e75c84a855eb9947357610753dc1942` |
| `…/statusbar/phone/CentralSurfacesImpl.java` | `6d705e652f275226303bc8cc570a39e6321233573878d018d2bdaf834ee852dd` |

### Hook table

| role | class.method | file:line (lineage-23.2) | why |
|---|---|---|---|
| **trigger** | `com.android.systemui.shade.NotificationPanelViewController#onPanelStateChanged(int)` | `shade/NotificationPanelViewController.java:3797`; listener registered `:790`; `STATE_OPENING` branch `:3805` | The shade state machine (`ShadeExpansionStateManager.kt:91-93`) emits `STATE_OPENING` (=1, `:157`) **once per CLOSED→OPENING transition**, not per frame. This is the gesture-level "the shade is now being pulled down" event. It is the only callback that is both semantic and once-per-gesture. |
| capture | `com.android.systemui.shade.ShadeControllerImpl#start()` | `ShadeControllerImpl.java:380` | `CoreStartable`, runs once at SystemUI boot; gives the controller instance used to close the shade, before the first gesture. |
| capture (inert) | `ShadeControllerImpl#makeExpandedVisible(boolean)` | `ShadeControllerImpl.java:283` | Second chance at the controller reference: `onOpenStarted()` calls it (`:391`) before our after-hook on the state change runs. Capture only — this method also runs on the screen-off/light-reveal path, so it must **never** be a trigger. |
| action (invoked, not hooked) | `ShadeController#instantCollapseShade()` → `#makeExpandedInvisible()` → `#isExpandedVisible()` | `ShadeControllerImpl.java:277`, `:301`, `:342` | Public collapse API: reset the panel to fraction 0, then hide the shade window exactly as SystemUI does on a normal close. No dependency on touch tracking (`canBeCollapsed()` would still be false mid-gesture). |
| action (invoked) | `android.content.Context#startActivity(Intent)` | — | `ACTION_MAIN` + explicit component `org.ztmetro.actioncentre/.ActionCentreActivity`, flags `FLAG_ACTIVITY_NEW_TASK \| FLAG_ACTIVITY_SINGLE_TOP`. The SystemUI process is `sharedUserId="android.uid.systemui"` and holds `START_ACTIVITIES_FROM_BACKGROUND` + `START_ANY_ACTIVITY` (`packages/SystemUI/AndroidManifest.xml:21,109-110`), so the launch is not blocked by background-activity-start rules. |

Trigger callback sequence (all on the SystemUI main thread): `STATE_OPENING`
after-hook → cheap guards (debounce, enabled flag, keyguard, edge, package,
controller) → `Handler(main).post(...)` → hide shade → verify
`isExpandedVisible()==false` → `startActivity`.

**Name stability under R8.** SystemUI is built with R8 optimization/shrinking on
user builds, but its `optimize` block never sets `obfuscate`
(`packages/SystemUI/Android.bp:1083-1103`), and Soong appends `-dontobfuscate`
whenever `obfuscate` is false (`build/soong/java/dex.go:727`); the only
access-related rule is `-allowaccessmodification`
(`packages/SystemUI/proguard_common.flags`), which can widen but not rename a
member. Method/field names used here are therefore stable on an optimized build;
R8 could theoretically inline a tiny hooked method, which is why the trigger is a
large, call-site-diverse state callback rather than a trivial accessor. If a hook
were ever inlined, installation logs `hook:… failed` once and the stock shade
remains (fail-safe).

### Alternatives considered and rejected

| candidate | file:line | why not |
|---|---|---|
| `CentralSurfacesImpl#onPanelExpansionChanged(ShadeExpansionChangeEvent)` | `statusbar/phone/CentralSurfacesImpl.java:1451` | Runs **every frame** of the drag (fraction changes); the brief asks for once per gesture. |
| `ShadeControllerImpl#makeExpandedVisible(boolean)` as trigger | `:283`, called from `onOpenStarted()` `:391` **and** `CentralSurfacesImpl#onStartedGoingToSleep` `:2603` | Semantically "shade window becomes visible", not "user opened the shade". The screen-off path would fire it with the display going dark. Kept for capture only. |
| `CommandQueue#animateExpandNotificationsPanel()` / `#animateCollapsePanels()` | `statusbar/CommandQueue.java:751`, `:758` | Binder entry points for *programmatic* expansion from system_server; they do not receive the user's swipe (the gesture goes straight to the panel/state manager). Not the gesture. |
| `ShadeViewController` (Kotlin interface) | `shade/ShadeViewController.kt` | No expansion/state callback; mostly deprecated alpha/latency calls. |
| `ShadeExpansionStateManager` (Kotlin) | `shade/ShadeExpansionStateManager.kt` | Hooking `updateStateInternal` would work but targets an internal Kotlin method; the NPCV listener it calls is the public contract and is already registered (`:790`). |

## 3. Debounce, guards and fallback design

Decision order, each step fail-safe (a failure leaves the stock shade):

1. **Debounce 800 ms** — `SystemClock.uptimeMillis()` at decision time. The
   panel can re-enter `OPENING` while our own collapse runs; a repeat inside the
   window is **ignored**, which means the stock shade is allowed to open. This is
   what "ignore repeats" means for the user: one swipe = Metro, an immediate
   second swipe is stock.
2. **Config flag** — `Settings.System ztmetro_shade_hook`, default `1`;
   `0` = stock shade everywhere. Universal escape hatch, no app UI needed. If the
   settings provider itself throws, the flag is treated as `1` (fail-open) — a
   provider hiccup is not a shade failure, and every step after it is still
   fail-safe.
3. **Unlocked shade only** — `mBarState` must be `StatusBarState.SHADE` (=0,
   `statusbar/StatusBarState.java:28`). `KEYGUARD` (=1) and `SHADE_LOCKED` (=2)
   are rejected. Extra guard when readable: `KeyguardStateController.isShowing()`
   and `isBouncerShowing()` must both be false. **The lock-screen shade is never
   replaced.**
4. **Stock-shade edge (the fallback path)** — if the opening gesture's
   `ACTION_DOWN` x is in the right **18 %** of the panel width
   (`mDownX >= 0.82 × width`, 720 px → right ~130 px / 65 dp), the module does
   nothing and the real shade opens with quick settings. `mDownX`/`mDownTime`
   are the panel's own gesture-down bookkeeping (`NotificationPanelViewController.java:374`,
   `:345`, set in `initDownStates` and the touch handlers); a touch older than
   1.5 s (programmatic/accessibility expansion) cannot trigger the edge branch.
5. **Action Centre present** — `PackageManager.getActivityInfo()` on the explicit
   component; `NameNotFoundException` → log once, stock shade.
6. **Controller captured** — otherwise log once, stock shade.
7. **Hide + verify** — `instantCollapseShade()`, `makeExpandedInvisible()`, then
   re-read `isExpandedVisible()`; if it is still true the module does **not**
   start the panel (never put the Metro panel over an open stock shade).
8. **Start** — explicit intent with `NEW_TASK | SINGLE_TOP`; if it throws, the
   shade is already closed and the next gesture is stock again.

**Why the post:** the collapse is posted to the main looper instead of running
inside `onPanelStateChanged`. That method updates its own `mCurrentPanelState`
**after** dispatching listeners (`:3821`); collapsing synchronously would re-enter
the state machine and let the inner `CLOSED` transition be overwritten by the
outer `OPENING` bookkeeping. The post runs after the callback returns.

## 4. Side effects and what the user keeps

| surface | behaviour with the module on | rationale |
|---|---|---|
| **Quick settings** | Reachable: swipe down starting in the **right 18 % of the status bar** opens the stock shade (QS included). The dedicated fallback; no config needed. | QS is still needed (screen record, hotspot, media output, dark mode). The Metro panel covers Wi-Fi/data/BT/airplane/rotation/flashlight/brightness/ALL SETTINGS itself, but not every QS tile. |
| **Lock screen** | Stock shade / notifications unchanged. `mBarState` must be `SHADE`; keyguard and bouncer are rejected. | Security: a normal activity started over the keyguard would be a lock-screen bypass attempt (it would be blocked by WM anyway, but the shade state would be left broken). Deliberate no-op. |
| **Notification full-screen intents** (calls, alarms) | Not touched. The hook reacts only to shade *expansion*; it never intercepts notifications, heads-up windows or activity starts. A call/alarm activity launches normally. | Replacing an incoming-call UI would be dangerous. |
| **Heads-up swipe-down** | Opens the shade → redirected to Metro (it is a real shade expansion). | Consistent with "the shade gesture opens Metro". |
| **Accessibility "expand notifications"** | Same `OPENING` transition → Metro. Unless the triggering touch is older than 1.5 s, it is redirected. | Programmatic expansion is still a shade expansion; no special case needed. |
| **Screen off / AOD / power menu** | Untouched (no trigger on `makeExpandedVisible`). | That is why `makeExpandedVisible` is capture-only. |
| **SystemUI resources / layout** | Untouched — no overlay, no view mutation, no Compose hook. | The stock shade code is unmodified, so disabling the module is a true revert. |

Honest limitation: a full-screen-intent activity (e.g. an ongoing call) that is
visible while `mBarState == SHADE` will still have a manual swipe-down redirected
to Metro. It does not abort the call, but it covers the call UI until Back/Home.
Detecting "fullscreen intent is showing" reliably needs an AMS query the module
does not make; treated as an accepted edge case rather than adding a risky check.

## 5. Install and enable (lead, device owner)

The APK is dev-signed; a previous build may use a different key, so uninstall
first (ignore the "not installed" failure on the very first install):

```sh
adb uninstall org.ztmetro.shadehook
adb install repo/evidence/shade-hook/ztmetro-shade-hook.apk

# LSPosed Manager -> Modules -> "ztMetro Shade Hook" -> enable
#   scope: System UI (com.android.systemui)  [the manifest pre-selects it]
# then restart SystemUI (or reboot) so the hook is injected at process start:
adb shell su -c 'killall com.android.systemui'
# ...or simply reboot the phone.
```

Verify injection (LSPosed logs go to `LSPosed-Bridge`):

```sh
adb logcat -s LSPosed-Bridge | grep ztmetro-shadehook
# boot:  "loading in SystemUI (xposed API …); stock shade is the fallback…"
#        "hooked …NotificationPanelViewController#onPanelStateChanged(int)"
#        "captured ShadeControllerImpl from start()"
# swipe: "shade collapsed; started org.ztmetro.actioncentre/.ActionCentreActivity"
# edge:  "right-edge swipe x=…/720 -> stock shade (quick settings)"
```

Acceptance checks on the device:

1. unlocked, any app: swipe down from the middle → the stock shade must not stay
   on screen; the Metro Action Centre (`d2-action.png` layout) appears;
2. swipe down starting from the far right of the status bar → stock shade with
   quick settings;
3. lock the phone, swipe down → stock lock-screen shade (Metro must not appear);
4. `adb shell settings put system ztmetro_shade_hook 0`, swipe → stock shade;
   set back to `1` for the normal behaviour;
5. uninstall `org.ztmetro.actioncentre` (or disable it) and swipe → stock shade.

## 6. Rollback and SystemUI crash-loop risk

The module is a passive container: no components, no permissions, no windows.
All hook callbacks are `try/catch(Throwable)` and every install step is guarded,
so a rename in a future SystemUI build skips a hook instead of throwing. The
realistic failure mode is therefore "no redirect, stock shade", not a crash.

Rollback, in order of preference:

1. **Disable the redirect without touching LSPosed** (instant, no reboot):
   `adb shell settings put system ztmetro_shade_hook 0`
   → every guard passes through, the stock shade opens everywhere.
2. **Uncheck the module / scope** in LSPosed Manager, then restart SystemUI.
   This is the supported rollback if the manager UI is usable.
3. **Uninstall** (complete):
   `adb uninstall org.ztmetro.shadehook` → nothing is injected after the next
   SystemUI start; `adb reboot` if the process must be restarted.

If SystemUI itself is crash-looping (worst case: no status bar, no shade, no
lock-screen UI), the LSPosed Manager may be unreachable. The lead has adb, so
recovery is:

```sh
adb shell settings put system ztmetro_shade_hook 0   # stop the action, if system_server is up
adb uninstall org.ztmetro.shadehook                   # remove the only injected code
adb reboot                                            # SystemUI starts clean
```

`settings` and `pm` go through system_server, not SystemUI, so they work even
while SystemUI is dead. If neither adb nor the manager is available and SystemUI
still loops, recovery needs a recovery/TWRP removal of the APK — that is why the
module keeps zero permissions, zero components and a config kill-switch: the
failure surface is deliberately one hook class and one optional action.

Residual crash-loop risk is **low but not zero**: the module calls into
SystemUI's own `makeExpandedInvisible()`; a defect inside that method would throw
*inside SystemUI* (our catch prevents propagation, but the shade window could be
left partially hidden until the next gesture or a SystemUI restart). This is the
top risk of the whole approach and is logged as `collapse failed, stock shade
left as-is`.

## 7. Not verified / open risks

1. **No runtime test.** No install, no gesture, no logcat from the device (adb
   ownership). Hook names/fields are verified against the lineage-23.2 source at
   `797e4324`, not against the built SystemUI APK on the phone. The first device
   run must confirm all three log lines and the four acceptance checks. If a hook
   install line is missing, that hook was not found (R8 inline/rename) and the
   stock shade is what remains — read `logcat -s LSPosed-Bridge` before judging the
   gesture.
2. **Right-edge fallback depends on private fields** `mDownX`/`mDownTime`. If a
   future SystemUI renames them, the edge fallback silently stops working (the
   redirect still works); the config flag remains the guaranteed fallback. The
   names are present at `NotificationPanelViewController.java:374,345`.
3. **`makeExpandedInvisible()` assumes a non-null `mShadeVisibilityListener`**
   (set during SystemUI start). In the normal gesture path it is set; an NPE
   would be caught and the launch skipped. Not observed (no device run).
4. **Immediate second swipe** within 800 ms shows the stock shade by design
   (debounce). If that feels wrong in use, the constant is `DEBOUNCE_MS` in
   `ShadeRouter`.
5. **Transition is a cut, not a slide.** The shade is hidden instantly (no
   animation), so the stock shade is not left on screen (at most the OPENING
   frame); the only motion is the Action Centre's own 260 ms slide-in. If
   acceptance wants the stock collapse
   animation first, switch the action to `animateCollapseShadeForcedDelayed()`
   (but it is a no-op while the gesture is still tracking — that is why the
   instant path was chosen).
6. **Accessibility users** who expand the shade via a service gesture get Metro
   too (no fresh touch → no edge fallback). Acceptable, but worth a device check.
7. **A full-screen intent visible at swipe time** is covered by Metro until
   Back/Home (see §4).
