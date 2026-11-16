package org.ztmetro.start;

import android.content.Context;
import android.provider.Settings;
import android.view.animation.Interpolator;

/**
 * One gate for every ztmetro duration in the launcher.
 *
 * <p>ztmetro advances its animations by wall clock, never by frame count
 * ({@code ztmetro.c:1160-1200}, {@code anims_step}), and eases them on the
 * Log(10) curve {@code (b^t - 1) / (b - 1), b = 1/10}
 * ({@code core/zrnmetrocore.c:1628}). This class carries the same curve and
 * scales every duration by the system animator scale, the way Windows'
 * animation setting shortens the same motion instead of removing it.
 *
 * <p>Input thresholds are not durations and are deliberately not scaled:
 * PRESS_DELAY_MS 80 ({@code ztmetro.c:148}) and LONG_PRESS_MS 450
 * ({@code ztmetro.c:145}) decide *when* a finger has pressed or held; at
 * animator scale 0 they still have to wait, while the lean itself is instant.
 */
final class Motion {

    /** One 60 Hz frame: WP lights a pressed tile this much before it leans. */
    static final int HIGHLIGHT_LEAD_MS = 16;

    /**
     * Windows Phone's Log(10) ease-out, the exact expression the core uses:
     * {@code t = (1 - pow(0.1, t)) / 0.9} at {@code core/zrnmetrocore.c:1628}.
     * Monotonic, so nothing overshoots.
     */
    static final Interpolator WP_LOG = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            return (float) ((1.0 - Math.pow(0.1, t)) / 0.9);
        }
    };

    private Motion() {
    }

    /**
     * {@code Settings.Global.ANIMATOR_DURATION_SCALE}: 0 = animations off,
     * 1 = normal, up to 10 in the developer menu. A missing or broken value
     * means 1 (Android's own default).
     */
    static float scale(Context context) {
        try {
            float value = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (Float.isNaN(value) || value <= 0f) {
                return 0f;
            }
            return value > 10f ? 10f : value;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    /** A token duration in milliseconds after the system scale. 0 = instant. */
    static int ms(Context context, int baseMs) {
        if (baseMs <= 0) {
            return 0;
        }
        return Math.round(baseMs * scale(context));
    }
}
