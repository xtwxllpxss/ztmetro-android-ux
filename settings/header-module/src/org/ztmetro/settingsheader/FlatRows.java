package org.ztmetro.settingsheader;

import android.app.Activity;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), depth + 1, count);
            }
        }
    }

    private static boolean flatten(ViewGroup row) {
        try {
            boolean changed = false;
            int pageMargin = Views.dp(row.getContext(), 24);

            if (row.getBackground() != null || row.getPaddingLeft() != pageMargin
                    || row.getPaddingRight() != pageMargin) {
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

            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                boolean marginsChanged = false;
                if (Math.abs(margins.leftMargin) <= pageMargin) {
                    margins.leftMargin = 0;
                    marginsChanged = true;
                }
                if (Math.abs(margins.rightMargin) <= pageMargin) {
                    margins.rightMargin = 0;
                    marginsChanged = true;
                }
                int verticalInset = Views.dp(row.getContext(), 16);
                if (Math.abs(margins.topMargin) <= verticalInset) {
                    margins.topMargin = 0;
                    marginsChanged = true;
                }
                if (Math.abs(margins.bottomMargin) <= verticalInset) {
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
}
