# ztmetro-keyguard

Independent LSPosed module that turns the stock LineageOS 23.2 (Android 16) lock
screen into a Metro lock screen: 56sp Semilight/Selawik clock, 13sp uppercase
date line, accent-coloured owner-info/indication text.  View hooks only — no new
windows, no permissions, no resource overlay.

* Package: `org.ztmetro.keyguard`
* Scope: `com.android.systemui`
* Build: `scripts/build-keyguard-module.sh` (aapt2 + javac + d8, signed dev APK)
* Evidence / hook table / rollback: `evidence/keyguard-module/REPORT.md`

Every hook is fail-safe: callback bodies are wrapped in `try/catch(Throwable)` and
log once via `XposedBridge.log`.  If any target is missing or throws, the stock
lock screen renders unchanged.
