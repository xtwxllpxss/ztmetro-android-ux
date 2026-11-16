package org.ztmetro.actioncentre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Our own status strip — not the system status bar. Operator on the left;
 * date, clock, battery percentage and a drawn battery on the right, 12 sp.
 */
final class StatusStripView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd.MM", Locale.getDefault());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshOperator();
            invalidate();
            handler.postDelayed(this, 15000);
        }
    };
    private BroadcastReceiver batteryReceiver;
    private int battery = -1;
    private boolean charging;
    private String operator = "ztmetro";

    StatusStripView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshOperator();
        readBattery(getContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                readBattery(intent);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(batteryReceiver, filter);
        }
        handler.postDelayed(tick, 15000);
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

    private void readBattery(Intent intent) {
        if (intent == null) {
            return;
        }
        int level = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", 100);
        int status = intent.getIntExtra("status", 0);
        if (level >= 0 && scale > 0) {
            battery = Math.min(100, level * 100 / scale);
        }
        charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        invalidate();
    }

    private void refreshOperator() {
        try {
            TelephonyManager tm =
                    (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
            String name = tm == null ? "" : tm.getNetworkOperatorName();
            if (name != null && name.trim().length() > 0) {
                operator = name.trim();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, U.dp(getContext(), 30));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int mid = getHeight() / 2;
        paint.setTypeface(U.regular(getContext()));
        paint.setTextSize(U.sp(getContext(), 12));
        paint.setLetterSpacing(0.02f);
        paint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = mid - (fm.ascent + fm.descent) / 2f;

        paint.setColor(U.WHITE_70);
        canvas.drawText(operator, U.dp(getContext(), 12), baseline, paint);

        int right = w - U.dp(getContext(), 12);
        int bw = U.dp(getContext(), 19);
        int bh = U.dp(getContext(), 9);
        int bx = right - bw;
        int by = mid - bh / 2;

        paint.setColor(U.WHITE_50);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(U.dp(getContext(), 1));
        canvas.drawRect(bx, by, bx + bw, by + bh, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(bx + bw + U.dp(getContext(), 1), mid - U.dp(getContext(), 2),
                bx + bw + U.dp(getContext(), 3), mid + U.dp(getContext(), 2), paint);

        if (battery >= 0) {
            int inner = Math.max(0, (bw - U.dp(getContext(), 3)) * battery / 100);
            paint.setColor(charging ? U.ACCENT : U.WHITE_70);
            canvas.drawRect(bx + U.dp(getContext(), 1), by + U.dp(getContext(), 1),
                    bx + U.dp(getContext(), 1) + inner, by + bh - U.dp(getContext(), 1), paint);
        }

        paint.setTextAlign(Paint.Align.RIGHT);
        String percent = battery >= 0 ? battery + "%" : "--";
        paint.setColor(U.WHITE_70);
        canvas.drawText(percent, bx - U.dp(getContext(), 4), baseline, paint);
        float percentLeft = bx - U.dp(getContext(), 4) - paint.measureText(percent);

        Date now = new Date();
        String clock = clockFormat.format(now);
        paint.setColor(U.WHITE);
        canvas.drawText(clock, percentLeft - U.dp(getContext(), 10), baseline, paint);
        float clockLeft = percentLeft - U.dp(getContext(), 10) - paint.measureText(clock);

        paint.setColor(U.WHITE_70);
        canvas.drawText(dateFormat.format(now), clockLeft - U.dp(getContext(), 8), baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
