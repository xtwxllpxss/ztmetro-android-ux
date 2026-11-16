package org.ztmetro.keyguard;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed glue for the Android 16 keyguard (LineageOS 23.2, packages/SystemUI).
 *
 * <p>Every hook body is wrapped in {@code try/catch(Throwable)} and every installation
 * step is individually guarded, so a missing/renamed target only skips that one hook.
 * No callback ever lets an exception reach SystemUI.
 *
 * <p>Hook targets were verified against the lineage-23.2 tree; the class/method that
 * older guides use (KeyguardClockSwitch, KeyguardStatusView, KeyguardStatusViewController)
 * no longer exists in this tree — see evidence/keyguard-module/REPORT.md.
 */
public final class KeyguardHooks {

    private static final String TAG = "ztmetro-keyguard";

    private static final String SHADE_WINDOW_VIEW =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String KEYGUARD_ROOT_VIEW =
            "com.android.systemui.keyguard.ui.view.KeyguardRootView";
    private static final String CLOCK_BINDER =
            "com.android.systemui.keyguard.ui.binder.KeyguardClockViewBinder";
    private static final String INDICATION_VIEW =
            "com.android.systemui.statusbar.phone.KeyguardIndicationTextView";
    private static final String SLICE_VIEW = "com.android.keyguard.KeyguardSliceView";
    private static final String ANIMATABLE_CLOCK =
            "com.android.systemui.shared.clocks.AnimatableClockView";

    private KeyguardHooks() {
    }

    public static void install(ClassLoader systemUiClassLoader) {
        KLog.info(TAG, "loading in SystemUI (xposed API " + xposedVersion() + "); "
                + "every hook is fail-safe");

        // 1. Window anchor: the keyguard container is inflated inside the shade window.
        hookDeclared(systemUiClassLoader, SHADE_WINDOW_VIEW, "onAttachedToWindow",
                new Class<?>[0], new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof View) {
                                MetroKeyguard.onShadeAttached((View) param.thisObject);
                            }
                        } catch (Throwable t) {
                            KLog.once(TAG, "hook:shade-attached", t);
                        }
                    }
                });

        // 2. Keyguard lifecycle: blueprint (re)build binds the clock into KeyguardRootView.
        hookAll(systemUiClassLoader, CLOCK_BINDER, "bind", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    MetroKeyguard.onClockBound(firstView(param.args));
                } catch (Throwable t) {
                    KLog.once(TAG, "hook:clock-bind", t);
                }
            }
        });

        // 3. Owner info / status indications: applied after the text style is set, and
        //    only when the indication is neutral (no error colour, no icon, no button).
        hookDeclared(systemUiClassLoader, INDICATION_VIEW, "setNextIndication",
                new Class<?>[0], new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            Object info = XposedHelpers.getObjectField(
                                    param.thisObject, "mKeyguardIndicationInfo");
                            if (isNeutralIndication(info)) {
                                MetroKeyguard.tintIndication((View) param.thisObject);
                            }
                        } catch (Throwable t) {
                            KLog.once(TAG, "hook:indication", t);
                        }
                    }
                });

        // 4. Date row: KeyguardSliceView.showSlice builds/reuses the date TextView.
        hookAll(systemUiClassLoader, SLICE_VIEW, "showSlice", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject instanceof View) {
                        MetroKeyguard.onSliceShown((View) param.thisObject);
                    }
                } catch (Throwable t) {
                    KLog.once(TAG, "hook:slice-shown", t);
                }
            }
        });

        // 5. Stock (default) clock face.  It is a TextView whose digits are painted by
        //    SystemUI's TextAnimator, so the typeface must be in place before the animator
        //    is created and the colour is only honoured through setColors().
        hookAnimatableClock(systemUiClassLoader);
    }

    private static void hookAnimatableClock(ClassLoader cl) {
        Class<?> clock = findClass(cl, ANIMATABLE_CLOCK);
        if (clock == null) {
            return;
        }
        hookDeclared(clock, "refreshTime", new Class<?>[0], new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    MetroKeyguard.onAnimatableClockRefresh((View) param.thisObject);
                } catch (Throwable t) {
                    KLog.once(TAG, "hook:clock-refresh", t);
                }
            }
        });
        hookDeclared(clock, "onMeasure", new Class<?>[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            MetroKeyguard.onAnimatableClockMeasure((View) param.thisObject);
                        } catch (Throwable t) {
                            KLog.once(TAG, "hook:clock-measure", t);
                        }
                    }
                });
        hookDeclared(clock, "setColors", new Class<?>[]{int.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // param.args[1] is lockScreenColor; keep the AOD/doze colour
                            // (args[0]) stock so burn-in behaviour is unchanged.
                            param.args[1] = MetroKeyguard.WHITE;
                        } catch (Throwable t) {
                            KLog.once(TAG, "hook:clock-colors", t);
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Neutral indications only: owner info, "unlocked by ...", plain status text.
     * Coloured (error) indications, icon indications and button indications keep the
     * stock styling — recolouring them could hide a wrong-PIN/face-unlock error.
     */
    private static boolean isNeutralIndication(Object indication) {
        if (indication == null) {
            return true;
        }
        try {
            Object background = XposedHelpers.callMethod(indication, "getBackground");
            if (background != null) {
                return false;
            }
            Object icon = XposedHelpers.callMethod(indication, "getIcon");
            if (icon != null) {
                return false;
            }
            Object colors = XposedHelpers.callMethod(indication, "getTextColor");
            if (!(colors instanceof ColorStateList)) {
                return false;
            }
            int color = ((ColorStateList) colors).getDefaultColor();
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            return spread <= 24; // white/grey = neutral; chromatic = keep stock
        } catch (Throwable t) {
            KLog.once(TAG, "indication-neutral", t);
            return false;
        }
    }

    private static View firstView(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof View) {
                return (View) arg;
            }
        }
        return null;
    }

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            KLog.once(TAG, "find:" + name, t);
            return null;
        }
    }

    /** Hooks a method declared on {@code className} only (never a superclass method). */
    private static void hookDeclared(ClassLoader cl, String className, String methodName,
                                    Class<?>[] params, XC_MethodHook callback) {
        Class<?> clazz = findClass(cl, className);
        if (clazz != null) {
            hookDeclared(clazz, methodName, params, callback);
        }
    }

    private static void hookDeclared(Class<?> clazz, String methodName, Class<?>[] params,
                                    XC_MethodHook callback) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, params);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            KLog.info(TAG, "hooked " + clazz.getName() + "#" + methodName
                    + " (" + params.length + " args)");
        } catch (Throwable t) {
            KLog.once(TAG, "hook:" + clazz.getSimpleName() + "#" + methodName, t);
        }
    }

    /** Hooks every overload of a declared method name; absent method is only logged. */
    private static void hookAll(ClassLoader cl, String className, String methodName,
                               XC_MethodHook callback) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(clazz, methodName, callback);
            KLog.info(TAG, "hooked " + className + "#" + methodName + " x" + unhooks.size());
        } catch (Throwable t) {
            KLog.once(TAG, "hookAll:" + className + "#" + methodName, t);
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
