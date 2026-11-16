package org.ztmetro.actioncentre;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * Turns the stock notification-shade gesture into our Action Centre panel.
 *
 * <p>Accessibility-only: the system binds this service when the user enables it
 * in Settings. It observes TYPE_WINDOW_STATE_CHANGED events from SystemUI only
 * (see res/xml/shade_bridge.xml), and when a shade / notification-shade / quick
 * settings window appears it collapses the stock shade with
 * {@link #GLOBAL_ACTION_BACK} and starts {@link ActionCentreActivity} a beat
 * later, so the two panels do not animate over each other.
 *
 * <p>Fail-safe: with the service disabled the app has no running code path that
 * touches the shade, so the stock shade behaves exactly as before. Every
 * callback is wrapped so a bad event can never crash the process.
 */
public class ShadeBridgeService extends AccessibilityService {
    static final String TAG = "ShadeBridge";

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /** Ignore repeat shade events for this long after a successful trigger. */
    private static final long THROTTLE_MS = 1500L;
    /** Let the BACK collapse start before our slide-up covers the screen. */
    private static final long PANEL_START_DELAY_MS = 100L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTriggerUptime;

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
            Log.i(TAG, "service connected; watching " + SYSTEMUI_PACKAGE
                    + " window state changes");
        } catch (Throwable t) {
            Log.w(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handleEvent(event);
        } catch (Throwable t) {
            // An accessibility callback must never take the app down.
            Log.w(TAG, "event ignored after error", t);
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        String pkg = asString(event.getPackageName());
        if (!SYSTEMUI_PACKAGE.equals(pkg)) {
            return;
        }
        String cls = asString(event.getClassName());
        if (!isShadeWindow(cls)) {
            // Debug breadcrumb: proves the event reaches us even when a future
            // SystemUI renames the window; keep it at debug level.
            Log.d(TAG, "systemui window-state class=" + cls + " (not a shade)");
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (lastTriggerUptime != 0 && now - lastTriggerUptime < THROTTLE_MS) {
            Log.i(TAG, "skip: throttled (" + (now - lastTriggerUptime) + " ms < "
                    + THROTTLE_MS + " ms) class=" + cls);
            return;
        }
        if (isKeyguardLocked()) {
            Log.i(TAG, "skip: keyguard locked, class=" + cls);
            return;
        }
        if (isOwnPanelFocused()) {
            Log.i(TAG, "skip: Action Centre is the focused window, class=" + cls);
            return;
        }

        lastTriggerUptime = now;
        Log.i(TAG, "shade window detected pkg=" + pkg + " class=" + cls
                + "; collapsing with GLOBAL_ACTION_BACK");
        boolean collapsed = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "GLOBAL_ACTION_BACK returned " + collapsed);
        handler.removeCallbacks(startPanel);
        handler.postDelayed(startPanel, PANEL_START_DELAY_MS);
    }

    /** Shade, NotificationShade, QuickSettings — the SystemUI shade surfaces. */
    private static boolean isShadeWindow(String className) {
        if (className == null || className.length() == 0) {
            return false;
        }
        String lower = className.toLowerCase(Locale.US);
        return lower.contains("shade") || lower.contains("quicksettings");
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
     * ActionCentreActivity is already in front and the event is not a fresh
     * shade opening over a third-party app.
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
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    return false;
                }
                try {
                    CharSequence focusedPkg = root.getPackageName();
                    return focusedPkg != null
                            && getPackageName().contentEquals(focusedPkg);
                } finally {
                    root.recycle();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "window inspection failed", t);
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
}
