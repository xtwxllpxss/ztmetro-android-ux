package org.ztmetro.start;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * Reads ztmetro sizes, colours and type from the compiled resources that
 * scripts/build-launcher.sh generates from design/tokens/ztmetro.tokens.json.
 * No design value is hard-coded in Java.
 */
final class MetroUi {

    private MetroUi() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int dimen(Context context, int resId) {
        return context.getResources().getDimensionPixelSize(resId);
    }

    static float spToPx(Context context, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                context.getResources().getDisplayMetrics());
    }

    /** One entry of the ztmetro type ramp, as compiled into a TextAppearance style. */
    static final class Text {
        final float sizePx;
        final Typeface typeface;
        final int color;

        private Text(float sizePx, Typeface typeface, int color) {
            this.sizePx = sizePx;
            this.typeface = typeface;
            this.color = color;
        }
    }

    /**
     * Resolve a generated TextAppearance.Metro.* style into paint-friendly
     * values. The family is the system sans-serif stack (sans-serif,
     * -light, -medium) so a system font module still applies; Typeface.create
     * falls back to the default face when the family is absent.
     */
    static Text text(Context context, int styleRes) {
        Resources res = context.getResources();
        TypedArray a = context.obtainStyledAttributes(styleRes,
                new int[]{android.R.attr.textSize, android.R.attr.fontFamily, android.R.attr.textColor});
        try {
            float size = a.getDimension(0, spToPx(context, 13f));
            String family = a.getString(1);
            Typeface typeface = (family == null || family.isEmpty())
                    ? Typeface.SANS_SERIF
                    : Typeface.create(family, Typeface.NORMAL);
            int color = a.getColor(2, 0xFFFFFFFF);
            return new Text(size, typeface, color);
        } finally {
            a.recycle();
        }
    }
}
