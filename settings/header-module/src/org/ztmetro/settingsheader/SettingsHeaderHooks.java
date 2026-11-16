package org.ztmetro.settingsheader;

import android.app.Activity;
import android.os.Bundle;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Installs the three Settings hooks on Android 16 (API 36).
 *
 * <p>Every callback is an {@link XC_MethodHook} with an {@code afterHookedMethod} body that
 * is wrapped in try/catch(Throwable): a failure logs and returns, so Settings keeps its
 * stock layout instead of crashing.  Hook installation is also per-hook guarded, so one
 * missing method does not stop the others.
 */
public final class SettingsHeaderHooks {

    private static final String TAG = "ztmetro-header";

    private static final String BASE_ACTIVITY =
            "com.android.settings.core.SettingsBaseActivity";
    private static final String HOMEPAGE_ACTIVITY =
            "com.android.settings.homepage.SettingsHomepageActivity";

    private SettingsHeaderHooks() {
    }

    public static void install(ClassLoader settingsClassLoader) {
        XposedBridge.log(TAG + ": loading in Settings (xposed API "
                + XposedBridge.getXposedVersion() + "), module resources will be resolved "
                + "lazily on the first attach");

        Class<?> base = null;
        Class<?> homepage = null;
        try {
            base = XposedHelpers.findClass(BASE_ACTIVITY, settingsClassLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        try {
            homepage = XposedHelpers.findClass(HOMEPAGE_ACTIVITY, settingsClassLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        if (base != null) {
            hook(base, "setContentView", new Class<?>[]{int.class},
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            run("setContentView(int)", param.thisObject, true);
                        }
                    });
            hook(base, "setTitle", new Class<?>[]{CharSequence.class},
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            run("setTitle", param.thisObject, false);
                        }
                    });
            // Not declared on SettingsBaseActivity itself (SettingsActivity overrides it,
            // homepage activities inherit it): resolved up the hierarchy and guarded.
            hook(base, "onResume", new Class<?>[0],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            run("onResume", param.thisObject, false);
                        }
                    });
        }

        if (homepage != null) {
            hook(homepage, "onCreate", new Class<?>[]{Bundle.class},
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            run("homepage.onCreate", param.thisObject, true);
                        }
                    });
        }
    }

    private static void run(String where, Object thisObject, boolean attach) {
        try {
            if (!(thisObject instanceof Activity)) {
                return;
            }
            Activity activity = (Activity) thisObject;
            if (attach) {
                if ("homepage.onCreate".equals(where)) {
                    HeaderBinder.attachHome(activity);
                } else {
                    HeaderBinder.attachBase(activity);
                }
            } else {
                HeaderBinder.refresh(activity);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + where + " hook failed (Settings untouched)");
            XposedBridge.log(t);
        }
    }

    private static void hook(Class<?> clazz, String name, Class<?>[] params,
                             XC_MethodHook callback) {
        Method method = findMethod(clazz, name, params);
        if (method == null) {
            XposedBridge.log(TAG + ": " + clazz.getName() + "#" + name
                    + " not found — that hook is skipped");
            return;
        }
        try {
            XposedBridge.hookMethod(method, callback);
            XposedBridge.log(TAG + ": hooked " + method.getDeclaringClass().getName()
                    + "#" + name + " (" + params.length + " args)");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Declared method or the closest superclass declaration (Xposed-version independent). */
    private static Method findMethod(Class<?> clazz, String name, Class<?>[] params) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (Throwable t) {
                XposedBridge.log(t);
                return null;
            }
        }
        return null;
    }
}
