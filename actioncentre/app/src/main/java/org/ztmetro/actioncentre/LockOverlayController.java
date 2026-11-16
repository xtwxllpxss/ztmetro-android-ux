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
import android.view.MotionEvent;
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
 * | LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED, {@code PixelFormat.TRANSLUCENT}, no
 * system-bar/cutout insets fitted. Touchable windows: a transparent bottom strip
 * (same type and layout flags plus NOT_TOUCH_MODAL) that consumes the swipe up
 * and starts the unlock proxy, and - when {@link #PIN_PAD_ENABLED} and the
 * device is secure - a second transparent window sized exactly to the
 * {@link LockPinPad} that forwards touches to the panel. The black surface
 * itself never receives touches, so the system's own unlock path is never taken
 * away. The surface is TRANSLUCENT, not OPAQUE: the reveal animation changes the
 * root view's alpha and scale, and an opaque buffer would drop the alpha channel
 * and turn both the 180 ms fade-in and the 160 ms fade/scale-out into an abrupt
 * window removal.
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
    private static final String SETTINGS = "com.android.settings";

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
    /** If the credential window event is missed, reveal it anyway after this. */
    private static final long CREDENTIAL_REVEAL_TIMEOUT_MS = 2500L;
    /** Ignore foreign-app events for this long after the surface appears. */
    private static final long FOREIGN_GRACE_MS = 1500L;
    private static final float FULLSCREEN_FRACTION = 0.7f;
    /** A swipe of this many dp on the bottom strip reveals the keyguard. */
    private static final int SWIPE_UP_DP = 24;
    private static final int STRIP_MIN_DP = 200;
    private static final int STRIP_MAX_DP = 280;

    /**
     * Owner switch for the reference custom PIN pad.
     *
     * <p>{@code true}: on a <b>secure</b> device the clock slots a
     * {@link LockPinPad}; the pad's enter starts {@link UnlockProxyActivity},
     * which launches the system credential screen. On a non-secure device the
     * clock stays and the swipe strip dismisses directly.
     *
     * <p>{@code false}: the pre-pad lock screen (clock, counts, hint) with the
     * unchanged swipe-up unlock path. This is the rollback switch; it needs no
     * other change and no reinstall of anything else.
     */
    static final boolean PIN_PAD_ENABLED = true;

    /** Audit-loud tag for the pad's window/touch lifecycle, separate from LockOverlay. */
    private static final String AUTH_TAG = "LockAuth";

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final KeyguardManager keyguardManager;
    private final PowerManager powerManager;
    private final AudioManager audioManager;
    private final WindowManager windowManager;
    private final LockOverlayView content;
    private final View stripView;
    /** Transparent view in the pad's own touchable window; forwards to the pad. */
    private final View authTouchView;

    /** The installed custom auth panel, or null while the clock is shown. */
    private LockAuthPanel authPanel;
    private WindowManager.LayoutParams authTouchParams;
    private boolean authTouchAdded;
    /** One failing pad per wake is enough; the clock + swipe path stay. */
    private boolean authTouchFailed;
    private final Rect authBounds = new Rect();

    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams stripParams;
    private boolean attached;
    /** True while the 120 ms reveal fade still owns the window. */
    private boolean hiding;
    /** ztmetro lock overlay: fade in on screen-on, fade/scale out on reveal. */
    private static final int FADE_IN_MS = 180;
    /**
     * Unlock reveal: alpha 1 -> 0 and scale 1 -> 0.96 over 160 ms, then the
     * window is removed. This build previously faded over 120 ms (and the root
     * view was never scaled); 160 ms with the 0.96 scale is the final value.
     */
    private static final int FADE_OUT_MS = 160;
    /** Root-view end scale of the unlock reveal (WP "step back" feel). */
    private static final float FADE_OUT_SCALE = 0.96f;
    private boolean started;
    private boolean receiverRegistered;
    /** A swipe has started the proxy/credential flow; do not start a second. */
    private boolean credentialFlow;
    private static volatile LockOverlayController instance;
    private boolean addFailed;
    private boolean userRevealed;
    private boolean notificationsListening;
    private int unlockedChecks;
    private long lastEventAt;
    private long lastWindowScanAt;
    private long overlayShownUptime;
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
        this.authTouchView = new AuthTouchLayer(service);
        instance = this;
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
        handler.removeCallbacks(credentialTimeoutRunnable);
        hide("service stopped");
        if (instance == this) {
            instance = null;
        }
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
                    UnlockTimeline.mark("user_present");
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
        handler.removeCallbacks(credentialTimeoutRunnable);
        UnlockTimeline.mark("screen_off");
        UnlockTimeline.reset();
        credentialFlow = false;
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
        // rather than on every event. The same applies to the pad's touch window.
        addFailed = false;
        authTouchFailed = false;
        // The surface becomes visible now, even if it was pre-added while the
        // display was off: the foreign-window grace is measured from wake, not
        // from the pre-add.
        overlayShownUptime = SystemClock.uptimeMillis();
        content.refreshClock();
        boolean locked = isKeyguardLocked();
        Log.i(TAG, "screen on: keyguard locked=" + locked + " attached=" + attached);
        if (locked && !userRevealed) {
            if (attached) {
                configureAuthPanel();
                content.refreshNotifications();
                scheduleAuthTouchSync();
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

            boolean credentialWindow = isCredentialWindow(packageName, className)
                    || (credentialFlow && SETTINGS.equals(packageName));
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && credentialWindow) {
                // The system credential screen is now on top of the keyguard but
                // under our layer-31 surface: remove us so the PIN is visible.
                UnlockTimeline.mark("credential_shown pkg=" + packageName
                        + " cls=" + className);
                userRevealed = true;
                hide("credential on top: " + packageName + "/" + className);
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && imePackages.contains(":" + packageName + ":")) {
                UnlockTimeline.mark("ime_shown: " + packageName);
                userRevealed = true;
                hide("IME window-state: " + packageName);
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && isForeignApp(packageName)) {
                // A window-state event does not mean the app is in front of the
                // keyguard: Settings (and any last app) keeps its window behind
                // it across screen-off/on. Decide by the accessibility window
                // order/layer and only take the overlay away for a window that
                // is actually above the keyguard.
                ForeignWindowDecision decision = foreignWindowDecision(event);
                Log.i(TAG, "foreign window decision: pkg=" + packageName
                        + " cls=" + className + " aboveKeyguard=" + decision.above
                        + " " + decision.detail);
                UnlockTimeline.mark("foreign_window pkg=" + packageName
                        + " above=" + decision.above);
                if (decision.above) {
                    userRevealed = true;
                    hide("app above keyguard: " + packageName + "/" + className);
                }
                return;
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && (isSecureInputClass(className) || isSystemDialogClass(className))) {
                UnlockTimeline.mark("system_surface_shown: " + className);
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

    /** Result of the window-stack check for a foreign window-state event. */
    private static final class ForeignWindowDecision {
        final boolean above;
        final String detail;

        ForeignWindowDecision(boolean above, String detail) {
            this.above = above;
            this.detail = detail;
        }
    }

    /**
     * Is the event's window above the keyguard, or only behind it?
     *
     * <p>{@code getWindows()} is ordered by window layer, topmost first. The
     * keyguard is the full-screen SystemUI window. If it is in the list and the
     * event window comes later, the app is behind the keyguard (the ordinary
     * Settings case after screen-off/on) and must not remove the overlay.
     *
     * <p>If the keyguard window is not listed (a showWhenLocked call/camera can
     * hide it), treat a full-screen *focused* foreign window as above, but not
     * during the first {@link #FOREIGN_GRACE_MS} after our surface appears: that
     * is the screen-on window-state race that hid the overlay over Settings.
     *
     * <p>The returned detail is logged at info level and contains the window
     * layer (API 30+), type, stack index and focus state actually used.
     */
    private ForeignWindowDecision foreignWindowDecision(AccessibilityEvent event) {
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null || windows.isEmpty()) {
                return new ForeignWindowDecision(false, "no accessible windows");
            }
            int eventId = event.getWindowId();
            int eventIndex = -1;
            int keyguardIndex = -1;
            int eventLayer = -1;
            int keyguardLayer = -1;
            int eventType = -1;
            boolean focused = false;
            boolean active = false;
            boolean fullscreen = false;
            boolean keyguardFocused = false;
            Rect display = displayBounds();
            long displayArea = (long) display.width() * display.height();
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window == null) {
                    continue;
                }
                int id;
                try {
                    id = window.getId();
                } catch (Throwable t) {
                    continue;
                }
                if (id == eventId) {
                    eventIndex = i;
                    eventType = window.getType();
                    eventLayer = windowLayer(window);
                    focused = window.isFocused();
                    active = window.isActive();
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    long area = (long) bounds.width() * bounds.height();
                    fullscreen = displayArea > 0
                            && area >= (long) (displayArea * FULLSCREEN_FRACTION);
                }
                if (keyguardIndex < 0 && isKeyguardWindow(window, display, displayArea)) {
                    keyguardIndex = i;
                    keyguardLayer = windowLayer(window);
                    keyguardFocused = window.isFocused();
                }
            }
            String base = "eventIndex=" + eventIndex + " keyguardIndex=" + keyguardIndex
                    + " eventLayer=" + eventLayer + " keyguardLayer=" + keyguardLayer
                    + " eventType=" + eventType + " focused=" + focused
                    + " active=" + active + " fullscreen=" + fullscreen
                    + " keyguardFocused=" + keyguardFocused;
            if (eventIndex < 0) {
                return new ForeignWindowDecision(false, base
                        + " (event window not in the accessible list)");
            }
            if (keyguardIndex >= 0) {
                // Ordered by layer, topmost first: a later index is behind.
                boolean above = eventIndex < keyguardIndex;
                long sinceShown = SystemClock.uptimeMillis() - overlayShownUptime;
                // A showWhenLocked window can occlude the keyguard while the
                // keyguard window is still listed above it. If our foreign
                // window is the focused one and the keyguard is not, it is the
                // active surface - but never during the screen-on race.
                if (!above && focused && !keyguardFocused
                        && sinceShown >= FOREIGN_GRACE_MS) {
                    above = true;
                }
                return new ForeignWindowDecision(above, base
                        + " sinceShown=" + sinceShown);
            }
            long sinceShown = SystemClock.uptimeMillis() - overlayShownUptime;
            boolean above = focused && fullscreen && sinceShown >= FOREIGN_GRACE_MS;
            return new ForeignWindowDecision(above, base + " sinceShown=" + sinceShown
                    + " (keyguard window absent)");
        } catch (Throwable t) {
            return new ForeignWindowDecision(false, "window inspection failed: "
                    + t.getClass().getSimpleName());
        }
    }

    private boolean isKeyguardWindow(AccessibilityWindowInfo window, Rect display,
                                     long displayArea) {
        try {
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (displayArea <= 0 || area < (long) (displayArea * FULLSCREEN_FRACTION)) {
                return false;
            }
            return SYSTEMUI.equals(windowPackage(window));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Window layer for the log; {@code getLayer()} exists from API 30. */
    private static int windowLayer(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT < 30) {
            return -1;
        }
        try {
            return window.getLayer();
        } catch (Throwable ignored) {
            return -1;
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

    /** The system confirm-credential activity (Settings) or a keyguard input. */
    private static boolean isCredentialWindow(String packageName, String className) {
        String name = className.toLowerCase(Locale.US);
        if (name.contains("confirmdevicecredential")) {
            return true;
        }
        return name.contains("credential")
                && (SETTINGS.equals(packageName) || SYSTEMUI.equals(packageName)
                    || ANDROID.equals(packageName));
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
        // A hide() fade-out may still hold the window; finish it now so the
        // next show adds a fresh surface instead of failing on an old parent.
        if (hiding) {
            content.animate().cancel();
            content.setAlpha(1f);
            hiding = false;
            removeViewsQuietly();
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
            // Fade in 180 ms on screen-on (mockup lock.html; Animator scale via
            // Motion.ms). The window is opaque, so start fully transparent.
            int fadeIn = Motion.ms(service, FADE_IN_MS);
            content.setAlpha(fadeIn > 0 ? 0f : 1f);
            // A previous reveal may have left the root view scaled; every show
            // starts from the untransformed surface.
            content.setScaleX(1f);
            content.setScaleY(1f);
            windowManager.addView(content, overlayParams);
            if (fadeIn > 0) {
                content.animate().alpha(1f).setDuration(fadeIn)
                        .setInterpolator(Motion.WP_LOG).start();
            }

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
            configureAuthPanel();
            addNotificationsListener();
            startPolling();
            scheduleMinuteTick();
            scheduleAuthTouchSync();
            Log.i(TAG, "shown: type=TYPE_ACCESSIBILITY_OVERLAY("
                    + WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY + ")"
                    + " flags=" + flags(overlayParams)
                    + " display=" + displayBounds()
                    + " stripHeight=" + stripParams.height
                    + " preDraw=" + preDraw
                    + " " + snapshot.describe());
            overlayShownUptime = SystemClock.uptimeMillis();
            UnlockTimeline.mark("overlay_shown");
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
                // TRANSLUCENT, not OPAQUE: the root view alpha/scale animation
                // needs an alpha-capable buffer (see the class javadoc).
                PixelFormat.TRANSLUCENT);
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

    // ------------------------------------------------------------ auth panel

    /**
     * Put the reference PIN pad in the clock slot when the owner switch is on
     * and the device is secure. A non-secure keyguard keeps the clock screen and
     * the swipe-up strip dismisses it directly; no pad is added and no window is
     * created. Never throws into {@link #show()}: a failure restores the clock.
     */
    private void configureAuthPanel() {
        try {
            if (!PIN_PAD_ENABLED || authTouchFailed || !isDeviceSecure()) {
                authPanel = null;
                content.setAuthPanel(null);
                return;
            }
            if (authPanel == null) {
                LockPinPad pad = new LockPinPad(service);
                pad.setOnSubmitListener(new LockAuthPanel.OnSubmitListener() {
                    @Override
                    public void onSubmit(String pin) {
                        onPinSubmitted(pin);
                    }
                });
                pad.getView().addOnLayoutChangeListener(authLayoutListener);
                authPanel = pad;
            }
            content.setAuthPanel(authPanel.getView());
            authPanel.reset();
            Log.i(TAG, "pin pad: secure keyguard, clock slot replaced; pad touches go"
                    + " through a separate touchable window, main surface stays"
                    + " NOT_TOUCHABLE");
        } catch (Throwable t) {
            authTouchFailed = true;
            authPanel = null;
            removeAuthTouchWindow();
            try {
                content.setAuthPanel(null);
            } catch (Throwable ignored) {
            }
            Log.w(TAG, "pin pad disabled; clock screen and swipe unlock stay intact ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")", t);
        }
    }

    private boolean isDeviceSecure() {
        try {
            return keyguardManager != null && keyguardManager.isDeviceSecure();
        } catch (Throwable ignored) {
            // Unknown security state: do not put a credential UI on the lock
            // screen; the swipe path is the safe default.
            return false;
        }
    }

    /**
     * The pad's enter. The pad is UI only: the digits are never compared,
     * stored or put in an Intent here. On a secure device the unlock proxy
     * launches {@code createConfirmDeviceCredentialIntent(null, null)} and the
     * system verifies whatever the user types there. The overlay stays up until
     * that credential window is actually on top.
     */
    private void onPinSubmitted(String pin) {
        try {
            if (!attached) {
                return;
            }
            if (credentialFlow) {
                Log.i(TAG, "pin enter ignored: unlock already in flight");
                return;
            }
            int digits = pin == null ? 0 : pin.length();
            Log.i(TAG, "pin enter: " + digits + " digit(s); not verified in this app");
            startUnlockProxy("pin_enter");
            UnlockTimeline.mark("pin_submitted digits=" + digits
                    + " (pad is UI only; system credential screen verifies)");
        } catch (Throwable t) {
            Log.w(TAG, "pin submit handling failed", t);
        }
    }

    private final View.OnLayoutChangeListener authLayoutListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    scheduleAuthTouchSync();
                }
            };

    /** The first layout may still be pending; retry once shortly after show. */
    private void scheduleAuthTouchSync() {
        handler.removeCallbacks(authLayoutRunnable);
        handler.post(authLayoutRunnable);
        handler.postDelayed(authLayoutRunnable, 300L);
    }

    private final Runnable authLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            syncAuthTouchWindow();
        }
    };

    /**
     * Add or move the second, touchable window so it covers exactly the pad's
     * on-screen bounds. The main surface keeps its NOT_TOUCHABLE flag; this
     * window is the only place the lock overlay accepts touches besides the
     * bottom swipe strip. A window's touchable region is its bounds, and because
     * this one is added after the strip it receives the keys that overlap it;
     * swipes that start below the pad still reach the strip.
     */
    private void syncAuthTouchWindow() {
        if (!attached || authTouchFailed || !PIN_PAD_ENABLED) {
            return;
        }
        try {
            View panel = content.getAuthPanel();
            if (panel == null || !panel.isAttachedToWindow()) {
                return;
            }
            int width = panel.getWidth();
            int height = panel.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int[] location = new int[2];
            panel.getLocationOnScreen(location);
            if (location[0] < 0 || location[1] < 0) {
                return;
            }
            authBounds.set(location[0], location[1],
                    location[0] + width, location[1] + height);
            if (!authTouchAdded) {
                authTouchParams = authTouchParams();
                windowManager.addView(authTouchView, authTouchParams);
                authTouchAdded = true;
                Log.i(AUTH_TAG, "pin pad touch window added: type="
                        + WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        + " bounds=" + authBounds.toShortString()
                        + " flags=NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN"
                        + " (main surface still NOT_TOUCHABLE; swipe strip still"
                        + " underneath)");
                UnlockTimeline.mark("pin_pad_touch_window " + authBounds.toShortString());
                return;
            }
            if (authTouchParams == null) {
                return;
            }
            if (authTouchParams.x == authBounds.left
                    && authTouchParams.y == authBounds.top
                    && authTouchParams.width == authBounds.width()
                    && authTouchParams.height == authBounds.height()) {
                return;
            }
            authTouchParams.x = authBounds.left;
            authTouchParams.y = authBounds.top;
            authTouchParams.width = authBounds.width();
            authTouchParams.height = authBounds.height();
            windowManager.updateViewLayout(authTouchView, authTouchParams);
            Log.i(AUTH_TAG, "pin pad touch window moved: " + authBounds.toShortString());
        } catch (Throwable t) {
            failAuthPanel("touch window: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage(), t);
        }
    }

    private WindowManager.LayoutParams authTouchParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                authBounds.width(),
                authBounds.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = authBounds.left;
        params.y = authBounds.top;
        params.setTitle("ztMetro lock pin pad");
        applyFullscreenGeometry(params);
        return params;
    }

    /**
     * Fail-safe: remove the whole pad (touch window and clock-slot view) and go
     * back to the clock screen. The swipe strip was never touched, so the
     * previous unlock path is still there.
     */
    private void failAuthPanel(String why, Throwable error) {
        authTouchFailed = true;
        handler.removeCallbacks(authLayoutRunnable);
        removeAuthTouchWindow();
        authPanel = null;
        try {
            content.setAuthPanel(null);
        } catch (Throwable ignored) {
        }
        Log.w(TAG, "pin pad disabled for this wake (" + why
                + "); clock screen and swipe unlock stay intact", error);
        UnlockTimeline.mark("pin_pad_fallback: " + why);
    }

    private void removeAuthTouchWindow() {
        if (authTouchAdded || authTouchView.getParent() != null) {
            removeViewQuietly(authTouchView, "pin pad");
        }
        authTouchAdded = false;
        authTouchParams = null;
    }

    private void hide(String reason) {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(minuteRunnable);
        handler.removeCallbacks(prepareRunnable);
        handler.removeCallbacks(credentialTimeoutRunnable);
        handler.removeCallbacks(authLayoutRunnable);
        credentialFlow = false;
        removeNotificationsListener();
        boolean hadWindow = attached || content.getParent() != null
                || stripView.getParent() != null || authTouchView.getParent() != null;
        if (hadWindow) {
            UnlockTimeline.mark("overlay_removed (" + reason + ")");
        }
        attached = false;
        if (!hadWindow) {
            removeViewsQuietly();
            return;
        }
        // Fade out 120 ms on reveal, then drop the window. The touch surfaces go
        // at once so a swipe or a key tap cannot land on a surface that is
        // already leaving.
        removeAuthTouchWindow();
        removeViewQuietly(stripView, "strip");
        int fadeOut = Motion.ms(service, FADE_OUT_MS);
        if (fadeOut <= 0 || content.getParent() == null) {
            // Animator scale 0 or a surface already gone: instant, as Android's
            // own "remove animations" setting demands.
            removeViewsQuietly();
            Log.i(TAG, "hidden: " + reason + " (instant: animator scale 0)");
            return;
        }
        hiding = true;
        content.animate().cancel();
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.animate().alpha(0f)
                .scaleX(FADE_OUT_SCALE)
                .scaleY(FADE_OUT_SCALE)
                .setDuration(fadeOut)
                .setInterpolator(Motion.WP_LOG)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        hiding = false;
                        removeViewsQuietly();
                    }
                })
                .start();
        Log.i(TAG, "hidden: " + reason + " (reveal: alpha 1.0->0.0, scale 1.0->"
                + FADE_OUT_SCALE + " over " + fadeOut + " ms, then window removed)");
    }

    private void removeViewsQuietly() {
        removeAuthTouchWindow();
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
                    if (credentialFlow) {
                        // The proxy is a showWhenLocked activity: it can occlude
                        // the keyguard without dismissing it. Do not treat that
                        // as an unlock; USER_PRESENT (or the 2.5 s net) ends it.
                        unlockedChecks = 0;
                    } else {
                        unlockedChecks++;
                        if (unlockedChecks >= UNLOCKED_CONFIRMATIONS) {
                            UnlockTimeline.mark("keyguard_dismissed_poll");
                            hide("keyguard dismissed");
                            return;
                        }
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
     * Keep this surface attached and hand the unlock to the transparent
     * {@link UnlockProxyActivity}. It calls requestDismissKeyguard for a
     * non-secure lock (our surface stays until USER_PRESENT) or opens the system
     * credential confirmation for a secure one (we hide only when that activity
     * reports itself on top). {@code trigger} is "swipe_up" or "pin_enter"; the
     * PIN itself is never put in the Intent - the credential screen asks the
     * system directly.
     */
    private void startUnlockProxy(String trigger) {
        if (!attached) {
            return;
        }
        if (credentialFlow) {
            Log.i(TAG, "unlock already in flight; " + trigger + " ignored");
            return;
        }
        credentialFlow = true;
        UnlockTimeline.start(trigger);
        UnlockTimeline.mark("proxy_launch_requested");
        try {
            Intent intent = new Intent(service, UnlockProxyActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            service.startActivity(intent);
            UnlockTimeline.mark("proxy_started");
        } catch (Throwable t) {
            UnlockTimeline.mark("proxy_start_failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            revealStock("proxy start failed: " + t.getClass().getSimpleName());
            return;
        }
        handler.removeCallbacks(credentialTimeoutRunnable);
        handler.postDelayed(credentialTimeoutRunnable, CREDENTIAL_REVEAL_TIMEOUT_MS);
    }

    /**
     * Safety net: if no credential window event arrives (unknown ROM class), do
     * not leave the user behind an inert black surface. Reveal the stock
     * keyguard after the timeout and log it.
     */
    private final Runnable credentialTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            UnlockTimeline.mark("credential_timeout");
            revealStock("no credential window within "
                    + CREDENTIAL_REVEAL_TIMEOUT_MS + " ms");
        }
    };

    private void revealStock(String reason) {
        UnlockTimeline.mark("fallback_reveal_stock: " + reason);
        userRevealed = true;
        hide("unlock fallback: " + reason);
    }

    /** Called by the proxy when it cannot dismiss/confirm; old fail-safe path. */
    static void revealStockKeyguard(String reason) {
        LockOverlayController controller = instance;
        if (controller == null) {
            Log.w(TAG, "fallback reveal requested without a controller: " + reason);
            return;
        }
        controller.revealStock(reason);
    }

    /**
     * Transparent surface of the pad's second window. The main lock surface is
     * NOT_TOUCHABLE; this view is the only receiver of pad touches and forwards
     * them to the {@link LockAuthPanel} view. Both windows share the same screen
     * origin for the pad rect, so the event coordinates need no translation and
     * the panel's own hit-testing/children work unchanged.
     */
    private final class AuthTouchLayer extends View {
        AuthTouchLayer(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) {
                return false;
            }
            try {
                LockAuthPanel panel = authPanel;
                View view = panel == null ? null : panel.getView();
                if (view == null) {
                    return false;
                }
                MotionEvent copy = MotionEvent.obtain(event);
                try {
                    return view.dispatchTouchEvent(copy);
                } finally {
                    copy.recycle();
                }
            } catch (Throwable t) {
                Log.w(TAG, "pin pad touch handling failed", t);
                return false;
            }
        }
    }

    /**
     * The bottom-strip swipe: the pre-pad unlock path, unchanged. It swallows
     * taps and starts the unlock proxy on a swipe up; with the PIN pad shown it
     * receives the touches outside the pad's touch window (below it, and beside
     * its narrower column), so swipe-up always remains a way into the system
     * credential screen.
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
                            // Keep our surface up during the unlock: the proxy
                            // dismisses a non-secure keyguard or opens the system
                            // credential screen, and the overlay leaves only when
                            // one of those is actually on top.
                            startUnlockProxy("swipe_up");
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
