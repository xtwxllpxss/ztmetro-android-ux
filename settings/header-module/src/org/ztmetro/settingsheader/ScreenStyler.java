package org.ztmetro.settingsheader;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Applies the ztmetro screen chrome after every attach/refresh:
 *
 * <ol>
 *   <li>flat black window/status/navigation background (no grey Material surfaces),</li>
 *   <li>the 40 dp bottom bar and its content padding ({@link BottomBar}),</li>
 *   <li>text-only dashboard rows on the home page ({@link RowIcons}),</li>
 *   <li>flat black preference rows everywhere ({@link FlatRows}).</li>
 * </ol>
 *
 * <p>Settings inflates the dashboard/fragment rows after {@code setContentView} and again
 * after some fragment transactions, so each kick runs immediately and then at
 * +200/+700/+1600 ms.  A new kick for the same activity replaces the pending chain
 * (coalesced per Activity, never a permanent poller).  All work happens on the main
 * thread, is idempotent, and every stage is guarded: a failure only logs.
 */
final class ScreenStyler {

    private static final String TAG = "ztmetro-header";
    private static final int BLACK = Color.BLACK;
    private static final long[] DELAYS_MS = {150L, 600L};

    private static final WeakHashMap<Activity, Passes> PASSES = new WeakHashMap<>();

    private ScreenStyler() {
    }

    static void apply(Activity activity, boolean homepage) {
        if (activity == null) {
            return;
        }
        try {
            BottomBar.attach(activity);
        } catch (Throwable t) {
            Log.w(TAG, "bottom bar pass failed (Settings untouched)", t);
        }
        Passes passes = PASSES.get(activity);
        if (passes == null) {
            passes = new Passes(activity, homepage);
            PASSES.put(activity, passes);
        }
        passes.kick();
    }

    private static final class Passes {
        // Weak on purpose: PASSES is a WeakHashMap keyed by the Activity, so the value
        // must not keep the key alive (otherwise the entry can never be collected).
        private final WeakReference<Activity> activity;
        private final boolean homepage;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int generation;

        Passes(Activity activity, boolean homepage) {
            this.activity = new WeakReference<>(activity);
            this.homepage = homepage;
        }

        void kick() {
            generation++;
            final int current = generation;
            handler.removeCallbacksAndMessages(null);
            runPass();
            for (final long delay : DELAYS_MS) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (current == generation) {
                            runPass();
                        }
                    }
                }, delay);
            }
        }

        private void runPass() {
            Activity target = activity.get();
            if (target != null) {
                ScreenStyler.pass(target, homepage);
            }
        }
    }

    private static void pass(Activity activity, boolean homepage) {
        try {
            blackOut(activity);
        } catch (Throwable t) {
            Log.d(TAG, "black background skipped: " + t);
        }
        if (homepage) {
            try {
                RowIcons.apply(activity);
            } catch (Throwable t) {
                Log.w(TAG, "dashboard icon pass failed (icons kept)", t);
            }
        }
        try {
            FlatRows.apply(activity);
        } catch (Throwable t) {
            Log.w(TAG, "flat-row pass failed (cards kept)", t);
        }
        try {
            // Title/white + summary/grey, and on the dashboard a 20 sp title with no
            // summary line (docs/17 §6; ztmetro.c:6393).  Runs after the rows exist.
            MetroTinter.apply(activity);
            RowType.apply(activity, homepage);
        } catch (Throwable t) {
            Log.w(TAG, "row-type pass failed (stock type kept)", t);
        }
        try {
            // Re-apply after Settings' own inset handling: the bar must never cover a row.
            BottomBar.attach(activity);
        } catch (Throwable t) {
            Log.d(TAG, "bottom bar re-apply skipped: " + t);
        }
    }

    private static void blackOut(Activity activity) {
        Window window = activity.getWindow();
        if (window != null) {
            try {
                window.setStatusBarColor(BLACK);      // ztmetro status strip is black
                window.setNavigationBarColor(BLACK);  // ztmetro nav strip is black
            } catch (Throwable ignored) {
                // Android 15+ ignores these on edge-to-edge windows
            }
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setBackgroundColor(BLACK);
            }
        }
        View content = activity.findViewById(android.R.id.content);
        if (content != null) {
            content.setBackgroundColor(BLACK);
        }
    }
}
