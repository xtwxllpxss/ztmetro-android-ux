package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The single view-tree pass behind every ztmetro Settings screen.
 *
 * <p>Before this class there were four independent traversals of the same tree per pass —
 * {@code MetroTinter}, {@code RowType}, {@code RowIcons} and {@code FlatRows} — each with
 * its own {@code getChildAt}/instanceof/id-name work, and {@link ScreenStyler} ran the
 * pass three times (0/150/600 ms).  This class visits every view exactly once and
 * dispatches by type:
 *
 * <ul>
 *   <li><b>protected container</b> (content host, app bar, RecyclerView/ScrollView) → descend
 *       only;</li>
 *   <li><b>preference row</b> (short group with exactly one {@code @android:id/title}) →
 *       flatten the row, then scan its subtree <em>inline</em> and style the title/summary,
 *       hide row images/chevrons, tint the switch; the main walk does not revisit it;</li>
 *   <li><b>row-wrapping group</b> (a category card) → paint black, drop the Material group
 *       gap when it is short, then descend to its rows;</li>
 *   <li><b>anything else</b> → descend.</li>
 * </ul>
 *
 * <p>No allocation per view: id names, class flags, reflection methods and colour lists
 * are cached; the walk and row stacks are static reusable arrays; the counters are
 * primitive fields.  Ids/class checks are the only classification work left.
 *
 * <p>One info line per pass proves the reduction on the device:
 * {@code pass 1/2 attach: 934 views, 61 applies, 7 ms}.
 */
final class ScreenPass {

    private static final String TAG = "ztmetro-header";

    private static final int BLACK = 0xFF000000;
    private static final int FALLBACK_LABEL = 0xFF999999;
    private static final int FALLBACK_VALUE = 0xFFFFFFFF;
    private static final int FALLBACK_ACCENT = 0xFF0050EF;

    private static final int MAX_DEPTH = 40;

    /** Main walk stack (view + depth), grown on demand and reused across passes. */
    private static View[] sStack = new View[256];
    /** Recycler views seen in the last pass; their rows are styled as they attach. */
    private static final java.util.ArrayList<View> sRecyclers = new java.util.ArrayList<>();
    private static int[] sStackDepth = new int[256];
    /** Row subtree stack, reused across rows. */
    private static View[] sRowStack = new View[128];

    private static boolean sIdsResolved;
    private static int sRowPaddingHId;
    private static int sRowPaddingVId;
    private static int sRowLabelSizeId;
    private static int sRowValueSizeId;
    private static int sRowMaxHeightId;
    private static int sGroupGapLimitId;
    private static int sRowInsetLimitId;
    private static int sRowLabelColorId;
    private static int sRowValueColorId;
    private static int sSwitchTrackId;
    private static int sSwitchThumbId;

    private static int sPageMargin;
    private static int sRowPadV;
    private static int sRowMaxHeight;
    private static int sGroupGapLimit;
    private static int sRowInsetLimit;
    private static int sGroupHeightLimit;
    private static float sLabelPx;
    private static float sValuePx;
    private static int sLabelColor;
    private static int sValueColor;
    private static int sTrackColor;
    private static int sThumbColor;

    private static int sVisited;
    private static int sApplies;

    private ScreenPass() {
    }

    /**
     * Runs one full pass over {@code activity}'s content and logs the single proof line.
     *
     * @param passNumber 1-based index of this pass within the current pair
     * @param passTotal  always 2 in this design (attach + post-layout)
     * @param phase      {@code attach} or {@code post-layout}
     */
    static void run(Activity activity, boolean homepage, int passNumber, int passTotal,
                    String phase) {
        if (activity == null) {
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        configure(activity);
        MetroTinter.configure(sTrackColor, sThumbColor);
        RowType.configure(sLabelPx, sValuePx, sLabelColor, sValueColor);

        sVisited = 0;
        sApplies = 0;
        long started = SystemClock.uptimeMillis();
        if (passNumber == 1) {
            sRecyclers.clear();
        }
        walk(root);
        if (passNumber == passTotal) {
            scheduleRowPass(root);
        }
        long elapsed = SystemClock.uptimeMillis() - started;
        Log.i(TAG, "pass " + passNumber + "/" + passTotal + " " + phase + ": "
                + sVisited + " views, " + sApplies + " applies, " + elapsed + " ms"
                + (homepage ? " (home)" : ""));
    }

    /** Styles rows of the recyclers found in the walk, as they attach (no full tree walk). */
    private static void scheduleRowPass(final View root) {
        if (root == null) {
            return;
        }
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                styleRecyclerRows();
            }
        }, 250L);
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                styleRecyclerRows();
            }
        }, 700L);
    }

    private static void styleRecyclerRows() {
        int styled = 0;
        for (int i = 0; i < sRecyclers.size(); i++) {
            View v = sRecyclers.get(i);
            if (!(v instanceof ViewGroup)) {
                continue;
            }
            ViewGroup g = (ViewGroup) v;
            for (int c = 0; c < g.getChildCount(); c++) {
                View child = g.getChildAt(c);
                if (!(child instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup row = (ViewGroup) child;
                if (Views.titleCount(row, 3) == 1 && isRowCandidate(row)) {
                    lightRow(row);
                    styled++;
                }
            }
        }
        Log.i(TAG, "row pass: " + styled + " recycler rows styled, " + sRecyclers.size() + " recycler(s)");
    }

    /** Minimal row styling without layout changes (safe inside a RecyclerView pass). */
    private static void lightRow(ViewGroup row) {
        int styled = 0;
        row.setBackgroundColor(0xFF000000);
        flattenCard(row);
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(row);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            View view = queue.poll();
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                if (Views.isTitle(text)) {
                    RowType.styleLabel(text);
                    styled++;
                } else if (Views.isSummary(text) && text.getVisibility() == View.VISIBLE) {
                    RowType.styleValue(text);
                    styled++;
                }
            } else if (view instanceof ImageView) {
                RowIcons.hide(view);
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                flattenCard(group);
                for (int i = 0; i < group.getChildCount() && i < 12; i++) {
                    View child = group.getChildAt(i);
                    if (!Views.isOurs(child)) {
                        queue.add(child);
                    }
                }
            }
        }
        // one line per row is too noisy; the aggregate line is logged by styleRecyclerRows
    }

    /** CardView/MaterialCardView: black fill, zero radius (no reflection failure noise). */
    private static void flattenCard(View view) {
        String name = view.getClass().getSimpleName();
        if (name.contains("Card")) {
            try {
                view.getClass().getMethod("setCardBackgroundColor", int.class)
                        .invoke(view, 0xFF000000);
                view.getClass().getMethod("setRadius", float.class).invoke(view, 0f);
            } catch (Throwable ignored) {
                // plain view: fall through to the background colour
            }
        }
        if (view.getBackground() != null) {
            view.setBackgroundColor(0xFF000000);
        }
    }

    // ---------------------------------------------------------------- configuration

    /**
     * Reads the generated module resources once per pass.  Sizes are read as pixels from
     * the generated sp/dp dimens (font-scale aware, no TypedArray); colours are ints.
     * The resource ids themselves are resolved only once per process.
     */
    private static void configure(Activity activity) {
        Resources host = activity.getResources();
        DisplayMetrics metrics = host.getDisplayMetrics();
        float density = metrics.density;

        sPageMargin = Math.round(24f * density);
        sRowPadV = Math.round(4f * density);
        sRowMaxHeight = Math.round(220f * density);
        sGroupGapLimit = Math.round(40f * density);
        sRowInsetLimit = Math.round(96f * density);
        sGroupHeightLimit = metrics.heightPixels * 3 / 4;
        sLabelPx = 15f * metrics.scaledDensity;
        sValuePx = 22f * metrics.scaledDensity;
        sLabelColor = FALLBACK_LABEL;
        sValueColor = FALLBACK_VALUE;
        sTrackColor = FALLBACK_ACCENT;
        sThumbColor = FALLBACK_VALUE;

        Resources module = null;
        try {
            module = HeaderResources.get(activity);
        } catch (Throwable ignored) {
            // header would already be absent; literals above keep the rows styled
        }
        if (module == null) {
            return;
        }
        if (!sIdsResolved) {
            sIdsResolved = true;
            sRowPaddingHId = id(module, "metro_row_padding_h", "dimen");
            sRowPaddingVId = id(module, "metro_row_padding_v", "dimen");
            sRowLabelSizeId = id(module, "metro_row_label_size", "dimen");
            sRowValueSizeId = id(module, "metro_row_value_size", "dimen");
            sRowMaxHeightId = id(module, "metro_row_max_height", "dimen");
            sGroupGapLimitId = id(module, "metro_group_gap_limit", "dimen");
            sRowInsetLimitId = id(module, "metro_row_inset_limit", "dimen");
            sRowLabelColorId = id(module, "metro_row_label", "color");
            sRowValueColorId = id(module, "metro_row_value", "color");
            sSwitchTrackId = id(module, "metro_switch_track", "color");
            sSwitchThumbId = id(module, "metro_switch_thumb", "color");
        }
        if (sRowPaddingHId != 0) {
            sPageMargin = module.getDimensionPixelSize(sRowPaddingHId);
        }
        if (sRowPaddingVId != 0) {
            sRowPadV = module.getDimensionPixelSize(sRowPaddingVId);
        }
        if (sRowLabelSizeId != 0) {
            sLabelPx = module.getDimension(sRowLabelSizeId);
        }
        if (sRowValueSizeId != 0) {
            sValuePx = module.getDimension(sRowValueSizeId);
        }
        if (sRowMaxHeightId != 0) {
            sRowMaxHeight = module.getDimensionPixelSize(sRowMaxHeightId);
        }
        if (sGroupGapLimitId != 0) {
            sGroupGapLimit = module.getDimensionPixelSize(sGroupGapLimitId);
        }
        if (sRowInsetLimitId != 0) {
            sRowInsetLimit = module.getDimensionPixelSize(sRowInsetLimitId);
        }
        if (sRowLabelColorId != 0) {
            sLabelColor = module.getColor(sRowLabelColorId);
        }
        if (sRowValueColorId != 0) {
            sValueColor = module.getColor(sRowValueColorId);
        }
        if (sSwitchTrackId != 0) {
            sTrackColor = module.getColor(sSwitchTrackId);
        }
        if (sSwitchThumbId != 0) {
            sThumbColor = module.getColor(sSwitchThumbId);
        }
    }

    private static int id(Resources module, String name, String type) {
        return module.getIdentifier(name, type, HeaderResources.MODULE_PACKAGE);
    }

    // ---------------------------------------------------------------- one walk

    private static void walk(View root) {
        int top = 0;
        sStack[0] = root;
        sStackDepth[0] = 0;
        top = 1;

        while (top > 0) {
            top--;
            View view = sStack[top];
            int depth = sStackDepth[top];
            if (view == null) {
                continue;
            }
            sVisited++;
            if (view instanceof ViewGroup
                    && view.getClass().getSimpleName().endsWith("RecyclerView")
                    && !sRecyclers.contains(view)) {
                sRecyclers.add(view);
            }
            if (Views.isOurs(view)) {
                continue;
            }
            if (!(view instanceof ViewGroup)) {
                continue;   // a lone TextView/ImageView outside a row is stock chrome
            }
            ViewGroup group = (ViewGroup) view;
            if (Views.isProtected(group)) {
                top = pushChildren(group, depth + 1, top);
                continue;
            }
            int titles = Views.titleCount(group, 3);
            if (titles == 1 && isRowCandidate(group)) {
                applyRow(group);
                continue;   // the row subtree was visited inline; never walk it twice
            }
            if (titles > 0 && FlatRows.flattenGroup(group, sGroupGapLimit, isShortGroup(group))) {
                sApplies++;
            }
            top = pushChildren(group, depth + 1, top);
        }
    }

    private static boolean isRowCandidate(ViewGroup group) {
        int children = group.getChildCount();
        if (children < 1 || children > 8) {
            return false;
        }
        int height = group.getHeight();
        return height <= 0 || height <= sRowMaxHeight;
    }

    private static boolean isShortGroup(ViewGroup group) {
        int height = group.getHeight();
        return height <= 0 || height <= sGroupHeightLimit;
    }

    private static int pushChildren(ViewGroup group, int depth, int top) {
        if (depth > MAX_DEPTH) {
            return top;
        }
        int count = group.getChildCount();
        int needed = top + count;
        if (needed > sStack.length) {
            growMain(needed);
        }
        for (int i = count - 1; i >= 0; i--) {
            sStack[top] = group.getChildAt(i);
            sStackDepth[top] = depth;
            top++;
        }
        return top;
    }

    // ---------------------------------------------------------------- one row

    /**
     * Visits a row's subtree once: styles the value line, hides icons/chevrons, tints the
     * switch, remembers the title, then applies the label or value style to it.
     */
    private static void applyRow(ViewGroup row) {
        TextView title = null;
        boolean hasValue = false;
        boolean hasSwitch = false;

        int top = 0;
        sRowStack[0] = row;
        top = 1;
        while (top > 0) {
            top--;
            View view = sRowStack[top];
            if (view == null) {
                continue;
            }
            if (view != row) {
                sVisited++;
                if (Views.isOurs(view)) {
                    continue;
                }
            }
            if (view instanceof CompoundButton) {
                hasSwitch = true;
                if (MetroTinter.tint(view)) {
                    sApplies++;
                }
                continue;   // switch internals are not row content
            }
            if (view instanceof TextView) {
                if (Views.isTitle(view)) {
                    if (title == null) {
                        title = (TextView) view;
                    }
                } else if (Views.isSummary(view)) {
                    TextView summary = (TextView) view;
                    if (summary.getVisibility() == View.GONE) {
                        // Settings hides an unused summary; the title becomes the value.
                        continue;
                    }
                    if (summary.getVisibility() == View.VISIBLE
                            && summary.getText().length() > 0) {
                        hasValue = true;
                    }
                    if (RowType.styleValue(summary)) {
                        sApplies++;
                    }
                }
                continue;
            }
            if (view instanceof ImageView) {
                if (RowIcons.hide(view)) {
                    sApplies++;
                }
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int count = group.getChildCount();
                top = growRow(top + count);
                for (int i = count - 1; i >= 0; i--) {
                    sRowStack[top] = group.getChildAt(i);
                    top++;
                }
            }
        }

        if (title != null) {
            boolean changed = hasValue ? RowType.styleLabel(title) : RowType.styleValue(title);
            if (changed) {
                sApplies++;
            }
        }
        if (FlatRows.flattenRow(row, sPageMargin, sRowPadV, title, hasSwitch, sRowInsetLimit)) {
            sApplies++;
        }
    }

    // ---------------------------------------------------------------- stacks

    private static int growRow(int needed) {
        if (needed <= sRowStack.length) {
            return needed;
        }
        int size = sRowStack.length;
        while (size < needed) {
            size <<= 1;
        }
        View[] grown = new View[size];
        System.arraycopy(sRowStack, 0, grown, 0, sRowStack.length);
        sRowStack = grown;
        return needed;
    }

    private static void growMain(int needed) {
        if (needed <= sStack.length) {
            return;
        }
        int size = sStack.length;
        while (size < needed) {
            size <<= 1;
        }
        View[] views = new View[size];
        int[] depths = new int[size];
        System.arraycopy(sStack, 0, views, 0, sStack.length);
        System.arraycopy(sStackDepth, 0, depths, 0, sStackDepth.length);
        sStack = views;
        sStackDepth = depths;
    }
}
