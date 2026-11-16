package org.ztmetro.keyguard;

import de.robv.android.xposed.XposedBridge;

/**
 * Rate-limited logging.  Every failure path in this module ends up here instead of
 * throwing into SystemUI; a site logs once per process so a per-frame failure cannot
 * flood logd or the LSPosed log.
 */
final class KLog {

    private static final java.util.HashSet<String> LOGGED = new java.util.HashSet<>();

    private KLog() {
    }

    static void once(String tag, String site, Object detail) {
        try {
            synchronized (LOGGED) {
                if (!LOGGED.add(site)) {
                    return;
                }
            }
            if (detail instanceof Throwable) {
                XposedBridge.log(tag + ": " + site + " failed, stock keyguard left as-is");
                XposedBridge.log((Throwable) detail);
            } else {
                XposedBridge.log(tag + ": " + site + (detail == null ? "" : " — " + detail));
            }
        } catch (Throwable ignored) {
            // logging must never throw into SystemUI
        }
    }

    static void info(String tag, String message) {
        try {
            XposedBridge.log(tag + ": " + message);
        } catch (Throwable ignored) {
        }
    }
}
