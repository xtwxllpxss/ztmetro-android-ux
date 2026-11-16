# ztmetro Start v2 — device test plan (lead, adb owner)

APK: [`ztmetro-start.apk`](ztmetro-start.apk) ·
sha256 `5a6cdc3af7b976646e27a3c1003754c1d528010b3f7739671d234a26791ab0ee` ·
package `org.ztmetro.start` · version 2.0 · signed with the same key as the installed v1.

## Install / make it HOME

```sh
adb install -r repo/evidence/launcher-v2/ztmetro-start.apk
# If the device v1 was signed with a different key: adb uninstall org.ztmetro.start first.
adb shell cmd package set-home-activity org.ztmetro.start/.StartActivity
# Alternative on Android 10+: adb shell cmd role add-role-holder android.app.role.HOME org.ztmetro.start
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys package org.ztmetro.start | grep -E 'versionName|versionCode'
```

Expected: versionName=2.0, no crash, Start opens as HOME. `adb shell cmd package
resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
should resolve to `org.ztmetro.start/.StartActivity`.

## 1. v1 regression (do first)

- Tiles show seeded apps and launch on tap.
- Long-press a tile: open / make wide / make medium / unpin all work; pin from apps long-press.
- Back on the apps page returns to Start; back on Start does nothing.

## 2. Pivot turnstile

- Tap **apps**, then tap **start** while the transition is still running. Expected: motion
  reverses from where it is; no blank frame, no stuck page, pivot underline returns.
- Repeat quickly ~10 times. Expected: same, no ANR/visible jank.

## 3. App list: search + semantic zoom

- Tap the search field, type the first letters of a pinned app. Expected: list filters as
  you type, letters on the right shrink to the matching sections, empty state shows for no match.
- Back with a query clears it; a second back returns to Start; the keyboard does not cover
  the active row.
- Drag the right-edge letters top→bottom. Expected: list jumps section by section, the sticky
  section letter stays visible and stretches while dragging; releasing restores it.

## 4. Appearance tile

- The last Start tile shows four accent swatches and `live tiles off` (expected before grant).
- Tap it: sheet title `appearance`, row `live tiles: off`, rows `notification access` and
  `system settings` open the right Settings screens; no crash if a screen is absent.

## 5. Live tiles (off by default, then grant)

Before granting, confirm:
```sh
adb shell settings get secure enabled_notification_listeners   # must not list ztmetro
adb shell dumpsys activity services org.ztmetro.start          # no listener service bound
```

Grant:
```sh
adb shell cmd notification allow_listener org.ztmetro.start/org.ztmetro.start.StartNotificationListener
adb shell settings get secure enabled_notification_listeners   # must now contain the full component
```
Leave Start (press Home again) and open the appearance sheet: it should read `live tiles: on`.

Trigger a notification while Start is visible **from a pinned app** (message, mail, calendar —
not `cmd notification post`, which posts as `com.android.shell` and therefore has no tile).
Expect:
- a white count badge on that app's tile;
- one 250 ms flip to the dark-accent live face showing the newest title (wide: title + summary);
- the face then stays still — there must be **no repeating flip/timer**;
- no flip for apps with no notification.
Remove the notification from the shade: the tile returns to its static label and the badge clears.

Revoke and confirm graceful degradation:
```sh
adb shell cmd notification disallow_listener org.ztmetro.start/org.ztmetro.start.StartNotificationListener
```
Leave/return to Start: appearance says `live tiles off`; tiles are static again. No crash.

## 6. Memory / stability

```sh
adb shell dumpsys meminfo org.ztmetro.start | sed -n '1,30p'
adb shell dumpsys activity services org.ztmetro.start
adb logcat -d | grep -Ei 'AndroidRuntime|ztmetro' | tail -50
```
Look for: total PSS in the expected ≈28–42 MB range, no growth while idle on Start (no
timer should be running), no `AndroidRuntime` exceptions, listener bound only after the grant.

## 7. What would fail the build acceptance

- Flip repeats on its own, or live content appears without a notification.
- Pivot reversal leaves a GONE page or the old title stuck at alpha 0.
- Search filtering crashes on rotation or while the IME is open.
- Any notification listener crash/`SecurityException` in logcat after revoke.
- APK installed as an update but version still 1.0, or HOME role not held.
