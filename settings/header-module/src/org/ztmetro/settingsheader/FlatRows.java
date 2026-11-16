package org.ztmetro.settingsheader;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.ViewGroup.MarginLayoutParams;

/**
 * Removes the Material card look from preference rows: no rounded surface, no side
 * margins, no elevation.
 *
 * <p>Mutates only {@link ViewGroup}s that {@link Views#looksLikePreferenceRow(View)}
 * accepts (short, contains {@code @android:id/title}, not a protected container).  For
 * each accepted row:
 *
 * <ol>
 *   <li>background → flat black (was the card/surface drawable),</li>
 *   <li>elevation → 0 (removes the card shadow),</li>
 *   <li>horizontal padding → 24 dp page margin, vertical padding untouched,</li>
 *   <li>layout margins → 0 when they are card insets (&le; 24 dp horizontal, &le; 16 dp
 *       vertical); larger margins are treated as structure and left alone.</li>
 * </ol>
 *
 * <p>Rows are walked after every attach/refresh and again at +200/+700/+1600 ms because
 * Settings inflates the dashboard/fragment rows after {@code setContentView}.  The
 * fragment container itself is never a row and is never modified.
 */
final class FlatRows {

    private static final String TAG = "ztmetro-header";
    private static final int BLACK = Color.BLACK;
    private static final int MAX_DEPTH = 24;

    private FlatRows() {
    }

    /** @return number of rows flattened in this pass (0 before Settings inflates them). */
    static int apply(Activity activity) {
        View root = activity == null ? null : activity.findViewById(android.R.id.content);
        if (root == null) {
            return 0;
        }
        int[] count = new int[1];
        walk(root, 0, count);
        if (count[0] > 0) {
            Log.i(TAG, "flat rows: " + count[0] + " preference rows flattened");
        }
        return count[0];
    }

    private static void walk(View view, int depth, int[] count) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }
        if (Views.isOurs(view)) {
            return;
        }
        if (view instanceof ViewGroup && Views.looksLikePreferenceRow(view)) {
            if (flatten((ViewGroup) view)) {
                count[0]++;
            }
            return;   // children are row content, never rows themselves
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // A group that wraps rows (a preference category) is not a row and must not
            // keep the Material card: it becomes flat black and loses the large gap it
            // puts between groups, so the page reads as one continuous ztmetro list
            // (docs/17-wp-design-reference.md §6: one list, ~8-12 dp between rows).
            if (!Views.isProtected(group) && Views.containsTitle(group, 3)
                    && isGroupContainer(group)) {
                if (flattenGroup(group)) {
                    count[0]++;
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), depth + 1, count);
            }
        }
    }

    /** A wrapper around rows: not the screen, not a list, not a page-height block. */
    private static boolean isGroupContainer(ViewGroup group) {
        int height = group.getHeight();
        if (height <= 0) {
            return true;   // before the first layout pass
        }
        int screen = group.getContext().getResources().getDisplayMetrics().heightPixels;
        return height <= screen * 3 / 4;
    }

    /**
     * Flattens a row-wrapping group: black background (a container's card cannot be
     * "removed" safely, so it is painted the page colour) and no vertical margins up to
     * 40 dp — that is where the Material group gaps live.  Anything larger is structure
     * and is left alone.
     */
    private static boolean flattenGroup(ViewGroup group) {
        try {
            boolean changed = false;
            Drawable background = group.getBackground();
            if (!(background instanceof ColorDrawable)
                    || ((ColorDrawable) background).getColor() != BLACK) {
                group.setBackgroundColor(BLACK);
                changed = true;
            }
            ViewGroup.LayoutParams params = group.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                int limit = Views.dp(group.getContext(), 40);
                boolean marginsChanged = false;
                if (margins.topMargin != 0 && Math.abs(margins.topMargin) <= limit) {
                    margins.topMargin = 0;
                    marginsChanged = true;
                }
                if (margins.bottomMargin != 0 && Math.abs(margins.bottomMargin) <= limit) {
                    margins.bottomMargin = 0;
                    marginsChanged = true;
                }
                if (marginsChanged) {
                    group.setLayoutParams(margins);
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            Log.d(TAG, "group flatten skipped for " + group.getClass().getSimpleName()
                    + ": " + t);
            return false;
        }
    }

    private static boolean flatten(ViewGroup row) {
        try {
            boolean changed = false;
            int pageMargin = Views.dp(row.getContext(), 24);

            Drawable background = row.getBackground();
            boolean blackBackground = background instanceof ColorDrawable
                    && ((ColorDrawable) background).getColor() == BLACK;
            boolean pagePadding = row.getPaddingLeft() == pageMargin
                    && row.getPaddingRight() == pageMargin;
            if (!blackBackground || !pagePadding) {
                row.setBackgroundColor(BLACK);
                row.setPadding(pageMargin, row.getPaddingTop(),
                        pageMargin, row.getPaddingBottom());
                changed = true;
            }
            try {
                row.setElevation(0f);
            } catch (Throwable ignored) {
                // pre-L only, minSdk is 29
            }
            // The stock row reserves the icon cell with a start inset on the text
            // container; with the icon hidden that would leave a ~60 dp hole.  Zero the
            // start inset of the title and its containers (never the end inset, which
            // reserves the switch/chevron side) so the label sits on the page margin.
            leftAlignTitle(row);

            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                boolean marginsChanged = false;
                if (margins.leftMargin != 0 && Math.abs(margins.leftMargin) <= pageMargin) {
                    margins.leftMargin = 0;
                    marginsChanged = true;
                }
                if (margins.rightMargin != 0 && Math.abs(margins.rightMargin) <= pageMargin) {
                    margins.rightMargin = 0;
                    marginsChanged = true;
                }
                int verticalInset = Views.dp(row.getContext(), 32);
                if (margins.topMargin != 0 && Math.abs(margins.topMargin) <= verticalInset) {
                    margins.topMargin = 0;
                    marginsChanged = true;
                }
                if (margins.bottomMargin != 0
                        && Math.abs(margins.bottomMargin) <= verticalInset) {
                    margins.bottomMargin = 0;
                    marginsChanged = true;
                }
                if (marginsChanged) {
                    row.setLayoutParams(margins);
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            Log.d(TAG, "row flatten skipped for " + row.getClass().getSimpleName() + ": " + t);
            return false;
        }
    }
    /** Moves the row label to the page margin by clearing its start inset. */
    private static void leftAlignTitle(ViewGroup row) {
        TextView title = findTitle(row, 3);
        if (title == null) {
            return;
        }
        int limit = Views.dp(row.getContext(), 96);
        View current = title;
        while (current != null && current != row) {
            clearStartInset(current, limit);
            View parent = current.getParent() instanceof View ? (View) current.getParent() : null;
            if (parent == row) {
                break;
            }
            current = parent;
        }
    }

    private static void clearStartInset(View view, int limit) {
        try {
            if (view instanceof ViewGroup
                    && view.getPaddingStart() > 0 && view.getPaddingStart() <= limit) {
                view.setPaddingRelative(0, view.getPaddingTop(),
                        view.getPaddingEnd(), view.getPaddingBottom());
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                if (margins.getMarginStart() > 0 && margins.getMarginStart() <= limit) {
                    margins.setMarginStart(0);
                    view.setLayoutParams(margins);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "start inset not cleared for " + view.getClass().getSimpleName());
        }
    }

    private static TextView findTitle(View view, int depth) {
        if (view == null || depth < 0) {
            return null;
        }
        if (view instanceof TextView && "title".equals(Views.idName(view))) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTitle(group.getChildAt(i), depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

}
