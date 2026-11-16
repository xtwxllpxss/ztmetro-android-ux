package org.ztmetro.settingsheader;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Search results surface — requirement 1: the Settings search list gets the same flat black
 * two-line rows (grey 15 sp label + white 22 sp value), no cards, no result icons.
 *
 * <p>On LineageOS 23.2 the search UI is a separate package,
 * {@code com.android.settings.intelligence}: {@code SearchActivity} hosts {@code
 * SearchFragment} and its {@code SearchResultsAdapter}/{@code SearchViewHolder} inflate
 * {@code search_intent_item.xml} (one {@code @android:id/title} + one
 * {@code @android:id/summary} per row).  The same hook also accepts a build where search
 * lives in Settings ({@code com.android.settings.search.SettingsSearchActivity}).
 *
 * <p>Only the existing {@link ScreenPass} walker is used: one attach pass over the search
 * activity plus the per-RecyclerView hierarchy listener that {@code ScreenPass} already
 * installs for late rows.  No new tree walker and no adapter/holder hook.  The only extra
 * work is the search bar card (the one Material card on the screen) and the list padding.
 */
final class SearchSurface {

    private static final String TAG = "ztmetro-header";

    /** SettingsIntelligence search UI on LineageOS 23.2. */
    static final String PACKAGE = "com.android.settings.intelligence";

    private static final String[] ACTIVITY_NAMES = {
            "com.android.settings.intelligence.search.SearchActivity",
            "com.android.settings.search.SettingsSearchActivity",
    };

    private static boolean sInstalled;

    private SearchSurface() {
    }

    static void install(ClassLoader classLoader) {
        if (sInstalled) {
            return;
        }
        Class<?> activity = null;
        String resolved = null;
        for (String name : ACTIVITY_NAMES) {
            try {
                activity = XposedHelpers.findClass(name, classLoader);
                resolved = name;
                break;
            } catch (Throwable ignored) {
                // try the next known search activity name
            }
        }
        if (activity == null) {
            XposedBridge.log(TAG + ": no search activity in this package — search stays stock");
            return;
        }
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(activity, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                try {
                                    apply((Activity) param.thisObject);
                                } catch (Throwable t) {
                                    Log.w(TAG, "search surface attach failed (stock search)", t);
                                }
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(activity, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        try {
                            refresh((Activity) param.thisObject);
                        } catch (Throwable t) {
                            Log.d(TAG, "search surface refresh skipped: " + t);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + resolved
                    + " (search rows via ScreenPass, one attach pass)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": search hooks failed, search stays stock");
            XposedBridge.log(t);
        }
    }

    /** One ScreenPass attach pass + the flat search bar; rows come from the same walker. */
    static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        ScreenStyler.paintBlack(activity);
        // passTotal = 1: the row pass is the one-shot global-layout listener, and rows that
        // attach after that are caught by ScreenPass.trackRecycler's hierarchy listener.
        ScreenPass.run(activity, false, 1, 1, "attach", "search");
        flattenSearchBar(activity);
        styleExistingRows(activity);
        Log.i(TAG, "search surface [search]: black window, flat search card, "
                + "rows two-line via ScreenPass/lightRow (1 pass)");
    }

    /** Resume: re-apply the flat chrome + already-attached rows, no new tree walk. */
    static void refresh(Activity activity) {
        if (activity == null) {
            return;
        }
        ScreenStyler.paintBlack(activity);
        flattenSearchBar(activity);
        styleExistingRows(activity);
        Log.d(TAG, "search surface resume: chrome + attached rows re-applied (no pass)");
    }

    /**
     * The search bar is the only card on the screen: black fill, 0 radius, no stroke;
     * the container keeps the 24 dp page margin so rows and search field line up.
     */
    private static void flattenSearchBar(Activity activity) {
        View bar = findById(activity, "search_bar");
        if (bar != null) {
            ScreenPass.flattenCard(bar);
            try {
                Method stroke = bar.getClass().getMethod("setStrokeWidth", int.class);
                stroke.invoke(bar, 0);
            } catch (Throwable ignored) {
                // not a MaterialCardView: the black fill is enough
            }
        }
        View container = findById(activity, "search_bar_container");
        if (container != null) {
            int padH = ScreenPass.pageMargin();
            if (container.getPaddingLeft() != padH || container.getPaddingRight() != padH) {
                container.setPadding(padH, container.getPaddingTop(),
                        padH, container.getPaddingBottom());
            }
        }
        View list = findById(activity, "list_results");
        if (list != null) {
            // Rows carry the 24 dp page margin themselves; drop the dashboard padding so it
            // is not doubled.
            if (list.getPaddingLeft() != 0 || list.getPaddingRight() != 0) {
                list.setPadding(0, list.getPaddingTop(), 0, list.getPaddingBottom());
            }
            if (!(list.getBackground() instanceof android.graphics.drawable.ColorDrawable)
                    || ((android.graphics.drawable.ColorDrawable) list.getBackground())
                    .getColor() != android.graphics.Color.BLACK) {
                list.setBackgroundColor(android.graphics.Color.BLACK);
            }
        }
    }

    /** Rows already attached when the surface is (re)applied; no walk, just the list children. */
    private static void styleExistingRows(Activity activity) {
        View list = findById(activity, "list_results");
        if (!(list instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) list;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ScreenPass.styleRow((ViewGroup) child);
            }
        }
    }

    private static View findById(Activity activity, String name) {
        try {
            int id = activity.getResources().getIdentifier(name, "id",
                    activity.getPackageName());
            return id == 0 ? null : activity.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
