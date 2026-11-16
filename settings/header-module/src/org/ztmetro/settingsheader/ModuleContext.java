package org.ztmetro.settingsheader;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;

/**
 * A Context that resolves resources through the <em>module's</em> {@link Resources}
 * while keeping the host Activity's services, display metrics and theme.
 *
 * <p>This is what makes a header with new drawables/dimens/fonts legal inside Settings:
 * {@code LayoutInflater.cloneInContext(moduleContext)} then resolves
 * {@code @layout/metro_header}, {@code @drawable/metro_header_bg} and
 * {@code @style/MetroHeaderTitle} in the module APK's resource table, not in Settings'.
 * The returned Context is only used for inflation; it is never handed back to Settings.
 */
final class ModuleContext extends ContextWrapper {

    private final Resources mResources;
    private Resources.Theme mTheme;

    ModuleContext(Context host, Resources moduleResources) {
        super(host);
        mResources = moduleResources;
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public AssetManager getAssets() {
        return mResources.getAssets();
    }

    @Override
    public ClassLoader getClassLoader() {
        // The layout only uses framework views, but a module-provided view class would
        // have to resolve through the module dex, not through Settings' ClassLoader.
        return getClass().getClassLoader();
    }

    @Override
    public Resources.Theme getTheme() {
        if (mTheme == null) {
            // Deliberately NOT Theme.setTo(hostTheme): the host theme was created by a
            // different AssetManager, and copying a Theme across resource tables can make
            // Settings' 0x7f… style ids resolve against the module's 0x7f… ids.  The header
            // layout is self-contained (every text size/colour/family is set explicitly),
            // so an empty theme created from the module resources is both sufficient and
            // safer.
            mTheme = mResources.newTheme();
        }
        return mTheme;
    }

    /** Visible for the (optional) host-side harness. */
    static Context wrap(Context host, Resources moduleResources) {
        return new ModuleContext(host, moduleResources);
    }
}
