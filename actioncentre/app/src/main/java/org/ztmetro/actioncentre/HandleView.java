package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 16 dp accent push-down handle with the 28x3 dp white grip from ztmetro.c. */
final class HandleView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    HandleView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.close));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), U.dp(getContext(), 16));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        paint.setColor(isPressed() ? U.ACCENT_PRESSED : U.ACCENT);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setColor(U.WHITE);
        float grip = U.dp(getContext(), 28);
        float gripHeight = U.dp(getContext(), 3);
        canvas.drawRect((w - grip) / 2f, (h - gripHeight) / 2f,
                (w + grip) / 2f, (h + gripHeight) / 2f, paint);
    }
}
