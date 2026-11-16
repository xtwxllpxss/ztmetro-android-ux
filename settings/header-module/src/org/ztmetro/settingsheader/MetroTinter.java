package org.ztmetro.settingsheader;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

/**
 * Recolours the stock switches/sliders to the ztMetro accent at runtime.
 *
 * <p>Settings builds its Material3 switch from the Material library palette, where the tint
 * chain does not resolve to any resource we can override from a preinstalled RRO (the switch
 * style is merged into the Settings APK and reads theme attrs from the Material theme).
 * Walking the view tree after the screen is laid out and applying the tint directly is
 * deterministic and cannot crash Settings: every call is guarded and only touches
 * CompoundButton instances.
 */
final class MetroTinter {

    private static final int ACCENT = 0xFF0050EF;

    private MetroTinter() {
    }

    /** Forces the window (and the content root) to pure black, like the ztmetro Settings. */
    static void applyWindowBackground(Activity activity) {
        try {
            activity.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0xFF000000));
            View content = activity.findViewById(android.R.id.content);
            if (content != null) content.setBackgroundColor(0xFF000000);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    static void apply(Activity activity) {
        try {
            applyWindowBackground(activity);
            View root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            // ScreenStyler owns the pass schedule; this walk runs once per pass.
            walk(root, 0);
        } catch (Throwable ignored) {
            // theming is cosmetic: never let it interfere with Settings
        }
    }

    private static void walk(View view, int depth) {
        if (view == null || depth > 24) return;
        try {
            if (view instanceof CompoundButton) {
                CompoundButton button = (CompoundButton) view;
                if (button.isEnabled()) {
                    // setTrackTintList/setThumbTintList live on Switch/SwitchCompat, not on
                    // the CompoundButton base class, and MaterialSwitch is a SwitchCompat.
                    invokeTint(button, "setTrackTintList", ACCENT);
                    invokeTint(button, "setThumbTintList", Color.WHITE);
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    walk(group.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // skip this node
        }
    }

    private static void invokeTint(View view, String method, int colour) {
        try {
            view.getClass().getMethod(method, ColorStateList.class)
                    .invoke(view, ColorStateList.valueOf(colour));
        } catch (Throwable ignored) {
            // view does not support this tint: leave its stock colours
        }
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }
}
