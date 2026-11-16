package org.ztmetro.settingsheader;

import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;

/**
 * Allocation-free view classification shared by the single-pass walker
 * ({@link ScreenPass}) and its per-view helpers.
 *
 * <p>This class used to allocate on every view it looked at: {@code idName()} called
 * {@code getResourceEntryName()} (a fresh String), {@code className()} lowercased a fresh
 * String and several callers lowercased it again.  On a 2 GB phone a Settings screen with
 * ~1,000 views × up to 12 walks made that the dominant cost.  Now:
 *
 * <ul>
 *   <li>id → resource-entry-name is cached once per id in a {@link SparseArray} (no
 *       boxing, no String per view);</li>
 *   <li>class → flags is cached once per {@link Class};</li>
 *   <li>the common title/summary ids are compared as {@code int} against
 *       {@code android.R.id.title}/{@code android.R.id.summary}.</li>
 * </ul>
 *
 * <p>The heuristics stay conservative: a preference row is a short {@link ViewGroup} with
 * 1–8 children that contains <em>exactly one</em> title (a group of rows is not a row),
 * and the protected containers (fragment host, app bar, list/scroll containers) are never
 * restyled.
 */
final class Views {

    private static final String EMPTY = "";

    /** id → resource entry name.  Values are lower-case by aapt convention. */
    private static final SparseArray<String> ID_NAMES = new SparseArray<>();
    /** class → FLAG_* bits; the class String is lowercased once per Class, not per view. */
    private static final HashMap<Class<?>, Integer> CLASS_FLAGS = new HashMap<>();

    private static final int FLAG_PROTECTED_CLASS = 1;

    private Views() {
    }

    /** Resource entry name of the view id ("title", "icon", …) or "" when anonymous. */
    static String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) {
            return EMPTY;
        }
        String cached = ID_NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            // An id from a Resources the view does not know (or a renamed id): anonymous.
            name = EMPTY;
        }
        ID_NAMES.put(id, name);
        return name;
    }

    static boolean isTitle(View view) {
        if (!(view instanceof TextView)) {
            return false;
        }
        if (view.getId() == android.R.id.title) {
            return true;
        }
        String name = idName(view);
        // SettingsLib's entity header (app info page) uses its own ids, not @android:id/title.
        // Without this the app-info header is skipped as "no row content" and stays the stock
        // centred icon block.
        return "title".equals(name) || "entity_header_title".equals(name);
    }

    static boolean isSummary(View view) {
        if (!(view instanceof TextView)) {
            return false;
        }
        if (view.getId() == android.R.id.summary) {
            return true;
        }
        String name = idName(view);
        return "summary".equals(name)
                || "entity_header_summary".equals(name)
                || "entity_header_second_summary".equals(name)
                || "install_type".equals(name);
    }

    /** SettingsLib entity header block (app info page): app icon + name + version. */
    static boolean isEntityHeader(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        String className = view.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if ("entity_header".equals(idName(view)) || className.contains("entityheader")) {
            return true;
        }
        // Preference wrappers differ between builds: also accept a short group whose
        // subtree carries the entity title, so the app-info header is never skipped.
        return hasEntityTitle(view, 2);
    }

    private static boolean hasEntityTitle(View view, int depth) {
        if (view instanceof TextView) {
            return "entity_header_title".equals(idName(view));
        }
        if (depth <= 0 || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        int children = Math.min(group.getChildCount(), 8);
        for (int i = 0; i < children; i++) {
            if (hasEntityTitle(group.getChildAt(i), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /** A two-state row widget (framework Switch, SwitchCompat, MaterialSwitch). */
    static boolean isSwitch(View view) {
        if (!(view instanceof CompoundButton)) {
            return false;
        }
        return view.getClass().getName().toLowerCase(Locale.ROOT).contains("switch");
    }

    /** A radio/checkbox row widget: selection, not a two-state value line. */
    static boolean isSelectable(View view) {
        return view instanceof android.widget.RadioButton
                || view instanceof android.widget.CheckBox;
    }

    /** MaterialButtonToggleGroup / the segmented-button preference's group. */
    static boolean isSegmentedGroup(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        String name = view.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("materialbuttontogglegroup") || "button_group".equals(idName(view));
    }

    static boolean isBreadcrumb(View view) {
        return "breadcrumb".equals(idName(view));
    }

    /** A view inflated by this module (header, bottom bar, their children). */
    static boolean isOurs(View view) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("ztmetro_")) {
            return true;
        }
        return idName(view).startsWith("metro_");
    }

    /**
     * Containers that own layout/behaviour and must never be restyled: the fragment host,
     * the home scroll host, the app bar and every list/scroll view.
     */
    static boolean isProtected(View view) {
        String id = idName(view);
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
        return (classFlags(view.getClass()) & FLAG_PROTECTED_CLASS) != 0;
    }

    private static int classFlags(Class<?> type) {
        Integer cached = CLASS_FLAGS.get(type);
        if (cached != null) {
            return cached.intValue();
        }
        String name = type.getName().toLowerCase(Locale.ROOT);
        int flags = 0;
        if (name.contains("recyclerview")
                || name.contains("scrollview")
                || name.contains("coordinatorlayout")
                || name.contains("toolbar")
                || name.contains("viewpager")
                || name.contains("tablayout")
                || name.contains("fragmentcontainer")) {
            flags |= FLAG_PROTECTED_CLASS;
        }
        CLASS_FLAGS.put(type, Integer.valueOf(flags));
        return flags;
    }

    /**
     * Number of preference titles at or below {@code view}, capped at 2.  The cap is all
     * the walker needs: 0 = no row content, 1 = a single row, 2 = a group of rows.
     */
    static int titleCount(View view, int depth) {
        if (view == null || depth < 0) {
            return 0;
        }
        if (view instanceof TextView) {
            return isTitle(view) ? 1 : 0;
        }
        if (!(view instanceof ViewGroup)) {
            return 0;
        }
        ViewGroup group = (ViewGroup) view;
        int count = 0;
        int children = group.getChildCount();
        for (int i = 0; i < children; i++) {
            count += titleCount(group.getChildAt(i), depth - 1);
            if (count > 1) {
                return 2;
            }
        }
        return count;
    }

    static boolean isInsideSwitch(View view) {
        View current = view;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current instanceof CompoundButton) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
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
}
