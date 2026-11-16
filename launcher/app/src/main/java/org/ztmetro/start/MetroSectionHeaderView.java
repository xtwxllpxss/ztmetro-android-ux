package org.ztmetro.start;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/**
 * ztmetro app-list section head: a hollow accent square (no fill) with the
 * lowercase section letter inside its lower-left corner. Replaces the v1/v2
 * text heading; it is the shape the official ztmetro app-list frame uses.
 */
final class MetroSectionHeaderView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int sidePad;
    private final int squarePx;
    private final int rowPx;
    private final int insetPx;
    private String letter = "";

    MetroSectionHeaderView(Context context) {
        super(context);
        sidePad = MetroUi.dimen(context, R.dimen.metro_page_margin);
        squarePx = MetroUi.dimen(context, R.dimen.metro_list_icon);
        rowPx = MetroUi.dimen(context, R.dimen.metro_v3_list_row);
        insetPx = Math.max(1, Math.round(4f * context.getResources()
                .getDisplayMetrics().density));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(MetroUi.dimen(context, R.dimen.metro_v3_stroke));
        stroke.setColor(context.getResources().getColor(R.color.metro_accent, null));

        MetroUi.Text text = MetroUi.text(context, R.style.TextAppearance_Metro_ListTitle);
        letterPaint.setTextSize(text.sizePx);
        letterPaint.setTypeface(text.typeface == null ? Typeface.SANS_SERIF : text.typeface);
        letterPaint.setColor(context.getResources().getColor(R.color.metro_accent, null));
    }

    void setLetter(String value) {
        letter = value == null ? "" : value.toLowerCase(java.util.Locale.getDefault());
        setContentDescription(letter);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : rowPx;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = (getHeight() - squarePx) / 2f;
        canvas.drawRect(sidePad, top, sidePad + squarePx, top + squarePx, stroke);
        float baseline = top + squarePx - insetPx;
        canvas.drawText(letter, sidePad + insetPx, baseline, letterPaint);
    }
}
