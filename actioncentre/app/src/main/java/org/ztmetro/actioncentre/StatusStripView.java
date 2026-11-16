package org.ztmetro.actioncentre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Action Centre's own status row, 50 dp tall as the ztmetro C draws it and
 * as {@code mockups/screens/action-centre.html} shows it:
 *
 * <ul>
 *   <li>the 28 dp glyph row: cellular bars, the network type, Wi-Fi on the left;
 *       the clock and the battery on the right (11.5 / 15 sp);</li>
 *   <li>the second line at 24.75 dp ({@code ztmetro.c:6503}, "base + 5" with the
 *       12.1 sp cell): the operator on the left, charge % and date on the
 *       right, right-aligned at the 14 dp margin.</li>
 * </ul>
 *
 * <p><b>Pass 2 (perf).</b> This view used to tick every 15 s and invalidate
 * unconditionally, and {@code onDraw} formatted the clock and the date on every
 * frame (up to four {@code SimpleDateFormat.format} allocations per frame plus
 * two {@code measureText} calls on a freshly built string). Now:
 *
 * <ul>
 *   <li>the tick lands on the next minute boundary, because the clock is the only
 *       thing that changes on a minute;</li>
 *   <li>a tick reads the radio (operator, Wi-Fi, signal) but invalidates only when
 *       a <em>drawn</em> value changed;</li>
 *   <li>the clock and date strings are cached and re-used by {@code onDraw}; a
 *       frame allocates nothing;</li>
 *   <li>the ticker stops when the view is not visible ({@code onStop} of the
 *       panel, screen off, page switch), so a hidden panel costs no wakeups.</li>
 * </ul>
 */
final class StatusStripView extends View {
    static final int HEIGHT_DP = 50;
    private static final int GLYPH_ROW_DP = 28;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd.MM", Locale.getDefault());
    /** Minute-aligned tick: the only thing that changes on a minute is the clock. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isShown()) {
                return;   // hidden panel: no wakeups, no binder reads
            }
            boolean changed = refreshClockText();
            changed |= refreshOperator();
            changed |= refreshRadio();
            changed |= refreshBattery();
            if (changed) {
                invalidate();
            }
            scheduleNextTick();
        }
    };
    private BroadcastReceiver batteryReceiver;
    private int battery = -1;
    private boolean charging;
    private String operator = "ztmetro";
    private boolean wifiOn;
    private int signalLevel = -1;
    private boolean radioRead;
    /** Cached draw strings: a frame must not format a date. */
    private String clockText;
    private String dateText;
    /** Width of {@link #dateText}, measured once per minute instead of per frame. */
    private String dateWidthFor;
    private float dateWidth;

    StatusStripView(Context context) {
        super(context);
        setWillNotDraw(false);
        refreshClockText();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshClockText();
        refreshOperator();
        refreshRadio();
        refreshBattery(getContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (refreshBattery(intent)) {
                    invalidate();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(batteryReceiver, filter);
        }
        scheduleNextTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        if (batteryReceiver != null) {
            try {
                getContext().unregisterReceiver(batteryReceiver);
            } catch (Throwable ignored) {
            }
            batteryReceiver = null;
        }
        super.onDetachedFromWindow();
    }

    /**
     * The panel is paused/covered: stop the minute tick (and with it the radio
     * reads) until it is visible again. {@code onWindowVisibilityChanged} fires for
     * a stopped activity and for a covered window.
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        handler.removeCallbacks(tick);
        if (visibility == View.VISIBLE && isAttachedToWindow()) {
            scheduleNextTick();
        }
    }

    private void scheduleNextTick() {
        handler.removeCallbacks(tick);
        if (!isAttachedToWindow()) {
            return;
        }
        long now = System.currentTimeMillis();
        long delay = 60000L - (now % 60000L) + 50L;
        handler.postDelayed(tick, delay);
    }

    /** @return true when the drawn minute changed. */
    private boolean refreshClockText() {
        String clock = clockFormat.format(new Date());
        String date = dateFormat.format(new Date());
        boolean changed = !clock.equals(clockText) || !date.equals(dateText);
        clockText = clock;
        dateText = date;
        return changed;
    }

    private boolean refreshBattery() {
        return refreshBattery(getContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    /** @return true when the drawn battery state changed. */
    private boolean refreshBattery(Intent intent) {
        if (intent == null) {
            return false;
        }
        int level = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", 100);
        int status = intent.getIntExtra("status", 0);
        int percent = battery;
        if (level >= 0 && scale > 0) {
            percent = Math.min(100, level * 100 / scale);
        }
        boolean nowCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        if (percent == battery && nowCharging == charging) {
            return false;
        }
        battery = percent;
        charging = nowCharging;
        return true;
    }

    /** @return true when the drawn operator changed. */
    private boolean refreshOperator() {
        try {
            TelephonyManager tm =
                    (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            String name = tm == null ? "" : tm.getNetworkOperatorName();
            if (name != null && name.trim().length() > 0) {
                String trimmed = name.trim();
                if (!trimmed.equals(operator)) {
                    operator = trimmed;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Wi-Fi and signal are read once per minute tick; no permission means dim bars. */
    private boolean refreshRadio() {
        radioRead = true;
        boolean changed = false;
        boolean nowWifi = false;
        try {
            WifiManager wm = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
            nowWifi = wm != null && wm.isWifiEnabled();
        } catch (Throwable ignored) {
            nowWifi = false;
        }
        if (nowWifi != wifiOn) {
            wifiOn = nowWifi;
            changed = true;
        }
        int nowSignal;
        try {
            TelephonyManager tm =
                    (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            SignalStrength strength = tm == null ? null : tm.getSignalStrength();
            nowSignal = strength == null ? -1 : strength.getLevel();
        } catch (Throwable ignored) {
            nowSignal = -1;
        }
        if (nowSignal != signalLevel) {
            signalLevel = nowSignal;
            changed = true;
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, U.dp(getContext(), HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        float mid = U.dp(getContext(), GLYPH_ROW_DP) / 2f;
        float left = U.dp(getContext(), 14);

        drawSignal(canvas, left, mid);
        paint.setColor(U.WHITE);
        paint.setTextSize(U.sp(getContext(), 11.5f));
        paint.setTypeface(U.regular(getContext()));
        paint.setTextAlign(Paint.Align.LEFT);
        drawCentred(canvas, "4G", left + U.dp(getContext(), 17), mid);
        drawWifi(canvas, left + U.dp(getContext(), 38), mid);

        int right = w - U.dp(getContext(), 14);
        int bw = U.dp(getContext(), 22);
        int bh = U.dp(getContext(), 11);
        int bx = right - bw;
        float by = mid - bh / 2f;

        paint.setColor(U.WHITE_50);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(U.dp(getContext(), 1));
        canvas.drawRect(bx, by, bx + bw, by + bh, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bx + bw + U.dp(getContext(), 1), mid - U.dp(getContext(), 2),
                bx + bw + U.dp(getContext(), 3), mid + U.dp(getContext(), 2), paint);
        if (battery >= 0) {
            int inner = Math.max(0, (bw - U.dp(getContext(), 3)) * battery / 100);
            paint.setColor(charging ? U.ACCENT : U.WHITE);
            canvas.drawRect(bx + U.dp(getContext(), 1), by + U.dp(getContext(), 1),
                    bx + U.dp(getContext(), 1) + inner, by + bh - U.dp(getContext(), 1), paint);
        }

        paint.setTextSize(U.sp(getContext(), 15));
        paint.setColor(U.WHITE);
        paint.setTextAlign(Paint.Align.RIGHT);
        drawCentred(canvas, clockText == null ? "" : clockText,
                bx - U.dp(getContext(), 8), mid);

        // ---- second line: operator left, charge + date right, 24.75 dp ----
        paint.setTextSize(U.sp(getContext(), 12.1f));
        paint.setTypeface(U.regular(getContext()));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float top = U.dp(getContext(), 24.75f);
        float baseline = top - fm.ascent;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(U.WHITE);
        canvas.drawText(operator, left, baseline, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(U.WHITE_70);
        if (dateText != null) {
            canvas.drawText(dateText, right, baseline, paint);
            if (!dateText.equals(dateWidthFor)) {
                // Measured once per minute, not once per frame.
                dateWidth = paint.measureText(dateText);
                dateWidthFor = dateText;
            }
        }
        float dateLeft = right - dateWidth;
        String percent = battery >= 0 ? battery + "%" : "--";
        canvas.drawText(percent, dateLeft - U.dp(getContext(), 12), baseline, paint);
    }

    private void drawCentred(Canvas canvas, String text, float x, float mid) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = mid - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, x, baseline, paint);
    }

    private void drawSignal(Canvas canvas, float x, float mid) {
        float barW = U.dp(getContext(), 2.6f);
        float step = U.dp(getContext(), 3.6f);
        float bottom = mid + U.dp(getContext(), 5.5f);
        int[] heights = {4, 6, 8, 11};
        for (int i = 0; i < 4; i++) {
            float h = U.dp(getContext(), heights[i]);
            boolean lit = signalLevel >= 0 && i <= signalLevel;
            paint.setColor(lit ? U.WHITE : U.WHITE_30);
            canvas.drawRect(x + i * step, bottom - h, x + i * step + barW, bottom, paint);
        }
    }

    private void drawWifi(Canvas canvas, float x, float mid) {
        paint.setColor(wifiOn ? U.WHITE : U.WHITE_30);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(U.dp(getContext(), 1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        float cx = x + U.dp(getContext(), 7);
        float cy = mid - U.dp(getContext(), 1);
        path.reset();
        path.moveTo(cx - U.dp(getContext(), 7), cy - U.dp(getContext(), 1.5f));
        path.quadTo(cx, cy - U.dp(getContext(), 9), cx + U.dp(getContext(), 7),
                cy - U.dp(getContext(), 1.5f));
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(cx - U.dp(getContext(), 4), cy + U.dp(getContext(), 1.5f));
        path.quadTo(cx, cy - U.dp(getContext(), 3.5f), cx + U.dp(getContext(), 4),
                cy + U.dp(getContext(), 1.5f));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + U.dp(getContext(), 4), U.dp(getContext(), 1), paint);
    }
}
