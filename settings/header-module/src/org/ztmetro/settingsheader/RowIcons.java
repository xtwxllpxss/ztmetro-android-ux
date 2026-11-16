package org.ztmetro.settingsheader;

import android.view.View;
import android.widget.ImageView;

/**
 * Per-view icon removal inside a preference row — no walk of its own.
 *
 * <p>The ztmetro about row has no leading glyph and no trailing disclosure chevron, so the
 * walker sends every {@link ImageView} inside a row here.  Switch internals are exempt
 * (the thumb is part of the switch, not a row icon), and a view that is already
 * {@code GONE} is left alone so the post-layout pass reports no redundant applies.
 *
 * <p>Hiding the {@code ImageView} (rather than only adding end padding) is what removes the
 * chevron; the row margins themselves are normalised by {@link FlatRows}.
 */
final class RowIcons {

    private RowIcons() {
    }

    /** @return true when the view was hidden by this call. */
    static boolean hide(View view) {
        if (!(view instanceof ImageView)) {
            return false;
        }
        if (view.getVisibility() == View.GONE) {
            return false;
        }
        if (Views.isInsideSwitch(view)) {
            return false;
        }
        view.setVisibility(View.GONE);
        return true;
    }
}
