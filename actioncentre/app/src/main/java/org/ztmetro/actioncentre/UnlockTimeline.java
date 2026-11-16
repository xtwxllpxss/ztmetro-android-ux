package org.ztmetro.actioncentre;

import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wall-clock timeline for the unlock path. One tag ({@code UnlockTimeline}) so
 * the whole swipe -> proxy -> credential -> USER_PRESENT -> overlay-removed
 * sequence can be read with:
 *
 * <pre>adb logcat -s UnlockTimeline LockOverlay ShadeBridge UnlockProxy</pre>
 *
 * Deltas are measured with {@link SystemClock#elapsedRealtime()} (monotonic);
 * the wall timestamp is local time with milliseconds. The state is static
 * because the accessibility service and the transparent proxy activity live in
 * the same process.
 */
final class UnlockTimeline {
    static final String TAG = "UnlockTimeline";

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat WALL =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static long startWall;
    private static long startElapsed;
    private static boolean active;

    private UnlockTimeline() {
    }

    /** Begin a new timeline (called on the bottom-strip swipe). */
    static void start(String event) {
        synchronized (LOCK) {
            startWall = System.currentTimeMillis();
            startElapsed = SystemClock.elapsedRealtime();
            active = true;
            Log.i(TAG, "unlock timeline | BEGIN " + event + " | +0 ms | wall="
                    + WALL.format(new Date(startWall)));
        }
    }

    /** Add one event with its delta from the swipe; no-op when idle. */
    static long mark(String event) {
        synchronized (LOCK) {
            if (!active) {
                return -1L;
            }
            long delta = SystemClock.elapsedRealtime() - startElapsed;
            Log.i(TAG, "unlock timeline | " + event + " | +" + delta + " ms | wall="
                    + WALL.format(new Date()));
            return delta;
        }
    }

    static boolean isActive() {
        synchronized (LOCK) {
            return active;
        }
    }

    static void reset() {
        synchronized (LOCK) {
            active = false;
        }
    }
}
