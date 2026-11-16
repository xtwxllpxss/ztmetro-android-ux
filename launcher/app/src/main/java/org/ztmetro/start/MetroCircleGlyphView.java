package org.ztmetro.start;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * The small WP chrome glyphs, drawn by us: a 1.5dp white circle with a right
 * arrow (Start -> app list), a circled search glyph, and the plain back/search
 * glyphs of the app-list bottom strip. 48dp touch target, no ripple, no
 * Material icon asset.
 */
final class MetroCircleGlyphView extends View {

    static final int ARROW_RIGHT = 0;
    static final int ARROW_LEFT = 1;
    static final int SEARCH = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    private final boolean circled;
    private final int sizePx;

    MetroCircleGlyphView(Context context, int kind, boolean circled) {
        super(context);
        this.kind = kind;
        this.circled = circled;
        sizePx = MetroUi.dimen(context, R.dimen.metro_v3_arrow);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(MetroUi.dimen(context, R.dimen.metro_v3_stroke));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(context.getResources().getColor(R.color.metro_text_primary, null));
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec) : sizePx;
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : sizePx;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float extent = Math.min(getWidth(), getHeight());
        float radius = extent * 0.46f;
        if (circled) {
            canvas.drawCircle(cx, cy, radius, paint);
        }
        if (kind == SEARCH) {
            float lensRadius = radius * 0.30f;
            float lensX = cx - radius * 0.12f;
            float lensY = cy - radius * 0.12f;
            canvas.drawCircle(lensX, lensY, lensRadius, paint);
            canvas.drawLine(lensX + lensRadius * 0.7f, lensY + lensRadius * 0.7f,
                    lensX + lensRadius * 2.0f, lensY + lensRadius * 2.0f, paint);
        } else {
            float half = radius * 0.38f;
            float tip = kind == ARROW_RIGHT ? cx + half : cx - half;
            float tail = kind == ARROW_RIGHT ? cx - half : cx + half;
            canvas.drawLine(tail, cy, tip, cy, paint);
            float head = radius * 0.22f;
            float back = kind == ARROW_RIGHT ? tip - head : tip + head;
            canvas.drawLine(tip, cy, back, cy - head, paint);
            canvas.drawLine(tip, cy, back, cy + head, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
