package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * One flat square quick-action cell: solid fill, 26 dp white glyph, 10 sp caps
 * label at the bottom-left. No elevation, no ripple, no rounded corner. The
 * pressed state is a plain colour change.
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
        iconSize = U.dp(context, 26);
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
            int left = (w - iconSize) / 2;
            int top = U.dp(getContext(), 13);
            glyph.setBounds(left, top, left + iconSize, top + iconSize);
            glyph.setAlpha(available ? 255 : 110);
            glyph.draw(canvas);
        }

        labelPaint.setTextSize(U.sp(getContext(), 10));
        float max = w - U.dp(getContext(), 11);
        if (labelPaint.measureText(label) > max) {
            labelPaint.setTextSize(U.sp(getContext(), 9));
        }
        labelPaint.setColor(!available ? U.WHITE_40 : (on ? U.WHITE : U.WHITE_70));
        float baseline = h - U.dp(getContext(), 8);
        canvas.drawText(label, U.dp(getContext(), 7), baseline, labelPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }
}
