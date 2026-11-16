package org.ztmetro.shadehook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point (assets/xposed_init).  The module is inert outside
 * com.android.systemui.
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String TAG = ShadeRouter.TAG;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        try {
            ShadeHooks.install(lpparam.classLoader);
        } catch (Throwable t) {
            // Never let module loading break SystemUI / the shade.
            XposedBridge.log(TAG + ": module init failed, stock shade stays");
            XposedBridge.log(t);
        }
    }
}
