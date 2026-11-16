package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
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
    /**
     * Recycler views seen in the last pass; their rows are styled as they attach.
     * Weak on purpose: a strong list kept every RecyclerView of every Settings
     * screen alive in this process, which is exactly the kind of retention a
     * 2 GB phone cannot afford.
     */
    private static final java.util.ArrayList<java.lang.ref.WeakReference<View>> sRecyclers =
            new java.util.ArrayList<>();
    /** CardView reflection targets, resolved once per class (not once per card). */
    private static final java.util.HashMap<Class<?>, java.lang.reflect.Method[]> CARD_METHODS =
            new java.util.HashMap<>();
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
    private static int sDialogTitleSizeId;
    private static int sAccentColorId;

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
    private static int sAccentColor = FALLBACK_ACCENT;
    private static float sDialogTitlePx;
    private static boolean sConfigured;

    private static int sVisited;
    private static int sApplies;

    /*
     * Per-surface proof counters, reset per pass and printed in the one log line:
     * rows / switch rows / entity-header rows (app info) / segmented groups.  They are how
     * the log shows which pass handled which surface without a per-view line.
     */
    private static int sRows;
    private static int sSwitchRows;
    private static int sEntityRows;
    private static int sSegmentedGroups;
    private static String sSurface = "settings";

    /* Row scratch: at most one title + four summary-ish lines per row; reused, no alloc. */
    private static final int MAX_SUMMARIES = 4;
    private static final TextView[] sSummaries = new TextView[MAX_SUMMARIES];
    private static int sSummaryCount;
    private static TextView sTitleView;
    private static CompoundButton sSwitchView;
    private static boolean sSelectableRow;
    private static boolean sEntityRow;

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
        run(activity, homepage, passNumber, passTotal, phase, surfaceOf(activity));
    }

    /**
     * Same single walk, tagged with the surface it is handling, so the proof line says both
     * which pass and which surface: {@code pass 1/2 attach [settings]: 934 views, ...}.
     */
    static void run(Activity activity, boolean homepage, int passNumber, int passTotal,
                    String phase, String surface) {
        if (activity == null) {
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        sSurface = surface == null ? "settings" : surface;
        configure(activity);
        MetroTinter.configure(sTrackColor, sThumbColor, sLabelColor);
        RowType.configure(sLabelPx, sValuePx, sLabelColor, sValueColor);

        sVisited = 0;
        sApplies = 0;
        sRows = 0;
        sSwitchRows = 0;
        sEntityRows = 0;
        sSegmentedGroups = 0;
        long started = SystemClock.uptimeMillis();
        if (passNumber == 1) {
            sRecyclers.clear();
        }
        walk(root);
        if (passNumber == passTotal) {
            scheduleRowPass(root);
        }
        long elapsed = SystemClock.uptimeMillis() - started;
        Log.i(TAG, "pass " + passNumber + "/" + passTotal + " " + phase + " [" + sSurface
                + "]: " + sVisited + " views, " + sApplies + " applies, " + elapsed + " ms"
                + " (rows=" + sRows + ", switch=" + sSwitchRows + ", entity=" + sEntityRows
                + ", segmented=" + sSegmentedGroups + ")"
                + (homepage ? " (home)" : ""));
        // One line per surface that the pass actually handled (no line when absent).
        if (sSwitchRows > 0) {
            Log.i(TAG, "surface switch [" + sSurface + "]: " + sSwitchRows
                    + " row(s) with the ВКЛ/ВЫКЛ value line");
        }
        if (sEntityRows > 0) {
            Log.i(TAG, "surface appinfo [" + sSurface + "]: " + sEntityRows
                    + " entity header row(s) two-line, icon hidden");
        }
        if (sSegmentedGroups > 0) {
            Log.i(TAG, "surface segmented [" + sSurface + "]: " + sSegmentedGroups
                    + " group(s) square/black/accent");
        }
        if ("search".equals(sSurface)) {
            Log.i(TAG, "surface search: rows styled by the ScreenPass row pass/hierarchy listener");
        }
    }

    private static String surfaceOf(Activity activity) {
        if (activity == null) {
            return "settings";
        }
        String name = activity.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return (name.contains("search") || name.contains("intelligence")) ? "search" : "settings";
    }

    /**
     * Exactly ONE post-layout row pass, and then event-driven styling.
     *
     * <p>Pass 2 used to run this twice (250 ms and 700 ms) on every screen open. It is now
     * a one-shot {@link ViewTreeObserver.OnGlobalLayoutListener}: it fires after the first
     * full layout traversal that follows the attach pass, when the RecyclerView already has
     * its first rows. Rows that attach later (scrolling, a slow fragment) are styled by the
     * per-recycler hierarchy listener installed in {@link #trackRecycler}, so no timer is
     * needed to catch them either.
     */
    private static void scheduleRowPass(final View root) {
        if (root == null) {
            return;
        }
        final ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            // Detached root (unusual): one plain post instead of the observer.
            root.post(new Runnable() {
                @Override
                public void run() {
                    styleRecyclerRows();
                }
            });
            return;
        }
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver current = root.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnGlobalLayoutListener(this);
                }
                styleRecyclerRows();
            }
        });
    }

    /** The one row pass; also the sink for rows added by the hierarchy listener. */
    private static void styleRecyclerRows() {
        int styled = 0;
        int live = 0;
        for (int i = 0; i < sRecyclers.size(); i++) {
            java.lang.ref.WeakReference<View> ref = sRecyclers.get(i);
            View v = ref == null ? null : ref.get();
            if (!(v instanceof ViewGroup)) {
                continue;
            }
            live++;
            ViewGroup g = (ViewGroup) v;
            for (int c = 0; c < g.getChildCount(); c++) {
                View child = g.getChildAt(c);
                if (child instanceof ViewGroup && styleRow((ViewGroup) child)) {
                    styled++;
                }
            }
        }
        Log.i(TAG, "row pass [" + sSurface + "]: " + styled + " recycler rows styled, "
                + live + " recycler(s)");
    }

    /** Styles one recycler row if it looks like a preference row. */
    static boolean styleRow(ViewGroup row) {
        if (Views.titleCount(row, 3) == 1 && isRowCandidate(row)) {
            lightRow(row);
            return true;
        }
        return false;
    }

    /**
     * Remembers a recycler and styles rows that attach after the single row pass.
     *
     * <p>{@code OnHierarchyChangeListener} is framework API (no AndroidX dependency) and
     * RecyclerView does not use it itself. The style is posted, not applied inline: a child
     * is added during the parent's layout, and writing padding/margins there would be a
     * {@code requestLayout()} inside layout.
     */
    private static void trackRecycler(ViewGroup recycler) {
        for (int i = 0; i < sRecyclers.size(); i++) {
            java.lang.ref.WeakReference<View> ref = sRecyclers.get(i);
            if (ref != null && ref.get() == recycler) {
                return;
            }
        }
        sRecyclers.add(new java.lang.ref.WeakReference<View>(recycler));
        recycler.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, final View child) {
                child.post(new Runnable() {
                    @Override
                    public void run() {
                        if (child instanceof ViewGroup) {
                            styleRow((ViewGroup) child);
                        }
                    }
                });
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    /**
     * Row styling for rows that attach after the one row pass (RecyclerView recycling).
     * Same collector/finisher as {@link #applyRow}, so a late row gets exactly the same
     * two-line look, entity-header order and switch state line as a row seen by the walk.
     * FlatRows' per-view style cache keeps the layout-param writes to once per row.
     */
    private static void lightRow(ViewGroup row) {
        prepareRow(row);
        flattenCard(row);
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(row);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            View view = queue.poll();
            if (view instanceof CompoundButton) {
                collectRowButton((CompoundButton) view);
            } else if (view instanceof TextView) {
                collectRowText((TextView) view);
            } else if (view instanceof ImageView) {
                RowIcons.hide(view);
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                if (sEntityRow && "entity_header_content".equals(Views.idName(group))
                        && alignEntityContent(group)) {
                    sApplies++;
                }
                flattenCard(group);
                for (int i = 0; i < group.getChildCount() && i < 12; i++) {
                    View child = group.getChildAt(i);
                    if (!Views.isOurs(child)) {
                        queue.add(child);
                    }
                }
            }
        }
        finishRow(row);
        // one line per row is too noisy; the aggregate line is logged by styleRecyclerRows
    }

    /**
     * CardView/MaterialCardView: black fill, zero radius. The two reflective methods are
     * looked up once per class ({@link #CARD_METHODS}, this used to be a {@code getMethod}
     * per card per pass) and the card is flattened at most once per view (style tag), so a
     * second pass does not re-write its background.
     */
    static boolean flattenCard(View view) {
        boolean changed = false;
        String name = view.getClass().getSimpleName();
        if (name.contains("Card") && !FlatRows.isApplied(view, FlatRows.BIT_CARD_FLAT)) {
            java.lang.reflect.Method[] methods = cardMethods(view.getClass());
            try {
                if (methods[0] != null) {
                    methods[0].invoke(view, 0xFF000000);
                }
                if (methods[1] != null) {
                    methods[1].invoke(view, 0f);
                }
            } catch (Throwable ignored) {
                // plain view: fall through to the background colour
            }
            FlatRows.markApplied(view, FlatRows.BIT_CARD_FLAT);
            changed = true;
        }
        if (!(view.getBackground() instanceof ColorDrawable)
                || ((ColorDrawable) view.getBackground()).getColor() != Color.BLACK) {
            view.setBackgroundColor(0xFF000000);
            changed = true;
        }
        return changed;
    }

    private static java.lang.reflect.Method[] cardMethods(Class<?> type) {
        java.lang.reflect.Method[] cached = CARD_METHODS.get(type);
        if (cached != null) {
            return cached;
        }
        java.lang.reflect.Method fill = null;
        java.lang.reflect.Method radius = null;
        try {
            fill = type.getMethod("setCardBackgroundColor", int.class);
        } catch (Throwable ignored) {
            // not a CardView
        }
        try {
            radius = type.getMethod("setRadius", float.class);
        } catch (Throwable ignored) {
            // not a CardView
        }
        java.lang.reflect.Method[] methods = {fill, radius};
        CARD_METHODS.put(type, methods);
        return methods;
    }

    // ---------------------------------------------------------------- configuration

    /**
     * Reads the generated module resources once per pass.  Sizes are read as pixels from
     * the generated sp/dp dimens (font-scale aware, no TypedArray); colours are ints.
     * The resource ids themselves are resolved only once per process.
     */
    private static void configure(Context host) {
        Resources hostResources = host.getResources();
        DisplayMetrics metrics = hostResources.getDisplayMetrics();
        float density = metrics.density;

        sPageMargin = Math.round(24f * density);
        sRowPadV = Math.round(4f * density);
        sRowMaxHeight = Math.round(220f * density);
        sGroupGapLimit = Math.round(40f * density);
        sRowInsetLimit = Math.round(96f * density);
        sGroupHeightLimit = metrics.heightPixels * 3 / 4;
        sLabelPx = 15f * metrics.scaledDensity;
        sValuePx = 22f * metrics.scaledDensity;
        sDialogTitlePx = 20f * metrics.scaledDensity;
        sLabelColor = FALLBACK_LABEL;
        sValueColor = FALLBACK_VALUE;
        sTrackColor = FALLBACK_ACCENT;
        sThumbColor = FALLBACK_VALUE;
        sAccentColor = FALLBACK_ACCENT;

        Resources module = null;
        try {
            module = HeaderResources.get(host);
        } catch (Throwable ignored) {
            // header would already be absent; literals above keep the rows styled
        }
        if (module == null) {
            sConfigured = true;
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
            sAccentColorId = id(module, "metro_accent", "color");
            sDialogTitleSizeId = id(module, "metro_dialog_title_size", "dimen");
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
        if (sAccentColorId != 0) {
            sAccentColor = module.getColor(sAccentColorId);
        }
        if (sDialogTitleSizeId != 0) {
            sDialogTitlePx = module.getDimension(sDialogTitleSizeId);
        }
        sConfigured = true;
        // Resolves the style-tag key once and invalidates cached row styles when
        // the page margin or the row padding changes (density / font scale).
        FlatRows.configure(module, sPageMargin, sRowPadV);
    }

    /** Configures on first use (switch/dialog hooks may run before the first pass). */
    static void ensureConfigured(Context context) {
        if (!sConfigured && context != null) {
            configure(context);
        }
    }

    static int pageMargin() {
        return sPageMargin;
    }

    static int rowPadV() {
        return sRowPadV;
    }

    static float density() {
        return sPageMargin / 24f;
    }

    static int accentColor() {
        return sAccentColor;
    }

    static int valueColor() {
        return sValueColor;
    }

    static int labelColor() {
        return sLabelColor;
    }

    static float labelPx() {
        return sLabelPx;
    }

    static float dialogTitlePx() {
        return sDialogTitlePx;
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
                    && view.getClass().getSimpleName().endsWith("RecyclerView")) {
                trackRecycler((ViewGroup) view);
            }
            if (Views.isOurs(view)) {
                continue;
            }
            if (!(view instanceof ViewGroup)) {
                continue;   // a lone TextView/ImageView outside a row is stock chrome
            }
            ViewGroup group = (ViewGroup) view;
            if (Views.isSegmentedGroup(group)) {
                // Segmented buttons (and radio-like groups): square, black, accent checked.
                // Styled inline; the children are not walked again.
                if (SegmentedStyler.apply(group)) {
                    sApplies++;
                }
                sSegmentedGroups++;
                continue;
            }
            if ("search_bar".equals(Views.idName(view)) && flattenCard(group)) {
                sApplies++;
            }
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
     * Visits a row's subtree once: collects title/summary/switch/radio/entity lines,
     * hides icons/chevrons and tints the widget, then lets {@link #finishRow} write the
     * two-line look.  The row subtree is visited inline and never walked again.
     */
    private static void applyRow(ViewGroup row) {
        prepareRow(row);
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
                collectRowButton((CompoundButton) view);
                continue;   // switch/radio internals are not row content
            }
            if (view instanceof TextView) {
                collectRowText((TextView) view);
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
                if (sEntityRow && "entity_header_content".equals(Views.idName(group))
                        && alignEntityContent(group)) {
                    sApplies++;
                }
                int count = group.getChildCount();
                top = growRow(top + count);
                for (int i = count - 1; i >= 0; i--) {
                    sRowStack[top] = group.getChildAt(i);
                    top++;
                }
            }
        }
        finishRow(row);
    }

    /** Resets the row scratch state (reused, no allocation per row). */
    private static void prepareRow(ViewGroup row) {
        sTitleView = null;
        sSummaryCount = 0;
        sSwitchView = null;
        sSelectableRow = false;
        sEntityRow = Views.isEntityHeader(row);
    }

    /**
     * Stores one text line.  An unused summary is GONE and is not the value line; a
     * breadcrumb (search result) is a third line and is removed so the row stays two-line.
     */
    private static void collectRowText(TextView text) {
        if (Views.isTitle(text)) {
            if (sTitleView == null) {
                sTitleView = text;
            }
            return;
        }
        if (Views.isSummary(text)) {
            if (text.getVisibility() == View.GONE || sSummaryCount >= MAX_SUMMARIES) {
                return;
            }
            sSummaries[sSummaryCount++] = text;
            return;
        }
        if (Views.isBreadcrumb(text) && text.getVisibility() != View.GONE) {
            text.setVisibility(View.GONE);
            sApplies++;
        }
    }

    /** Switch -> cobalt track/white thumb; radio/checkbox -> accent dot, grey when off. */
    private static void collectRowButton(CompoundButton button) {
        if (Views.isSwitch(button)) {
            if (sSwitchView == null) {
                sSwitchView = button;
            }
        } else if (Views.isSelectable(button)) {
            sSelectableRow = true;
        }
        if (MetroTinter.tint(button)) {
            sApplies++;
        }
    }

    /**
     * Writes the ztmetro two-line look for one row and flattens it.
     *
     * <ul>
     *   <li><b>entity header</b> (app info): name is the 22 sp white value, version/install
     *       type are 15 sp grey labels below it; no centred icon block;</li>
     *   <li><b>switch row</b>: grey 15 sp label + the live ВКЛ/ВЫКЛ value line;
     *       {@code TwoStateRow} keeps it in sync after the user toggles;</li>
     *   <li><b>radio/checkbox row</b>: white 22 sp option label, summary as a grey note;</li>
     *   <li><b>plain row</b>: grey label above white value, or the title alone as value.</li>
     * </ul>
     */
    private static void finishRow(ViewGroup row) {
        sRows++;
        if (sEntityRow) {
            sEntityRows++;
            if (sTitleView != null) {
                if (RowType.styleValue(sTitleView)) {
                    sApplies++;
                }
                if (sTitleView.getGravity() != android.view.Gravity.START) {
                    sTitleView.setGravity(android.view.Gravity.START);
                    sApplies++;
                }
                if (sTitleView.getTextAlignment() != View.TEXT_ALIGNMENT_VIEW_START) {
                    sTitleView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                    sApplies++;
                }
            }
            for (int i = 0; i < sSummaryCount; i++) {
                if (RowType.styleLabel(sSummaries[i])) {
                    sApplies++;
                }
            }
        } else if (sSwitchView != null) {
            sSwitchRows++;
            if (sTitleView != null && RowType.styleLabel(sTitleView)) {
                sApplies++;
            }
            if (sSummaryCount > 0) {
                for (int i = 0; i < sSummaryCount; i++) {
                    if (TwoStateRow.applyState(sSwitchView, sSummaries[i])) {
                        sApplies++;
                    }
                }
            } else if (sTitleView != null && RowType.styleValue(sTitleView)) {
                // No stock summary line exists to hold the state: keep the row name as the
                // white value line instead of inventing a view inside Settings' layout.
                sApplies++;
            }
        } else if (sSelectableRow) {
            if (sTitleView != null && RowType.styleValue(sTitleView)) {
                sApplies++;
            }
            for (int i = 0; i < sSummaryCount; i++) {
                if (RowType.styleLabel(sSummaries[i])) {
                    sApplies++;
                }
            }
        } else if (sTitleView != null) {
            boolean hasValue = sSummaryCount > 0 && sSummaries[0].getText().length() > 0;
            if (hasValue && "search".equals(sSurface)) {
                // A search summary is a full description; let the 22 sp value wrap to two
                // lines like the about/sensors rows instead of ellipsizing at one line.
                for (int i = 0; i < sSummaryCount; i++) {
                    TextView value = sSummaries[i];
                    if (value.getMaxLines() != 2) {
                        value.setSingleLine(false);
                        value.setMaxLines(2);
                        value.setEllipsize(TextUtils.TruncateAt.END);
                        sApplies++;
                    }
                }
            }
            boolean changed = hasValue
                    ? RowType.styleLabel(sTitleView) : RowType.styleValue(sTitleView);
            if (changed) {
                sApplies++;
            }
            for (int i = 0; i < sSummaryCount; i++) {
                if (RowType.styleValue(sSummaries[i])) {
                    sApplies++;
                }
            }
        }
        if (FlatRows.flattenRow(row, sPageMargin, sRowPadV, sTitleView,
                sSwitchView != null, sRowInsetLimit)) {
            sApplies++;
        }
    }

    /**
     * Entity header content is centred by SettingsLib.  With the icon hidden the two lines
     * must start at the page margin like every other row.
     */
    private static boolean alignEntityContent(ViewGroup content) {
        boolean changed = false;
        ViewGroup.LayoutParams params = content.getLayoutParams();
        if (params instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams relative = (RelativeLayout.LayoutParams) params;
            if (relative.getRules()[RelativeLayout.CENTER_HORIZONTAL] != 0) {
                relative.removeRule(RelativeLayout.CENTER_HORIZONTAL);
                changed = true;
            }
            if (relative.getRules()[RelativeLayout.ALIGN_PARENT_START] == 0) {
                relative.addRule(RelativeLayout.ALIGN_PARENT_START);
                changed = true;
            }
            if (relative.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                relative.width = ViewGroup.LayoutParams.MATCH_PARENT;
                changed = true;
            }
        }
        if (content instanceof LinearLayout
                && ((LinearLayout) content).getGravity() != android.view.Gravity.START) {
            ((LinearLayout) content).setGravity(android.view.Gravity.START);
            changed = true;
        }
        if (changed) {
            content.setLayoutParams(params);
        }
        return changed;
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
