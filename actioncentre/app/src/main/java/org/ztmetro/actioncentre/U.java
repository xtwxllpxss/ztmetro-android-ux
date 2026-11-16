package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

/** Small shared helpers: flat palette and the bundled Open Sans faces. */
final class U {
    static final int BLACK = 0xFF000000;
    static final int OFF = 0xFF1F1F1F;
    static final int OFF_PRESSED = 0xFF333333;
    static final int ACCENT = 0xFF0050EF;
    static final int ACCENT_PRESSED = 0xFF1A5FE0;
    static final int ROW_PRESSED = 0xFF141414;
    static final int WHITE = 0xFFFFFFFF;
    static final int WHITE_70 = 0xB3FFFFFF;
    static final int WHITE_50 = 0x80FFFFFF;
    static final int WHITE_40 = 0x66FFFFFF;
    /** Notification body: 62 % white (ztmetro.c:6395). */
    static final int WHITE_62 = 0x9EFFFFFF;
    static final int WHITE_30 = 0x4DFFFFFF;

    private static Typeface light;
    private static Typeface regular;
    private static Typeface semibold;

    private U() {
    }

    static synchronized Typeface light(Context context) {
        if (light == null) {
            light = load(context, "fonts/OpenSans-Light.ttf", "sans-serif-light");
        }
        return light;
    }

    static synchronized Typeface regular(Context context) {
        if (regular == null) {
            regular = load(context, "fonts/OpenSans-Regular.ttf", "sans-serif");
        }
        return regular;
    }

    static synchronized Typeface semibold(Context context) {
        if (semibold == null) {
            semibold = load(context, "fonts/OpenSans-SemiBold.ttf", "sans-serif-medium");
        }
        return semibold;
    }

    private static Typeface load(Context context, String asset, String fallback) {
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (Throwable ignored) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }
}
