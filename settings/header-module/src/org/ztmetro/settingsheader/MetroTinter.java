package org.ztmetro.settingsheader;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.CompoundButton;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Per-view switch tint — no tree walk of its own any more: {@link ScreenPass} visits the
 * row subtree once and hands every {@link CompoundButton} to {@link #tint(View)}.
 *
 * <p>The expensive parts of the old implementation are cached process-wide:
 *
 * <ul>
 *   <li>{@code view.getClass().getMethod(...)} is resolved once per class and stored in
 *       {@link #METHODS}; the old code looked the two methods up on every walk of every
 *       switch;</li>
 *   <li>the two {@link ColorStateList} objects are created once per colour, not per view;</li>
 *   <li>the reflective call reuses a static {@code Object[1]} argument array, so the
 *       varargs array is not allocated per invocation.</li>
 * </ul>
 *
 * <p>Track = {@code #0050EF} opaque, thumb = {@code #FFFFFF} (ztmetro accent / WP toggle).
 */
final class MetroTinter {

    private static final HashMap<Class<?>, Method[]> METHODS = new HashMap<>();

    private static final Object[] TRACK_ARGS = new Object[1];
    private static final Object[] THUMB_ARGS = new Object[1];

    private static ColorStateList sTrackList;
    private static ColorStateList sThumbList;
    private static int sTrackColor;
    private static int sThumbColor;

    private MetroTinter() {
    }

    /** Installs the pass colours once per pass; recreates the lists only on a colour change. */
    static void configure(int trackColor, int thumbColor) {
        if (sTrackList == null || sTrackColor != trackColor) {
            sTrackColor = trackColor;
            sTrackList = ColorStateList.valueOf(trackColor);
            TRACK_ARGS[0] = sTrackList;
        }
        if (sThumbList == null || sThumbColor != thumbColor) {
            sThumbColor = thumbColor;
            sThumbList = ColorStateList.valueOf(thumbColor);
            THUMB_ARGS[0] = sThumbList;
        }
    }

    /** @return true when at least one tint was applied to this button. */
    static boolean tint(View view) {
        if (!(view instanceof CompoundButton)) {
            return false;
        }
        CompoundButton button = (CompoundButton) view;
        if (!button.isEnabled() || sTrackList == null) {
            return false;
        }
        // setTrackTintList/setThumbTintList live on Switch/SwitchCompat, not on the
        // CompoundButton base class, and MaterialSwitch is a SwitchCompat.
        Method[] methods = methodsFor(button.getClass());
        boolean applied = false;
        if (methods[0] != null) {
            try {
                methods[0].invoke(button, TRACK_ARGS);
                applied = true;
            } catch (Throwable ignored) {
                // view does not accept the tint: leave its stock colours
            }
        }
        if (methods[1] != null) {
            try {
                methods[1].invoke(button, THUMB_ARGS);
                applied = true;
            } catch (Throwable ignored) {
                // same: cosmetic only
            }
        }
        return applied;
    }

    private static Method[] methodsFor(Class<?> type) {
        Method[] cached = METHODS.get(type);
        if (cached != null) {
            return cached;
        }
        Method track = null;
        Method thumb = null;
        try {
            track = type.getMethod("setTrackTintList", ColorStateList.class);
        } catch (Throwable ignored) {
            // not a SwitchCompat/MaterialSwitch subclass
        }
        try {
            thumb = type.getMethod("setThumbTintList", ColorStateList.class);
        } catch (Throwable ignored) {
            // not a SwitchCompat/MaterialSwitch subclass
        }
        Method[] methods = {track, thumb};
        METHODS.put(type, methods);
        return methods;
    }
}
