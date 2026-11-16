package org.ztmetro.shadehook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point (assets/xposed_init).
 *
 * <p>Two scopes, two different jobs:
 *
 * <ul>
 *   <li><b>android</b> (system_server) — {@link FrameworkHooks}: block the
 *       {@code WindowState#transferTouch()} that DisplayPolicy uses to hand the top-edge
 *       gesture to the status bar window. This is what keeps the stock shade from opening
 *       at all; without this scope the APK degrades to the SystemUI-only safety net.</li>
 *   <li><b>com.android.systemui</b> — {@link ShadeHooks}: the proven fallback that closes a
 *       stock shade which opened anyway and starts the Action Centre.</li>
 * </ul>
 *
 * <p>Any other package is ignored, so enabling a wider scope cannot affect it.
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    /** LSPosed reports system_server as package "android". */
    private static final String SYSTEM_SERVER_PACKAGE = "android";
    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String TAG = ShadeRouter.TAG;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        try {
            if (SYSTEM_SERVER_PACKAGE.equals(lpparam.packageName)) {
                FrameworkHooks.install(lpparam.classLoader);
            } else if (TARGET_PACKAGE.equals(lpparam.packageName)) {
                ShadeHooks.install(lpparam.classLoader);
            }
        } catch (Throwable t) {
            // Never let module loading break system_server or SystemUI.
            XposedBridge.log(TAG + ": module init failed in " + lpparam.packageName
                    + ", stock shade stays");
            XposedBridge.log(t);
        }
    }
}
