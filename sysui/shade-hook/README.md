# ztmetro-shade-hook

Independent LSPosed module that makes the **system shade gesture** open the Metro
Action Centre instead of the stock Android shade, on LineageOS 23.2 / Android 16
(ugglite).

* Package: `org.ztmetro.shadehook`
* Scope: `com.android.systemui`
* Target app: `org.ztmetro.actioncentre/.ActionCentreActivity`
* Build: `scripts/build-shade-hook.sh` (aapt2 + javac + d8, signed dev APK)
* Evidence, hook table, install and rollback: `evidence/shade-hook/REPORT.md`

How it behaves: when `NotificationPanelViewController` reports the shade state
`OPENING` on the unlocked shade, the module hides the stock shade through the
public `ShadeController` API and starts the Action Centre with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`. The stock shade is never
modified in place.

Fail-safe rules (every callback is `try/catch(Throwable)`; a failure means the
stock shade simply stays):

* the keyguard shade is never replaced (`StatusBarState.SHADE` only, no bouncer);
* a swipe that starts in the right 18 % of the status bar opens the real shade,
  so quick settings stay reachable;
* `settings put system ztmetro_shade_hook 0` turns the redirect off entirely;
* repeats within 800 ms are ignored (the stock shade is allowed through);
* if `org.ztmetro.actioncentre` is not installed the module does nothing.

No Activity, Service, Receiver, permission or window of its own. Uninstalling the
APK or unchecking the LSPosed scope is the complete rollback.
