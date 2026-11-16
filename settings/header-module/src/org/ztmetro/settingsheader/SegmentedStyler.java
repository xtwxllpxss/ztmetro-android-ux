package org.ztmetro.settingsheader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Segmented-button group ({@code MaterialButtonToggleGroup}): square, black, accent
 * selected, white label — requirement 4.
 *
 * <p>Reached from the single {@link ScreenPass} walk (the group is recognised by class/id
 * and styled inline; its children are not walked again).  A {@code StateListDrawable}-free
 * implementation is used: the MaterialButton keeps its own shape drawable and only its
 * background tint list becomes {@code checked -> #0050EF, default -> #000000}, so selection
 * changes repaint without a new listener.  Corner radius and stroke are set through
 * reflection because the Material library is not on this module's compile classpath.
 */
final class SegmentedStyler {

    private static final int BLACK = Color.BLACK;
    private static final int OUTLINE = 0xFF333333;

    private static final HashMap<Class<?>, Method[]> METHODS = new HashMap<>();

    private SegmentedStyler() {
    }

    /** @return true when the group or a child changed. */
    static boolean apply(ViewGroup group) {
        if (group == null) {
            return false;
        }
        boolean changed = false;
        if (!isBlack(group.getBackground())) {
            group.setBackgroundColor(BLACK);
            changed = true;
        }
        int padH = ScreenPass.pageMargin();
        int padV = ScreenPass.rowPadV();
        if (group.getPaddingLeft() != padH || group.getPaddingRight() != padH
                || group.getPaddingTop() != padV || group.getPaddingBottom() != padV) {
            group.setPadding(padH, padV, padH, padV);
            changed = true;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                if (Views.idName(text).endsWith("_text")) {
                    if (text.getCurrentTextColor() != ScreenPass.valueColor()) {
                        text.setTextColor(ScreenPass.valueColor());
                        changed = true;
                    }
                    if (text.getTextSize() != ScreenPass.labelPx()) {
                        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                                ScreenPass.labelPx());
                        changed = true;
                    }
                    continue;
                }
            }
            if (isMaterialButton(child) && styleButton(child)) {
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isMaterialButton(View view) {
        return view.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("materialbutton");
    }

    private static boolean styleButton(View button) {
        boolean changed = false;
        Method[] methods = methodsFor(button.getClass());
        if (methods[0] != null) {
            try {
                methods[0].invoke(button, 0);
                changed = true;
            } catch (Throwable ignored) {
                // not a MaterialButton shape API: keep the stock corner
            }
        }
        if (methods[1] != null) {
            try {
                methods[1].invoke(button, Math.max(1, Math.round(ScreenPass.density())));
            } catch (Throwable ignored) {
                // stroke is cosmetic only
            }
        }
        if (methods[2] != null) {
            try {
                methods[2].invoke(button, OUTLINE);
            } catch (Throwable ignored) {
                // stroke is cosmetic only
            }
        }
        try {
            ColorStateList tint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{ScreenPass.accentColor(), BLACK});
            if (!tint.equals(button.getBackgroundTintList())) {
                button.setBackgroundTintList(tint);
                changed = true;
            }
        } catch (Throwable ignored) {
            // plain View: the background colour below still gives a square black cell
        }
        if (button instanceof TextView) {
            TextView text = (TextView) button;
            if (text.getCurrentTextColor() != ScreenPass.valueColor()) {
                text.setTextColor(ScreenPass.valueColor());
                changed = true;
            }
            if (text.getTextSize() != ScreenPass.labelPx()) {
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ScreenPass.labelPx());
                changed = true;
            }
        }
        return changed;
    }

    private static Method[] methodsFor(Class<?> type) {
        Method[] cached = METHODS.get(type);
        if (cached != null) {
            return cached;
        }
        Method radius = null;
        Method strokeWidth = null;
        Method strokeColor = null;
        try {
            radius = type.getMethod("setCornerRadius", int.class);
        } catch (Throwable ignored) {
            // not a MaterialButton
        }
        try {
            strokeWidth = type.getMethod("setStrokeWidth", int.class);
        } catch (Throwable ignored) {
            // not a MaterialButton
        }
        try {
            strokeColor = type.getMethod("setStrokeColor", int.class);
        } catch (Throwable ignored) {
            // not a MaterialButton
        }
        Method[] methods = {radius, strokeWidth, strokeColor};
        METHODS.put(type, methods);
        return methods;
    }

    private static boolean isBlack(Drawable drawable) {
        return drawable instanceof android.graphics.drawable.ColorDrawable
                && ((android.graphics.drawable.ColorDrawable) drawable).getColor() == BLACK;
    }
}
