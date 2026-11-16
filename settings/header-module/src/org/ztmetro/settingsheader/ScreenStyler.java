package org.ztmetro.settingsheader;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Applies the ztmetro screen chrome after every attach/refresh with exactly <b>two</b>
 * passes:
 *
 * <ol>
 *   <li><b>pass 1/2 "attach"</b> — immediately after {@code setContentView}/header attach;</li>
 *   <li><b>pass 2/2 "post-layout"</b> — one {@code postDelayed(150 ms)} run, after Settings
 *       has inflated and laid out the fragment rows.</li>
 * </ol>
 *
 * <p>The old schedule ran at 0/150/600 ms and every pass contained four independent tree
 * walks (MetroTinter, RowIcons, FlatRows, RowType): up to 12 traversals per screen, and
 * more because {@code setTitle}/{@code onResume} kicked their own chains.  Now a pass is
 * one {@link ScreenPass} walk, and a kick that arrives while the post-layout pass is still
 * queued joins that pass instead of starting a third.  The chain is coalesced per Activity
 * and never becomes a poller.
 *
 * <p>Each pass logs one line (tag {@code ztmetro-header}) with the number of views visited
 * and applies, e.g. {@code pass 1/2 attach: 934 views, 61 applies, 7 ms}.
 */
final class ScreenStyler {

    private static final String TAG = "ztmetro-header";
    private static final int BLACK = Color.BLACK;
    private static final long POST_LAYOUT_DELAY_MS = 150L;
    private static final int PASS_TOTAL = 2;

    private static final WeakHashMap<Activity, Chain> CHAINS = new WeakHashMap<>();

    private ScreenStyler() {
    }

    /** Black window chrome, one walk, content offset and the bottom bar; call on attach/refresh. */
    static void apply(Activity activity, boolean homepage) {
        if (activity == null) {
            return;
        }
        Chain chain = CHAINS.get(activity);
        if (chain == null) {
            chain = new Chain(activity);
            CHAINS.put(activity, chain);
        }
        chain.kick(homepage);
    }

    private static final class Chain {
        // Weak on purpose: CHAINS is a WeakHashMap keyed by the Activity, so the value
        // must not keep the key alive (otherwise the entry can never be collected).
        private final WeakReference<Activity> activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable postLayout = new Runnable() {
            @Override
            public void run() {
                if (!queued) {
                    return;
                }
                queued = false;
                runPass(PASS_TOTAL, "post-layout");
                lastPairEnd = SystemClock.uptimeMillis();
            }
        };
        private boolean homepage;
        private boolean queued;
        private long lastPairEnd;

        Chain(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        void kick(boolean home) {
            homepage = home;
            if (queued) {
                // The queued post-layout pass will pick this screen state up; do not add
                // a third walk for setTitle/onResume arriving inside the same window.
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (lastPairEnd != 0 && now - lastPairEnd < 250L) {
                // onResume/setTitle arriving just after pass 2/2: the rows it would touch
                // were already visited, so do not start a third/fourth walk.
                return;
            }
            runPass(1, "attach");
            queued = true;
            handler.postDelayed(postLayout, POST_LAYOUT_DELAY_MS);
        }

        private void runPass(int number, String phase) {
            Activity target = activity.get();
            if (target == null) {
                return;
            }
            try {
                blackOut(target);
            } catch (Throwable t) {
                Log.d(TAG, "black background skipped: " + t);
            }
            try {
                ScreenPass.run(target, homepage, number, PASS_TOTAL, phase);
            } catch (Throwable t) {
                Log.w(TAG, "row pass failed (stock rows kept)", t);
            }
            try {
                // The header grows with the 58 sp title and the status-bar inset; the
                // screen layout is pushed down by its measured height.
                HeaderBinder.applyContentOffsets(target);
            } catch (Throwable t) {
                Log.d(TAG, "header offset not applied: " + t);
            }
            try {
                // Re-apply after Settings' own inset handling: the bar must never cover a
                // row.  No tree walk (tag/findViewById only); suppressed on hardware keys.
                BottomBar.attach(target);
            } catch (Throwable t) {
                Log.d(TAG, "bottom bar re-apply skipped: " + t);
            }
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
