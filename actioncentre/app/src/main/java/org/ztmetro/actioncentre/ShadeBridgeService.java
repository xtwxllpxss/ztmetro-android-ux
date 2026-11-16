package org.ztmetro.actioncentre;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Turns the stock notification-shade gesture into our Action Centre panel.
 *
 * <p>Accessibility-only: the system binds this service when the user enables it
 * in Settings. It observes TYPE_WINDOW_STATE_CHANGED events and looks for the
 * SystemUI shade window by geometry, not by class name: on this LineageOS 23.2
 * build the shade reports as {@code android.widget.FrameLayout}, so the class
 * name says nothing. The expanded shade is the SystemUI window (TYPE_SYSTEM or
 * TYPE_APPLICATION) whose bounds cover at least 85 % of the display; the status
 * bar is the small SystemUI window and is ignored.
 *
 * <p>On a hit the service collapses the stock shade with
 * {@link #GLOBAL_ACTION_BACK} and starts {@link ActionCentreActivity} 100 ms
 * later, so the two panels do not animate over each other.
 *
 * <p>Fail-safe: with the service disabled the app has no running code path that
 * touches the shade, so the stock shade behaves exactly as before. Every
 * callback is wrapped so a bad event can never crash the process.
 */
public class ShadeBridgeService extends AccessibilityService {
    static final String TAG = "ShadeBridge";

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /** A window this large is the expanded shade, not the status bar. */
    private static final float SHADE_MIN_SCREEN_FRACTION = 0.85f;
    /** Ignore repeat shade events for this long after a successful trigger. */
    private static final long THROTTLE_MS = 1500L;
    /** Let the BACK collapse start before our slide-up covers the screen. */
    private static final long PANEL_START_DELAY_MS = 100L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTriggerUptime;
    /**
     * Package of the last foreground app. Tracked from window-state events so
     * that a shade opening over our own panel can be told apart from one over a
     * third-party app (the accessibility event for the shade itself is SystemUI).
     */
    private volatile String lastForegroundPackage = "";

    private final Runnable startPanel = new Runnable() {
        @Override
        public void run() {
            try {
                Intent intent = new Intent(ShadeBridgeService.this,
                        ActionCentreActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                Log.i(TAG, "Action Centre started (" + PANEL_START_DELAY_MS + " ms after BACK)");
            } catch (Throwable t) {
                Log.w(TAG, "starting Action Centre failed", t);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        try {
            super.onServiceConnected();
            Log.i(TAG, "service connected; shade = fullscreen " + SYSTEMUI_PACKAGE
                    + " window (class name is not used)");
        } catch (Throwable t) {
            Log.w(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null
                    || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return;
            }
            String pkg = asString(event.getPackageName());
            if (pkg.length() == 0) {
                return;
            }
            if (!SYSTEMUI_PACKAGE.equals(pkg)) {
                // Remember what was in front; the shade event itself is SystemUI.
                lastForegroundPackage = pkg;
                return;
            }
            handleSystemUiWindowState();
        } catch (Throwable t) {
            // An accessibility callback must never take the app down.
            Log.w(TAG, "event ignored after error", t);
        }
    }

    private void handleSystemUiWindowState() {
        Rect display = displayBounds();
        if (display == null) {
            Log.w(TAG, "skip: display bounds unavailable");
            return;
        }
        ShadeWindow shade = findFullscreenSystemUiWindow(display);
        if (shade == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (lastTriggerUptime != 0 && now - lastTriggerUptime < THROTTLE_MS) {
            Log.i(TAG, "skip: throttled (" + (now - lastTriggerUptime) + " ms < "
                    + THROTTLE_MS + " ms) shade=" + shade.describe(display));
            return;
        }
        if (isKeyguardLocked()) {
            Log.i(TAG, "skip: keyguard locked, shade=" + shade.describe(display));
            return;
        }
        if (isOwnPanelFocused()) {
            Log.i(TAG, "skip: Action Centre is the focused window, shade="
                    + shade.describe(display));
            return;
        }
        if (wasOwnPanelPreviouslyForeground()) {
            Log.i(TAG, "skip: previous foreground was Action Centre (last="
                    + lastForegroundPackage + "), shade=" + shade.describe(display));
            return;
        }

        lastTriggerUptime = now;
        Log.i(TAG, "shade detected: " + shade.describe(display)
                + " last=" + lastForegroundPackage + "; collapsing with GLOBAL_ACTION_BACK");
        boolean collapsed = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "GLOBAL_ACTION_BACK returned " + collapsed);
        handler.removeCallbacks(startPanel);
        handler.postDelayed(startPanel, PANEL_START_DELAY_MS);
    }

    /**
     * Scans the interactive windows for the expanded shade: a SystemUI window of
     * type SYSTEM or APPLICATION covering at least 85 % of the display. Small
     * SystemUI windows (status bar, volume dialog, status icons) never qualify.
     */
    private ShadeWindow findFullscreenSystemUiWindow(Rect display) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                Log.d(TAG, "skip: no interactive windows reported");
                return null;
            }
            Log.d(TAG, "systemui window-state; inspecting " + windows.size()
                    + " window(s), display=" + display.width() + "x" + display.height());
            long displayArea = (long) display.width() * display.height();
            if (displayArea <= 0) {
                return null;
            }
            ShadeWindow best = null;
            long bestArea = 0;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                int type;
                Rect bounds = new Rect();
                CharSequence title;
                try {
                    type = window.getType();
                    window.getBoundsInScreen(bounds);
                    title = window.getTitle();
                } catch (Throwable t) {
                    continue;
                }
                if (type != AccessibilityWindowInfo.TYPE_SYSTEM
                        && type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                if (!SYSTEMUI_PACKAGE.equals(windowPackage(window))) {
                    continue;
                }
                long area = (long) bounds.width() * bounds.height();
                float fraction = (float) area / (float) displayArea;
                Log.d(TAG, "systemui window type=" + type + " bounds="
                        + bounds.toShortString() + " " + Math.round(fraction * 100)
                        + "% of display title=" + asString(title));
                if (fraction >= SHADE_MIN_SCREEN_FRACTION && area > bestArea) {
                    best = new ShadeWindow(type, bounds, fraction);
                    bestArea = area;
                }
            }
            if (best == null) {
                Log.d(TAG, "skip: no SystemUI window covers >= "
                        + Math.round(SHADE_MIN_SCREEN_FRACTION * 100) + "% of the display");
            }
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "window inspection failed", t);
            return null;
        }
    }

    /** Window package via its root node; the only content ever read is the package. */
    private String windowPackage(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = null;
        try {
            root = window.getRoot();
            if (root == null) {
                return "";
            }
            CharSequence pkg = root.getPackageName();
            return pkg == null ? "" : pkg.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    /** Current display bounds; getMaximumWindowMetrics needs API 30. */
    private Rect displayBounds() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return new Rect(bounds);
                    }
                }
                DisplayMetrics metrics = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "reading display bounds failed", t);
        }
        return null;
    }

    private boolean isKeyguardLocked() {
        try {
            KeyguardManager km =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
            // If the lock state is unreadable, stay conservative and do nothing.
            return true;
        }
    }

    /**
     * True when the focused interactive window belongs to this app, i.e. our
     * ActionCentreActivity is already in front.
     */
    private boolean isOwnPanelFocused() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) {
                return false;
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || !window.isFocused()) {
                    continue;
                }
                return getPackageName().equals(windowPackage(window));
            }
        } catch (Throwable t) {
            Log.w(TAG, "window inspection failed", t);
        }
        return false;
    }

    /**
     * The recorded previous foreground package is ours, and our panel window is
     * still on screen. The window check keeps a stale record from blocking the
     * bridge after the panel has been closed (closing it may not emit a new
     * window-state event before the next swipe).
     */
    private boolean wasOwnPanelPreviouslyForeground() {
        if (!getPackageName().contentEquals(lastForegroundPackage)) {
            return false;
        }
        return hasVisibleOwnWindow();
    }

    private boolean hasVisibleOwnWindow() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) {
                return false;
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    continue;
                }
                if (getPackageName().equals(windowPackage(window))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public void onInterrupt() {
        try {
            handler.removeCallbacks(startPanel);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            handler.removeCallbacks(startPanel);
            Log.i(TAG, "service unbound");
        } catch (Throwable ignored) {
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            handler.removeCallbacks(startPanel);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /**
     * Whether the user has switched this service on in Settings. Read-only
     * probe used by the panel to label its "Enable gesture" row.
     */
    static boolean isEnabled(Context context) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) {
                return false;
            }
            String ourClass = ShadeBridgeService.class.getName();
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null || info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                ServiceInfo serviceInfo = info.getResolveInfo().serviceInfo;
                if (context.getPackageName().equals(serviceInfo.packageName)
                        && ourClass.equals(serviceInfo.name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Snapshot of the detected shade window, for logging and decisions. */
    private static final class ShadeWindow {
        final int type;
        final Rect bounds;
        final float fraction;

        ShadeWindow(int type, Rect bounds, float fraction) {
            this.type = type;
            this.bounds = bounds;
            this.fraction = fraction;
        }

        String describe(Rect display) {
            return "type=" + type + " bounds=" + bounds.toShortString()
                    + " " + Math.round(fraction * 100) + "% of display "
                    + display.width() + "x" + display.height();
        }
    }
}
