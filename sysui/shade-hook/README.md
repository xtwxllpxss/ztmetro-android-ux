# ztmetro-shade-hook

LSPosed module that stops the Android 16 stock shade from opening on a top-edge
swipe and opens the Metro Action Centre
(`org.ztmetro.actioncentre/.ActionCentreActivity`) instead. LineageOS 23.2
(ugglite), frameworks_base pinned at `797e43244b96c6a0dff5c81b21c4d4be665b082e`.

* Package: `org.ztmetro.shadehook` v2 (install over v1; same package id)
* Scopes: **`android`** (system_server) **and** `com.android.systemui`
* Build: `scripts/build-shade-hook.sh` (aapt2 + javac + d8, signed dev APK)
* Evidence, hook table, enable steps, logcat acceptance:
  [`evidence/shade-hook-v2/REPORT.md`](../../evidence/shade-hook-v2/REPORT.md)

## Why two scopes

The stock shade is not opened by the accessibility layer. A swipe that starts in
the top 24 dp and travels more than 24 dp is recognised by the **system_server**
monitor `com.android.server.wm.SystemGesturesPointerEventListener`, and
`DisplayPolicy` reroutes the touch to the status bar window with
`WindowState#transferTouch()` (`DisplayPolicy.java:2435`). SystemUI then gets a
synthetic DOWN and starts expanding the shade — while the accessibility strip
that owns the real gesture is cancelled. No SystemUI-scoped hook can undo that;
by the time `NotificationPanelViewController` reports `STATE_OPENING` the shade
is already on screen (that was v1, and the 100–400 ms flash).

So:

| scope | class | role |
|---|---|---|
| `android` | `com.android.server.wm.WindowState#transferTouch()` | **primary**: return `false` for the `TYPE_STATUS_BAR` window while the strip is the intended owner. The gesture stays with the strip, which starts the Action Centre on ACTION_DOWN. |
| `com.android.systemui` | `NotificationPanelViewController#onPanelStateChanged`, `ShadeControllerImpl` | **safety net**: if a stock shade opened anyway, collapse it and start the Action Centre with `extra_instant=true`. |

`transferTouch()` has exactly one caller (the status bar branch in
`DisplayPolicy`), so blocking it changes nothing else: the transient status-bar
reveal, the bottom/left/right swipes and the lock screen (which returns earlier,
`DisplayPolicy.java:2399`) are untouched.

## Fail-safe rules

Every callback is `try/catch(Throwable)`; a failure means the stock shade stays.

* the guard only acts when `org.ztmetro.actioncentre/.ShadeBridgeService` is an
  enabled accessibility service **and** `Settings.System ztmetro_shade_hook` is
  not 0 (both cached 2 s; if they cannot be read, the guard is off);
* the lock screen is never involved (the framework returns before the transfer);
* `settings put system ztmetro_shade_hook 0` returns the fully stock shade live,
  without a reboot;
* the keyguard shade is never replaced, and the safety net keeps v1's rules
  (unlocked shade only, right-edge swipe untouched, 800 ms debounce);
* if `org.ztmetro.actioncentre` is not installed the module does nothing.

No Activity, Service, Receiver, permission or window of its own. Unchecking the
scopes or uninstalling the APK is the complete rollback after a reboot.

## Files

| file | role |
|---|---|
| `src/org/ztmetro/shadehook/FrameworkHooks.java` | system_server guard (the fix) |
| `src/org/ztmetro/shadehook/ModuleEntry.java` | scope dispatch |
| `src/org/ztmetro/shadehook/ShadeHooks.java` | SystemUI hook installation |
| `src/org/ztmetro/shadehook/ShadeRouter.java` | safety-net policy + Action Centre launch |
| `src/org/ztmetro/shadehook/SLog.java` | logging that cannot throw into the host process |
| `res/values/arrays.xml` | `xposed_scope` = `android`, `com.android.systemui` |
