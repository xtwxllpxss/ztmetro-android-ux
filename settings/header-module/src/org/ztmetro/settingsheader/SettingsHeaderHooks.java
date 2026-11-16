package org.ztmetro.settingsheader;

import android.app.Activity;
import android.os.Bundle;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Installs the Settings hooks on Android 16 (API 36):
 *
 * <ul>
 *   <li>{@code SettingsBaseActivity.setContentView(int)} → header + bottom bar + rows,</li>
 *   <li>{@code setTitle(CharSequence)} → re-bind the page title/subtitle,</li>
 *   <li>{@code onResume()} → re-apply the chrome (rows are inflated late),</li>
 *   <li>{@code SettingsHomepageActivity.onCreate(Bundle)} → the scrolling home header,</li>
 *   <li>{@code SettingsHomepageActivity.onResume()} → self-heal the home header + chrome.</li>
 * </ul>
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

    /** The Settings process itself. */
    public static final String SETTINGS_PACKAGE = "com.android.settings";
    /** SettingsIntelligence hosts the search UI on LineageOS 23.2 (separate package). */
    public static final String SEARCH_PACKAGE = SearchSurface.PACKAGE;

    private SettingsHeaderHooks() {
    }

    /** True for the two packages the module decorates. */
    public static boolean isScope(String packageName) {
        return SETTINGS_PACKAGE.equals(packageName) || SEARCH_PACKAGE.equals(packageName);
    }

    /** Package-aware entry point: Settings chrome or the search results surface. */
    public static void install(String packageName, ClassLoader classLoader) {
        if (SETTINGS_PACKAGE.equals(packageName)) {
            installSettings(classLoader);
        } else if (SEARCH_PACKAGE.equals(packageName)) {
            SearchSurface.install(classLoader);
        }
    }

    private static void installSettings(ClassLoader settingsClassLoader) {
        XposedBridge.log(TAG + ": loading in Settings (xposed API "
                + XposedBridge.getXposedVersion() + "), module resources will be resolved "
                + "lazily on the first attach");

        // Dialogs are separate windows (requirement 2) and switch state lines need the
        // checked-change event (requirement 3); both are Settings-process only.
        DialogStyler.install(settingsClassLoader);
        TwoStateRow.install(settingsClassLoader);
        // Builds where search still lives in the Settings package (guarded, usually none
        // on LineageOS 23.2: search is SettingsIntelligence).
        SearchSurface.install(settingsClassLoader);

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
            // SettingsHomepageActivity extends FragmentActivity directly (not
            // SettingsBaseActivity), so its resume is a separate hook: it re-attaches a
            // header lost to an early attach and re-applies the two-pass chrome.
            hook(homepage, "onResume", new Class<?>[0],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            run("homepage.onResume", param.thisObject, false);
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
