package org.ztmetro.settingsheader;

import android.app.Activity;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

/**
 * Row typography: the ztmetro list reads as a white line of text on black, with one grey
 * line under it at most.
 *
 * <p>Reference numbers (docs/17-wp-design-reference.md §6, tokens in
 * {@code design/tokens/ztmetro.tokens.json}):
 *
 * <ul>
 *   <li>{@code color.neutrals.text_primary} #FFFFFF for every row title,</li>
 *   <li>{@code color.neutrals.text_secondary} #999999 for summaries, breadcrumbs and
 *       captions ({@code core/zrnmetrocore.c:549-550} draws the detail line at 0.60 white,
 *       which composites to #999999 on black),</li>
 *   <li>the ztmetro hub row value is 20 sp ({@code ztmetro.c:6393}); the Settings
 *       dashboard uses it for its titles so the home list reads like a ztmetro page,</li>
 *   <li>no summary line on the dashboard (a ztmetro row is label + value, never three
 *       lines); sub-screens keep theirs.</li>
 * </ul>
 *
 * <p>Same shape as {@link MetroTinter}: walk after every attach/refresh, touch only
 * TextViews whose id is AndroidX/SettingsLib's {@code title}/{@code summary}, guard every
 * call, and log a count instead of failing the screen.
 */
final class RowType {

    private static final String TAG = "ztmetro-header";

    /** color.neutrals.text_primary */
    private static final int WHITE = 0xFFFFFFFF;
    /** color.neutrals.text_secondary */
    private static final int SECONDARY = 0xFF999999;
    /** ztmetro hub row value, ztmetro.c:6393 */
    private static final float DASHBOARD_TITLE_SP = 20f;

    private static final int MAX_DEPTH = 24;

    private RowType() {
    }

    /** @return number of rows restyled in this pass. */
    static int apply(Activity activity, boolean homepage) {
        View root = activity == null ? null : activity.findViewById(android.R.id.content);
        if (root == null) {
            return 0;
        }
        int[] count = new int[1];
        walk(root, 0, homepage, count);
        if (count[0] > 0) {
            Log.i(TAG, "row type: " + count[0] + " rows"
                    + (homepage ? " (dashboard: 20 sp titles, no summary)" : ""));
        }
        return count[0];
    }

    private static void walk(View view, int depth, boolean homepage, int[] count) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        if (Views.isOurs(view)) {
            return;
        }
        if (view instanceof ViewGroup && Views.looksLikePreferenceRow(view)) {
            if (style((ViewGroup) view, homepage)) {
                count[0]++;
            }
            return;   // children are row content, never rows themselves
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), depth + 1, homepage, count);
            }
        }
    }

    private static boolean style(ViewGroup row, boolean homepage) {
        try {
            boolean changed = false;
            TextView title = find(row, "title", 3);
            if (title != null) {
                title.setTextColor(WHITE);
                if (homepage) {
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, DASHBOARD_TITLE_SP);
                }
                changed = true;
            }
            TextView summary = find(row, "summary", 3);
            if (summary != null) {
                summary.setTextColor(SECONDARY);
                if (homepage && summary.getVisibility() != View.GONE) {
                    summary.setVisibility(View.GONE);   // a ztmetro row is two lines, not three
                }
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            Log.d(TAG, "row type skipped for "
                    + row.getClass().getSimpleName() + ": " + t);
            return false;
        }
    }

    /** First TextView at {@code depth} whose resource entry name equals {@code name}. */
    private static TextView find(View view, String name, int depth) {
        if (view == null || depth < 0) {
            return null;
        }
        if (view instanceof TextView
                && name.equals(Views.idName(view).toLowerCase(Locale.ROOT))) {
            return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = find(group.getChildAt(i), name, depth - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
