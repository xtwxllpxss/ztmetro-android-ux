# Measurement plan — who animates app → HOME on the phone

This is the step the host could not do (no adb). Run it once before choosing B1,
B2 or C1. Result to keep: one `resolve-activity` line, one `navigation_mode` line,
one handler name, one 5-second capture.

## 0. Baseline facts

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME
adb shell settings get secure navigation_mode
adb shell getprop ro.build.version.sdk          # expect 36
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
```

Expected on the documented ztmetro install: `org.ztmetro.start/.StartActivity`.
If it is `com.android.launcher3/...`, the Launcher3 path of report §1.3 applies.
`navigation_mode`: 0 = 3-button, 1 = 2-button, 2 = gesture. With the ugglite's
capacitive keys and no on-screen nav bar, expect 0; if it is 2, expect the
gesture/recents path.

## 1. Name the handler without logs (fastest)

Press HOME from a third-party app (e.g. Settings), then immediately:

```sh
adb shell dumpsys window transitions
```

Look for the last/active transition's handler line. The Shell code logs
`"animated by <handler>"` in `Transitions.dispatchTransition`
(`Transitions.java:1076-1083`). Record the class name:

- `com.android.wm.shell.transition.RemoteTransitionHandler` → Launcher3/Quickstep
  or another remote owns it.
- `com.android.wm.shell.transition.DefaultTransitionHandler` → the framework task
  anims (`task_open_*`) are live and the B1 RRO is relevant.

`dumpsys window transitions` is a transient view; if it is empty, use the module
or perfetto below.

## 2. Name the handler with the stub LSPosed module (reliable)

The module is [built and ready](lsposed/artifacts/ZtMetroTransitionTrack.apk); it is not
installed.

```sh
adb install -r lsposed/artifacts/ZtMetroTransitionTrack.apk
# LSPosed Manager -> Modules -> ztmetro transition track -> enable
# scope: com.android.systemui AND com.android.launcher3
adb shell su -c 'killall com.android.systemui'
adb logcat -c
# open an app, press HOME
adb logcat -d -s ztmetro-transition
```

Expected lines: `remote type=... changes=...` or `default type=... changes=...`,
with one `[mode=... task=... act=...]` per change. If the launcher process hooks
load, the launcher line prints as well.

## 3. Perfetto / screen capture

```sh
adb shell perfetto -o /data/misc/perfetto-traces/home.pftrace -t 6s -b 32mb \
  sched freq am wm gfx view input
adb shell screenrecord --time-limit 6 /sdcard/home.mp4
adb pull /data/misc/perfetto-traces/home.pftrace
adb pull /sdcard/home.mp4
```

Open the trace with `wm` + `gfx`; the Shell code emits
`TRACE_TAG_WINDOW_MANAGER` instant slices named
`<Handler>#startAnimation animated <type>` (`Transitions.java:1079-1083`). The
screen recording is the visual ground truth for whether the current motion is the
105 % horizontal slide of `task_open_*` or Launcher's spring.

## 4. After the B1 RRO (if chosen)

```sh
# preinstalled static overlay, same policy as ZtMetroFramework.apk
adb push rro/artifacts/ZtMetroTransitions.apk /data/local/tmp/
adb shell su -c 'mount -o rw,remount /product'   # or the Magisk module mapping
adb shell su -c 'cp /data/local/tmp/ZtMetroTransitions.apk /product/overlay/'
adb reboot
adb shell su -c 'cmd overlay list | grep transitions'
adb shell su -c 'cmd overlay dump org.ztmetro.overlay.transitions'
# repeat §2/§3 and compare the capture
```

A static overlay is enabled at boot and immutable; if the home screen does not
change, check first that §1/§2 says `DefaultTransitionHandler`, then that the
overlay is actually in `/product/overlay` and `idmap2` did not reject it
(`adb logcat -d | grep -i idmap`).

## 5. Acceptance criterion

The research is accepted only when the phone shows a **visible** change in the
return animation, with the handler named in §1/§2 and the same transition still
interruptible by a second HOME/swipe. A build artifact, an installed overlay, or a
log line alone is not acceptance.
