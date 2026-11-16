package org.ztmetro.actioncentre;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * <p>Two collapse mechanisms, chosen by whether the top strip exists:
 *
 * <ul>
 *   <li><b>Strip present</b> (normal): start {@link ActionCentreActivity}
 *       immediately with the instant-open extra, so it draws under the shade;
 *       after {@value #BACK_AFTER_START_MS} ms send at most one
 *       {@link #GLOBAL_ACTION_BACK} if the shade window is still there and our
 *       panel has not taken focus. This removes the blank/launcher frame
 *       between the shade closing and the panel appearing.</li>
 *   <li><b>No strip</b> (overlay failed): the legacy path — one BACK, then poll
 *       for up to 250 ms, then start the panel.</li>
 * </ul>
 *
 * <p>Neither path ever sends a second BACK; a late BACK could dismiss our own
 * panel once it has focus.
 *
 * <p>Fail-safe: with the service disabled the app has no running code path that
 * touches the shade, so the stock shade behaves exactly as before. Every
 * callback is wrapped so a bad event can never crash the process.
 */
public class ShadeBridgeService extends AccessibilityService {
    static final String TAG = "ShadeBridge";
    /**
     * Separate tag for the top-edge strip only: every touch event and every
     * touchable-flag change is logged here, so one filter proves the strip is
     * receiving touches:
     *
     * <pre>adb logcat -s ShadeTouch ShadeBridge</pre>
     */
    static final String TOUCH_TAG = "ShadeTouch";

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
    /**
     * Transparent touch strip over the status bar (MetroWinLauncher's A1 trick).
     * 32 dp, not 24: the status bar itself is 24 dp, so the old window matched
     * it exactly with no margin for the cutout inset/rounding, and the top few
     * pixels of a top-edge swipe could still start in the SystemUI window. 32 dp
     * covers the status bar plus the first 8 dp of the app window.
     *
     * <p>This is the <b>rest</b> height and the only place a gesture may start:
     * the window at rest is exactly the top 32 dp, so a touch that starts in the
     * app content below the strip is never taken from the app.
     */
    private static final int STRIP_HEIGHT_DP = 32;
    /**
     * Height of the strip window while it owns a top-edge gesture, as a
     * fraction of the display height: 40 % = 512 px on the 720x1280 / 320 dpi
     * panel. A 32 dp window cannot contain a downward swipe, and the device
     * proof for the previous build was <code>ACTION_DOWN</code> + a few
     * <code>ACTION_MOVE</code> events followed by <code>ACTION_CANCEL</code>
     * when the pointer left the window, after which SystemUI saw the rest of
     * the gesture and the stock shade flashed. Growing the window for the
     * duration of the gesture keeps the whole swipe inside this window, so that
     * bounds-exit cancel cannot happen and SystemUI never receives it.
     *
     * <p>The height is derived from the display metrics at creation time, not
     * hardcoded: 512 px is the number for this panel only.
     */
    private static final float STRIP_GESTURE_SCREEN_FRACTION = 0.40f;
    /**
     * Safety net: if the end of the gesture (UP/CANCEL) is ever lost, the tall
     * window must not stay over the app. The window is only tall while a
     * top-edge gesture is in flight, normally well under a second.
     */
    private static final long STRIP_GESTURE_HOLD_MAX_MS = 1500L;
    /** A downward drag further than this in the strip opens the panel. */
    private static final int STRIP_DRAG_DP = 24;
    /** Ignore strip touches while a panel launch is already in flight. */
    private static final long STRIP_OPEN_THROTTLE_MS = 600L;
    /**
     * Safety net for the touchable flag: even if a ROM drops both the
     * keyguard listener and the USER_PRESENT broadcast, re-read the keyguard
     * state on accessibility events, at most this often. Logs only changes.
     */
    private static final long STRIP_KEYGUARD_SYNC_MS = 500L;

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
    /** USER_PRESENT / SCREEN_ON / SCREEN_OFF while the strip exists. */
    private BroadcastReceiver stripReceiver;
    private boolean stripReceiverRegistered;
    /**
     * Keyguard-locked state the strip acts on. Updated from explicit events
     * (service connect, USER_PRESENT, keyguard listener) and from a throttled
     * getter read; the device-observed bug is a keyguard getter that stays
     * true after unlock, so a USER_PRESENT always wins and the getter is never
     * allowed to re-lock the strip on its own.
     */
    private boolean stripKeyguardLocked;
    /** Applied window flag state: true when FLAG_NOT_TOUCHABLE is absent. */
    private boolean stripTouchable;
    private long lastStripKeyguardSyncUptime;
    private long lastStripOpenUptime;

    /** Strip height at rest: the top 32 dp gesture start region, in pixels. */
    private int stripRestHeightPx;
    /** Strip height while a gesture is in flight: 40 % of the display, in pixels. */
    private int stripGestureHeightPx;
    /** Display height the gesture height was derived from (for the log proof). */
    private int stripDisplayHeightPx;
    /** True while the window is at {@link #stripGestureHeightPx}. */
    private boolean stripTall;
    /** True while the strip's touch view is attached to the window manager. */
    private boolean stripViewAttached;
    /** True while a gesture that started in the strip is being swallowed. */
    private boolean stripGestureActive;
    /** True once the current gesture has opened the panel. */
    private boolean stripOpened;
    private long stripDownUptime;
    private int stripDownRawY;
    private int stripMoveCount;
    private int stripMaxDyPx;

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
     * Adds a 32 dp full-width transparent window above the status bar. Because
     * TYPE_ACCESSIBILITY_OVERLAY stacks above TYPE_STATUS_BAR, the top-edge
     * swipe is delivered here instead of to SystemUI, so the stock shade never
     * opens for the common gesture.
     *
     * <p>The window is created touchable and is <b>never</b> created with
     * FLAG_NOT_TOUCHABLE. That flag is owned by {@link #applyStripTouchable}:
     * added only while the keyguard is actually locked (the lock overlay owns
     * the screen then) and removed on every USER_PRESENT. The device-observed
     * defect was the opposite - the flag was set at service bind while locked
     * and the keyguard listener never brought it back, so the strip stayed
     * NOT_TOUCHABLE for the rest of the boot and every top-edge swipe went to
     * SystemUI first.
     */
    private void addStripOverlay() {
        try {
            removeStripOverlay();
            stripWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (stripWindowManager == null) {
                return;
            }
            View view = new View(this);
            // Alpha-0 content: the strip exists only as an input surface. The
            // window itself is not alpha-0, so its input region always exists.
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setAlpha(0f);
            stripRestHeightPx = dpToPx(STRIP_HEIGHT_DP);
            stripDisplayHeightPx = displayHeightPx();
            stripGestureHeightPx = Math.max(stripRestHeightPx,
                    Math.round(stripDisplayHeightPx * STRIP_GESTURE_SCREEN_FRACTION));
            // The window is created at the rest height (top 32 dp) and only
            // grows during a gesture. A permanent 40 %-tall touchable window was
            // rejected: it would eat every touch over the top 40 % of every app.
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    stripRestHeightPx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSPARENT);
            // No inherited NOT_TOUCHABLE: a fresh strip must be touchable, and
            // only applyStripTouchable() may add the flag afterwards.
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            params.alpha = 1f;
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            params.setTitle("ztMetro Action Centre strip");
            view.setOnTouchListener(stripTouchListener);
            stripWindowManager.addView(view, params);
            stripView = view;
            stripParams = params;
            stripTouchable = true;
            // Sources of the locked state, in order of trust: USER_PRESENT /
            // SCREEN_OFF / SCREEN_ON broadcast, the keyguard listener (API 33+),
            // then a throttled getter read on accessibility events.
            registerStripReceiver();
            watchStripKeyguard();
            lastStripKeyguardSyncUptime = SystemClock.uptimeMillis();
            stripKeyguardLocked = isKeyguardLocked();
            Log.i(TOUCH_TAG, "strip overlay added: height=" + STRIP_HEIGHT_DP
                    + "dp type=TYPE_ACCESSIBILITY_OVERLAY("
                    + WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY + ")"
                    + " alpha=0 content, flags=" + stripFlags(params)
                    + " touchable=" + stripTouchable
                    + " keyguardLocked=" + stripKeyguardLocked);
            applyStripTouchable(!stripKeyguardLocked,
                    "strip added; keyguard "
                            + (stripKeyguardLocked ? "locked" : "unlocked"));
            stripActive = true;
        } catch (Throwable t) {
            Log.w(TOUCH_TAG, "strip overlay not available", t);
            stripView = null;
            stripParams = null;
            stripTouchable = false;
            stripActive = false;
        }
    }

    private void removeStripOverlay() {
        unregisterStripReceiver();
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
        stripTouchable = false;
        stripActive = false;
    }

    /** USER_PRESENT / SCREEN_ON / SCREEN_OFF are the event half of the flag lifecycle. */
    private void registerStripReceiver() {
        if (stripReceiverRegistered) {
            return;
        }
        stripReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    String action = intent == null ? "" : String.valueOf(intent.getAction());
                    if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        // Definitive: the user is on the unlocked side of the
                        // keyguard. A getter stuck on true must not win here.
                        applyStripKeyguardState(false, "ACTION_USER_PRESENT");
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        // Screen off -> the lock overlay owns the top edge.
                        applyStripKeyguardState(true, "ACTION_SCREEN_OFF");
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        applyStripKeyguardState(isKeyguardLocked(), "ACTION_SCREEN_ON");
                    }
                } catch (Throwable t) {
                    Log.w(TOUCH_TAG, "strip receiver failed", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stripReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stripReceiver, filter);
            }
            stripReceiverRegistered = true;
        } catch (Throwable t) {
            stripReceiver = null;
            stripReceiverRegistered = false;
            Log.w(TOUCH_TAG, "strip receiver registration failed", t);
        }
    }

    private void unregisterStripReceiver() {
        if (!stripReceiverRegistered || stripReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(stripReceiver);
        } catch (Throwable ignored) {
        }
        stripReceiverRegistered = false;
        stripReceiver = null;
    }

    /** The strip must not eat the lock screen's top edge; listener (API 33+). */
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
                public void onKeyguardLockedStateChanged(boolean lockedNow) {
                    // Only a "locked" callback is cross-checked with the getter:
                    // a getter stuck on true is the defect being worked around,
                    // and it must never re-arm NOT_TOUCHABLE after an unlock.
                    boolean locked = lockedNow && isKeyguardLocked();
                    applyStripKeyguardState(locked,
                            "keyguard listener (lockedNow=" + lockedNow + ")");
                }
            };
            stripKeyguardManager.addKeyguardLockedStateListener(
                    getMainExecutor(), stripKeyguardListener);
        } catch (Throwable t) {
            Log.w(TOUCH_TAG, "strip keyguard watcher failed", t);
        }
    }

    /**
     * The single owner of the strip's NOT_TOUCHABLE flag. Logs every transition
     * with the reason that caused it (tag ShadeTouch), so logcat proves both the
     * locked state and the unlock that brought the strip back.
     */
    private void applyStripTouchable(boolean touchable, String reason) {
        try {
            if (stripView == null || stripParams == null || stripWindowManager == null) {
                return;
            }
            int flags = stripParams.flags;
            boolean currentlyTouchable =
                    (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
            if (currentlyTouchable == touchable) {
                return;
            }
            int next = touchable
                    ? flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    : flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            stripParams.flags = next;
            stripWindowManager.updateViewLayout(stripView, stripParams);
            stripTouchable = touchable;
            Log.i(TOUCH_TAG, "strip touchable " + (touchable ? "ON" : "OFF")
                    + " reason=" + reason
                    + " keyguardLocked=" + stripKeyguardLocked
                    + " flags=" + stripFlags(stripParams));
        } catch (Throwable t) {
            Log.w(TOUCH_TAG, "strip touchable update failed", t);
        }
    }

    private void applyStripKeyguardState(boolean locked, String reason) {
        stripKeyguardLocked = locked;
        applyStripTouchable(!locked, reason + "; keyguard "
                + (locked ? "locked" : "unlocked"));
    }

    /**
     * Throttled safety net for a ROM that drops both the keyguard listener and
     * the USER_PRESENT broadcast. The getter may only clear the locked state,
     * never set it: setting it from the getter is exactly the stale-flag bug
     * this code works around.
     */
    private void reconcileStripKeyguard() {
        if (stripView == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastStripKeyguardSyncUptime < STRIP_KEYGUARD_SYNC_MS) {
            return;
        }
        lastStripKeyguardSyncUptime = now;
        if (stripKeyguardLocked && !isKeyguardLocked()) {
            applyStripKeyguardState(false,
                    "keyguard getter reports unlocked (safety net)");
        }
    }

    private void openFromStrip(String gesture) {
        try {
            if (stripKeyguardLocked) {
                Log.i(TOUCH_TAG, "strip " + gesture + " ignored: keyguard locked");
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastStripOpenUptime < STRIP_OPEN_THROTTLE_MS) {
                Log.i(TOUCH_TAG, "strip " + gesture + " ignored: open already in flight");
                return;
            }
            if (isOwnPanelFocused()) {
                Log.i(TOUCH_TAG, "strip " + gesture
                        + " ignored: Action Centre already focused");
                return;
            }
            lastStripOpenUptime = now;
            detectedAtUptime = now;
            detectedAtWall = System.currentTimeMillis();
            panelVisibleLogged = false;
            triggerLabel = "strip trigger";
            Log.i(TOUCH_TAG, "strip open " + gesture
                    + " -> ActionCentreActivity (NEW_TASK, extra_instant=true);"
                    + " gesture swallowed by the strip");
            startPanel("strip " + gesture);
        } catch (Throwable t) {
            Log.w(TOUCH_TAG, "strip open failed", t);
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
                int action = event.getActionMasked();
                // Every delivered touch is logged with its coordinates: this is
                // the proof that the strip, and not SystemUI, owns the gesture.
                if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_MOVE
                        || action == MotionEvent.ACTION_UP) {
                    Log.i(TOUCH_TAG, actionName(action)
                            + " x=" + Math.round(event.getX())
                            + " y=" + Math.round(event.getY())
                            + " rawX=" + Math.round(event.getRawX())
                            + " rawY=" + Math.round(event.getRawY())
                            + " touchable=" + stripTouchable
                            + " keyguardLocked=" + stripKeyguardLocked);
                }
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (stripKeyguardLocked && !isKeyguardLocked()) {
                            // A real unlock that no event reported yet.
                            applyStripKeyguardState(false,
                                    "ACTION_DOWN: getter reports unlocked");
                        }
                        if (stripKeyguardLocked) {
                            Log.i(TOUCH_TAG, "strip DOWN ignored: keyguard locked");
                            return false;
                        }
                        if (isOwnPanelFocused()) {
                            Log.i(TOUCH_TAG,
                                    "strip DOWN ignored: Action Centre already focused");
                            return false;
                        }
                        downY = event.getY();
                        opened = false;
                        // The strip window is only 32 dp tall, so a long drag would leave
                        // its bounds and be cancelled by the system. Open on DOWN: a swipe
                        // and a tap on the top edge both mean "show the panel" in WP.
                        openFromStrip("down");
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!opened) {
                            float dy = event.getY() - downY;
                            if (dy > dpToPx(STRIP_DRAG_DP)) {
                                opened = true;
                                openFromStrip("drag "
                                        + Math.round(dy / getResources()
                                                .getDisplayMetrics().density)
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
                        Log.i(TOUCH_TAG, "CANCEL (opened=" + opened + ")");
                        opened = false;
                        return true;
                    default:
                        return true;
                }
            } catch (Throwable t) {
                Log.w(TOUCH_TAG, "strip touch failed", t);
                return false;
            }
        }
    };

    private static String actionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            default:
                return "ACTION_" + action;
        }
    }

    /** Readable flag set for the logcat proof. */
    private static String stripFlags(WindowManager.LayoutParams params) {
        int flags = params.flags;
        StringBuilder builder = new StringBuilder("0x");
        builder.append(Integer.toHexString(flags)).append(" (");
        builder.append("NOT_FOCUSABLE=")
                .append((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        builder.append(",NOT_TOUCH_MODAL=")
                .append((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0);
        builder.append(",LAYOUT_IN_SCREEN=")
                .append((flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);
        builder.append(",LAYOUT_NO_LIMITS=")
                .append((flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0);
        builder.append(",NOT_TOUCHABLE=")
                .append((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        return builder.append(')').toString();
    }

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
            // Safety net for the strip's touchable flag: even when both the
            // keyguard listener and USER_PRESENT are missing, a getter that
            // reports "unlocked" turns the strip back on (throttled; silent
            // unless the flag actually changes).
            reconcileStripKeyguard();
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
        panelStartUptime = 0;
        backAtUptime = 0;
        backSent = false;
        panelVisibleLogged = false;
        triggerLabel = "detection";
        handler.removeCallbacks(collapsePoll);
        handler.removeCallbacks(shadeWatch);
        handler.removeCallbacks(shadeGoneWatch);

        if (stripActive) {
            // Panel-first mechanism. The strip owns the normal gesture, so this
            // path is only reached when the shade opened some other way while
            // the strip exists. Start our panel immediately: it draws its first
            // frame under the shade, so the shade's close reveals the panel
            // instead of a blank/launcher frame. BACK is only a fallback and
            // only after the panel had BACK_AFTER_START_MS to draw.
            Log.i(TAG, "shade detected: " + shade.describe(display)
                    + " last=" + lastForegroundPackage
                    + "; strip present -> panel first, BACK only if still visible at "
                    + BACK_AFTER_START_MS + " ms");
            startPanel("shade detected; panel drawn under the shade before BACK");
            handler.postDelayed(shadeWatch, BACK_AFTER_START_MS);
        } else {
            // No strip (service/overlay unavailable): the legacy BACK-first
            // path. Exactly one BACK, then a short poll, then the panel.
            Log.i(TAG, "shade detected: " + shade.describe(display)
                    + " last=" + lastForegroundPackage
                    + "; no strip overlay -> collapsing with GLOBAL_ACTION_BACK");
            backSent = performGlobalAction(GLOBAL_ACTION_BACK);
            backAtUptime = SystemClock.uptimeMillis();
            Log.i(TAG, "GLOBAL_ACTION_BACK #1 at " + (backAtUptime - detectedAtUptime)
                    + " ms returned " + backSent + " (cap 1)");
            handler.postDelayed(collapsePoll, POLL_INTERVAL_MS);
        }
    }

    /**
     * 120 ms after the panel start: is the stock shade still there? If yes and
     * the panel has not taken focus, send the single allowed BACK. Never sends a
     * second one; after this the shade is only observed for the log.
     */
    private final Runnable shadeWatch = new Runnable() {
        @Override
        public void run() {
            try {
                if (collapseDisplay == null || backSent) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - detectedAtUptime;
                if (findFullscreenSystemUiWindow(collapseDisplay, false) == null) {
                    Log.i(TAG, "no BACK needed: stock shade gone at " + elapsed + " ms");
                    return;
                }
                if (panelVisibleLogged || isOwnPanelFocused()) {
                    // Our panel is already in front, so the shade is collapsing
                    // or not focusable; a BACK here would hit our own panel.
                    Log.i(TAG, "BACK skipped at " + elapsed
                            + " ms: Action Centre already focused (shade window still listed)");
                    handler.postDelayed(shadeGoneWatch, POLL_INTERVAL_MS);
                    return;
                }
                backSent = true;
                boolean collapsed = performGlobalAction(GLOBAL_ACTION_BACK);
                backAtUptime = SystemClock.uptimeMillis();
                Log.i(TAG, "shade still visible at " + elapsed
                        + " ms; GLOBAL_ACTION_BACK #1 at "
                        + (backAtUptime - detectedAtUptime) + " ms returned " + collapsed
                        + " (cap 1)");
                handler.postDelayed(shadeGoneWatch, POLL_INTERVAL_MS);
            } catch (Throwable t) {
                Log.w(TAG, "shade watch failed", t);
            }
        }
    };

    /** Logs when the stock shade window finally leaves; never sends a BACK. */
    private final Runnable shadeGoneWatch = new Runnable() {
        @Override
        public void run() {
            try {
                if (collapseDisplay == null) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - detectedAtUptime;
                if (findFullscreenSystemUiWindow(collapseDisplay, false) == null) {
                    if (backAtUptime != 0) {
                        Log.i(TAG, "stock shade gone at " + elapsed
                                + " ms after detection ("
                                + (elapsed - (backAtUptime - detectedAtUptime))
                                + " ms after BACK #1)");
                    } else {
                        Log.i(TAG, "stock shade gone at " + elapsed
                                + " ms after detection (no BACK sent)");
                    }
                    return;
                }
                if (elapsed < SHADE_SETTLE_MAX_MS) {
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                    return;
                }
                Log.i(TAG, "stock shade still listed at " + elapsed
                        + " ms; no second BACK (cap 1)");
            } catch (Throwable t) {
                Log.w(TAG, "shade-gone poll failed", t);
            }
        }
    };

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
            long now = SystemClock.uptimeMillis();
            long elapsed = now - detected;
            long wall = System.currentTimeMillis() - detectedAtWall;
            long fromStart = panelStartUptime == 0 ? -1 : now - panelStartUptime;
            String back = backAtUptime == 0 ? "none"
                    : ((backAtUptime - detected) + " ms");
            Log.i(TAG, "panel visible " + elapsed + " ms after " + triggerLabel
                    + " (start +" + fromStart + " ms, BACK " + back
                    + ", wall-clock delta " + wall + " ms)");
        } catch (Throwable t) {
            Log.w(TAG, "panel-visible log failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        try {
            handler.removeCallbacks(collapsePoll);
            handler.removeCallbacks(shadeWatch);
            handler.removeCallbacks(shadeGoneWatch);
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
            handler.removeCallbacks(shadeWatch);
            handler.removeCallbacks(shadeGoneWatch);
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
            handler.removeCallbacks(shadeWatch);
            handler.removeCallbacks(shadeGoneWatch);
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
