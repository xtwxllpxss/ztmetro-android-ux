package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The pixels of the ztMetro lock screen: a flat black surface with the WP lock
 * clock block at the bottom, one row of per-app notification glyphs with counts,
 * and a short swipe-up hint. Nothing here is tappable; the touch strip that
 * reveals the stock keyguard lives in {@link LockOverlayController}.
 *
 * <p>Metrics follow {@code design/components/lock-screen.md} and
 * {@code design/tokens/ztmetro.tokens.json}: the clock is {@code display_large}
 * 92 sp Light, the date is two 30 sp lines, the glyph row is 26 dp glyph + count,
 * the background is pure black, square, no elevation, no corners. The clock sits
 * in the lower third, where Windows Phone puts it, instead of the Material top
 * centre.
 *
 * <p>The clock/date block lives in its own {@link LockClockPanel}. A custom
 * authentication panel (the reference {@link LockPinPad}) replaces that block
 * through {@link #setAuthPanel(View)}; the view itself stays untouched and
 * non-interactive, and the chip gains a separate touchable window in
 * {@link LockOverlayController}. The panel is UI only: it never verifies the
 * PIN, the system credential screen does.
 *
 * <p>The view is deliberately excluded from the accessibility tree
 * ({@code NO_HIDE_DESCENDANTS}) so TalkBack keeps seeing the stock keyguard
 * underneath and can still unlock the phone.
 */
final class LockOverlayView extends FrameLayout {
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int WHITE_80 = 0xCCFFFFFF;
    private static final int WHITE_55 = 0x8CFFFFFF;
    private static final int MAX_APPS = 5;
    private static final int GLYPH_DP = 26;
    private static final int COUNT_SIZE_SP = 22;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService iconExecutor;
    private final LockClockPanel clockPanel;
    private final LinearLayout countsRow;
    private final TextView hintView;
    private final LinearLayout column;

    /** Custom auth panel currently in the clock slot, or null for the clock. */
    private View authPanel;

    private volatile int refreshGeneration;

    LockOverlayView(Context context) {
        super(context);
        // mockups/screens/lock.html + ztmetro.c:6029-6035: black until 25 % from
        // the top, then a fade to accent x 0.5 at the bottom edge. v3 shipped
        // flat black, which lost the lock screen's only colour.
        GradientDrawable fade = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF002878, BLACK, BLACK});
        fade.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        fade.setColors(new int[]{0xFF002878, BLACK, BLACK},
                new float[]{0f, 0.75f, 1f});
        setBackground(fade);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        iconExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ztmetro-lock-icons");
                thread.setDaemon(true);
                return thread;
            }
        });

        clockPanel = new LockClockPanel(context);

        countsRow = new LinearLayout(context);
        countsRow.setOrientation(LinearLayout.HORIZONTAL);
        countsRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        countsRow.setMinimumHeight(dp(GLYPH_DP));
        // mockup lock.html: the glyph row starts at x 22, 8 dp in from the
        // 14 dp clock margin, and the chips are 22 dp apart.
        countsRow.setPadding(dp(8), 0, 0, 0);
        countsRow.setVisibility(View.GONE);

        hintView = new TextView(context);
        hintView.setText(R.string.lock_swipe_hint);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        hintView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hintView.setTextColor(WHITE_55);
        hintView.setLetterSpacing(0.08f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setIncludeFontPadding(false);
        hintView.setSingleLine(true);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.START);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        columnParams.leftMargin = dp(14);
        columnParams.rightMargin = dp(14);
        columnParams.bottomMargin = dp(14);
        addView(column, columnParams);
        // On a lock screen that still shows a navigation bar, the hint must not
        // sit under it. The overlay opts out of inset fitting, so read the
        // inset here and lift the column by the bar height (capped at 48 dp).
        setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                try {
                    int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
                    int target = dp(14) + Math.min(dp(48), bottom);
                    FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) column.getLayoutParams();
                    if (params != null && params.bottomMargin != target) {
                        params.bottomMargin = target;
                        column.setLayoutParams(params);
                    }
                } catch (Throwable ignored) {
                }
                return insets;
            }
        });

        column.addView(clockPanel, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams countsParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        // 18.5 dp, not the mockup's 22: Android's Open Sans line box is 1.36 em
        // (36 dp where CSS says 36 dp only at multiplier .882), so the smaller
        // gap puts the count row's ink at y 57..83 from the bottom, the
        // mockup's 56..82. Model checked against the measured v3 screenshot.
        countsParams.topMargin = dp(18.5f);
        column.addView(countsRow, countsParams);

        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        // 25.5 dp for the same reason: hint ink 17..24 from the bottom (mockup
        // 17.5..24) and a 32 dp ink gap to the counts row.
        hintParams.topMargin = dp(25.5f);
        column.addView(hintView, hintParams);
    }

    /** Current wall clock, 24 h or the system 12 h preference. */
    void refreshClock() {
        clockPanel.refreshClock();
    }

    /**
     * Auth panel hook: replace the clock/date block with {@code panel} in the
     * same column slot, or restore the clock when {@code panel} is null.
     *
     * <p>The panel is the visual half only. The overlay's main window stays
     * {@code NOT_TOUCHABLE}; {@link LockOverlayController} adds a second,
     * touchable accessibility window sized exactly to this view's on-screen
     * bounds and forwards the touches. The notification row and the swipe-up
     * hint below the slot are untouched.
     *
     * <p>Fail-safe: a bad panel (not a view, detached, throwing layout) is
     * rejected and the clock stays; nothing here can remove the swipe strip.
     */
    void setAuthPanel(View panel) {
        try {
            if (panel == authPanel) {
                return;
            }
            int index = authPanel != null ? column.indexOfChild(authPanel)
                    : column.indexOfChild(clockPanel);
            if (index < 0) {
                index = 0;
            }
            if (authPanel != null) {
                column.removeViewAt(index);
                authPanel = null;
            }
            if (panel == null) {
                if (clockPanel.getParent() == null) {
                    column.addView(clockPanel, Math.min(index, column.getChildCount()),
                            new LinearLayout.LayoutParams(
                                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                }
                return;
            }
            // The panel must not already live in another window's hierarchy.
            if (panel.getParent() instanceof ViewGroup) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
            if (clockPanel.getParent() != null) {
                column.removeView(clockPanel);
            }
            column.addView(panel, Math.min(index, column.getChildCount()),
                    new LinearLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            authPanel = panel;
        } catch (Throwable ignored) {
            // Keep whatever the column still holds (the clock is the fallback).
            authPanel = null;
        }
    }

    /** The current auth panel, or null while the clock occupies the slot. */
    View getAuthPanel() {
        return authPanel;
    }

    /** The view in the clock slot: the clock panel or the installed auth panel. */
    View getClockArea() {
        return authPanel != null ? authPanel : clockPanel;
    }

    /**
     * One glyph + count per app, newest first, at most five apps, as the C lock
     * screen does. The icon is the app's own launcher icon because the
     * notification small icon is not kept by {@link NotificationRepository};
     * while it loads, a square outline with the app's initial is shown.
     */
    void refreshNotifications() {
        try {
            final int generation = ++refreshGeneration;
            countsRow.removeAllViews();
            if (!NotificationRepository.isConnected()) {
                countsRow.setVisibility(View.GONE);
                return;
            }
            List<Notif> items = NotificationRepository.snapshot();
            LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
            LinkedHashMap<String, String> labels = new LinkedHashMap<>();
            for (Notif item : items) {
                if (item == null || item.packageName == null) {
                    continue;
                }
                int[] count = counts.get(item.packageName);
                if (count == null) {
                    if (counts.size() >= MAX_APPS) {
                        continue;
                    }
                    counts.put(item.packageName, new int[]{1});
                    labels.put(item.packageName,
                            item.appLabel == null || item.appLabel.length() == 0
                                    ? item.packageName : item.appLabel);
                } else {
                    count[0]++;
                }
            }
            if (counts.isEmpty()) {
                countsRow.setVisibility(View.GONE);
                return;
            }

            final List<String> packages = new ArrayList<>();
            final List<ImageView> icons = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : counts.entrySet()) {
                String packageName = entry.getKey();
                String label = labels.get(packageName);

                LinearLayout chip = new LinearLayout(getContext());
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);

                ImageView icon = new ImageView(getContext());
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                        dp(GLYPH_DP), dp(GLYPH_DP));
                icon.setLayoutParams(iconParams);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageDrawable(new LetterIcon(label));
                icon.setContentDescription(label);

                TextView countView = new TextView(getContext());
                countView.setText(String.valueOf(entry.getValue()[0]));
                countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, COUNT_SIZE_SP);
                countView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                countView.setTextColor(WHITE);
                countView.setIncludeFontPadding(false);
                countView.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                countParams.leftMargin = dp(6);
                countView.setLayoutParams(countParams);

                chip.addView(icon);
                chip.addView(countView);

                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                chipParams.rightMargin = dp(22);
                countsRow.addView(chip, chipParams);
                packages.add(packageName);
                icons.add(icon);
            }
            countsRow.setVisibility(View.VISIBLE);
            loadIcons(generation, packages, icons);
        } catch (Throwable ignored) {
            // Keep the clock and date; an empty count row is not a crash.
        }
    }

    private void loadIcons(final int generation, final List<String> packages,
                           final List<ImageView> targets) {
        iconExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable[] loaded = new Drawable[packages.size()];
                for (int i = 0; i < packages.size(); i++) {
                    loaded[i] = loadAppIcon(packages.get(i));
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != refreshGeneration) {
                            return;
                        }
                        for (int i = 0; i < targets.size() && i < loaded.length; i++) {
                            if (loaded[i] != null) {
                                targets.get(i).setImageDrawable(loaded[i]);
                            }
                        }
                    }
                });
            }
        });
    }

    private Drawable loadAppIcon(String packageName) {
        try {
            return getContext().getPackageManager().getApplicationIcon(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Stop the icon worker; the view is kept for the next unlock. */
    void release() {
        refreshGeneration++;
        try {
            iconExecutor.shutdownNow();
        } catch (Throwable ignored) {
        }
        main.removeCallbacksAndMessages(null);
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    /** Square outline with the app's initial, the C shell's fallback glyph. */
    private final class LetterIcon extends Drawable {
        private final Paint stroke = new Paint();
        private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letter;

        LetterIcon(String label) {
            String first = label == null || label.length() == 0
                    ? "?" : label.substring(0, 1).toUpperCase(Locale.getDefault());
            letter = first;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(WHITE_80);
            letterPaint.setColor(WHITE);
            letterPaint.setTextAlign(Paint.Align.CENTER);
            letterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }
            canvas.drawRect(1, 1, bounds.width() - 1, bounds.height() - 1, stroke);
            letterPaint.setTextSize(bounds.height() * 0.5f);
            Paint.FontMetrics metrics = letterPaint.getFontMetrics();
            float baseline = bounds.height() / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(letter, bounds.width() / 2f, baseline, letterPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            letterPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
