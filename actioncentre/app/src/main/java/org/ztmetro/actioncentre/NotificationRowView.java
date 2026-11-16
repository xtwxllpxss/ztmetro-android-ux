package org.ztmetro.actioncentre;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One flat notification row. Tap opens the notification's contentIntent;
 * swipe left (or the per-row ×) cancels it. No cards, no radius, no ripple.
 */
final class NotificationRowView extends LinearLayout {
    interface Host {
        void onOpen(Notif notif);

        void onDismiss(Notif notif);
    }

    private final Notif notif;
    private final Host host;
    private final float slop;
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean vertical;

    NotificationRowView(Context context, Notif notif, Host host) {
        super(context);
        this.notif = notif;
        this.host = host;
        this.slop = U.dp(context, 8);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(U.dp(context, 12), U.dp(context, 9), U.dp(context, 4),
                U.dp(context, 9));
        setClickable(true);
        setFocusable(true);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(U.ROW_PRESSED));
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        setBackground(background);
        setContentDescription(notif.appLabel + ": " + notif.title);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(meta, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        TextView sender = new TextView(context);
        sender.setText(notif.sender);
        sender.setTextSize(13);
        sender.setTypeface(U.regular(context));
        sender.setTextColor(U.WHITE);
        sender.setSingleLine(true);
        sender.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(sender, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(context);
        time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(notif.time)));
        time.setTextSize(11);
        time.setTypeface(U.regular(context));
        time.setTextColor(U.WHITE_50);
        time.setPadding(U.dp(context, 8), 0, 0, 0);
        meta.addView(time, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(notif.title)) {
            TextView title = new TextView(context);
            title.setText(notif.title);
            title.setTextSize(13);
            title.setTypeface(U.light(context));
            title.setTextColor(U.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, U.dp(context, 1), 0, 0);
            content.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }

        if (!TextUtils.isEmpty(notif.text)) {
            TextView body = new TextView(context);
            body.setText(notif.text);
            body.setTextSize(12);
            body.setTypeface(U.light(context));
            body.setTextColor(U.WHITE_70);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setPadding(0, U.dp(context, 1), 0, 0);
            content.addView(body, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }

        TextView close = new TextView(context);
        close.setText("\u00d7");
        close.setTextSize(20);
        close.setTypeface(U.regular(context));
        close.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{U.ACCENT, U.WHITE_50}));
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Удалить уведомление");
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationRowView.this.host.onDismiss(NotificationRowView.this.notif);
            }
        });
        addView(close, new LayoutParams(U.dp(context, 36), LayoutParams.WRAP_CONTENT));

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationRowView.this.host.onOpen(NotificationRowView.this.notif);
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                swiping = false;
                vertical = false;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!swiping && !vertical && Math.abs(dx) > slop
                        && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    swiping = true;
                    setPressed(false);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (!swiping && Math.abs(dy) > slop) {
                    vertical = true;
                    setPressed(false);
                }
                if (swiping) {
                    setTranslationX(Math.min(0f, dx));
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (swiping) {
                    if (getTranslationX() < -U.dp(getContext(), 64)) {
                        dismissAnimated();
                    } else {
                        animate().translationX(0f).setDuration(140).start();
                    }
                    swiping = false;
                    return true;
                }
                if (!vertical) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (swiping) {
                    animate().translationX(0f).setDuration(120).start();
                }
                swiping = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void dismissAnimated() {
        animate()
                .translationX(-getWidth())
                .alpha(0f)
                .setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        host.onDismiss(notif);
                    }
                })
                .start();
    }
}
