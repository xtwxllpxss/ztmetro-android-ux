package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * One flat quick-action cell, the mockup's 1:0.66 rectangle: solid fill, an
 * 18 dp white glyph at (8, 8) and a 7.6 sp caps label at (7, h-4)
 * ({@code mockups/screens/action-centre.html}; {@code ztmetro.c:6313-6350}).
 * No elevation, no ripple, no rounded corner.
 *
 * <p>Press feedback is motion, not a ripple: the cell scales to 0.94 and its
 * alpha drops to 0.85 in 90 ms on touch-down, and both return in 150 ms on
 * release ({@link Motion#TILE_DOWN_MS}/{@link Motion#TILE_UP_MS}). The fill
 * still changes colour as the C does. Every duration is multiplied by
 * ANIMATOR_DURATION_SCALE through {@link Motion#ms}.
 */
final class QuickTile extends View {
    private final Paint fill = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int iconSize;
    private Drawable glyph;
    private String label = "";
    private boolean on;
    private boolean available = true;
    private boolean actionOnly;

    QuickTile(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setLongClickable(false);
        iconSize = U.dp(context, 18);
        labelPaint.setTypeface(U.semibold(context));
        labelPaint.setLetterSpacing(0.04f);
    }

    void configure(int iconRes, String text) {
        glyph = getContext().getDrawable(iconRes);
        label = text;
        updateDescription();
        invalidate();
    }

    void setLabel(String text) {
        label = text;
        updateDescription();
        invalidate();
    }

    void setActionOnly(boolean value) {
        actionOnly = value;
        updateDescription();
    }

    void setTileState(boolean isOn, boolean isAvailable) {
        on = isOn;
        available = isAvailable;
        updateDescription();
        invalidate();
    }

    boolean isTileOn() {
        return on;
    }

    private void updateDescription() {
        if (actionOnly) {
            setContentDescription(available ? label : label + " недоступно");
            return;
        }
        String suffix = on ? " включено" : " выключено";
        setContentDescription(available ? label + suffix : label + " недоступно");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int background;
        if (on) {
            background = isPressed() ? U.ACCENT_PRESSED : U.ACCENT;
        } else {
            background = isPressed() ? U.OFF_PRESSED : U.OFF;
        }
        fill.setColor(background);
        canvas.drawRect(0, 0, w, h, fill);

        if (glyph != null) {
            int left = U.dp(getContext(), 8);
            int top = U.dp(getContext(), 8);
            glyph.setBounds(left, top, left + iconSize, top + iconSize);
            glyph.setAlpha(available ? 255 : 77);
            glyph.draw(canvas);
        }

        labelPaint.setTextSize(U.sp(getContext(), 7.6f));
        float max = w - U.dp(getContext(), 11);
        if (labelPaint.measureText(label) > max) {
            labelPaint.setTextSize(U.sp(getContext(), 7));
        }
        labelPaint.setColor(available ? U.WHITE : U.WHITE_40);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float baseline = h - U.dp(getContext(), 4) - fm.descent;
        canvas.drawText(label, U.dp(getContext(), 7), baseline, labelPaint);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        boolean down = isPressed();
        int duration = Motion.ms(getContext(),
                down ? Motion.TILE_DOWN_MS : Motion.TILE_UP_MS);
        animate().cancel();
        if (duration <= 0) {
            setScaleX(down ? 0.94f : 1f);
            setScaleY(down ? 0.94f : 1f);
            setAlpha(down ? 0.85f : 1f);
            return;
        }
        animate().scaleX(down ? 0.94f : 1f).scaleY(down ? 0.94f : 1f)
                .alpha(down ? 0.85f : 1f)
                .setDuration(duration)
                .setInterpolator(Motion.WP_LOG)
                .start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }
}
