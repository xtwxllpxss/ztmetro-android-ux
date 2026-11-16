package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The ztMetro status strip, drawn by us and laid over the stock status bar.
 *
 * <p>This is not the in-panel {@link StatusStripView}: that one shows the
 * operator/date/battery percentage inside the Action Centre. This view is the
 * 28 dp strip at the very top of the screen, and it follows ztmetro: tiny white
 * glyphs on the left (battery, Wi-Fi, cellular signal) and the clock on the
 * right, the reverse of the Android layout. All ink is white; there is no green
 * or red anywhere, including the battery fill.
 *
 * <p>The view does not read the system itself. {@link StatusStripService}
 * pushes battery / Wi-Fi / signal / airplane state and calls
 * {@link #refreshClock()} from the ACTION_TIME_TICK receiver, so there is no
 * polling loop and no wakelock in this class.
 */
final class StatusStripOverlayView extends View {
    /** ztmetro.tokens.json metrics.status_strip_height_dp (ztmetro.c:128 STATUS_H 28). */
    static final int HEIGHT_DP = 28;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int WHITE_70 = 0xB3FFFFFF;
    private static final int WHITE_50 = 0x80FFFFFF;
    private static final int WHITE_30 = 0x4DFFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final RectF oval = new RectF();

    private int batteryLevel = -1;
    private boolean charging;
    private boolean wifiEnabled;
    private boolean wifiConnected;
    private boolean simReady;
    private int signalLevel = -1;
    private boolean cellularActive;
    private boolean airplane;
    private String clockText;

    StatusStripOverlayView(Context context) {
        super(context);
        setBackgroundColor(U.BLACK);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /*
     * Every setter is a no-op when the value it would draw is already on screen.
     * ACTION_BATTERY_CHANGED and NetworkCallback.onCapabilitiesChanged arrive far
     * more often than the glyphs actually change (temperature/voltage on battery,
     * bandwidth/strength on network); before pass 2 each of those posted a frame.
     * Guarding here means "no change -> no invalidate", whatever the caller does.
     */

    void setBattery(int levelPercent, boolean charging) {
        if (batteryLevel == levelPercent && this.charging == charging) {
            return;
        }
        batteryLevel = levelPercent;
        this.charging = charging;
        postInvalidateOnAnimation();
    }

    void setWifi(boolean enabled, boolean connected) {
        if (wifiEnabled == enabled && wifiConnected == connected) {
            return;
        }
        wifiEnabled = enabled;
        wifiConnected = connected;
        postInvalidateOnAnimation();
    }

    void setSignal(boolean simReady, int level, boolean cellularActive) {
        if (this.simReady == simReady && signalLevel == level
                && this.cellularActive == cellularActive) {
            return;
        }
        this.simReady = simReady;
        signalLevel = level;
        this.cellularActive = cellularActive;
        postInvalidateOnAnimation();
    }

    void setAirplane(boolean on) {
        if (airplane == on) {
            return;
        }
        airplane = on;
        postInvalidateOnAnimation();
    }

    /** Called only from ACTION_TIME_TICK / TIME_CHANGED / TIMEZONE_CHANGED. */
    void refreshClock() {
        String now = clockFormat.format(new Date());
        if (!now.equals(clockText)) {
            clockText = now;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        setMeasuredDimension(width, dp(HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float mid = getHeight() / 2f;
        float left = dp(12);

        drawBattery(canvas, left, mid);
        if (!airplane) {
            drawWifi(canvas, dp(44), mid);
            if (simReady) {
                drawSignal(canvas, dp(68), mid);
            }
        }
        drawClock(canvas, getWidth(), mid);
    }

    private void drawBattery(Canvas canvas, float left, float mid) {
        float bodyW = dp(18);
        float bodyH = dp(9.5f);
        float top = mid - bodyH / 2f;
        float right = left + bodyW;

        stroke.setColor(WHITE);
        stroke.setStrokeWidth(dp(1));
        canvas.drawRect(left, top, right, top + bodyH, stroke);

        // WP nub: a small square block, not a rounded cap.
        fill.setColor(WHITE);
        canvas.drawRect(right + dp(0.5f), mid - dp(2), right + dp(2.5f), mid + dp(2), fill);

        if (batteryLevel >= 0) {
            float inset = dp(1.5f);
            float maxW = bodyW - 2 * inset;
            float levelW = maxW * Math.min(100, Math.max(0, batteryLevel)) / 100f;
            fill.setColor(WHITE);
            canvas.drawRect(left + inset, top + inset,
                    left + inset + levelW, top + bodyH - inset, fill);
        }

        if (charging) {
            drawPlug(canvas, right + dp(4), mid);
        }
    }

    /** Small two-prong plug, right of the battery, when charging. */
    private void drawPlug(Canvas canvas, float x, float mid) {
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(dp(1));
        canvas.drawRect(x, mid - dp(2.5f), x + dp(3), mid + dp(2.5f), stroke);
        canvas.drawLine(x + dp(3), mid - dp(1.5f),
                x + dp(5.5f), mid - dp(1.5f), stroke);
        canvas.drawLine(x + dp(3), mid + dp(1.5f),
                x + dp(5.5f), mid + dp(1.5f), stroke);
    }

    /** Three arcs and a dot, the WP Wi-Fi glyph. */
    private void drawWifi(Canvas canvas, float left, float mid) {
        if (!wifiEnabled) {
            return;
        }
        int color = wifiConnected ? WHITE : WHITE_50;
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(1.2f));
        float cx = left + dp(8);
        float base = mid + dp(4.5f);
        float[] radii = {dp(3), dp(6), dp(8.5f)};
        for (float radius : radii) {
            oval.set(cx - radius, base - radius, cx + radius, base + radius);
            canvas.drawArc(oval, 225f, 90f, false, stroke);
        }
        fill.setColor(color);
        canvas.drawCircle(cx, base, dp(1), fill);
    }

    /** Four bars; white for a real level, 70 % if only the transport is known. */
    private void drawSignal(Canvas canvas, float left, float mid) {
        float barW = dp(2.4f);
        float gap = dp(1.6f);
        float baseline = mid + dp(5);
        float[] heights = {dp(3), dp(5), dp(7), dp(9)};
        int known = Math.max(0, Math.min(4, signalLevel));
        int on = cellularActive ? WHITE : WHITE_70;
        for (int i = 0; i < heights.length; i++) {
            if (signalLevel >= 0) {
                fill.setColor(i < known ? on : WHITE_30);
            } else {
                // No READ_PHONE_STATE / no telephony API result: static four bars.
                fill.setColor(WHITE_50);
            }
            float x = left + i * (barW + gap);
            canvas.drawRect(x, baseline - heights[i], x + barW, baseline, fill);
        }
    }

    private void drawClock(Canvas canvas, float width, float mid) {
        if (clockText == null) {
            clockText = clockFormat.format(new Date());
        }
        fill.setColor(WHITE);
        fill.setTypeface(U.light(getContext()));
        fill.setTextSize(U.sp(getContext(), 12));
        fill.setTextAlign(Paint.Align.RIGHT);
        fill.setLetterSpacing(0.01f);
        Paint.FontMetrics fm = fill.getFontMetrics();
        float baseline = mid - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(clockText, width - dp(12), baseline, fill);
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }
}
