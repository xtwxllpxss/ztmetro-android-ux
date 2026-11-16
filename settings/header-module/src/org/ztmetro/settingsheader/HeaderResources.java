package org.ztmetro.settingsheader;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Loads the module's own {@link Resources} <em>inside the Settings process</em>.
 *
 * <p>This is the part of the module that is genuinely tricky: an LSPosed module is
 * loaded as a bare dex by the LSPosed class loader, so the hooked process does not have a
 * Context for the module package and {@code R.layout.metro_header} cannot be resolved with
 * Settings' own {@code Resources}.  The header must therefore be inflated from the module
 * APK's resource table.  Three independent mechanisms are tried, in this order:
 *
 * <ol>
 *   <li><b>package-context</b> — {@code createPackageContext(module, INCLUDE_CODE |
 *       IGNORE_SECURITY)}.  Public API, returns the module's real {@code Resources}. This
 *       is the documented approach of {@code docs/10b} §3(b) and the preferred path.</li>
 *   <li><b>package-resources</b> — {@code PackageManager.getResourcesForApplication()}.
 *       Public API, no code loading, no Context.  Survives the cases where creating a
 *       Context with code fails (e.g. a process that is not allowed to load another
 *       package's dex).</li>
 *   <li><b>asset-manager</b> — {@code AssetManager.addAssetPath(base.apk)} plus a fresh
 *       {@code Resources}.  Hidden API, but the classic Xposed resource fallback: it does
 *       not touch the host's resource table and cannot pollute Settings' ids.</li>
 * </ol>
 *
 * <p>Each path is validated by looking up the layout id in the result; the first one that
 * contains it wins and the successful path is logged once per process:
 * {@code ztmetro-header: resources via <path>}.  Nothing here throws out to Settings: the
 * caller catches and logs, so a failure leaves stock Settings untouched.
 */
public final class HeaderResources {

    public static final String MODULE_PACKAGE = "org.ztmetro.settingsheader";

    private static final String TAG = "ztmetro-header";
    private static final String LAYOUT_NAME = "metro_header";

    private static Resources sResources;
    private static String sSource;
    private static String sFailure;

    private HeaderResources() {
    }

    /** Returns the module resources, or throws if no mechanism worked. */
    public static synchronized Resources get(Context host) throws Exception {
        if (sResources != null) {
            return sResources;
        }
        if (sFailure != null) {
            throw new IllegalStateException("no module resources: " + sFailure);
        }

        Resources loaded = fromPackageContext(host);
        if (loaded == null) {
            loaded = fromPackageManager(host);
        }
        if (loaded == null) {
            loaded = fromAssetManager(host);
        }
        if (loaded == null) {
            sFailure = "createPackageContext, getResourcesForApplication and "
                    + "AssetManager.addAssetPath all failed (see logcat tag " + TAG + ")";
            throw new IllegalStateException(sFailure);
        }

        sResources = loaded;
        Log.i(TAG, "module resources via " + sSource);
        return loaded;
    }

    /** Human-readable description of the successful mechanism (for the log and the evidence report). */
    public static synchronized String source() {
        return sSource == null ? "none" : sSource;
    }

    private static Resources fromPackageContext(Context host) {
        try {
            Context module = host.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Resources res = module.getResources();
            if (hasLayout(res)) {
                sSource = "package-context";
                return res;
            }
            Log.w(TAG, "createPackageContext returned resources without " + LAYOUT_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "createPackageContext failed", t);
        }
        return null;
    }

    private static Resources fromPackageManager(Context host) {
        try {
            Resources res = host.getPackageManager().getResourcesForApplication(MODULE_PACKAGE);
            if (hasLayout(res)) {
                sSource = "package-resources";
                return res;
            }
            Log.w(TAG, "getResourcesForApplication returned resources without " + LAYOUT_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "getResourcesForApplication failed", t);
        }
        return null;
    }

    private static Resources fromAssetManager(Context host) {
        try {
            String apk = moduleApkPath(host);
            if (apk == null) {
                Log.w(TAG, "module APK path is unknown, cannot use AssetManager");
                return null;
            }

            Constructor<AssetManager> ctor = AssetManager.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AssetManager assets = ctor.newInstance();

            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object cookie = addAssetPath.invoke(assets, apk);
            if (cookie instanceof Integer && ((Integer) cookie).intValue() == 0) {
                Log.w(TAG, "addAssetPath rejected " + apk);
                return null;
            }

            Resources hostResources = host.getResources();
            DisplayMetrics metrics = hostResources.getDisplayMetrics();
            Resources res = new Resources(assets, metrics, hostResources.getConfiguration());
            if (hasLayout(res)) {
                sSource = "asset-manager";
                return res;
            }
            Log.w(TAG, "AssetManager resources do not contain " + LAYOUT_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager fallback failed", t);
        }
        return null;
    }

    private static String moduleApkPath(Context host) {
        try {
            return host.getPackageManager()
                    .getApplicationInfo(MODULE_PACKAGE, 0).sourceDir;
        } catch (Throwable t) {
            Log.w(TAG, "cannot resolve the module APK path", t);
            return null;
        }
    }

    private static boolean hasLayout(Resources res) {
        return res != null
                && res.getIdentifier(LAYOUT_NAME, "layout", MODULE_PACKAGE) != 0;
    }
}
