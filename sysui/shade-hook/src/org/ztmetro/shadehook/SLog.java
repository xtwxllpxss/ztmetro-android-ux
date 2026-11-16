package org.ztmetro.shadehook;

import de.robv.android.xposed.XposedBridge;

/**
 * Logging that can never throw into SystemUI.
 *
 * <p>{@link #once} writes a site at most once per process, so a hook that fails on
 * every gesture cannot flood logd; {@link #info} is for the few events worth
 * seeing each time (a successful redirect, a deliberate edge fallback).
 */
final class SLog {

    private static final java.util.HashSet<String> LOGGED = new java.util.HashSet<>();

    private SLog() {
    }

    static void once(String tag, String site, Object detail) {
        try {
            synchronized (LOGGED) {
                if (!LOGGED.add(site)) {
                    return;
                }
            }
            if (detail instanceof Throwable) {
                XposedBridge.log(tag + ": " + site + " failed, stock shade left as-is");
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
