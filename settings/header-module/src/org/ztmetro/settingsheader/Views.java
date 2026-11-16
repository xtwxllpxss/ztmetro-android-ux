package org.ztmetro.settingsheader;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/**
 * View-tree heuristics shared by {@link FlatRows} and {@link RowIcons}.
 *
 * <p>Everything here is conservative: a view is only treated as a preference row when it
 * carries AndroidX/SettingsLib's own {@code @android:id/title} id (the one id every
 * preference item layout has), when it is short, and when it is not one of the known
 * containers (fragment host, RecyclerView, scroll view, app bar).  The module never
 * touches {@code content_frame}, {@code main_content}, {@code homepage_container} or any
 * scrolling/list container, so the fragment layout is always left intact.
 */
final class Views {

    private static final int MAX_DEPTH = 3;

    private Views() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Resource entry name of the view id ("title", "icon", …) or "" when anonymous. */
    static String idName(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || id == 0) {
                return "";
            }
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "";
        }
    }

    static String className(View view) {
        return view.getClass().getName().toLowerCase(Locale.ROOT);
    }

    /** A view inflated by this module (header, bottom bar, their children). */
    static boolean isOurs(View view) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("ztmetro_")) {
            return true;
        }
        String id = idName(view).toLowerCase(Locale.ROOT);
        return id.startsWith("metro_");
    }

    /**
     * Containers that own layout/behaviour and must never be restyled: the fragment host,
     * the home scroll host, the app bar and every list/scroll view.
     */
    static boolean isProtected(View view) {
        String id = idName(view).toLowerCase(Locale.ROOT);
        switch (id) {
            case "content":
            case "content_parent":
            case "content_frame":
            case "main_content":
            case "main_content_scrollable_container":
            case "homepage_container":
            case "settings_homepage_container":
            case "suggestion_content":
            case "contextual_cards_content":
            case "switch_bar":
            case "button_bar":
            case "app_bar":
            case "app_bar_container":
            case "action_bar":
            case "collapsing_toolbar":
            case "search_action_bar":
            case "search_bar_title":
                return true;
            default:
                break;
        }
        String cls = className(view);
        return cls.contains("recyclerview")
                || cls.contains("scrollview")
                || cls.contains("coordinatorlayout")
                || cls.contains("toolbar")
                || cls.contains("viewpager")
                || cls.contains("tablayout")
                || cls.contains("fragmentcontainer");
    }

    /** True when this group directly (depth {@code <= max}) contains a preference title. */
    static boolean containsTitle(View view, int maxDepth) {
        if (view == null || maxDepth < 0) {
            return false;
        }
        if (view instanceof TextView && "title".equals(idName(view))) {
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsTitle(group.getChildAt(i), maxDepth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A preference row: short {@link ViewGroup}, inside the screen (not a protected
     * container), containing a preference title.  The class-name hint is accepted as an
     * alternative signal but is not required — Settings ships several row layouts whose
     * view class is a plain LinearLayout.
     */
    static boolean looksLikePreferenceRow(View view) {
        if (!(view instanceof ViewGroup) || isOurs(view) || isProtected(view)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        int children = group.getChildCount();
        if (children < 1 || children > 8) {
            return false;
        }
        int height = group.getHeight();
        if (height > 0 && height > dp(group.getContext(), 220)) {
            return false;   // a row is short; a page block is not
        }
        return containsTitle(group, MAX_DEPTH);
    }

    static boolean isInsideSwitch(View view) {
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            String cls = className(current);
            if (cls.contains("switch") || cls.contains("compoundbutton")) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    /** Nearest ancestor (up to {@code levels}) that looks like a preference row. */
    static ViewGroup rowFor(View view, int levels) {
        View current = view;
        for (int i = 0; i < levels && current != null; i++) {
            if (current instanceof ViewGroup && looksLikePreferenceRow(current)) {
                return (ViewGroup) current;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    static boolean isAncestor(View ancestor, View view) {
        View current = view;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    static boolean isImageView(View view) {
        return view instanceof ImageView;
    }
}
