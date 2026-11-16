# ztMetro shade hook v2 — stop the stock shade at the source (LSPosed, system_server + SystemUI)

Date: 2026-11-16 · Target: Redmi Note 5A (ugglite), LineageOS 23.2 / Android 16,
720×1280 @ 320 dpi · Package: `org.ztmetro.shadehook` v2 (same id as v1, install
over it) · **Static only**: the lead owns adb, so nothing here was installed or
run on the phone. Every runtime line below is a prediction from verified source,
labelled as such.

Evidence: `repo/evidence/shade-hook-v2/` — this report, `hook-sources.txt` (exact
source lines and file hashes), `logs/build-verify.txt`, `logs/build-info.txt`,
`ztmetro-shade-hook.apk`.

## 0. Verdict

* **Doable without a ROM fork.** The 100–400 ms stock shade is not an
  accessibility problem: `DisplayPolicy` reroutes the touch to the status bar
  window from **system_server** (`WindowState#transferTouch()`), and SystemUI's
  shade starts from the synthetic DOWN. A SystemUI-scoped hook is late by
  construction.
* **The fix is one hook**: `com.android.server.wm.WindowState#transferTouch()`
  returns `false` for the type-2000 status bar window while the ztmetro strip is
  the intended owner. That call has exactly one caller in the pinned tree
  (`DisplayPolicy.java:2435`), so nothing else changes.
* **The assigned hook #1 does not exist as described.**
  `com.android.systemui.statusbar.phone.SystemGesturesPointerEventListener` is a
  **404** at the pinned commit; `SystemGesturesPointerEventListener` lives only in
  `services/core/java/com/android/server/wm/` (system_server). Consequence: the
  module needs the LSPosed scope **`android`**, not only `com.android.systemui`.
  That is the one enable-step change the lead must make (§6).
* v1's SystemUI path is kept as a **safety net** (collapse + launch Action
  Centre). It should never fire; when it does, the log says so explicitly.

## 1. Artifact

| field | value |
|---|---|
| APK | `repo/evidence/shade-hook-v2/ztmetro-shade-hook.apk` |
| size | **20 947 bytes (20.5 KiB)** |
| sha256 | `164a61f689c53f675f5bdd6966b4a50f8e3811b5225f4b6bd3fe5d2536c046f3` |
| package / version | `org.ztmetro.shadehook`, versionCode 2 / versionName 2.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| scopes (`xposed_scope`) | **`["android", "com.android.systemui"]`** |
| components / permissions | **none** — no activity, service, receiver, provider, permission, feature |
| dex classes | `ModuleEntry`, `FrameworkHooks` (+`FrameworkHooks$1`), `ShadeHooks`, `ShadeRouter`, `SLog` |
| Xposed API | `api-82.jar`, sha256 `f48c635f…8e25`, compile-only, not packaged |
| build | `repo/scripts/build-shade-hook.sh` on `hermes-ubuntu` in `~/ztux/sysuihook` (aapt2 + javac 17 `--release 11` + d8 `--min-api 29` + zipalign + apksigner), no Gradle |
| signer | v3, throwaway RSA-2048 dev key in the gitignored `build/keystore/` |

Static checks (full log: `logs/build-verify.txt`): package/targetSdk, no
`uses-permission`, no `launchable-activity`, `assets/xposed_init`, scope array
`["android", "com.android.systemui"]`, dex strings for
`com.android.server.wm.WindowState`, `transferTouch`,
`enabled_accessibility_services`, `extra_instant`, `NotificationPanelViewController`,
`ShadeControllerImpl`, `onPanelStateChanged`, `makeExpandedVisible`,
`instantCollapseShade`, `makeExpandedInvisible`, `org.ztmetro.actioncentre/.ActionCentreActivity`;
`apksigner verify` v3, one signer.

## 2. The real chain (verified, file:line)

Pin: `android_frameworks_base` `lineage-23.2` @
`797e43244b96c6a0dff5c81b21c4d4be665b082e`. Excerpts and hashes:
[`hook-sources.txt`](hook-sources.txt).

1. `SystemGesturesPointerEventListener.java:179` `onPointerEvent` → `:238-242`
   `detectSwipe` returns `SWIPE_FROM_TOP` → `mCallbacks.onSwipeFromTop()`.
   Thresholds `:388-390`: start ≤ 24 dp, travel > 24 dp, < 500 ms — **48 px of
   travel on this panel**. The monitor is on the global monitor channel, so it
   sees the gesture even when the accessibility strip owns the touch.
2. `DisplayPolicy.java:492-495` `onSwipeFromTop()` →
   `requestTransientBars(mTopGestureHost, …)` (`:2379`).
3. The insets control target cannot show transient bars on an ordinary unlocked
   display (`WindowState.canShowTransient()` = `:3948-3950`), so control reaches
   the else branch `:2429-2437` and calls `mStatusBar.transferTouch()` at
   **`:2435`**.
4. `WindowState.java:2634-2635` `transferTouch()` →
   `InputManagerInternal#transferTouch()`: a synthetic DOWN is delivered to the
   status bar window and the strip's gesture is cancelled. SystemUI's
   `PhoneStatusBarView#onTouchEvent` (`:251`, delegates at `:278`) feeds its touch
   handler, and the shade expands. **This is the 100–400 ms flash.**

Why a SystemUI hook cannot win: after the transfer, SystemUI receives a DOWN at
the finger's *current* position (`y ≈ 24 dp+`), so "this started at the top edge"
is no longer recoverable there, and the first shade frame is already drawn before
`NotificationPanelViewController#onPanelStateChanged(STATE_OPENING)` (`:3797`,
`:3805`) — the exact point v1 hooked.

Also relevant: `requestTransientBars` returns early on the lock screen
(`:2399`, `controlTarget == getNotificationShade()`), so the guard never
interferes with the keyguard shade.

## 3. Chosen hook (least invasive that works)

| # | class#method (exact signature) | process / scope | what the module does | risk |
|---|---|---|---|---|
| 1 | `com.android.server.wm.WindowState#transferTouch()` → `boolean` | **android** (system_server) | **before-hook**, `setResult(Boolean.FALSE)` only when `this` is the `TYPE_STATUS_BAR` window and the gate is on. The gesture stays with the accessibility strip, which has already opened the Action Centre on ACTION_DOWN. | **medium-low**: hidden name drift; a bug here is a system_server crash — mitigated by a fully wrapped callback that lets the original run |
| 2 | `com.android.systemui.shade.NotificationPanelViewController#onPanelStateChanged(@PanelState int)` (`:3797`) | com.android.systemui | safety net; on `STATE_OPENING` only | low (v1, unchanged) |
| 3 | `…NotificationPanelViewController#makeExpandedVisible(boolean)` / `ShadeControllerImpl#start()` (`:283` / `:380`) | com.android.systemui | instance capture only, no behaviour | negligible |
| 4 | `ShadeControllerImpl#instantCollapseShade()` (`:277`), `#makeExpandedInvisible()` (`:301`) | com.android.systemui | close a shade that opened anyway, then start `org.ztmetro.actioncentre/.ActionCentreActivity` with `NEW_TASK | SINGLE_TOP | NO_ANIMATION` and `extra_instant=true` | low (v1, unchanged) |

Why not the assigned alternatives:

| rejected | reason |
|---|---|
| `com.android.systemui.statusbar.phone.SystemGesturesPointerEventListener` | **does not exist** (404 at the pin); the class is system_server-only |
| `SystemGesturesPointerEventListener#onPointerEvent` / `detectSwipe` in system_server | hot path (every motion event), would also suppress the transient status-bar reveal, and needs DOWN-coordinate bookkeeping for no gain |
| `DisplayPolicy#requestTransientBars` in system_server | works, but suppresses the whole transient-bar handling (wallpaper/immersive reveal) instead of only the reroute; kept as the documented fallback if `transferTouch` drifts |
| SystemUI-only (v1) | late by construction; cannot classify the synthetic DOWN |
| ROM fork | unnecessary for this defect |

Because the hook is at `transferTouch` and not at `requestTransientBars`, the
status bar is still revealed transiently (`showInsets` runs), only the touch
reroute is dropped. That is the minimum delta on stock behaviour.

## 4. Fail-safe matrix

| failure | result |
|---|---|
| `WindowState`/`transferTouch`/`mAttrs` missing or renamed | install logs once, no hook; stock shade works (SystemUI safety net still active) |
| hook body throws | caught; original `transferTouch()` runs → stock shade works |
| no system context / Settings read fails | gate = off, no suppression |
| `ShadeBridgeService` not an enabled accessibility service | gate = off (checked via `Settings.Secure.enabled_accessibility_services`, cached 2 s) |
| `Settings.System ztmetro_shade_hook` = 0 | gate = off, and v1 safety net also disabled → fully stock shade |
| Action Centre not installed | v1 router returns before touching anything |
| anything in SystemUI fails | v1 behaviour: stock shade stays |

The two scopes are independent: losing `android` degrades to v1, losing
`com.android.systemui` keeps the primary guard.

## 5. Build facts

Built 2026-11-16 on `hermes-ubuntu` in `~/ztux/sysuihook` (module tree synced from
`repo/sysui/shade-hook`, builder from `repo/scripts/build-shade-hook.sh`), then
`rsync --checksum` back-compared with the repo tree: **no differences**.
APK 20 947 bytes, sha256 `164a61f689c53f675f5bdd6966b4a50f8e3811b5225f4b6bd3fe5d2536c046f3`.
Full tool output and dex dump: [`logs/build-verify.txt`](logs/build-verify.txt);
toolchain paths: [`logs/build-info.txt`](logs/build-info.txt).

## 6. Enable steps (lead, Vector)

1. Install over v1 — same package, no data:
   `adb install -r repo/evidence/shade-hook-v2/ztmetro-shade-hook.apk`
2. Vector CLI: **enable the module** (`org.ztmetro.shadehook`) and set the scope
   to **both** `android` and `com.android.systemui`:
   `vector module enable org.ztmetro.shadehook`
   `vector scope org.ztmetro.shadehook android`
   `vector scope org.ztmetro.shadehook com.android.systemui`
   (exact subcommand names depend on the Vector build — the requirement is the
   two scope entries; `android` is the one that was not in the old instructions)
3. In the Vector UI the scope list must show **Android System** *and*
   **System UI**. Without *Android System* the APK still runs, but only as the
   v1 safety net — §7 tells the two states apart in one logcat line.
4. Reboot (`adb reboot`). The system_server hook is installed when system_server
   starts; a scope change does not apply to an already-running system_server.
5. Optional escape hatch, no reboot: `adb shell settings put system ztmetro_shade_hook 0`
   returns the stock shade immediately (both halves read it live, 2 s cache).

## 7. Logcat acceptance — what proves the stock shade never opened

Filter (the message text is unique, do not depend on the tag):
`adb logcat -d | grep -iE "ztmetro-shadehook|ShadeTouch|Could not transfer touch"`

**A. Module really in system_server (must appear once after boot):**
```
ztmetro-shadehook: loading in android (system_server); stock behaviour is the fallback
ztmetro-shadehook: hooked com.android.server.wm.WindowState#transferTouch() — status-bar reroute is its only caller (DisplayPolicy.java:2435)
ztmetro-shadehook: transfer guard ENABLED — ShadeBridgeService enabled, ztmetro_shade_hook=1
```
If the first two are missing → the `android` scope is not active; the guard is
absent and every swipe will log the safety net instead (case D).

**B. One top-edge swipe (the pass condition):**
```
ztmetro-shadehook: BLOCKED status-bar transferTouch() #1 — gesture stays with the ztmetro strip; stock shade not opened
WindowManager: Could not transfer touch to the status bar        <- stock code, corroborates
ShadeTouch: ACTION_DOWN x=… y=…                                  <- strip owns the gesture
ShadeTouch: … strip open … / panel start …                        <- Action Centre started on DOWN
```
and **no** `ztmetro-shadehook: SAFETY NET:` line, and no
`NotificationPanelViewController` OPENING work between them.

**C. Counter check:** the `#N` in the BLOCKED line increases by one per top-edge
swipe (≤ 1 line/s by design), and `ShadeTouch` shows no `ACTION_CANCEL` between
that DOWN and the panel start — previously the cancel was the reroute.

**D. Failure mode, explicit:**
```
ztmetro-shadehook: SAFETY NET: stock shade reached STATE_OPENING — the system_server transfer guard did not stop it (scope 'android' missing, or another shade path); collapsing and starting the Action Centre
```
This is still a working (v1) experience, but with the flash. It is the line to
report back with.

**E. Gate-off check:** `transfer guard disabled — ShadeBridgeService not enabled, ztmetro_shade_hook=1`
means the accessibility service is off (or its setting entry has not been written
yet), which is the intended fail-safe.

## 8. Rollback

* Undo the behaviour: `adb shell settings put system ztmetro_shade_hook 0`
  (instant, both scopes live).
* Undo the module: disable/remove the scope in Vector and reboot, or
  `adb uninstall org.ztmetro.shadehook`. Nothing of the module persists in
  SystemUI or system_server after a reboot.

## 9. Not verified / residual risk

1. **No device run.** Everything above is source-level; the acceptance lines in
   §7 are predictions built from the verified call sites, not observed output.
2. **system_server injection** was not exercised. If this Vector build refuses
   the `android` scope, case D is the expected (safe) result; that is a
   deployment fact the lead can settle in one reboot.
3. **Right-edge quick settings**: while the guard is on, the old v1 "swipe the
   right 18 % of the status bar for the real shade" path is unreachable (the
   shade no longer opens at all), because `transferTouch()` carries no
   coordinates. Quick settings live in the Metro panel; the stock shade returns
   with `ztmetro_shade_hook=0`. If the right-edge exception is wanted, the next
   step is the documented `detectSwipe` hook (more invasive, hot path).
4. **Gate lag**: after enabling/disabling the accessibility service the gate can
   be stale for up to 2 s.
5. **Strip absent while the service is enabled** (overlay add failed): a top-edge
   swipe would do nothing. ShadeBridgeService logs its own overlay failure; the
   recovery is the setting above.
6. **Hidden-name drift**: `WindowState#transferTouch`, `mAttrs`,
   `NotificationPanelViewController#onPanelStateChanged`,
   `ShadeControllerImpl#{start,makeExpandedVisible,instantCollapseShade,makeExpandedInvisible}`
   are all private/hidden API of this Lineage drop. Each failure is logged and
   leaves the stock shade working, but a future drop can silently degrade the
   module to the safety net.
7. **The guard is not per-gesture**: it is process-wide while the gate is on. A
   second, unrelated `transferTouch()` to the status bar (none exists in this
   tree) would also be blocked.

## 10. Files

| path | role |
|---|---|
| `repo/sysui/shade-hook/src/org/ztmetro/shadehook/FrameworkHooks.java` | system_server guard (§3 #1) |
| `repo/sysui/shade-hook/src/org/ztmetro/shadehook/ModuleEntry.java` | scope dispatch (`android` vs SystemUI) |
| `repo/sysui/shade-hook/src/org/ztmetro/shadehook/ShadeHooks.java`, `ShadeRouter.java` | v1 safety net, now with `extra_instant` and the SAFETY NET log |
| `repo/sysui/shade-hook/src/org/ztmetro/shadehook/SLog.java` | non-throwing, once-per-site logging |
| `repo/sysui/shade-hook/AndroidManifest.xml`, `res/values/arrays.xml` | two scopes, version 2.0 |
| `repo/scripts/build-shade-hook.sh` | build + verification incl. the system_server strings |
| `repo/evidence/shade-hook-v2/` | this report, source facts, APK, build logs |
