package org.ztmetro.keyguard;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * All lock-screen restyling.  No Xposed hook types are referenced here; logging
 * goes through {@link KLog}, so this class only depends on the Android framework.
 *
 * <p>Safety contract: every public entry point catches {@link Throwable}, logs at most
 * once per failure site and returns.  Nothing here replaces a view's behaviour, removes
 * a child it does not own, or adds a window: the stock keyguard keeps rendering with
 * edited typography/colour, and if any step fails the stock view is simply left alone.
 *
 * <p>Tokens (design/tokens/ztmetro.tokens.json):
 * <ul>
 *   <li>accent.base #0050EF — kept for reference;</li>
 *   <li>accent.light1 #3D7BFF — accent text on the black lock screen (contrast [E]);</li>
 *   <li>type.families.display (Semilight) -> Typeface weight 350 on API 28+; the
 *       ztmetro-font Magisk module maps that weight to Selawik-Semilight.ttf;</li>
 *   <li>type.ramp caption 13sp / tracking 0 for the date line (module spec: uppercase);</li>
 *   <li>metrics.page_margin_dp 16 for the fallback overlay's start inset.</li>
 * </ul>
 */
public final class MetroKeyguard {

    static final String TAG = "ztmetro-keyguard";

    /** color.neutrals.text_primary */
    public static final int WHITE = 0xFFFFFFFF;
    /** color.accent.base */
    static final int ACCENT = 0xFF0050EF;
    /** color.accent.light1 — accent for text drawn on the black lock screen. */
    static final int ACCENT_LIGHT = 0xFF3D7BFF;
    /** Module spec: 56sp light clock.  A16 stock large clock is 150dp (clocks dimens:21). */
    static final float CLOCK_SP = 56f;
    /** Module spec: 13sp date line. */
    static final float DATE_SP = 13f;
    static final float DATE_TRACKING = 0.08f;
    static final float INDICATION_TRACKING = 0.04f;

    /** KeyguardSliceProvider.KEYGUARD_DATE_URI — the date row's tag in KeyguardSliceView. */
    static final String DATE_URI = "content://com.android.systemui.keyguard/date";

    static final String OVERLAY_TAG = "ztmetro_keyguard_overlay";
    static final String OVERLAY_CLOCK_TAG = "ztmetro_keyguard_overlay_clock";
    static final String OVERLAY_DATE_TAG = "ztmetro_keyguard_overlay_date";

    /** Root views we already watch (SystemUI window views live for the process). */
    private static final WeakHashMap<View, Boolean> sWatched = new WeakHashMap<>();
    private static final WeakHashMap<View, View> sOverlays = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> sOverlayPending = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> sDateCapsApplied = new WeakHashMap<>();

    private static Typeface sSemilight;
    private static Typeface sRegular;
    private static Handler sHandler;
    /** Cached {@code @id/keyguard_slice_view}; -1 = not resolved yet, 0 = absent. */
    private static int sSliceViewId = -1;

    private MetroKeyguard() {
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Called once per {@code NotificationShadeWindowView.onAttachedToWindow}.  Finds the
     * keyguard container ({@code @id/keyguard_root_view}) and starts a layout observer so
     * clock views added later by the blueprint are seen.
     */
    public static void onShadeAttached(View shadeWindowView) {
        try {
            if (!(shadeWindowView instanceof ViewGroup)) {
                return;
            }
            int id = shadeWindowView.getResources()
                    .getIdentifier("keyguard_root_view", "id", "com.android.systemui");
            if (id == 0) {
                logOnce("root-id", "keyguard_root_view id not found");
                return;
            }
            View root = shadeWindowView.findViewById(id);
            if (root == null) {
                logOnce("root-null", "keyguard_root_view not inflated");
                return;
            }
            watch(root);
            applyAll(root);
        } catch (Throwable t) {
            logOnce("shade-attached", t);
        }
    }

    /** Called after {@code KeyguardClockViewBinder.bind(...)} — a blueprint (re)build. */
    public static void onClockBound(View root) {
        try {
            if (root != null) {
                applyAll(root);
            }
        } catch (Throwable t) {
            logOnce("clock-bind", t);
        }
    }

    /**
     * Called after {@code KeyguardSliceView.showSlice(...)}: the date TextView is a
     * {@code KeyguardSliceTextView} tagged with the date URI.  This is the only place the
     * date gets its full style (allCaps triggers a re-layout, so it is not done from the
     * layout observer).
     */
    public static void onSliceShown(View sliceView) {
        try {
            if (!(sliceView instanceof ViewGroup)) {
                return;
            }
            for (TextView tv : findDateTextViews((ViewGroup) sliceView)) {
                styleDateFull(tv);
            }
        } catch (Throwable t) {
            logOnce("slice-shown", t);
        }
    }

    /**
     * Called after {@code AnimatableClockView.refreshTime()}, i.e. once a minute and before
     * the first layout.  The clock digits are drawn by SystemUI's TextAnimator with its own
     * Paint, and its typeface cache is captured from the view's Layout in onMeasure — so the
     * typeface has to be in place before the animator is created.  The size is set here
     * (not from onMeasure): a TextView that is measured with an EXACTLY height clamps its
     * own text size to height/2, so re-applying a size during measure would fight that
     * clamp and re-request layout on every pass.
     */
    public static void onAnimatableClockRefresh(View clockView) {
        try {
            if (clockView instanceof TextView) {
                styleClock((TextView) clockView, true);
            }
        } catch (Throwable t) {
            logOnce("clock-refresh", t);
        }
    }

    /**
     * Called before {@code AnimatableClockView.onMeasure()}: typography and colour only, no
     * size (see {@link #onAnimatableClockRefresh(View)} for why).
     */
    public static void onAnimatableClockMeasure(View clockView) {
        try {
            if (clockView instanceof TextView) {
                styleClock((TextView) clockView, false);
            }
        } catch (Throwable t) {
            logOnce("clock-measure", t);
        }
    }

    /** Accent colour for a neutral indication (owner info, plain status text). */
    public static void tintIndication(View indicationView) {
        try {
            if (!(indicationView instanceof TextView)) {
                return;
            }
            TextView tv = (TextView) indicationView;
            if (tv.getCurrentTextColor() != ACCENT_LIGHT) {
                tv.setTextColor(ACCENT_LIGHT);
            }
            if (Math.abs(tv.getLetterSpacing() - INDICATION_TRACKING) > 0.001f) {
                tv.setLetterSpacing(INDICATION_TRACKING);
            }
            Typeface regular = regular();
            if (regular != null && !regular.equals(tv.getTypeface())) {
                tv.setTypeface(regular);
            }
        } catch (Throwable t) {
            logOnce("indication", t);
        }
    }

    // ------------------------------------------------------------------ layout pass

    private static void watch(final View root) {
        try {
            if (sWatched.containsKey(root)) {
                return;
            }
            sWatched.put(root, Boolean.TRUE);
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            applyAll(root);
                        }
                    });
        } catch (Throwable t) {
            logOnce("watch", t);
        }
    }

    /** Idempotent pass: compare before every setter so it cannot loop layout. */
    static void applyAll(View root) {
        try {
            if (!(root instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) root;
            List<TextView> clocks = new ArrayList<>();
            collectClockTextViews(group, clocks, 0);

            for (TextView tv : clocks) {
                String name = tv.getClass().getName();
                if (name.endsWith("AnimatableClockView")) {
                    // Handled by the refreshTime hook; touching it here would fight the
                    // TextAnimator's own style pass for no additional effect.
                    continue;
                }
                // Flex/digital clock (separate digit views) and third-party clock faces:
                // typography only, never the size — a per-digit size change would break
                // the face's own layout.
                styleClock(tv, false);
            }

            removeOverlayIfClockFound(group, !clocks.isEmpty());
            if (clocks.isEmpty()) {
                maybeAddOverlay(group);
            }

            styleDateFromLayout(group);
        } catch (Throwable t) {
            logOnce("apply-all", t);
        }
    }

    // ------------------------------------------------------------------ clock

    private static void collectClockTextViews(ViewGroup parent, List<TextView> out, int depth) {
        if (depth > 24) {
            return;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                String name = child.getClass().getName();
                // com.android.systemui.statusbar.policy.Clock is the status-bar clock; the
                // keyguard root does not carry one on the View path, but never touch it.
                boolean statusBarClock = name.endsWith(".Clock") || name.endsWith("TextClock");
                if (!statusBarClock && name.contains("Clock")) {
                    out.add((TextView) child);
                }
            }
            if (child instanceof ViewGroup) {
                collectClockTextViews((ViewGroup) child, out, depth + 1);
            }
        }
    }

    private static void styleClock(TextView tv, boolean withSize) {
        try {
            Typeface semilight = semilight();
            if (semilight != null && !semilight.equals(tv.getTypeface())) {
                tv.setTypeface(semilight);
            }
            if (tv.getLetterSpacing() != 0f) {
                tv.setLetterSpacing(0f);
            }
            if (withSize) {
                float target = sp(tv, CLOCK_SP);
                if (Math.abs(tv.getTextSize() - target) > 0.5f) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, target);
                }
            }
            if (tv.getCurrentTextColor() != WHITE) {
                tv.setTextColor(WHITE);
            }
        } catch (Throwable t) {
            logOnce("style-clock", t);
        }
    }

    // ------------------------------------------------------------------ date line

    private static List<TextView> findDateTextViews(ViewGroup sliceView) {
        List<TextView> out = new ArrayList<>();
        collectDateTextViews(sliceView, out, 0);
        return out;
    }

    private static void collectDateTextViews(ViewGroup parent, List<TextView> out, int depth) {
        if (depth > 8) {
            return;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                Object tag = child.getTag();
                if (tag != null && DATE_URI.equals(tag.toString())) {
                    out.add((TextView) child);
                }
            }
            if (child instanceof ViewGroup) {
                collectDateTextViews((ViewGroup) child, out, depth + 1);
            }
        }
    }

    private static void styleDateFull(TextView tv) {
        try {
            Typeface regular = regular();
            if (regular != null && !regular.equals(tv.getTypeface())) {
                tv.setTypeface(regular);
            }
            float target = sp(tv, DATE_SP);
            if (Math.abs(tv.getTextSize() - target) > 0.5f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, target);
            }
            if (Math.abs(tv.getLetterSpacing() - DATE_TRACKING) > 0.001f) {
                tv.setLetterSpacing(DATE_TRACKING);
            }
            if (!sDateCapsApplied.containsKey(tv)) {
                sDateCapsApplied.put(tv, Boolean.TRUE);
                // "Uppercase-ish": rendering-only caps, the underlying text (and TalkBack)
                // keeps its original casing.
                tv.setAllCaps(true);
            }
            if (tv.getCurrentTextColor() != WHITE) {
                tv.setTextColor(WHITE);
            }
        } catch (Throwable t) {
            logOnce("style-date", t);
        }
    }

    /** Colour-only refresh (no re-layout) run from the layout observer. */
    private static void styleDateFromLayout(ViewGroup root) {
        try {
            int id = sSliceViewId;
            if (id < 0) {
                // getIdentifier is not free; this runs on every layout pass.
                id = root.getResources()
                        .getIdentifier("keyguard_slice_view", "id", "com.android.systemui");
                sSliceViewId = id;
            }
            if (id == 0) {
                return;
            }
            View slice = root.findViewById(id);
            if (!(slice instanceof ViewGroup)) {
                return;
            }
            for (TextView tv : findDateTextViews((ViewGroup) slice)) {
                if (tv.getCurrentTextColor() != WHITE) {
                    tv.setTextColor(WHITE);
                }
            }
        } catch (Throwable t) {
            logOnce("date-layout", t);
        }
    }

    // ------------------------------------------------------------------ fallback overlay

    /** A clock that appeared after the fallback overlay must win: drop our overlay. */
    private static void removeOverlayIfClockFound(ViewGroup root, boolean clockFound) {
        try {
            if (!clockFound) {
                return;
            }
            View overlay = sOverlays.remove(root);
            if (overlay != null && overlay.getParent() == root) {
                root.removeView(overlay);
            }
        } catch (Throwable t) {
            logOnce("overlay-remove", t);
        }
    }

    private static void maybeAddOverlay(final ViewGroup root) {
        try {
            if (sOverlays.containsKey(root) || sOverlayPending.containsKey(root)
                    || !root.isAttachedToWindow()) {
                return;
            }
            // applyAll runs on every layout pass: keep exactly one pending probe per root.
            sOverlayPending.put(root, Boolean.TRUE);
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sOverlayPending.remove(root);
                        if (!root.isAttachedToWindow() || sOverlays.containsKey(root)) {
                            return;
                        }
                        // Still no clock after a full layout pass but the blueprint has
                        // already populated the container: the clock lives outside the
                        // View hierarchy (Compose scene / unknown plugin).  Add the Metro
                        // clock+date fallback instead of leaving the stock one untouched.
                        if (root.getChildCount() == 0) {
                            return;
                        }
                        List<TextView> clocks = new ArrayList<>();
                        collectClockTextViews(root, clocks, 0);
                        if (!clocks.isEmpty()) {
                            return;
                        }
                        addOverlay(root);
                    } catch (Throwable t) {
                        logOnce("overlay-post", t);
                    }
                }
            }, 1500L);
        } catch (Throwable t) {
            logOnce("overlay-schedule", t);
        }
    }

    private static void addOverlay(ViewGroup root) {
        Context context = root.getContext();
        LinearLayout overlay = new LinearLayout(context);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setTag(OVERLAY_TAG);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int start = Math.round(dp(root, 16f));
        int top = Math.round(dp(root, 64f));
        overlay.setPadding(start, top, start, 0);

        TextView clock = new TextView(context);
        clock.setTag(OVERLAY_CLOCK_TAG);
        clock.setIncludeFontPadding(false);
        clock.setSingleLine(true);
        clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        styleClock(clock, true);
        overlay.addView(clock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView date = new TextView(context);
        date.setTag(OVERLAY_DATE_TAG);
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        date.setAllCaps(true);
        date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        styleDateFull(date);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = Math.round(dp(root, 6f));
        overlay.addView(date, dateParams);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sOverlays.put(root, overlay);

        // The minute ticker stops by itself once the overlay leaves the window
        // (checked on every run), so no listener needs to cancel it.

        updateOverlayText(overlay);
        scheduleOverlayTick(overlay);
        logOnce("overlay-added", "no View clock found, Metro overlay added");
    }

    private static void scheduleOverlayTick(final View overlay) {
        Handler handler = handler();
        if (handler == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!overlay.isAttachedToWindow()) {
                        return;
                    }
                    updateOverlayText(overlay);
                    long now = System.currentTimeMillis();
                    long delay = 60000L - (now % 60000L) + 100L;
                    Handler h = handler();
                    if (h != null && overlay.isAttachedToWindow()) {
                        h.postDelayed(this, delay);
                    }
                } catch (Throwable t) {
                    logOnce("overlay-tick", t);
                }
            }
        }, 1000L);
    }

    private static void updateOverlayText(View overlay) {
        try {
            if (!(overlay instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) overlay;
            TextView clock = findTagged(group, OVERLAY_CLOCK_TAG);
            TextView date = findTagged(group, OVERLAY_DATE_TAG);
            Locale locale = Locale.getDefault();
            long now = System.currentTimeMillis();
            boolean is24 = DateFormat.is24HourFormat(overlay.getContext());
            if (clock != null) {
                String pattern = is24 ? "HH:mm" : "h:mm";
                clock.setText(new SimpleDateFormat(pattern, locale).format(new Date(now)));
            }
            if (date != null) {
                String pattern = DateFormat.getBestDateTimePattern(locale, "EEE d MMM");
                date.setText(new SimpleDateFormat(pattern, locale).format(new Date(now)));
            }
        } catch (Throwable t) {
            logOnce("overlay-text", t);
        }
    }

    private static TextView findTagged(ViewGroup group, String tag) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && tag.equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static Typeface semilight() {
        Typeface tf = sSemilight;
        if (tf != null) {
            return tf;
        }
        try {
            // Typeface.create(String, int, boolean) does not exist on android.jar:
            // the weight overload takes a base Typeface (API 28+).
            tf = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 350, false);
        } catch (Throwable ignored) {
            tf = null;
        }
        if (tf == null) {
            try {
                tf = Typeface.create("sans-serif-light", Typeface.NORMAL);
            } catch (Throwable ignored) {
                tf = null;
            }
        }
        sSemilight = tf;
        return tf;
    }

    private static Typeface regular() {
        Typeface tf = sRegular;
        if (tf != null) {
            return tf;
        }
        try {
            tf = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 400, false);
        } catch (Throwable ignored) {
            tf = null;
        }
        if (tf == null) {
            try {
                tf = Typeface.create("sans-serif", Typeface.NORMAL);
            } catch (Throwable ignored) {
                tf = null;
            }
        }
        sRegular = tf;
        return tf;
    }

    private static float sp(View view, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                view.getResources().getDisplayMetrics());
    }

    private static float dp(View view, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                view.getResources().getDisplayMetrics());
    }

    private static Handler handler() {
        try {
            if (sHandler == null) {
                sHandler = new Handler(Looper.getMainLooper());
            }
            return sHandler;
        } catch (Throwable t) {
            logOnce("handler", t);
            return null;
        }
    }

    static void logOnce(String site, Object detail) {
        KLog.once(TAG, site, detail);
    }
}
