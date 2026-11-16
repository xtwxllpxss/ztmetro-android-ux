# ztmetro-keyguard — Metro lock screen LSPosed module (evidence)

Independent module, package `org.ztmetro.keyguard`, scope `com.android.systemui`.
Built 2026-11-16 on `hermes-ubuntu` (android-sdk build-tools 36.0.0, JDK 17,
`--release 11`, minSdk 29 / targetSdk 36). No commit, no push, device untouched
(device offline; nothing was installed).

```
APK:     repo/evidence/keyguard-module/ztmetro-keyguard.apk  (20 947 bytes)
SHA256:  d7a9ac84802af2668e0f709510a105cba4a8ea00d80b6cb80b07d0612f7a56e2
Xposed:  api-82.jar sha256 f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25 (compile-only)
Manifest: xposedmodule=true, xposedminversion=93, xposedscope=[com.android.systemui],
          no permissions, no activity/service/receiver, no resources outside the module APK
Dex:     Lorg/ztmetro/keyguard/{ModuleEntry,KeyguardHooks,MetroKeyguard,KLog};
         strings for all seven hook targets present (logs/build-verify.txt:66-82)
Source:  repo/sysui/keyguard-module/  (build script: repo/scripts/build-keyguard-module.sh)
```

---

## 1. Hook points: what actually exists in lineage-23.2 (Android 16)

The brief's assumed classes are **gone** in this tree. Verified against
`LineageOS/android_frameworks_base` branch `lineage-23.2`
(commit `797e43244b96c6a0dff5c81b21c4d4be665b082e`) by raw-file fetch plus GitHub
code search over the repo:

| Class from older guides | Status at lineage-23.2 | Evidence |
|---|---|---|
| `KeyguardClockSwitch` | **deleted** | 0 code-search hits; absent from the SystemUI tree |
| `KeyguardStatusView` | **deleted** | only a stale test exclude (`packages/SystemUI/Android.bp:157`) and method names `updateKeyguardStatusViewAlignment` (`shade/NotificationPanelViewController.java:1242-1254`) survive |
| `KeyguardStatusViewController` | **deleted** | 0 class hits; test-exclude only (`Android.bp:157`) |
| `KeyguardIndicationTextView` | **present, a View** | `src/com/android/systemui/statusbar/phone/KeyguardIndicationTextView.java:43` extends `DoubleShadowTextView` |

What replaced them, and whether each is a View (hookable via findViewById /
View edits) or Compose (needs compose hooks):

| Component | Kind at 23.2 | Where |
|---|---|---|
| `KeyguardRootView` — container for **all** keyguard content | **View** (`ConstraintLayout`) | `keyguard/ui/view/KeyguardRootView.kt:25-32`; inflated as `@id/keyguard_root_view` in `res/layout/super_notification_shade.xml:62-66` (id in `res/values/ids.xml:231`) |
| clock **container** / lifecycle | **View** — blueprint section adds clock views directly to `KeyguardRootView` | `ClockSection.kt:82` (`addViews` empty), `:84-96` (`bindData`), `KeyguardClockViewBinder.kt:56` (`bind`), `:249` (`addClockViews`) |
| default clock face | **View** — `AnimatableClockView extends TextView` | `customization/src/com/android/systemui/shared/clocks/AnimatableClockView.kt:56`; inflated from `customization/res/layout/clock_default_large.xml:19` |
| clock **drawing** | not `TextView.onDraw` — SystemUI `TextAnimator` paints with its own Paint | `AnimatableClockView.kt:270-278` (`onDraw` → `textAnimator.draw`), `:448` (`setTextStyle`) |
| clock **ids** | `View.generateViewId()` at runtime — **not** resource ids, not stable between processes | `plugin/src/com/android/systemui/plugins/keyguard/ui/clocks/ClockViewIds.kt:26-27` |
| clock hosted by Compose when the Compose scene is on | **still a View** — `AndroidView` puts the same `AnimatableClockView` into the hierarchy | `customization/clocks/common/.../DefaultClockFaceLayout.kt:50,95-118` |
| Compose lock screen (`LockscreenScene`, `LockscreenContent`, `NotificationStackElementProvider`, …) | **Compose**, but gated by the `scene_container` aconfig flag, which has no default/`is_fixed_read_only` and is therefore off in a stock build | `compose/features/src/com/android/systemui/keyguard/ui/composable/…`; `packages/SystemUI/aconfig/systemui.aconfig:206-211`; `scene/shared/flag/SceneContainerFlag.kt:41-44` |
| date line (stock, non-GMS) | **View** — `KeyguardSliceView` (`LinearLayout`) + programmatic `KeyguardSliceTextView extends TextView` | `src/com/android/keyguard/KeyguardSliceView.java:135` (`showSlice`), `:297` (`Row`), `:422` (`KeyguardSliceTextView`); date URI `src/com/android/systemui/keyguard/KeyguardSliceProvider.java:92`, added `:226`; view inflated in `keyguard/ui/view/layout/sections/KeyguardSliceViewSection.kt:65-68` |
| smartspace date/weather | **plugin view** (`BcSmartspaceDataPlugin`); `buildView` returns `null` when no plugin is installed (LineageOS has no GMS smartspace), so on this ROM the date comes from `KeyguardSliceView`, not smartspace | `statusbar/lockscreen/LockscreenSmartspaceController.kt:400-401`; `keyguard/ui/view/layout/sections/SmartspaceSection.kt:77-80` |
| indication / owner info | **View** — `KeyguardIndicationArea extends LinearLayout` builds two `KeyguardIndicationTextView`s | `keyguard/ui/view/KeyguardIndicationArea.kt:31,57-67` |
| `NotificationShadeWindowView` | **View** (`WindowRootView`) | `shade/NotificationShadeWindowView.java:67`, `onAttachedToWindow` `:90` |
| `NotificationStackScrollLayout` | **ViewGroup**, not Compose | `statusbar/notification/stack/NotificationStackScrollLayout.java:174` (`extends ViewGroup`), `onViewAdded` `:3500` |

Consequences for the module:

* The clock is **not Compose-only** on this version: it is a `TextView` subclass
  whose digits are painted by `TextAnimator`. So the primary path is real
  View-level restyling, no overlay needed.
* `findViewById(R.id.lockscreen_clock_view_large)` is impossible — those ids are
  `View.generateViewId()`. The module never looks the clock up by id; it hooks
  the clock class itself and uses a class-name scan only for the *fallback*
  detection and for Flex/third-party clock faces.
* The date is a plain TextView, tagged with the date URI; the full style can be
  applied in the `showSlice` hook.

---

## 2. Implemented hooks

All callbacks: `try/catch(Throwable)` → rate-limited `XposedBridge.log`; a failed
hook install is logged and skipped. No callback throws into SystemUI.

| # | Target | Why | Fail mode |
|---|---|---|---|
| 1 | `NotificationShadeWindowView#onAttachedToWindow` (declared, `:90`) | anchor: find `@id/keyguard_root_view`, install `OnGlobalLayoutListener`, run the idempotent style pass | root/id missing → log once, module inert for that window |
| 2 | `KeyguardClockViewBinder#bind` (all overloads, static/instance) | blueprint (re)build: re-run the style pass after clock views are (re)added (`KeyguardClockViewBinder.kt:56`) | method absent → log; clock hooks below still run |
| 3 | `AnimatableClockView#refreshTime` (after, `:192`) | once a minute and before first layout: set 56sp + Semilight + tracking 0 + white; must be in place before `TextAnimator` captures the typeface in `onMeasure` | class/method absent → skip; stock clock unchanged |
| 4 | `AnimatableClockView#onMeasure` (before, `:237`) | guarantee the typeface cache is built from the Metro face even if `refreshTime` ran late; **typography only, never size** (size would fight the `height/2` clamp and re-request layout every pass) | throws → caught; stock measure continues |
| 5 | `AnimatableClockView#setColors` (before, `:302`) | force `lockScreenColor` = white; doze/AOD colour (arg 0) untouched | throws → stock colours |
| 6 | `KeyguardIndicationTextView#setNextIndication` (after, private, `:214`) | tint owner-info/neutral indication text accent `#3D7BFF`, tracking 0.04, regular face — after `setTextColor(indication colour)` at `:232` | indication has error colour / icon / button background → **skipped**, stock styling kept |
| 7 | `KeyguardSliceView#showSlice` (after, `:135`) | style the date TextView (tag `content://com.android.systemui.keyguard/date`) 13sp, uppercase-rendered, tracking 0.08, white | date view not found → no change |

Fallback (not on the stock path): `MetroKeyguard.applyAll` also scans
`KeyguardRootView` for `*Clock*` TextViews. Flex/digital clock faces
(`FlexClockTextView`, `DigitalClockTextView`) get typography only (their digit
views own the size). If the container is populated but **no** clock view is found
(e.g. a future/unknown clock impl), the module adds its own Metro clock+date
`LinearLayout` inside `KeyguardRootView` (no new window), updates it once a
minute, and removes it again if a real clock view later appears. If the
container stays empty (fully Compose keyguard), nothing is added — fail-safe
no-op rather than a clock over the launcher.

No new windows, no permissions, no `WindowManager.addView`, no
`removeAllViews`, no replacement of `TextView` behaviour: only property edits
on views SystemUI already owns.

---

## 3. Stylable vs impossible without a ROM build

Stylable at runtime (implemented):

* clock size/face/tracking/colour — 56sp, `sans-serif` weight 350
  (→ `Selawik-Semilight.ttf` when the ztmetro-font module is installed),
  white, tracking 0;
* date line — 13sp, uppercase-rendered caps, tracking 0.08, white;
* owner-info / neutral indication — accent `#3D7BFF` ([E] light1 of accent.base,
  chosen for contrast on black; base `#0050EF` is ~3.4:1 on black);
* fallback Metro clock+date overlay if the container has no View clock.

Not achievable from the module (needs a ROM/RRO change):

* **notification rows**: corner radii are resource-driven. The existing
  `ztmetro-systemui-rro` already sets `notification_corner_radius` 28dp→0dp and
  `notification_corner_radius_small` 4dp→0dp (`evidence/sysui-rro/REPORT.md:46-47`,
  consumers listed there). The keyguard module deliberately does not walk
  `NotificationStackScrollLayout` rows: `ExpandableNotificationRow`/`NotificationBackgroundView`
  own a state machine (expanded/clipped/elevation) and programmatic background
  swaps are a crash/blink risk with no visual gain over the RRO.
* smartspace date/weather (plugin surface) — no plugin on LineageOS; the date is
  the slice row, which is styled.
* PIN keypad/bouncer backgrounds, ripple colours, lock icon, shortcuts, media
  player: framework/SystemUI resource styling (RRO), not TextView properties.
* any clock face that draws to a Canvas/Compose without TextViews: only the
  overlay fallback, and only when the keyguard container is populated.

---

## 4. Enable / rollback

Enable (on device, lead-owned):

1. Install `ztmetro-keyguard.apk` (`adb install -r` or a file manager).
2. LSPosed/Vector → Modules → **ztMetro Keyguard** → enable → **scope:
   SystemUI** (`com.android.systemui`) only. The manifest already preselects it
   (`res/values/arrays.xml`).
3. Reboot (or `adb shell su -c 'killall com.android.systemui'` if the lead
   accepts a one-off SystemUI restart).
4. Check LSPosed log for `ztmetro-keyguard: hooked …` lines and for
   `… failed, stock keyguard left as-is`.

Rollback:

* Preferred: LSPosed → disable the module (or remove SystemUI from its scope) →
  reboot. Stock keyguard returns; no files changed.
* Full: uninstall the APK → reboot. The module writes nothing to disk, no props,
  no settings, no overlay APK.
* Crash loop / black lock screen: hold Power+VolUp to force-reboot into
  recovery/bootloader-safe state, or boot with LSPosed **safe mode**
  (press VolUp/VolDown repeatedly during boot — LSPosed's safe mode disables all
  modules), then disable this module. Because the module never blocks a method
  and only edits view properties, the realistic failure mode is a SystemUI
  restart loop caused by a hook throwing outside a guarded path, not a
  boot-blocking change; the safe-mode route is the recovery.
* Since the module has no persistent state, removing the APK is always complete.

---

## 5. On-device screen checklist (not executed; device offline)

| Screen | Expect |
|---|---|
| Lock screen, large clock | clock rendered at 56sp in Selawik Semilight, white, tracking 0; date line below, 13sp uppercase-rendered with 0.08 tracking |
| Lock screen, owner info enabled | owner-info text in accent `#3D7BFF`, tracking 0.04 |
| Lock screen, charging/status indication | neutral text accent-tinted; error ("wrong PIN", face misalignment) keeps stock colour |
| Notification stack on lock screen | unchanged by this module; flat/square corners come from the SystemUI RRO |
| Swipe-up bouncer, PIN keypad | untouched (stock), no delay, digits work |
| Face unlock / UDFPS bouncer | untouched; misalignment/error text keeps stock colour |
| AOD / doze | clock 56sp but stock doze dimming and burn-in translation intact (only `lockScreenColor` forced white, doze colour not modified) |
| Shade expanded over lock screen | clock hidden by stock scrim logic; no overlay on top of notifications |
| Unlock to launcher | no residual overlay, no extra window, no TalkBack duplicate node |

---

## 6. Known risks

1. **Compose keyguard build**: if a future/Lineage variant enables
   `scene_container`, the clock is still `AnimatableClockView` (hosted by
   `AndroidView`), so hooks 3-5 should still style it; the date, however, moves
   out of `KeyguardSliceView` and is not styled. Module then degrades to
   clock-only, no crash. Confirm with the LSPosed log line
   `hooked com.android.systemui.shared.clocks.AnimatableClockView#refreshTime`.
2. **Clock replaced by a third-party/flex face**: typography-only best effort;
   the face's own code can overwrite the typeface. Fallback overlay appears only
   if no `*Clock*` TextView is found at all.
3. **SystemUI crash loop** (worst case, unobserved): a hook throwing outside the
   guarded bodies would restart SystemUI, not the device. Recover with LSPosed
   safe mode / disable scope (see §4). Hooks are wrapped, so this is treated as
   an unknown-unknown and must be validated on the first boot.
4. **Hard-coded 56sp vs stock 150dp**: the A16 stock large clock is
   `large_clock_text_size = 150dp` (`customization/clocks/common/res/values/dimens.xml:21`);
   56sp is the module spec's deliberate Metro scale-down and will look much
   smaller than stock. If the lead prefers the 92pt reference from
   `design/tokens/README.md:139`, change `CLOCK_SP` only.
5. **`sans-serif` weight 350** depends on the ztmetro-font module mapping weight
   350 to `Selawik-Semilight.ttf`; without it Android falls back to
   `sans-serif-light`/Roboto Light.
6. **No runtime validation was possible**: the device is offline and adb is
   lead-owned. Verification here is compile + manifest/dex/static target checks
   against the exact lineage-23.2 sources.

---

## 7. Reproduction

```
# on hermes-ubuntu, module synced to ~/ztux/keyguard/repo/
cd ~/ztux/keyguard/repo
ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk \
KEYGUARD_EVIDENCE_DIR=$HOME/ztux/keyguard/evidence \
  ./scripts/build-keyguard-module.sh
```

The script pins the Xposed API jar (sha256 above), compiles resources with
aapt2, compiles with `javac --release 11`, dexes with d8, zipaligns, signs with a
throwaway dev key under the gitignored `build/`, verifies package/xposed
metadata/scope/dex classes, and writes `logs/build-verify.txt` +
`logs/build-info.txt`.
