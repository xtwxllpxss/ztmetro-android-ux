package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.Locale;

/**
 * Hides the dashboard row icons (the blue circled glyphs in every Settings home row), so
 * the home page becomes the reference's text-first list.
 *
 * <p>Only runs for the Settings home page and only inside its dashboard list: the method
 * finds {@code @id/main_content} (fallback: {@code homepage_container}) and the
 * RecyclerView inside it, then walks <em>that</em> list.  A glyph is hidden only when
 * all of the following hold (conservative):
 *
 * <ul>
 *   <li>it is an {@link ImageView} that this module did not inflate,</li>
 *   <li>its id/class is not a chevron/arrow/button/toggle/search glyph,</li>
 *   <li>it is not inside a switch/CompoundButton (switch thumbs stay),</li>
 *   <li>an ancestor within 3 levels is a preference row ({@link Views#looksLikePreferenceRow})
 *       that actually carries a text title — i.e. the row keeps its label,</li>
 *   <li>it is not a standalone clickable icon with its own content description.</li>
 * </ul>
 *
 * <p>The visibility change is {@link View#GONE}; the row's text/title and its chevron are
 * untouched.  The number of newly hidden icons is logged at info level.
 */
final class RowIcons {

    private static final String TAG = "ztmetro-header";
    private static final String TARGET_PACKAGE = "com.android.settings";
    private static final int MAX_DEPTH = 28;

    private RowIcons() {
    }

    /** @return number of icons hidden in this pass. */
    static int apply(Activity activity) {
        ViewGroup list = dashboardList(activity);
        if (list == null) {
            return 0;
        }
        int[] count = new int[1];
        walk(list, 0, count);
        if (count[0] > 0) {
            Log.i(TAG, "hid " + count[0] + " dashboard row icons "
                    + "(text-only rows, chevrons/switches kept)");
        }
        return count[0];
    }

    private static ViewGroup dashboardList(Activity activity) {
        try {
            Resources res = activity.getResources();
            ViewGroup scope = null;
            int main = res.getIdentifier("main_content", "id", TARGET_PACKAGE);
            if (main != 0) {
                View view = activity.findViewById(main);
                if (view instanceof ViewGroup) {
                    scope = (ViewGroup) view;
                }
            }
            if (scope == null) {
                int home = res.getIdentifier("homepage_container", "id", TARGET_PACKAGE);
                if (home != 0) {
                    View view = activity.findViewById(home);
                    if (view instanceof ViewGroup) {
                        scope = (ViewGroup) view;
                    }
                }
            }
            if (scope == null) {
                return null;
            }
            ViewGroup recycler = findRecycler(scope, 0);
            return recycler != null ? recycler : scope;
        } catch (Throwable t) {
            Log.d(TAG, "dashboard list not found: " + t);
            return null;
        }
    }

    private static ViewGroup findRecycler(ViewGroup group, int depth) {
        if (depth > 8) {
            return null;
        }
        if (Views.className(group).contains("recyclerview")) {
            return group;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup found = findRecycler((ViewGroup) child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void walk(View view, int depth, int[] count) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        if (view instanceof ImageView) {
            if (shouldHide((ImageView) view) && view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                count[0]++;
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), depth + 1, count);
            }
        }
    }

    private static boolean shouldHide(ImageView icon) {
        try {
            if (Views.isOurs(icon) || Views.isInsideSwitch(icon)) {
                return false;
            }
            String id = Views.idName(icon).toLowerCase(Locale.ROOT);
            String cls = Views.className(icon);
            String hint = id + " " + cls;
            for (String word : new String[]{"arrow", "chevron", "expand", "collapse",
                    "more", "overflow", "button", "toggle", "switch", "search",
                    "check", "radio"}) {
                if (hint.contains(word)) {
                    return false;
                }
            }
            if (icon.getContentDescription() != null && icon.isClickable()) {
                return false;   // standalone control, not a row decoration
            }
            ViewGroup row = Views.rowFor(icon, 3);
            if (row == null) {
                return false;
            }
            // Keep the row's label: only rows that carry their own title are icon-stripped.
            return Views.containsTitle(row, 3);
        } catch (Throwable t) {
            Log.d(TAG, "icon check skipped: " + t);
            return false;
        }
    }
}
