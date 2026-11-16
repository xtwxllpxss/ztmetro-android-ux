package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Resolves what the Metro header shows.
 *
 * <p>Title: {@code Activity.getTitle()} — Settings has already run
 * {@code setTitleFromIntent()} / {@code setTitleFromBackStack()}
 * ({@code SettingsActivity.java:556-624}), so this is the correct per-screen breadcrumb.
 * The reference pages are lowercase ({@code about}, {@code cellular + SIM}) while stock
 * Settings strings are title-case, so the title is lowercased with ALL-CAPS acronym tokens
 * preserved ({@code SIM}, {@code USB}, {@code VPN}); the module never invents a title.
 *
 * <p>Subtitle: for a sub-screen the last <em>different</em> breadcrumb on the support
 * fragment back stack; otherwise the module's static "ztMetro" string.  "different"
 * matters because Settings pushes the current screen onto the back stack too, and showing
 * the title twice would be noise.  AndroidX classes are reached by reflection so the module
 * does not need androidx.fragment on its compile classpath.
 */
public final class HeaderTitles {

    private static final String TAG = "ztmetro-header";
    private static final String STATIC_SUBTITLE = "metro_header_subtitle_static";

    private HeaderTitles() {
    }

    public static CharSequence title(Activity activity) {
        return lowercase(activity.getTitle());
    }

    public static CharSequence subtitle(Activity activity, Resources moduleResources,
                                        CharSequence title) {
        CharSequence breadcrumb = lastDifferentBreadcrumb(activity, title);
        if (breadcrumb != null && breadcrumb.length() > 0) {
            return breadcrumb;
        }
        int id = moduleResources.getIdentifier(
                STATIC_SUBTITLE, "string", HeaderResources.MODULE_PACKAGE);
        if (id != 0) {
            return moduleResources.getString(id);
        }
        Log.w(TAG, "static subtitle string missing from module resources");
        return "";
    }

    /**
     * Lowercases the page title for the Metro page block, keeping already-ALL-CAPS tokens
     * ("SIM", "USB") as the Settings string provides them.  Returns "" for null.
     */
    static CharSequence lowercase(CharSequence raw) {
        if (raw == null) {
            return "";
        }
        String source = raw.toString();
        if (source.length() == 0) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            int start = index;
            while (index < source.length() && !Character.isWhitespace(source.charAt(index))) {
                index++;
            }
            String token = source.substring(start, index);
            out.append(isAcronym(token) ? token : token.toLowerCase(Locale.getDefault()));
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                out.append(source.charAt(index));
                index++;
            }
        }
        return out.toString();
    }

    private static boolean isAcronym(String token) {
        if (token.length() < 2) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            hasLetter = true;
            if (!Character.isUpperCase(c)) {
                return false;
            }
        }
        return hasLetter;
    }

    private static CharSequence lastDifferentBreadcrumb(Activity activity, CharSequence title) {
        try {
            ClassLoader loader = activity.getClassLoader();
            Method getFm = activity.getClass().getMethod("getSupportFragmentManager");
            Object fm = getFm.invoke(activity);
            if (fm == null) {
                return null;
            }
            // Resolve through the PUBLIC androidx types, never through
            // FragmentManagerImpl#getClass(): the implementation class may be
            // package-private, which makes Method.invoke throw IllegalAccessException.
            Class<?> fmClass = Class.forName(
                    "androidx.fragment.app.FragmentManager", false, loader);
            int count = ((Integer) fmClass.getMethod("getBackStackEntryCount")
                    .invoke(fm)).intValue();
            if (count <= 0) {
                return null;
            }
            Class<?> entryClass = Class.forName(
                    "androidx.fragment.app.FragmentManager$BackStackEntry", false, loader);
            Method atMethod = fmClass.getMethod("getBackStackEntryAt", int.class);
            Method crumbMethod = entryClass.getMethod("getBreadCrumbTitle");
            String titleText = title == null ? "" : title.toString();

            for (int i = count - 1; i >= 0; i--) {
                Object entry = atMethod.invoke(fm, Integer.valueOf(i));
                if (entry == null) {
                    continue;
                }
                Object value = crumbMethod.invoke(entry);
                CharSequence crumb = value instanceof CharSequence ? (CharSequence) value : null;
                if (crumb != null && crumb.length() > 0
                        && !crumb.toString().equalsIgnoreCase(titleText)) {
                    return crumb;
                }
            }
        } catch (Throwable t) {
            // A missing/renamed androidx method only costs the subtitle, never the header.
            Log.d(TAG, "back-stack breadcrumb unavailable: " + t);
        }
        return null;
    }
}
