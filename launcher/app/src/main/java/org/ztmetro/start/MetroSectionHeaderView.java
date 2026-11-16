package org.ztmetro.start;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

import java.util.Locale;

/**
 * ztmetro app-list section head, drawn as the mockup draws it
 * ({@code mockups/screens/app-list.html}): the section letter at 27sp Light in
 * the accent, on the page margin, sitting on the lower edge of a 36dp block
 * (34dp line + 2dp). Every later section adds the mockup's 10dp top margin,
 * carried by a taller view, so the letter keeps the same baseline.
 *
 * <p>This replaces the v1/v2 hollow 1.5dp outline square at {@code LIST_LEFT}
 * that the C source draws ({@code ztmetro.c:4514-4518}); the approved mockup
 * puts a plain letter at the margin instead, and the app rows keep the accent
 * square in the 80dp icon column.
 */
final class MetroSectionHeaderView extends View {

    /** Mockup: letter ink bottom sits 8.5dp above the block's bottom edge. */
    private static final float BASELINE_INSET_DP = 8.5f;

    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int sidePad;
    private final float baselineInset;
    private String letter = "";

    MetroSectionHeaderView(Context context) {
        super(context);
        sidePad = MetroUi.dimen(context, R.dimen.metro_page_margin);
        baselineInset = BASELINE_INSET_DP * context.getResources().getDisplayMetrics().density;

        MetroUi.Text text = MetroUi.text(context, R.style.TextAppearance_Metro_SectionLetter);
        letterPaint.setTextSize(text.sizePx);
        letterPaint.setTypeface(text.typeface == null ? Typeface.SANS_SERIF : text.typeface);
        letterPaint.setColor(text.color);
    }

    void setLetter(String value) {
        letter = value == null ? "" : value.toLowerCase(Locale.getDefault());
        setContentDescription(letter);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float baseline = getHeight() - baselineInset;
        canvas.drawText(letter, sidePad, baseline, letterPaint);
    }
}
