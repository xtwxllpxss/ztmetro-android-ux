package org.ztmetro.start;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * One app-list row, redrawn as a WP letterboard row: a flat 45dp accent square
 * in the icon column at x = LIST_LEFT (80dp), a 25dp white glyph inside it, then
 * the app name in 22sp Semilight at 80 + 45 + 13 = 138dp (ztmetro.c:162-164,
 * 4519-4525). 56dp tall on black, no divider, no Material background. Pressed
 * is a flat #1F1F1F fill, not a ripple.
 */
final class MetroListRowView extends View {

    private final TileArt art;
    private final Paint squarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint pressPaint = new Paint();
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glyphBox = new RectF();

    private final int sidePad;
    private final int squareLeft;
    private final int iconPx;
    private final int labelGap;
    private final int rowPx;
    private final int accent;
    private final int glyphPx;
    private final int rightReserve;

    private TileStore.App app;
    private TileArt.Art glyph;
    private CharSequence label = "";
    private float labelX;
    private int squareTop;
    private float baseline;

    MetroListRowView(Context context, TileArt art, int rightReserve) {
        super(context);
        this.art = art;
        this.rightReserve = rightReserve;
        sidePad = MetroUi.dimen(context, R.dimen.metro_page_margin);
        squareLeft = MetroUi.dimen(context, R.dimen.metro_list_left);
        iconPx = MetroUi.dimen(context, R.dimen.metro_list_icon);
        labelGap = MetroUi.dimen(context, R.dimen.metro_v3_list_gap);
        rowPx = MetroUi.dimen(context, R.dimen.metro_v3_list_row);
        accent = context.getResources().getColor(R.color.metro_accent, null);
        /* draw_glyph(..., LIST_ICON / 2, LIST_ICON / 2, 25): the square is
         * 45dp, the white glyph inside it 25dp (ztmetro.c:4523-4525). */
        glyphPx = Math.max(1, Math.round(iconPx * 25f / 45f));
        pressPaint.setColor(context.getResources().getColor(
                R.color.metro_surface_off, null));

        MetroUi.Text title = MetroUi.text(context, R.style.TextAppearance_Metro_AppListTitle);
        labelPaint.setTextSize(title.sizePx);
        labelPaint.setTypeface(title.typeface == null ? Typeface.SANS_SERIF : title.typeface);
        labelPaint.setColor(title.color);
        squarePaint.setColor(accent);
        glyphPaint.setColor(Color.WHITE);
        letterPaint.setTextSize(Math.max(1f, iconPx * 0.48f));
        letterPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        letterPaint.setColor(Color.WHITE);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    void setApp(TileStore.App app) {
        this.app = app;
        glyph = app == null ? null : art.art(app.component, glyphPx);
        if (getWidth() > 0) {
            rebuildLabel();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        labelX = squareLeft + iconPx + labelGap;
        squareTop = Math.max(0, (h - iconPx) / 2);
        float glyphLeft = squareLeft + (iconPx - glyphPx) / 2f;
        float glyphTop = squareTop + (iconPx - glyphPx) / 2f;
        glyphBox.set(glyphLeft, glyphTop, glyphLeft + glyphPx, glyphTop + glyphPx);
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
        canvas.drawRect(squareLeft, squareTop, squareLeft + iconPx, squareTop + iconPx,
                squarePaint);
        if (glyph != null && glyph.type == TileArt.TYPE_WHITE) {
            canvas.drawBitmap(glyph.bitmap, null, glyphBox, glyphPaint);
        } else if (glyph != null && glyph.type == TileArt.TYPE_COLOR) {
            canvas.drawBitmap(glyph.bitmap, null, glyphBox, colorPaint);
        } else if (glyph != null && glyph.type == TileArt.TYPE_SYSTEM) {
            SystemGlyphs.draw(canvas, glyph.systemKind, glyphBox);
        } else if (app != null) {
            Paint.FontMetrics metrics = letterPaint.getFontMetrics();
            float centerY = squareTop + iconPx / 2f;
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(TileArt.firstLetter(app.label),
                    squareLeft + iconPx / 2f, baseline, letterPaint);
        }
        canvas.drawText(label, 0, label.length(), labelX, baseline, labelPaint);
    }
}
