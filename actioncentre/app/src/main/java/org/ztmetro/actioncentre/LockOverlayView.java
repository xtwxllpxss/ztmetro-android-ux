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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
 * 92 sp Light, the date is one 20 sp line (the requirement for this overlay; the
 * C shell uses two 30 sp lines), the glyph row is 26 dp glyph + count, the
 * background is pure black, square, no elevation, no corners. The clock sits in
 * the lower third, where Windows Phone puts it, instead of the Material top
 * centre.
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
    private final TextView clockView;
    private final TextView dateView;
    private final LinearLayout countsRow;
    private final TextView hintView;
    private final LinearLayout column;

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

        clockView = new TextView(context);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setTextColor(WHITE);
        clockView.setIncludeFontPadding(false);
        clockView.setSingleLine(true);
        clockView.setGravity(Gravity.START);
        clockView.setText("00:00");

        dateView = new TextView(context);
        // mockup: two 30 sp Semilight lines (weekday, then "d MMMM"), 36 dp
        // line height, under the 92 sp clock (ztmetro.c:5930-5945).
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        dateView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dateView.setTextColor(WHITE);
        dateView.setIncludeFontPadding(false);
        dateView.setSingleLine(false);
        dateView.setGravity(Gravity.START);
        // Android's Open Sans box is 1.36 em; 0.882 x 40.8 dp = the mockup's
        // 36 dp line, so the two-line block is 72 dp as drawn.
        dateView.setLineSpacing(0f, 0.882f);

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

        column.addView(clockView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = 0;
        column.addView(dateView, dateParams);

        LinearLayout.LayoutParams countsParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        countsParams.topMargin = dp(22);
        column.addView(countsRow, countsParams);

        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(30);
        column.addView(hintView, hintParams);
    }

    /** Current wall clock, 24 h or the system 12 h preference. */
    void refreshClock() {
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            boolean hour24 = android.text.format.DateFormat.is24HourFormat(getContext());
            int hour = hour24 ? now.get(java.util.Calendar.HOUR_OF_DAY)
                    : now.get(java.util.Calendar.HOUR);
            if (!hour24 && hour == 0) {
                hour = 12;
            }
            int minute = now.get(java.util.Calendar.MINUTE);
            String time = hour24
                    ? String.format(Locale.US, "%02d:%02d", hour, minute)
                    : String.format(Locale.US, "%d:%02d", hour, minute);
            SpannableStringBuilder text = new SpannableStringBuilder(time);
            if (!hour24) {
                int start = text.length() + 1;
                text.append(' ').append(now.get(java.util.Calendar.AM_PM)
                        == java.util.Calendar.AM ? "AM" : "PM");
                text.setSpan(new RelativeSizeSpan(0.26f), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(WHITE_80), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            clockView.setText(text);
            Date now2 = new Date();
            dateView.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(now2)
                    + "\n" + new SimpleDateFormat("d MMMM", Locale.getDefault()).format(now2));
        } catch (Throwable ignored) {
            // A failed clock refresh must never take the service down.
        }
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

    private int dp(int value) {
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
