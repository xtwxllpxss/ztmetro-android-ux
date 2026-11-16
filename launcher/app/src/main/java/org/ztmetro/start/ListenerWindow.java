package org.ztmetro.start;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * Pass 2 (perf): the notification listener is released when Start is not visible.
 *
 * <p>A bound {@link NotificationListenerService} keeps the launcher process at a
 * perceptible importance for as long as the grant exists: the system holds the
 * binder, keeps the service alive and re-delivers every notification. On a 2 GB
 * phone that is the standing 26-32 MB of the Start process long after the user
 * left HOME for a game or a browser.
 *
 * <p>Lifecycle contract, exactly two minutes of grace:
 *
 * <ul>
 *   <li>{@link #onHidden(Context)} from {@code onPause}: schedule
 *       {@code requestUnbind()} in {@value #HIDDEN_UNBIND_MS} ms. A quick app
 *       switch, the Action Centre panel or a translucent dialog never fires it
 *       because {@link #onVisible(Context)} cancels the pending release;</li>
 *   <li>{@link #onVisible(Context)} from {@code onResume}: cancel the pending
 *       release; if the listener was released, {@code requestRebind()} asks the
 *       system to bind it again, and {@code onListenerConnected()} rebuilds the
 *       live-tile map from the active notification set (nothing is persisted, so
 *       the rebuild is the recovery path).</li>
 * </ul>
 *
 * <p>Both calls are cheap and safe without the grant: with no notification access
 * the service is never bound, {@code requestRebind} is a no-op, and each step logs
 * one line under {@code StartPerf} so a device run can prove the release.
 */
final class ListenerWindow {

    static final String TAG = "StartPerf";

    /** Start not visible for this long -> release the listener binding. */
    static final long HIDDEN_UNBIND_MS = 2L * 60L * 1000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static final Runnable UNBIND = new Runnable() {
        @Override
        public void run() {
            if (!StartNotificationListener.requestUnbindIfConnected()) {
                return;
            }
            released = true;
            Log.i(TAG, "notification listener released: Start hidden for "
                    + (HIDDEN_UNBIND_MS / 1000L) + " s");
        }
    };

    /** True between a successful release and the next resume. */
    private static boolean released;

    private ListenerWindow() {
    }

    static void onVisible(Context context) {
        HANDLER.removeCallbacks(UNBIND);
        if (!released || context == null) {
            return;
        }
        try {
            NotificationListenerService.requestRebind(
                    LiveTiles.component(context.getApplicationContext()));
            released = false;
            Log.i(TAG, "notification listener rebound on resume");
        } catch (Throwable t) {
            Log.w(TAG, "requestRebind failed; live tiles stay static this session", t);
        }
    }

    static void onHidden(Context context) {
        HANDLER.removeCallbacks(UNBIND);
        HANDLER.postDelayed(UNBIND, HIDDEN_UNBIND_MS);
    }

    /** Evidence readback for the device protocol: true when the listener is released. */
    static boolean isReleased() {
        return released;
    }
}
