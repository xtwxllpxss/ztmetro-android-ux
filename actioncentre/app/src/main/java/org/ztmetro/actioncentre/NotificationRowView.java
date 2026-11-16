package org.ztmetro.actioncentre;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One flat notification row, in the mockup's order
 * ({@code mockups/screens/action-centre.html}, {@code ztmetro.c:6356-6405}):
 * a 28 dp accent square with the app's initial at x = 14, the app name 22 sp
 * Semilight, the time 13.5 sp right-aligned, the title 14.5 sp Semibold and the
 * body 13.5 sp at 62 % white. Rows are 14 dp apart; no cards, no radius.
 *
 * <p>Tap opens the notification, swipe left or the per-row × cancels it. The
 * cancel animates the row out to the left in 160 ms
 * ({@link Motion#NOTIF_DISMISS_MS}), scaled by ANIMATOR_DURATION_SCALE.
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
        setGravity(Gravity.TOP);
        setPadding(U.dp(context, 14), U.dp(context, 7), U.dp(context, 14),
                U.dp(context, 7));
        setClickable(true);
        setFocusable(true);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(U.ROW_PRESSED));
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        setBackground(background);
        setContentDescription(notif.appLabel + ": " + notif.title);

        TextView square = new TextView(context);
        square.setText(initial(notif.appLabel));
        square.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        square.setTypeface(U.semibold(context));
        square.setTextColor(U.WHITE);
        square.setGravity(Gravity.CENTER);
        square.setBackgroundColor(U.ACCENT);
        LayoutParams squareParams = new LayoutParams(U.dp(context, 28), U.dp(context, 28));
        squareParams.rightMargin = U.dp(context, 10);
        addView(square, squareParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        LayoutParams contentParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(content, contentParams);

        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(meta, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(context);
        name.setText(notif.appLabel);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setTypeface(U.light(context));
        name.setTextColor(U.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(context);
        time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(notif.time)));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        time.setTypeface(U.regular(context));
        time.setTextColor(U.WHITE_50);
        time.setPadding(U.dp(context, 8), 0, 0, 0);
        meta.addView(time, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(notif.title)) {
            TextView title = new TextView(context);
            title.setText(notif.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            title.setTypeface(U.semibold(context));
            title.setTextColor(U.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, U.dp(context, 2), 0, 0);
            content.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }

        String bodyText = !TextUtils.isEmpty(notif.text) ? notif.text : notif.sender;
        if (!TextUtils.isEmpty(bodyText)) {
            TextView body = new TextView(context);
            body.setText(bodyText);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            body.setTypeface(U.regular(context));
            body.setTextColor(U.WHITE_62);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setLineSpacing(0f, 18f / 13.5f);
            body.setPadding(0, U.dp(context, 1), 0, 0);
            content.addView(body, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }

        TextView close = new TextView(context);
        close.setText("\u00d7");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setTypeface(U.regular(context));
        close.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{U.ACCENT, U.WHITE_50}));
        close.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        close.setContentDescription("Удалить уведомление");
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAnimated();
            }
        });
        LayoutParams closeParams = new LayoutParams(U.dp(context, 24),
                LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = U.dp(context, 4);
        addView(close, closeParams);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationRowView.this.host.onOpen(NotificationRowView.this.notif);
            }
        });
    }

    private static String initial(String label) {
        if (label == null || label.length() == 0) {
            return "?";
        }
        return label.substring(0, 1).toUpperCase(Locale.getDefault());
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

    /** Slide out 160 ms; own the view's animation so the scale applies. */
    private void dismissAnimated() {
        int duration = Motion.ms(getContext(), Motion.NOTIF_DISMISS_MS);
        animate().cancel();
        if (duration <= 0) {
            host.onDismiss(notif);
            return;
        }
        animate()
                .translationX(-getWidth())
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(Motion.WP_LOG)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        host.onDismiss(notif);
                    }
                })
                .start();
    }
}
