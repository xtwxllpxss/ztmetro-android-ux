package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The clock/date block of the lock overlay, kept in its own class on purpose:
 * this is the slot a future custom PIN pad replaces. {@link LockOverlayView}
 * owns the black surface, the notification row and the hint; this class owns
 * the 92 sp Light clock and the 20 sp date line, and nothing else.
 *
 * <p>Future hook: {@code LockOverlayView.setAuthPanel(View)} removes this view
 * from the column and inserts a PIN pad in the same place. The overlay's main
 * window is {@code NOT_TOUCHABLE} today, so a real PIN pad also needs the
 * touchable-window change documented in {@code evidence/lock-overlay/README.md};
 * the layout slot is what this class guarantees.
 */
final class LockClockPanel extends LinearLayout {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int WHITE_80 = 0xCCFFFFFF;

    private final TextView clockView;
    private final TextView dateView;

    LockClockPanel(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.START);

        clockView = new TextView(context);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setTextColor(WHITE);
        clockView.setIncludeFontPadding(false);
        clockView.setSingleLine(true);
        clockView.setGravity(Gravity.START);
        clockView.setText("00:00");
        addView(clockView, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        dateView = new TextView(context);
        // Two 30 sp Semilight lines (weekday, then "d MMMM") under the 92 sp
        // clock, as the mockup draws them (ztmetro.c:5930-5945).
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        dateView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dateView.setTextColor(WHITE);
        dateView.setIncludeFontPadding(false);
        dateView.setSingleLine(false);
        dateView.setGravity(Gravity.START);
        // Android's Open Sans box is 1.36 em; 0.882 x 40.8 dp is the mockup's
        // 36 dp line, so the two-line block is 72 dp as drawn.
        dateView.setLineSpacing(0f, 0.882f);
        addView(dateView, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
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

    private int dp(int value) {
        return Math.max(1, Math.round(value
                * getResources().getDisplayMetrics().density));
    }
}
