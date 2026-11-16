package org.ztmetro.start;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * One app-list row, redrawn as a WP letterboard row: a flat 45dp accent square
 * with the white app silhouette, then the app name in 22sp Semilight. 56dp
 * tall on black, no divider, no Material background. Pressed is a flat #1F1F1F
 * fill, not a ripple.
 */
final class MetroListRowView extends View {

    private final TileArt art;
    private final Paint squarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint pressPaint = new Paint();
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final int sidePad;
    private final int iconPx;
    private final int labelGap;
    private final int rowPx;
    private final int accent;
    private final int glyphPx;
    private final int rightReserve;

    private TileStore.App app;
    private Bitmap glyph;
    private CharSequence label = "";
    private float labelX;
    private int squareTop;
    private float baseline;

    MetroListRowView(Context context, TileArt art, int rightReserve) {
        super(context);
        this.art = art;
        this.rightReserve = rightReserve;
        sidePad = MetroUi.dimen(context, R.dimen.metro_page_margin);
        iconPx = MetroUi.dimen(context, R.dimen.metro_list_icon);
        labelGap = MetroUi.dimen(context, R.dimen.metro_v3_list_gap);
        rowPx = MetroUi.dimen(context, R.dimen.metro_v3_list_row);
        accent = context.getResources().getColor(R.color.metro_accent, null);
        glyphPx = Math.max(1, Math.round(iconPx * 0.64f));
        pressPaint.setColor(context.getResources().getColor(
                R.color.metro_surface_off, null));

        MetroUi.Text title = MetroUi.text(context, R.style.TextAppearance_Metro_AppListTitle);
        labelPaint.setTextSize(title.sizePx);
        labelPaint.setTypeface(title.typeface == null ? Typeface.SANS_SERIF : title.typeface);
        labelPaint.setColor(title.color);
        squarePaint.setColor(accent);
        glyphPaint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    void setApp(TileStore.App app) {
        this.app = app;
        glyph = app == null ? null : art.glyph(app.component, glyphPx);
        if (getWidth() > 0) {
            rebuildLabel();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        labelX = sidePad + iconPx + labelGap;
        squareTop = Math.max(0, (h - iconPx) / 2);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        baseline = (h - metrics.bottom - metrics.top) / 2f;
        rebuildLabel();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : rowPx;
        setMeasuredDimension(width, height);
    }

    private void rebuildLabel() {
        if (app == null) {
            label = "";
            setContentDescription(null);
            return;
        }
        float maxWidth = Math.max(1f, getWidth() - labelX - sidePad - rightReserve);
        label = TextUtils.ellipsize(app.label, labelPaint, maxWidth,
                TextUtils.TruncateAt.END);
        setContentDescription(app.label);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (app == null) {
            return;
        }
        if (isPressed()) {
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), pressPaint);
        }
        canvas.drawRect(sidePad, squareTop, sidePad + iconPx, squareTop + iconPx,
                squarePaint);
        if (glyph != null) {
            float left = sidePad + (iconPx - glyphPx) / 2f;
            float top = squareTop + (iconPx - glyphPx) / 2f;
            canvas.drawBitmap(glyph, left, top, glyphPaint);
        }
        canvas.drawText(label, 0, label.length(), labelX, baseline, labelPaint);
    }
}
