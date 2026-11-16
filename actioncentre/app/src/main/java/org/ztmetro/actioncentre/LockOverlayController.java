package org.ztmetro.actioncentre;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.List;
import java.util.Locale;

/**
 * Draws the ztMetro lock screen over the system keyguard and takes it away when
 * the keyguard is gone.
 *
 * <p><b>Window type.</b> {@link WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY}.
 * In AOSP android16 {@code WindowManagerPolicy.getWindowLayerFromTypeLw()} gives
 * it layer 31, while the keyguard is drawn inside SystemUI's
 * {@code TYPE_NOTIFICATION_SHADE} window (layer 17); status bar is 15, IME 13,
 * nav bar 24. It is therefore above the keyguard without
 * {@code SYSTEM_ALERT_WINDOW}, which the app does not hold (and
 * {@code TYPE_APPLICATION_OVERLAY} is layer 11, i.e. under the keyguard, so it
 * is not used). This is the same type MetroWinLauncher's LockOverlay.kt uses on
 * Android 13+.
 *
 * <p><b>Flags.</b> Main surface: NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN
 * | LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED, {@code PixelFormat.OPAQUE}, no
 * system-bar/cutout insets fitted. The only touchable window is a transparent
 * bottom strip (same type and layout flags plus NOT_TOUCH_MODAL) that consumes
 * the swipe up and removes the overlay, revealing the stock keyguard. The black
 * surface never receives touches, so the system's own unlock path is never
 * taken away.
 *
 * <p><b>When it is shown.</b> {@link KeyguardManager#isKeyguardLocked()} is the
 * authoritative signal, with {@link PowerManager#isInteractive()} and
 * {@code ACTION_SCREEN_ON/OFF/USER_PRESENT} plus window inspection for the IME
 * and for the log. It is pre-added 250 ms after SCREEN_OFF while the keyguard is
 * locked, so the first frame after wake is already ours. The clock is refreshed
 * on every SCREEN_ON and once a minute only while the screen is interactive;
 * there is no timer while the screen is off.
 *
 * <p><b>Fail-safe.</b> Every callback is wrapped. If addView throws
 * (SecurityException/BadTokenException on a ROM that forbids the overlay) the
 * failure is logged once per wake, no window is left behind, and the stock
 * keyguard stays visible. The overlay is removed immediately on USER_PRESENT,
 * on the keyguard no longer being locked, when an IME/bouncer appears, and when
 * any foreign app window comes to the front while locked (incoming call, alarm,
 * camera from the lock screen) - a lock overlay must never cover those.
 */
final class LockOverlayController {
    static final String TAG = "LockOverlay";

    private static final String SYSTEMUI = "com.android.systemui";
    private static final String ANDROID = "android";

    /** Give the keyguard a moment at SCREEN_OFF before pre-adding the surface. */
    private static final long PREPARE_DELAY_MS = 250L;
    /** Keyguard unlock confirmation poll while the overlay is up. */
    private static final long POLL_MS = 400L;
    /** Two consecutive "not locked" reads before removing, as the lock lags. */
    private static final int UNLOCKED_CONFIRMATIONS = 2;
    /** Accessibility events are noisy; this is enough to catch a bouncer. */
    private static final long EVENT_THROTTLE_MS = 150L;
    /** Window inspection while locked (IME detection, log evidence). */
    private static final long WINDOW_SCAN_MS = 1000L;
    private static final float FULLSCREEN_FRACTION = 0.7f;
    /** A swipe of this many dp on the bottom strip reveals the keyguard. */
    private static final int SWIPE_UP_DP = 24;
    private static final int STRIP_MIN_DP = 200;
    private static final int STRIP_MAX_DP = 280;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final KeyguardManager keyguardManager;
    private final PowerManager powerManager;
    private final AudioManager audioManager;
    private final WindowManager windowManager;
    private final LockOverlayView content;
    private final View stripView;

    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams stripParams;
    private boolean attached;
    private boolean started;
    private boolean receiverRegistered;
    private boolean addFailed;
    private boolean userRevealed;
    private boolean notificationsListening;
    private int unlockedChecks;
    private long lastEventAt;
    private long lastWindowScanAt;
    private String imePackages = "";

    private final NotificationRepository.Listener notificationsListener =
            new NotificationRepository.Listener() {
                @Override
                public void onNotificationsChanged() {
                    try {
                        if (attached) {
                            content.refreshNotifications();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "notification refresh failed", t);
                    }
                }
            };

    LockOverlayController(AccessibilityService service) {
        this.service = service;
        this.keyguardManager =
                (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        this.powerManager =
                (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        this.audioManager =
                (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        this.windowManager =
                (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.content = new LockOverlayView(service);
        this.stripView = new SwipeStrip(service);
    }

    // ------------------------------------------------------------------ life

    void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            registerScreenReceiver();
            refreshImePackages();
            boolean locked = isKeyguardLocked();
            Log.i(TAG, "started: keyguard locked=" + locked
                    + " interactive=" + isInteractive());
            if (locked && isInteractive() && !userRevealed) {
                show(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
        }
    }

    void stop() {
        started = false;
        try {
            if (receiverRegistered) {
                service.unregisterReceiver(screenReceiver);
                receiverRegistered = false;
            }
        } catch (Throwable ignored) {
        }
        hide("service stopped");
        try {
            content.release();
        } catch (Throwable ignored) {
        }
    }

    void onInterrupt() {
        try {
            hide("accessibility interrupt");
        } catch (Throwable t) {
            Log.w(TAG, "interrupt handling failed", t);
        }
    }

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent == null ? "" : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    userRevealed = true;
                    hide("user present");
                } else if (Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                        || Intent.ACTION_DATE_CHANGED.equals(action)) {
                    if (attached) {
                        content.refreshClock();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "screen receiver failed", t);
            }
        }
    };

    private void onScreenOff() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(minuteRunnable);
        handler.removeCallbacks(prepareRunnable);
        userRevealed = false;
        if (attached && !isKeyguardLocked()) {
            hide("screen off without keyguard");
            return;
        }
        // Pre-add while the display is off: the surface is already there when
        // the screen comes on, instead of the stock keyguard flashing first.
        handler.postDelayed(prepareRunnable, PREPARE_DELAY_MS);
    }

    private void onScreenOn() {
        // One add attempt per wake, so a ROM that refuses the window logs once
        // rather than on every event.
        addFailed = false;
        content.refreshClock();
        boolean locked = isKeyguardLocked();
        Log.i(TAG, "screen on: keyguard locked=" + locked + " attached=" + attached);
        if (locked && !userRevealed) {
            if (attached) {
                content.refreshNotifications();
                startPolling();
                scheduleMinuteTick();
            } else {
                show(false);
            }
        } else {
            hide("screen on without a locked keyguard");
        }
    }

    private final Runnable prepareRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!started || userRevealed) {
                    return;
                }
                if (isKeyguardLocked() && audioNormal()) {
                    show(true);
                } else if (attached) {
                    hide("keyguard not locked after screen off");
                }
            } catch (Throwable t) {
                Log.w(TAG, "screen-off prepare failed", t);
            }
        }
    };

    // --------------------------------------------------------------- events

    /** Called from the service for every window-state/content-changed event. */
    void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        try {
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            // Content-changed events are frequent; window-state changes are not
            // and must never be dropped: an incoming call has to hide us at once.
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (now - lastEventAt < EVENT_THROTTLE_MS) {
                    return;
                }
                lastEventAt = now;
            }

            String packageName = asString(event.getPackageName());
            String className = asString(event.getClassName());
            if (packageName.length() == 0
                    || packageName.equals(service.getPackageName())) {
                return;
            }

            if (!attached) {
                // The service was restarted while the phone was already locked.
                if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        && SYSTEMUI.equals(packageName) && !userRevealed
                        && isKeyguardLocked() && isInteractive()) {
                    show(false);
                }
                return;
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && imePackages.contains(":" + packageName + ":")) {
                userRevealed = true;
                hide("IME window-state: " + packageName);
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && isForeignApp(packageName)) {
                userRevealed = true;
                hide("app over keyguard: " + packageName + "/" + className);
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && (isSecureInputClass(className) || isSystemDialogClass(className))) {
                userRevealed = true;
                hide("system surface over keyguard: " + className);
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && SYSTEMUI.equals(packageName)
                    && now - lastWindowScanAt >= WINDOW_SCAN_MS) {
                lastWindowScanAt = now;
                if (inspectWindows(false).imeVisible) {
                    userRevealed = true;
                    hide("IME window visible over keyguard");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "event ignored after error", t);
        }
    }

    /**
     * A non-SystemUI window came to the front while locked: an incoming call,
     * an alarm, the camera or the emergency dialer. Our surface must not cover
     * it, and it stays away until the next screen off.
     */
    private boolean isForeignApp(String packageName) {
        if (packageName.length() == 0 || SYSTEMUI.equals(packageName)
                || ANDROID.equals(packageName)
                || packageName.equals(service.getPackageName())) {
            return false;
        }
        return !imePackages.contains(":" + packageName + ":");
    }

    /** The stock keyguard's bouncer and any text-entry surface. */
    private static boolean isSecureInputClass(String className) {
        String name = className.toLowerCase(Locale.US);
        return name.contains("bouncer")
                || name.contains("keyguardpin")
                || name.contains("keyguardpassword")
                || name.contains("keyguardpattern")
                || name.contains("keyguardsim")
                || name.contains("keyguardinput")
                || name.contains("pinview")
                || name.contains("passwordview")
                || name.contains("patternview");
    }

    /**
     * Power menu, volume panel, shutdown/emergency dialogs: they are system
     * windows below the overlay layer, so the overlay must yield or the user
     * would lose them while locked.
     */
    private static boolean isSystemDialogClass(String className) {
        String name = className.toLowerCase(Locale.US);
        return name.contains("globalactions")
                || name.contains("volumedialog")
                || name.contains("volumeui")
                || name.contains("powerdialog")
                || name.contains("shutdown")
                || name.contains("emergency");
    }

    private void refreshImePackages() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    service.getSystemService(Context.INPUT_METHOD_SERVICE);
            StringBuilder builder = new StringBuilder();
            List<android.view.inputmethod.InputMethodInfo> methods = imm == null
                    ? null : imm.getEnabledInputMethodList();
            if (methods != null) {
                for (android.view.inputmethod.InputMethodInfo info : methods) {
                    if (info != null && info.getPackageName() != null) {
                        builder.append(':').append(info.getPackageName()).append(':');
                    }
                }
            }
            imePackages = builder.toString();
        } catch (Throwable t) {
            imePackages = "";
        }
    }

    // ---------------------------------------------------------------- window

    private boolean show(boolean preDraw) {
        if (!started || attached || addFailed || userRevealed) {
            return false;
        }
        if (!isKeyguardLocked()) {
            return false;
        }
        if (!preDraw && !isInteractive()) {
            return false;
        }
        if (!audioNormal()) {
            Log.i(TAG, "skip: call audio mode " + (audioManager == null ? -1
                    : audioManager.getMode()) + ", stock keyguard stays");
            return false;
        }
        refreshImePackages();
        WindowSnapshot snapshot = inspectWindows(true);
        if (snapshot.imeVisible) {
            Log.i(TAG, "skip: IME visible, stock keyguard stays");
            return false;
        }

        try {
            overlayParams = overlayParams();
            windowManager.addView(content, overlayParams);

            stripParams = stripParams();
            try {
                windowManager.addView(stripView, stripParams);
            } catch (Throwable stripFailure) {
                // No touch path to the stock keyguard: do not leave an opaque
                // surface with only a hint and no working reveal gesture.
                removeViewsQuietly();
                throw stripFailure;
            }

            attached = true;
            unlockedChecks = 0;
            content.refreshClock();
            content.refreshNotifications();
            addNotificationsListener();
            startPolling();
            scheduleMinuteTick();
            Log.i(TAG, "shown: type=TYPE_ACCESSIBILITY_OVERLAY("
                    + WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY + ")"
                    + " flags=" + flags(overlayParams)
                    + " display=" + displayBounds()
                    + " stripHeight=" + stripParams.height
                    + " preDraw=" + preDraw
                    + " " + snapshot.describe());
            return true;
        } catch (Throwable t) {
            addFailed = true;
            removeViewsQuietly();
            Log.w(TAG, "addView failed; stock keyguard stays visible ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")", t);
            return false;
        }
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.alpha = 1f;
        params.setTitle("ztMetro lock overlay");
        applyFullscreenGeometry(params);
        return params;
    }

    private WindowManager.LayoutParams stripParams() {
        Rect display = displayBounds();
        int height = stripHeightPx(display);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.setTitle("ztMetro lock swipe strip");
        applyFullscreenGeometry(params);
        return params;
    }

    private void applyFullscreenGeometry(WindowManager.LayoutParams params) {
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            // Do not shrink the surface to the status/nav bar insets: the whole
            // point is to cover the stock keyguard edge to edge.
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private void hide(String reason) {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(minuteRunnable);
        handler.removeCallbacks(prepareRunnable);
        removeNotificationsListener();
        boolean hadWindow = attached || content.getParent() != null
                || stripView.getParent() != null;
        attached = false;
        removeViewsQuietly();
        if (hadWindow) {
            Log.i(TAG, "hidden: " + reason);
        }
    }

    private void removeViewsQuietly() {
        removeViewQuietly(stripView, "strip");
        removeViewQuietly(content, "surface");
        stripParams = null;
        overlayParams = null;
    }

    private void removeViewQuietly(View view, String name) {
        if (windowManager == null || view == null || view.getParent() == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "removeViewImmediate(" + name + ") failed; retrying", t);
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable t) {
            Log.w(TAG, "removeView(" + name + ") failed; the window is still attached", t);
        }
    }

    // ------------------------------------------------------------- keyguard

    private boolean isKeyguardLocked() {
        try {
            return keyguardManager != null && keyguardManager.isKeyguardLocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isInteractive() {
        try {
            return powerManager != null && powerManager.isInteractive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean audioNormal() {
        try {
            return audioManager == null
                    || audioManager.getMode() == AudioManager.MODE_NORMAL;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            try {
                if (!isInteractive()) {
                    // SCREEN_ON restarts the poll; no timer while the screen is off.
                    return;
                }
                if (!isKeyguardLocked()) {
                    unlockedChecks++;
                    if (unlockedChecks >= UNLOCKED_CONFIRMATIONS) {
                        hide("keyguard dismissed");
                        return;
                    }
                } else {
                    unlockedChecks = 0;
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastWindowScanAt >= WINDOW_SCAN_MS) {
                    lastWindowScanAt = now;
                    if (inspectWindows(false).imeVisible) {
                        userRevealed = true;
                        hide("IME window visible over keyguard");
                        return;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "keyguard poll failed", t);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void startPolling() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    private final Runnable minuteRunnable = new Runnable() {
        @Override
        public void run() {
            if (!attached || !isInteractive()) {
                return;
            }
            content.refreshClock();
            scheduleMinuteTick();
        }
    };

    private void scheduleMinuteTick() {
        handler.removeCallbacks(minuteRunnable);
        long now = System.currentTimeMillis();
        handler.postDelayed(minuteRunnable, 60000L - (now % 60000L) + 100L);
    }

    // -------------------------------------------------------------- windows

    private static final class WindowSnapshot {
        boolean imeVisible;
        boolean keyguardWindowVisible;
        int count;
        String top = "";

        String describe() {
            return "windows=" + count + " ime=" + imeVisible
                    + " keyguardWindow=" + keyguardWindowVisible + " top=" + top;
        }
    }

    /**
     * Window inspection is the second half of the keyguard decision: it names
     * the window in the log and it is the reliable IME guard. It is not used to
     * decide "locked" - on Lineage the keyguard can be absent from the
     * accessibility window list while {@code isKeyguardLocked()} is still true.
     */
    private WindowSnapshot inspectWindows(boolean verbose) {
        WindowSnapshot snapshot = new WindowSnapshot();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) {
                return snapshot;
            }
            snapshot.count = windows.size();
            Rect display = displayBounds();
            long displayArea = (long) display.width() * display.height();
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window == null) {
                    continue;
                }
                int type;
                Rect bounds = new Rect();
                try {
                    type = window.getType();
                    window.getBoundsInScreen(bounds);
                } catch (Throwable t) {
                    continue;
                }
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    snapshot.imeVisible = true;
                }
                long area = (long) bounds.width() * bounds.height();
                boolean fullscreen = displayArea > 0
                        && area >= (long) (displayArea * FULLSCREEN_FRACTION);
                String packageName = "";
                if (verbose) {
                    packageName = windowPackage(window);
                    if (fullscreen && SYSTEMUI.equals(packageName)) {
                        snapshot.keyguardWindowVisible = true;
                    }
                    if (i == 0
                            || (snapshot.top.length() == 0
                                && !service.getPackageName().equals(packageName))) {
                        snapshot.top = "type=" + type + " pkg=" + packageName
                                + " bounds=" + bounds.toShortString()
                                + " focused=" + window.isFocused();
                    }
                    Log.d(TAG, "window[" + i + "] type=" + type + " pkg=" + packageName
                            + " bounds=" + bounds.toShortString()
                            + " focused=" + window.isFocused());
                }
            }
        } catch (Throwable t) {
            if (verbose) {
                Log.w(TAG, "window inspection failed", t);
            }
        }
        return snapshot;
    }

    private String windowPackage(AccessibilityWindowInfo window) {
        android.view.accessibility.AccessibilityNodeInfo root = null;
        try {
            root = window.getRoot();
            if (root == null) {
                return "";
            }
            CharSequence packageName = root.getPackageName();
            return packageName == null ? "" : packageName.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Rect displayBounds() {
        try {
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return new Rect(bounds);
                    }
                }
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "display bounds failed", t);
        }
        return new Rect(0, 0, 720, 1280);
    }

    private int stripHeightPx(Rect display) {
        int min = dp(STRIP_MIN_DP);
        int max = dp(STRIP_MAX_DP);
        int third = display.height() / 3;
        return Math.max(min, Math.min(max, third));
    }

    // ------------------------------------------------------------- helpers

    private void addNotificationsListener() {
        if (!notificationsListening) {
            NotificationRepository.addListener(notificationsListener);
            notificationsListening = true;
        }
    }

    private void removeNotificationsListener() {
        if (notificationsListening) {
            NotificationRepository.removeListener(notificationsListener);
            notificationsListening = false;
        }
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value
                * service.getResources().getDisplayMetrics().density));
    }

    private static String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String flags(WindowManager.LayoutParams params) {
        if (params == null) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        appendFlag(builder, params.flags,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, "NOT_FOCUSABLE");
        appendFlag(builder, params.flags,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, "NOT_TOUCHABLE");
        appendFlag(builder, params.flags,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, "LAYOUT_IN_SCREEN");
        appendFlag(builder, params.flags,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, "LAYOUT_NO_LIMITS");
        appendFlag(builder, params.flags,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, "HARDWARE_ACCELERATED");
        return builder.toString();
    }

    private static void appendFlag(StringBuilder builder, int flags, int flag, String name) {
        if ((flags & flag) != 0) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(name);
        }
    }

    /**
     * The only touchable part of the overlay. It swallows taps (nothing else is
     * interactive) and removes the overlay on a swipe up, revealing the stock
     * keyguard. It never unlocks anything itself.
     */
    private final class SwipeStrip extends View {
        private float downY;
        private float downX;

        SwipeStrip(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event == null) {
                return false;
            }
            try {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = event.getY();
                        downX = event.getX();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dy = downY - event.getY();
                        float dx = Math.abs(event.getX() - downX);
                        if (dy >= dp(SWIPE_UP_DP) && dx < dy) {
                            userRevealed = true;
                            // Remove after this touch dispatch returns: never
                            // detach the window from inside its own onTouchEvent.
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    hide("swipe up: stock keyguard revealed");
                                }
                            });
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "swipe handling failed", t);
                return false;
            }
        }
    }
}
