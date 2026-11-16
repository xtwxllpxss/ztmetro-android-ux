package org.ztmetro.settingsheader;

import android.util.TypedValue;
import android.widget.TextView;

/**
 * Per-view row typography for the ztmetro "about" row — no walk of its own.
 *
 * <p>The reference frame (docs/17 §6, {@code ztos-about.png}) has two stacked lines with no
 * icon, no chevron and no card:
 *
 * <ul>
 *   <li><b>label</b> — 15 sp, {@code #999999} ({@code color.neutrals.text_secondary}), the
 *       category/name line;</li>
 *   <li><b>value</b> — 22 sp, {@code #FFFFFF} ({@code color.neutrals.text_primary}), the
 *       actual value line.</li>
 * </ul>
 *
 * <p>Settings' {@code @android:id/title} becomes the grey label and its
 * {@code @android:id/summary} the white value.  A row without a summary is a single white
 * value line at 22 sp (no grey line), which is exactly what a ztmetro hub row does.
 *
 * <p>Sizes arrive as pixels (the generated {@code 15sp}/{@code 22sp} dimens converted with
 * the live font scale), so a user font-scale change is picked up on the next pass and no
 * TypedArray is involved.  Mutations are skipped when the value is already correct, so the
 * post-layout pass logs very few applies.
 */
final class RowType {

    /** color.neutrals.text_secondary, ztos-about.png row label. */
    private static final int FALLBACK_LABEL_COLOR = 0xFF999999;
    /** color.neutrals.text_primary, ztos-about.png row value. */
    private static final int FALLBACK_VALUE_COLOR = 0xFFFFFFFF;

    /** Fallbacks are only used if the module resources failed (the header would fail too). */
    private static float sLabelPx = 60f;
    private static float sValuePx = 88f;
    private static int sLabelColor = FALLBACK_LABEL_COLOR;
    private static int sValueColor = FALLBACK_VALUE_COLOR;

    private RowType() {
    }

    static void configure(float labelPx, float valuePx, int labelColor, int valueColor) {
        if (labelPx > 0f) {
            sLabelPx = labelPx;
            sValuePx = valuePx;
        }
        sLabelColor = labelColor;
        sValueColor = valueColor;
    }

    /** Grey 15 sp label line (only used when the row has a value line below it). */
    static boolean styleLabel(TextView title) {
        return apply(title, sLabelPx, sLabelColor);
    }

    /** White 22 sp value line (also the only line of a row without a summary). */
    static boolean styleValue(TextView value) {
        return apply(value, sValuePx, sValueColor);
    }

    private static boolean apply(TextView text, float sizePx, int color) {
        boolean changed = false;
        if (text.getCurrentTextColor() != color) {
            text.setTextColor(color);
            changed = true;
        }
        if (text.getTextSize() != sizePx) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
            // The frame's two lines are set solid: no extra font padding between them.
            text.setIncludeFontPadding(false);
            changed = true;
        }
        return changed;
    }
}
