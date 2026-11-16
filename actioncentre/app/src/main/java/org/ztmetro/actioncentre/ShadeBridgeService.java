package org.ztmetro.actioncentre;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
 * {@link #GLOBAL_ACTION_BACK} once, waits up to 250 ms for the full-screen
 * SystemUI window to disappear, then starts {@link ActionCentreActivity} with
 * the instant-open extra. There is no second BACK: after the panel starts it can
 * be the focused window, and a late BACK would dismiss our own panel.
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
    /** How often the short collapse wait checks whether the shade is gone. */
    private static final long POLL_INTERVAL_MS = 50L;
    /** Wait at most this long for the shade window; then start the panel anyway. */
    private static final long BACK_WAIT_MAX_MS = 250L;
    /**
     * Panel-first mechanism (strip present): give the panel this long to draw
     * its first frame under the shade, then send the one allowed BACK if the
     * shade is still on screen.
     */
    private static final long BACK_AFTER_START_MS = 120L;
    /** How long the shade-gone timeline is followed after BACK #1 (log only). */
    private static final long SHADE_SETTLE_MAX_MS = 700L;
    /** Transparent touch strip over the status bar (MetroWinLauncher's A1 trick). */
    private static final int STRIP_HEIGHT_DP = 24;
    /** A downward drag further than this in the strip opens the panel. */
    private static final int STRIP_DRAG_DP = 24;
    /** Ignore strip touches while a panel launch is already in flight. */
    private static final long STRIP_OPEN_THROTTLE_MS = 600L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTriggerUptime;
    /**
     * Package of the last foreground app. Tracked from window-state events so
     * that a shade opening over our own panel can be told apart from one over a
     * third-party app (the accessibility event for the shade itself is SystemUI).
     */
    private volatile String lastForegroundPackage = "";

    /** Display bounds captured at detection, reused by the collapse poll. */
    private Rect collapseDisplay;

    /** Transparent top-edge strip: the system shade never sees these touches. */
    private View stripView;
    private WindowManager stripWindowManager;
    private WindowManager.LayoutParams stripParams;
    private KeyguardManager stripKeyguardManager;
    private KeyguardManager.KeyguardLockedStateListener stripKeyguardListener;
    private long lastStripOpenUptime;

    /** ztMetro lock screen over the keyguard; its windows live in its own class. */
    private LockOverlayController lockOverlay;

    /**
     * Exactly one BACK per trigger. In the panel-first path BACK comes after
     * the panel has started; it is never sent twice.
     */
    private boolean backSent;
    private static volatile boolean stripActive;

    /** Detection -> panel-start -> BACK -> panel-visible timeline (log). */
    private static volatile long detectedAtUptime;
    private static volatile long detectedAtWall;
    private static volatile long panelStartUptime;
    private static volatile long backAtUptime;
    private static volatile boolean panelVisibleLogged;
    /** "detection" for the window-state fallback, "strip trigger" for the strip. */
    private static volatile String triggerLabel = "detection";

    /**
     * Waits up to 250 ms for the SystemUI full-screen window to disappear, then
     * starts the panel (instant). This poll never sends another BACK: after
     * BACK #1 the panel may become the focused window, and a late BACK would
     * dismiss our own panel.
     */
    private final Runnable collapsePoll = new Runnable() {
        @Override
        public void run() {
            try {
                if (collapseDisplay == null) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - detectedAtUptime;
                if (findFullscreenSystemUiWindow(collapseDisplay, false) == null) {
                    Log.i(TAG, "shade gone after " + elapsed
                            + " ms; starting Action Centre");
                    startPanel("shade window gone after BACK #1");
                    return;
                }
                if (elapsed >= BACK_WAIT_MAX_MS) {
                    Log.i(TAG, "shade still visible at " + elapsed
                            + " ms; starting Action Centre anyway");
                    startPanel("shade still visible at " + BACK_WAIT_MAX_MS + " ms cap");
                    return;
                }
                handler.postDelayed(this, POLL_INTERVAL_MS);
            } catch (Throwable t) {
                Log.w(TAG, "collapse poll failed", t);
                startPanel("collapse poll failed");
            }
        }
    };

    /**
     * Starts the panel (instant, NEW_TASK). Called before BACK in the strip
     * path, so the panel is already drawn under the closing shade.
     */
    private void startPanel(String reason) {
        handler.removeCallbacks(collapsePoll);
        panelStartUptime = SystemClock.uptimeMillis();
        long elapsed = detectedAtUptime == 0 ? 0
                : panelStartUptime - detectedAtUptime;
        Log.i(TAG, "panel start at " + elapsed + " ms (" + reason + "); strip="
                + (stripActive ? "present" : "absent")
                + ", instant=true, NEW_TASK");
        try {
            Intent intent = new Intent(ShadeBridgeService.this,
                    ActionCentreActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(ActionCentreActivity.EXTRA_INSTANT, true);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "starting Action Centre failed", t);
        }
    }

    // ---- top-edge strip: the common path, before SystemUI sees the gesture ----

    /**
     * Adds a 20 dp full-width transparent window above the status bar. Because
     * TYPE_ACCESSIBILITY_OVERLAY stacks above TYPE_STATUS_BAR, the top-edge
     * swipe is delivered here instead of to SystemUI, so the stock shade never
     * opens for the common gesture.
     */
    private void addStripOverlay() {
        try {
            removeStripOverlay();
            stripWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (stripWindowManager == null) {
                return;
            }
            View view = new View(this);
            view.setBackgroundColor(Color.TRANSPARENT);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    dpToPx(STRIP_HEIGHT_DP),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSPARENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            params.setTitle("ztMetro Action Centre strip");
            view.setOnTouchListener(stripTouchListener);
            stripWindowManager.addView(view, params);
            stripView = view;
            stripParams = params;
            watchStripKeyguard();
            if (isKeyguardLocked()) {
                setStripTouchable(false);
            }
            stripActive = true;
            Log.i(TAG, "strip overlay added: " + STRIP_HEIGHT_DP
                    + " dp at top, TYPE_ACCESSIBILITY_OVERLAY");
        } catch (Throwable t) {
            Log.w(TAG, "strip overlay not available", t);
            stripView = null;
            stripParams = null;
            stripActive = false;
        }
    }

    private void removeStripOverlay() {
        try {
            if (stripKeyguardManager != null && stripKeyguardListener != null
                    && Build.VERSION.SDK_INT >= 33) {
                stripKeyguardManager.removeKeyguardLockedStateListener(stripKeyguardListener);
            }
        } catch (Throwable ignored) {
        }
        stripKeyguardManager = null;
        stripKeyguardListener = null;
        try {
            if (stripWindowManager != null && stripView != null) {
                stripWindowManager.removeViewImmediate(stripView);
            }
        } catch (Throwable ignored) {
        }
        stripView = null;
        stripParams = null;
        stripActive = false;
    }

    /** The strip must not eat the lock screen's top edge. */
    private void watchStripKeyguard() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            stripKeyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (stripKeyguardManager == null) {
                return;
            }
            stripKeyguardListener = new KeyguardManager.KeyguardLockedStateListener() {
                @Override
                public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
                    setStripTouchable(!isKeyguardLocked);
                }
            };
            stripKeyguardManager.addKeyguardLockedStateListener(
                    getMainExecutor(), stripKeyguardListener);
        } catch (Throwable t) {
            Log.w(TAG, "strip keyguard watcher failed", t);
        }
    }

    private void setStripTouchable(boolean touchable) {
        try {
            if (stripView == null || stripParams == null || stripWindowManager == null) {
                return;
            }
            int flags = stripParams.flags;
            int next = touchable
                    ? flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    : flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (next != flags) {
                stripParams.flags = next;
                stripWindowManager.updateViewLayout(stripView, stripParams);
                Log.i(TAG, "strip overlay " + (touchable
                        ? "touchable" : "not touchable (keyguard)"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "strip touchable update failed", t);
        }
    }

    private void openFromStrip(String gesture) {
        try {
            if (isKeyguardLocked()) {
                Log.d(TAG, "strip " + gesture + " ignored: keyguard locked");
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastStripOpenUptime < STRIP_OPEN_THROTTLE_MS) {
                Log.d(TAG, "strip " + gesture + " ignored: open already in flight");
                return;
            }
            if (isOwnPanelFocused()) {
                Log.d(TAG, "strip " + gesture + " ignored: Action Centre already focused");
                return;
            }
            lastStripOpenUptime = now;
            detectedAtUptime = now;
            detectedAtWall = System.currentTimeMillis();
            panelVisibleLogged = false;
            triggerLabel = "strip trigger";
            Log.i(TAG, "strip " + gesture + ": opening Action Centre (instant)");
            startPanel("strip " + gesture);
        } catch (Throwable t) {
            Log.w(TAG, "strip open failed", t);
        }
    }

    private final View.OnTouchListener stripTouchListener = new View.OnTouchListener() {
        private float downY;
        private boolean opened;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            try {
                if (event == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (isKeyguardLocked() || isOwnPanelFocused()) {
                            return false;
                        }
                        downY = event.getY();
                        opened = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!opened) {
                            float dy = event.getY() - downY;
                            if (dy > dpToPx(STRIP_DRAG_DP)) {
                                opened = true;
                                openFromStrip("drag "
                                        + Math.round(dy / getResources().getDisplayMetrics().density)
                                        + " dp");
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!opened) {
                            openFromStrip("tap");
                        }
                        opened = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        opened = false;
                        return true;
                    default:
                        return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "strip touch failed", t);
                return false;
            }
        }
    };

    private int dpToPx(int dp) {
        return Math.max(1, Math.round(dp * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onServiceConnected() {
        try {
            super.onServiceConnected();
            Log.i(TAG, "service connected; shade = fullscreen " + SYSTEMUI_PACKAGE
                    + " window (class name is not used)");
            addStripOverlay();
            if (lockOverlay != null) {
                lockOverlay.stop();
            }
            lockOverlay = new LockOverlayController(this);
            lockOverlay.start();
        } catch (Throwable t) {
            Log.w(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            // Lock overlay first: it needs window-state *and* content-changed
            // events, while the shade detector below only reads window-state.
            if (lockOverlay != null) {
                lockOverlay.onAccessibilityEvent(event);
            }
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
        long now = SystemClock.uptimeMillis();
        if (lastTriggerUptime != 0 && now - lastTriggerUptime < THROTTLE_MS) {
            Log.i(TAG, "skip: throttled (" + (now - lastTriggerUptime) + " ms < "
                    + THROTTLE_MS + " ms)");
            return;
        }
        Rect display = displayBounds();
        if (display == null) {
            Log.w(TAG, "skip: display bounds unavailable");
            return;
        }
        ShadeWindow shade = findFullscreenSystemUiWindow(display, true);
        if (shade == null) {
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
        collapseDisplay = new Rect(display);
        detectedAtUptime = now;
        detectedAtWall = System.currentTimeMillis();
        panelVisibleLogged = false;
        triggerLabel = "detection";
        Log.i(TAG, "shade detected: " + shade.describe(display)
                + " last=" + lastForegroundPackage + "; collapsing with GLOBAL_ACTION_BACK");
        boolean collapsed = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "GLOBAL_ACTION_BACK #1 returned " + collapsed);
        handler.removeCallbacks(collapsePoll);
        handler.postDelayed(collapsePoll, POLL_INTERVAL_MS);
    }

    /**
     * Scans the interactive windows for the expanded shade: a SystemUI window of
     * type SYSTEM or APPLICATION covering at least 85 % of the display. Small
     * SystemUI windows (status bar, volume dialog, status icons) never qualify.
     */
    private ShadeWindow findFullscreenSystemUiWindow(Rect display, boolean verbose) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                if (verbose) {
                    Log.d(TAG, "skip: no interactive windows reported");
                }
                return null;
            }
            if (verbose) {
                Log.d(TAG, "systemui window-state; inspecting " + windows.size()
                        + " window(s), display=" + display.width() + "x" + display.height());
            }
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
                if (verbose) {
                    Log.d(TAG, "systemui window type=" + type + " bounds="
                            + bounds.toShortString() + " " + Math.round(fraction * 100)
                            + "% of display title=" + asString(title));
                }
                if (fraction >= SHADE_MIN_SCREEN_FRACTION && area > bestArea) {
                    best = new ShadeWindow(type, bounds, fraction);
                    bestArea = area;
                }
            }
            if (best == null && verbose) {
                Log.d(TAG, "skip: no SystemUI window covers >= "
                        + Math.round(SHADE_MIN_SCREEN_FRACTION * 100) + "% of the display");
            }
            return best;
        } catch (Throwable t) {
            if (verbose) {
                Log.w(TAG, "window inspection failed", t);
            } else {
                Log.w(TAG, "window inspection failed during collapse poll", t);
            }
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

    /**
     * Called by ActionCentreActivity when its window first gains focus after a
     * bridge launch. Logs the detection -> panel-visible time for the record.
     */
    static void notifyPanelVisible() {
        try {
            long detected = detectedAtUptime;
            if (detected == 0 || panelVisibleLogged) {
                return;
            }
            panelVisibleLogged = true;
            long elapsed = SystemClock.uptimeMillis() - detected;
            long wall = System.currentTimeMillis() - detectedAtWall;
            Log.i(TAG, "panel visible " + elapsed + " ms after " + triggerLabel
                    + " (wall-clock delta " + wall + " ms)");
        } catch (Throwable t) {
            Log.w(TAG, "panel-visible log failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        try {
            handler.removeCallbacks(collapsePoll);
            if (lockOverlay != null) {
                lockOverlay.onInterrupt();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            handler.removeCallbacks(collapsePoll);
            removeStripOverlay();
            if (lockOverlay != null) {
                lockOverlay.stop();
                lockOverlay = null;
            }
            Log.i(TAG, "service unbound");
        } catch (Throwable ignored) {
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            handler.removeCallbacks(collapsePoll);
            removeStripOverlay();
            if (lockOverlay != null) {
                lockOverlay.stop();
                lockOverlay = null;
            }
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
