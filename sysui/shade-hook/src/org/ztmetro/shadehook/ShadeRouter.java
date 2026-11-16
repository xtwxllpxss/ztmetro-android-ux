package org.ztmetro.shadehook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI half of the module — the <b>safety net</b>. The primary defence is
 * {@link FrameworkHooks} in system_server, which stops the top-edge gesture from being
 * transferred to the status bar window in the first place (see that class for the exact
 * DisplayPolicy path). This class only runs when a stock shade opened anyway: it closes the
 * shade and starts the Metro Action Centre, at the cost of the visible shade flash that the
 * framework guard exists to remove.
 *
 * <p>Redirect policy: when the stock shade starts opening on the unlocked shade,
 * close it and start the Metro Action Centre.
 *
 * <p>Guards, in order (any failure or exclusion leaves the stock shade alone):
 *
 * <ol>
 *   <li><b>Debounce</b> — one redirect per {@link #DEBOUNCE_MS}.  The panel state
 *       machine can re-enter OPENING while our own collapse runs; a repeat inside
 *       the window is ignored, i.e. the stock shade is allowed to open.</li>
 *   <li><b>Unlocked shade only</b> — the keyguard is never replaced.  The bar state
 *       must be StatusBarState.SHADE (0) and, when readable, the keyguard must not be
 *       showing and no bouncer may be up (statusbar/StatusBarState.java:28-47).</li>
 *   <li><b>Stock-shade edge</b> — a swipe that starts in the right
 *       {@link #STOCK_EDGE_FRACTION} of the status bar is a deliberate request for
 *       the real shade (quick settings).  This is the fallback path; no config is
 *       needed to use it.</li>
 *   <li><b>Config flag</b> — {@code Settings.System ztmetro_shade_hook}; 0 turns the
 *       redirect off entirely (stock shade everywhere).  Universal escape hatch.</li>
 *   <li><b>Action Centre present</b> — if org.ztmetro.actioncentre is not installed
 *       the hook does nothing.</li>
 * </ol>
 *
 * <p>The collapse and the activity start are posted to the main looper so they run
 * <em>after</em> NotificationPanelViewController#onPanelStateChanged has returned and
 * updated its own mCurrentPanelState bookkeeping.  Collapsing synchronously inside
 * the state callback would make the inner CLOSED transition overwrite the outer
 * OPENING bookkeeping.
 *
 * <p>Safety contract: every entry point catches {@link Throwable}; the stock shade is
 * never modified in place, only closed through public ShadeController methods.
 */
final class ShadeRouter {

    static final String TAG = "ztmetro-shadehook";

    static final String ACTION_CENTRE_PACKAGE = "org.ztmetro.actioncentre";
    static final String ACTION_CENTRE_ACTIVITY =
            "org.ztmetro.actioncentre.ActionCentreActivity";

    /**
     * ActionCentreActivity.EXTRA_INSTANT (ActionCentreActivity.java:51, value "extra_instant"):
     * "this launch came from the shade gesture", so the panel skips its entry animation.
     */
    static final String EXTRA_INSTANT = "extra_instant";

    /** Settings.System boolean-ish flag (1 = redirect, 0 = stock shade). */
    static final String SETTING_ENABLED = "ztmetro_shade_hook";

    /** One redirect per 800 ms; repeats are left to the stock shade. */
    static final long DEBOUNCE_MS = 800L;

    /** Down-x at or right of this fraction of the panel width = stock shade. */
    static final float STOCK_EDGE_FRACTION = 0.82f;

    /** StatusBarState.SHADE — normal unlocked shade (statusbar/StatusBarState.java:28). */
    private static final int BAR_STATE_SHADE = 0;

    /** A touch older than this cannot be the gesture that just opened the shade. */
    private static final long TOUCH_FRESH_MS = 1500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile long sLastRedirectMs = -DEBOUNCE_MS;
    private static volatile boolean sWarnedMissingApp;

    private ShadeRouter() {
    }

    /**
     * Called from the after-hook of NotificationPanelViewController#onPanelStateChanged
     * when the argument is STATE_OPENING.
     */
    static void onShadeOpening(Object panel) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - sLastRedirectMs < DEBOUNCE_MS) {
                SLog.once(TAG, "debounce",
                        "repeat within " + DEBOUNCE_MS + " ms ignored; stock shade allowed");
                return;
            }

            View view = (View) XposedHelpers.getObjectField(panel, "mView");
            if (view == null) {
                SLog.once(TAG, "panel-view", "mView missing; stock shade");
                return;
            }
            final Context context = view.getContext();
            if (context == null) {
                SLog.once(TAG, "context", "panel has no context; stock shade");
                return;
            }

            if (!isEnabled(context)) {
                SLog.once(TAG, "disabled", SETTING_ENABLED + "=0; stock shade");
                return;
            }
            if (isKeyguard(panel)) {
                return; // locked or locking: never replace the keyguard shade
            }
            if (isStockEdgeSwipe(panel, view)) {
                return; // deliberate right-edge swipe: real shade + quick settings
            }
            if (!isActionCentreInstalled(context)) {
                return;
            }

            final Object controller = ShadeHooks.shadeController();
            if (controller == null) {
                SLog.once(TAG, "controller", "no ShadeControllerImpl reference; stock shade");
                return;
            }

            sLastRedirectMs = now;
            SLog.info(TAG, "SAFETY NET: stock shade reached STATE_OPENING — the system_server"
                    + " transfer guard did not stop it (scope 'android' missing, or another"
                    + " shade path); collapsing and starting the Action Centre");
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        collapseAndLaunch(context, controller);
                    } catch (Throwable t) {
                        SLog.once(TAG, "redirect", t);
                    }
                }
            });
        } catch (Throwable t) {
            SLog.once(TAG, "opening", t);
        }
    }

    // ------------------------------------------------------------------ policy

    private static boolean isEnabled(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), SETTING_ENABLED, 1) != 0;
        } catch (Throwable t) {
            // A provider hiccup must not silently turn a working hook off; the rest of
            // the path is still fail-safe.
            SLog.once(TAG, "setting-read", t);
            return true;
        }
    }

    /**
     * True when the shade belongs to the lock screen.  mBarState is the authority
     * (KEYGUARD = 1, SHADE_LOCKED = 2; StatusBarState.java:28-47).  The keyguard
     * controller is an extra guard for unlock transitions where the bar state has not
     * caught up yet; if that field is unreadable, mBarState still decides.
     */
    private static boolean isKeyguard(Object panel) {
        try {
            int barState = XposedHelpers.getIntField(panel, "mBarState");
            if (barState != BAR_STATE_SHADE) {
                SLog.once(TAG, "bar-state", "barState=" + barState + "; keyguard shade kept stock");
                return true;
            }
        } catch (Throwable t) {
            SLog.once(TAG, "bar-state-read", t);
            return true; // cannot prove unlocked -> keep the stock shade (fail-safe)
        }

        try {
            Object keyguard = XposedHelpers.getObjectField(panel, "mKeyguardStateController");
            if (keyguard != null) {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(keyguard, "isBouncerShowing"))) {
                    SLog.once(TAG, "bouncer", "bouncer visible; stock shade");
                    return true;
                }
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(keyguard, "isShowing"))) {
                    SLog.once(TAG, "keyguard-showing", "keyguard showing; stock shade");
                    return true;
                }
            }
        } catch (Throwable t) {
            SLog.once(TAG, "keyguard-extra", t); // mBarState == SHADE remains authoritative
        }
        return false;
    }

    /**
     * The fallback path: a swipe whose ACTION_DOWN landed in the right edge of the
     * status bar opens the real shade, so quick settings stay reachable.
     *
     * <p>mDownX/mDownTime are the panel's own gesture-down bookkeeping
     * (NotificationPanelViewController.java:345,374; set in initDownStates and the
     * touch handlers).  Without a fresh touch (programmatic expansion, accessibility
     * action) the stale mDownX is ignored and the redirect applies.
     */
    private static boolean isStockEdgeSwipe(Object panel, View view) {
        try {
            long downTime = XposedHelpers.getLongField(panel, "mDownTime");
            long now = SystemClock.uptimeMillis();
            if (downTime <= 0L || now - downTime > TOUCH_FRESH_MS) {
                return false;
            }
            float downX = XposedHelpers.getFloatField(panel, "mDownX");
            int width = view.getWidth();
            if (width <= 0) {
                return false;
            }
            if (downX < width * STOCK_EDGE_FRACTION) {
                return false;
            }
            SLog.info(TAG, "right-edge swipe x=" + Math.round(downX) + "/" + width
                    + " -> stock shade (quick settings)");
            return true;
        } catch (Throwable t) {
            // The edge fallback is optional: if its bookkeeping is unreadable the
            // redirect still works (the gesture just cannot opt out).
            SLog.once(TAG, "edge", t);
            return false;
        }
    }

    private static boolean isActionCentreInstalled(Context context) {
        try {
            context.getPackageManager().getActivityInfo(
                    new ComponentName(ACTION_CENTRE_PACKAGE, ACTION_CENTRE_ACTIVITY), 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            if (!sWarnedMissingApp) {
                sWarnedMissingApp = true;
                SLog.info(TAG, ACTION_CENTRE_PACKAGE
                        + " is not installed — stock shade stays");
            }
            return false;
        } catch (Throwable t) {
            SLog.once(TAG, "package", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ action

    private static void collapseAndLaunch(Context context, Object controller) {
        try {
            Object wasVisible = XposedHelpers.callMethod(controller, "isExpandedVisible");

            // Reset the panel without animation, then hide the shade window the way
            // SystemUI itself does on close (ShadeControllerImpl.java:277,301).  This
            // does not depend on the gesture having stopped tracking, unlike
            // animateCollapseShade() (which checks canBeCollapsed()).
            XposedHelpers.callMethod(controller, "instantCollapseShade");
            XposedHelpers.callMethod(controller, "makeExpandedInvisible");

            Object stillVisible = XposedHelpers.callMethod(controller, "isExpandedVisible");
            if (Boolean.TRUE.equals(wasVisible) && Boolean.TRUE.equals(stillVisible)) {
                SLog.once(TAG, "collapse", "shade still visible; not launching, stock shade");
                return;
            }
        } catch (Throwable t) {
            SLog.once(TAG, "collapse", t);
            return; // never start the panel over a shade we could not close
        }

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(ACTION_CENTRE_PACKAGE, ACTION_CENTRE_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(EXTRA_INSTANT, true);
            context.startActivity(intent);
            SLog.info(TAG, "shade collapsed; started " + ACTION_CENTRE_PACKAGE
                    + "/.ActionCentreActivity (NEW_TASK, " + EXTRA_INSTANT + "=true)");
        } catch (Throwable t) {
            SLog.once(TAG, "start", t);
        }
    }
}
