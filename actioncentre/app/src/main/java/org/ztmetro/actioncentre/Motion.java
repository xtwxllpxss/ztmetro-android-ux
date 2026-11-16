package org.ztmetro.actioncentre;

import android.content.Context;
import android.provider.Settings;
import android.view.animation.Interpolator;

/**
 * Action Centre motion, in one place.
 *
 * <p>Open and close are ztmetro's shade travel:
 * {@code animate(&shade_h, open ? SHADE_MAX_H : 0, SHADE_MS, 0, false)}
 * at {@code ztmetro.c:6558} — one 260 ms Log(10) move, {@code SHADE_MS} at
 * {@code ztmetro.c:138}. This build keeps the repo's existing 220 ms close
 * (the shipped {@code ac_slide_down.xml}); ztmetro itself uses the same 260 ms
 * in both directions.
 *
 * <p>The finger releases with a 40 pt commit, as the C does for the shade:
 * {@code shade_set_open(server, was_open ? dy > -40 : dy > 40)}
 * ({@code ztmetro.c:11761}); the 40 is in points and is not an animation, so
 * the scale does not apply to it.
 */
final class Motion {

    /** {@code ztmetro.c:138} SHADE_MS 260: shade open / snap back open. */
    static final int SHADE_OPEN_MS = 260;
    /** {@code actioncentre/app/src/main/res/anim/ac_slide_down.xml}: 220. */
    static final int SHADE_CLOSE_MS = 220;
    /** Quick-tile press: down 90 ms, up 150 ms (mockup/UX brief). */
    static final int TILE_DOWN_MS = 90;
    static final int TILE_UP_MS = 150;
    /** One notification row leaving the list: 160 ms slide out. */
    static final int NOTIF_DISMISS_MS = 160;
    /** {@code ztmetro.c:11761}: drag further than this and it closes. */
    static final int DRAG_COMMIT_DP = 40;

    /** The core's Log(10) ease-out, {@code core/zrnmetrocore.c:1628}. */
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

    /** {@code Settings.Global.ANIMATOR_DURATION_SCALE}; 0 means instant. */
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

    static int ms(Context context, int baseMs) {
        if (baseMs <= 0) {
            return 0;
        }
        return Math.round(baseMs * scale(context));
    }
}
