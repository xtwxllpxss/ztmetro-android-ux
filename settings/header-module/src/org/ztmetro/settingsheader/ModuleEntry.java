package org.ztmetro.settingsheader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point.  The module is inert outside its two scoped packages:
 * {@code com.android.settings} (chrome, rows, dialogs, switches) and
 * {@code com.android.settings.intelligence} (the search results surface).
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "ztmetro-header";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !SettingsHeaderHooks.isScope(lpparam.packageName)) {
            return;
        }
        try {
            SettingsHeaderHooks.install(lpparam.packageName, lpparam.classLoader);
        } catch (Throwable t) {
            // Never let module loading break Settings.
            XposedBridge.log(TAG + ": module init failed, Settings stays stock");
            XposedBridge.log(t);
        }
    }
}
