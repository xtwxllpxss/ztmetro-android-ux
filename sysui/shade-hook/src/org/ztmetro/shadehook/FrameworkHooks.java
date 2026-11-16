package org.ztmetro.shadehook;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server half of the module. Everything else in this APK runs in
 * com.android.systemui and is too late: by the time SystemUI sees the shade it is
 * already opening.
 *
 * <p>The theft happens in exactly one place in the LineageOS 23.2 tree
 * (frameworks_base 797e43244b96c6a0dff5c81b21c4d4be665b082e):
 *
 * <ol>
 *   <li>{@code com.android.server.wm.SystemGesturesPointerEventListener#detectSwipe(int, long,
 *       float, float)} (SystemGesturesPointerEventListener.java:381-391) returns SWIPE_FROM_TOP
 *       for a gesture that starts in the top 24 dp and travels more than 24 dp within 500 ms.
 *       The monitor sees every motion event on the global monitor channel, so it fires even when
 *       the touch belongs to the ztmetro accessibility strip.</li>
 *   <li>{@code DisplayPolicy#onSwipeFromTop()} (DisplayPolicy.java:492-495) turns that into
 *       {@code requestTransientBars(mTopGestureHost, ...)} (DisplayPolicy.java:2379).</li>
 *   <li>In the non-transient branch it calls {@code mStatusBar.transferTouch()}
 *       (DisplayPolicy.java:2435), i.e. {@code WindowState#transferTouch()}
 *       (WindowState.java:2634-2635) → {@code InputManagerInternal#transferTouch()}. That
 *       synthesizes a DOWN into the status bar window and cancels the gesture in the strip:
 *       SystemUI's shade starts opening while our Action Centre is already drawing.</li>
 * </ol>
 *
 * <p>Blocking that single call is the whole fix: {@code transferTouch()} has exactly one caller
 * (the status bar branch above), so returning {@code false} for the status bar window keeps the
 * gesture with the ztmetro strip and no other stock behaviour changes — the transient status bar
 * reveal, the bottom/left/right swipe handling and the lock screen path all stay untouched.
 *
 * <p>Gate: the guard acts only while the Metro strip can actually own the gesture, i.e. while
 * {@code org.ztmetro.actioncentre/.ShadeBridgeService} is an enabled accessibility service and
 * {@code Settings.System ztmetro_shade_hook} is not 0. Both are read at most once per
 * {@link #DECISION_CACHE_MS} on the WMS thread (SettingsProvider lives in this same process).
 * If the decision cannot be made, the guard does nothing and the stock shade keeps working.
 *
 * <p>This class is never allowed to throw into WindowManagerService: every callback body is
 * wrapped in {@code try/catch(Throwable)} and a failure just lets the original method run.
 */
final class FrameworkHooks {

    private static final String TAG = ShadeRouter.TAG;

    // Exact names in the pinned tree (see the class comment for file:line).
    static final String WINDOW_STATE = "com.android.server.wm.WindowState";
    static final String TRANSFER_TOUCH = "transferTouch";

    /** Settings keys shared with the SystemUI half and with the Action Centre app. */
    static final String ACCESSIBILITY_SERVICES_KEY = "enabled_accessibility_services";

    private static final String SERVICE_PACKAGE = ShadeRouter.ACTION_CENTRE_PACKAGE;
    private static final String SERVICE_CLASS_SIMPLE_NAME = "ShadeBridgeService";

    /** Re-read the gate at most this often; a gesture burst costs one read. */
    private static final long DECISION_CACHE_MS = 2000L;

    /** At most one suppression line per second, so logd cannot be flooded by swipes. */
    private static final long LOG_INTERVAL_MS = 1000L;

    /** WindowState.mAttrs — the only way to learn a WindowState's type from the hook. */
    private static volatile Field sAttrsField;

    private static volatile ContentResolver sResolver;

    private static volatile boolean sGuardOn;
    private static volatile boolean sDecided;
    private static volatile long sDecidedAt;

    private static volatile long sBlocked;
    private static volatile long sLastLogAt = -LOG_INTERVAL_MS;

    /** -1 = nothing logged yet, 0 = guard off, 1 = guard on. */
    private static volatile int sLoggedState = -1;

    private FrameworkHooks() {
    }

    static void install(ClassLoader classLoader) {
        try {
            SLog.info(TAG, "loading in android (system_server); stock behaviour is the fallback");
        } catch (Throwable ignored) {
        }

        try {
            Class<?> windowState = XposedHelpers.findClass(WINDOW_STATE, classLoader);
            sAttrsField = windowState.getDeclaredField("mAttrs");
            sAttrsField.setAccessible(true);

            Method transferTouch = windowState.getDeclaredMethod(TRANSFER_TOUCH);
            transferTouch.setAccessible(true);
            XposedBridge.hookMethod(transferTouch, TRANSFER_HOOK);
            SLog.info(TAG, "hooked " + WINDOW_STATE + "#" + TRANSFER_TOUCH
                    + "() — status-bar reroute is its only caller (DisplayPolicy.java:2435)");
        } catch (Throwable t) {
            // Missing/renamed target: the module stays inert in system_server and the
            // SystemUI safety net is all that is left. Stock shade keeps working.
            SLog.once(TAG, "install:framework", t);
        }
    }

    private static final XC_MethodHook TRANSFER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!isStatusBarWindow(param.thisObject)) {
                    noteOtherWindow(param.thisObject);
                    return;
                }
                if (!guardOn()) {
                    return;
                }
                // boolean transferTouch(): false == "not transferred". DisplayPolicy logs its
                // own "Could not transfer touch to the status bar" line, which corroborates
                // this decision from stock code.
                param.setResult(Boolean.FALSE);
                sBlocked++;
                long now = SystemClock.uptimeMillis();
                if (now - sLastLogAt >= LOG_INTERVAL_MS) {
                    sLastLogAt = now;
                    XposedBridge.log(TAG + ": BLOCKED status-bar transferTouch() #" + sBlocked
                            + " — gesture stays with the ztmetro strip; stock shade not opened");
                }
            } catch (Throwable t) {
                // A throwing before-hook would abort the original call; swallow it so the
                // stock transfer still happens.
                SLog.once(TAG, "hook:transferTouch", t);
            }
        }
    };

    // ------------------------------------------------------------------ policy

    private static boolean isStatusBarWindow(Object windowState) {
        try {
            Field attrs = sAttrsField;
            if (attrs == null || windowState == null) {
                return false;
            }
            Object value = attrs.get(windowState);
            if (!(value instanceof WindowManager.LayoutParams)) {
                return false;
            }
            return ((WindowManager.LayoutParams) value).type
                    == WindowManager.LayoutParams.TYPE_STATUS_BAR;
        } catch (Throwable t) {
            SLog.once(TAG, "status-bar-window", t);
            return false;
        }
    }

    /**
     * Diagnostic only: record which other window types call transferTouch(), so the first device
     * run proves the "only the status bar" claim on the real build. No behaviour change.
     */
    private static void noteOtherWindow(Object windowState) {
        try {
            Object value = sAttrsField == null ? null : sAttrsField.get(windowState);
            if (value instanceof WindowManager.LayoutParams) {
                int type = ((WindowManager.LayoutParams) value).type;
                if (type != WindowManager.LayoutParams.TYPE_STATUS_BAR) {
                    SLog.once(TAG, "transferTouch-other-type:" + type,
                            "transferTouch() called on window type " + type
                                    + " (left stock)");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * True only when the Metro strip is the intended owner of a top-edge gesture.
     *
     * <p>Reads are cached for {@link #DECISION_CACHE_MS}; a provider failure disables the guard
     * for that window (fail-safe: stock shade).
     */
    private static boolean guardOn() {
        try {
            long now = SystemClock.uptimeMillis();
            if (sDecided && now - sDecidedAt < DECISION_CACHE_MS) {
                return sGuardOn;
            }

            final ContentResolver resolver = resolver();
            if (resolver == null) {
                sDecided = true;
                sDecidedAt = now;
                sGuardOn = false;
                logDecision(false, "no system context (cannot read the gate)");
                return false;
            }

            final String services = Settings.Secure.getString(resolver, ACCESSIBILITY_SERVICES_KEY);
            final boolean serviceEnabled = services != null
                    && services.contains(SERVICE_PACKAGE)
                    && services.contains(SERVICE_CLASS_SIMPLE_NAME);
            final int flag = Settings.System.getInt(resolver, ShadeRouter.SETTING_ENABLED, 1);

            sDecided = true;
            sDecidedAt = now;
            sGuardOn = serviceEnabled && flag != 0;
            logDecision(sGuardOn, "ShadeBridgeService "
                    + (serviceEnabled ? "enabled" : "not enabled")
                    + ", " + ShadeRouter.SETTING_ENABLED + "=" + flag);
            return sGuardOn;
        } catch (Throwable t) {
            SLog.once(TAG, "guard-on", t);
            sDecided = true;
            sDecidedAt = SystemClock.uptimeMillis();
            sGuardOn = false;
            return false;
        }
    }

    private static void logDecision(boolean on, String why) {
        try {
            int state = on ? 1 : 0;
            if (state == sLoggedState) {
                return;
            }
            sLoggedState = state;
            XposedBridge.log(TAG + ": transfer guard " + (on ? "ENABLED" : "disabled")
                    + " — " + why);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The system context of this process (system_server). ActivityThread is hidden API, so it is
     * reached by name; any failure just means "no gate", i.e. stock behaviour.
     */
    private static ContentResolver resolver() {
        ContentResolver cached = sResolver;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Context context = null;
            for (String name : new String[] {"getSystemContext", "getSystemUiContext"}) {
                try {
                    Method method = activityThread.getMethod(name);
                    method.setAccessible(true);
                    Object value = method.invoke(thread);
                    if (value instanceof Context) {
                        context = (Context) value;
                        break;
                    }
                } catch (Throwable ignored) {
                    // try the next accessor
                }
            }
            if (context == null) {
                return null;
            }
            ContentResolver resolver = context.getContentResolver();
            sResolver = resolver;
            return resolver;
        } catch (Throwable t) {
            SLog.once(TAG, "system-context", t);
            return null;
        }
    }
}
