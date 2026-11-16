package org.ztmetro.shadehook;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed glue for the Android 16 shade (LineageOS 23.2, packages/SystemUI).
 *
 * <p>Hook set, all verified against the lineage-23.2 tree
 * (frameworks_base 797e43244b96c6a0dff5c81b21c4d4be665b082e):
 *
 * <ol>
 *   <li>{@code NotificationPanelViewController#onPanelStateChanged(int)}
 *       — shade/NotificationPanelViewController.java:3797; listener registered at
 *       :790.  The state manager (ShadeExpansionStateManager.kt:91-93) calls it with
 *       STATE_OPENING (=1, :157) exactly once per CLOSED→OPENING transition, not per
 *       frame.  This is the trigger.</li>
 *   <li>{@code ShadeControllerImpl#start()} — ShadeControllerImpl.java:380 (a
 *       CoreStartable, called once at SystemUI boot).  Captures the controller
 *       instance used to close the shade.</li>
 *   <li>{@code ShadeControllerImpl#makeExpandedVisible(boolean)} —
 *       ShadeControllerImpl.java:283.  Instance capture only: this method also runs
 *       on the screen-off/light-reveal path (CentralSurfacesImpl.java:2603), so it
 *       must never be a trigger.</li>
 * </ol>
 *
 * <p>Every body is wrapped in {@code try/catch(Throwable)} and every installation
 * step is separately guarded, so a renamed/missing target only skips that hook and
 * the stock shade keeps working.
 */
final class ShadeHooks {

    private static final String TAG = ShadeRouter.TAG;

    private static final String NPCV =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String SHADE_CONTROLLER_IMPL =
            "com.android.systemui.shade.ShadeControllerImpl";

    /** ShadeExpansionStateManagerKt.STATE_OPENING (ShadeExpansionStateManager.kt:157). */
    private static final int STATE_OPENING = 1;

    /** Set on the SystemUI main thread at boot; read from the same thread. */
    private static volatile Object sShadeController;

    private ShadeHooks() {
    }

    static Object shadeController() {
        return sShadeController;
    }

    static void install(ClassLoader systemUiClassLoader) {
        SLog.info(TAG, "loading in SystemUI (xposed API " + xposedVersion()
                + "); stock shade is the fallback for every failure");

        Class<?> controller = findClass(systemUiClassLoader, SHADE_CONTROLLER_IMPL);
        if (controller != null) {
            hookControllerStart(controller);
            hookExpandedVisibleForCapture(controller);
        }
        hookPanelStateChanged(systemUiClassLoader);
    }

    // ------------------------------------------------------------------ trigger

    private static void hookPanelStateChanged(final ClassLoader cl) {
        try {
            Class<?> panel = XposedHelpers.findClass(NPCV, cl);
            Method method = panel.getDeclaredMethod("onPanelStateChanged", int.class);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int state = (Integer) param.args[0];
                        if (state != STATE_OPENING) {
                            return;
                        }
                        ShadeRouter.onShadeOpening(param.thisObject);
                    } catch (Throwable t) {
                        SLog.once(TAG, "hook:onPanelStateChanged", t);
                    }
                }
            });
            SLog.info(TAG, "hooked " + NPCV + "#onPanelStateChanged(int)");
        } catch (Throwable t) {
            SLog.once(TAG, "hook:" + NPCV + "#onPanelStateChanged", t);
        }
    }

    // ------------------------------------------------------------------ captures

    /** CoreStartable start(): the first and most reliable controller reference. */
    private static void hookControllerStart(Class<?> controller) {
        try {
            Method method = controller.getDeclaredMethod("start");
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        sShadeController = param.thisObject;
                        SLog.info(TAG, "captured ShadeControllerImpl from start()");
                    } catch (Throwable t) {
                        SLog.once(TAG, "hook:controller-start", t);
                    }
                }
            });
        } catch (Throwable t) {
            SLog.once(TAG, "hook:ShadeControllerImpl#start", t);
        }
    }

    /**
     * The first shade open calls onOpenStarted → makeExpandedVisible(false) before our
     * after-hook on the state change runs, so this also guarantees a controller
     * reference even if {@code start()} was missed.  Inert: instance capture only.
     */
    private static void hookExpandedVisibleForCapture(Class<?> controller) {
        try {
            Method method = controller.getDeclaredMethod("makeExpandedVisible", boolean.class);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sShadeController == null) {
                            sShadeController = param.thisObject;
                        }
                    } catch (Throwable t) {
                        SLog.once(TAG, "hook:makeExpandedVisible-capture", t);
                    }
                }
            });
        } catch (Throwable t) {
            SLog.once(TAG, "hook:ShadeControllerImpl#makeExpandedVisible", t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            SLog.once(TAG, "find:" + name, t);
            return null;
        }
    }

    private static String xposedVersion() {
        try {
            return Integer.toString(XposedBridge.getXposedVersion());
        } catch (Throwable t) {
            return "?";
        }
    }
}
