package org.ztmetro.settingsheader;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.TextView;

/**
 * Per-row / per-group flattening helpers — no walk of their own: {@link ScreenPass}
 * dispatches each accepted row here exactly once per pass.
 *
 * <p>A preference row becomes a flat black about-style row:
 *
 * <ol>
 *   <li>background → {@code #000000} (the Material card/surface drawable is replaced);</li>
 *   <li>elevation → 0 (no card shadow);</li>
 *   <li>padding → 24 dp page margin left/right, 4 dp top/bottom (the frame's compact
 *       two-line pitch), no right-side slot reserved for a chevron;</li>
 *   <li>card margins → 0 when they are card insets (≤ 24 dp horizontal, ≤ 32 dp
 *       vertical);</li>
 *   <li>the title's start inset (the stock icon cell, ≤ 96 dp) is cleared so hiding the
 *       icon does not leave a ~60 dp hole; its end inset is cleared as well unless the
 *       row carries a switch, so no space is kept for the removed disclosure chevron.</li>
 * </ol>
 *
 * <p>A row-wrapping group (a preference category card) is painted black and its
 * Material group gaps are zeroed when the group is short; a page-height group keeps its
 * margins (they are structure) but still loses the grey card fill.
 */
final class FlatRows {

    private static final String TAG = "ztmetro-header";
    private static final int BLACK = Color.BLACK;

    /*
     * Per-view style cache. The low bits are what has been applied to this view,
     * the bits above them are the configuration epoch (page margin / vertical
     * padding). A layout-param write happens at most once per view per epoch, so
     * a second pass — or a RecyclerView re-bind — never issues requestLayout()
     * for a row that is already styled.
     */
    private static final int BIT_START_INSETS = 1;
    private static final int BIT_END_INSETS = 2;
    private static final int BIT_CARD_MARGINS = 4;
    private static final int BIT_GROUP_GAPS = 8;
    /** Owned by ScreenPass.flattenCard: CardView fill/radius already flat. */
    static final int BIT_CARD_FLAT = 16;
    private static final int STYLE_MASK = 0x1F;

    /** Resource id of res/values/ids.xml#metro_style; 0 disables the cache. */
    private static int sStyleTagId;
    private static int sEpoch = 1;
    private static int sConfiguredMargin = -1;
    private static int sConfiguredPadV = -1;

    private FlatRows() {
    }

    /**
     * Resolves the tag key and bumps the epoch when the layout numbers change
     * (density or font scale), which invalidates every cached row.
     */
    static void configure(Resources module, int pageMargin, int padV) {
        if (module != null && sStyleTagId == 0) {
            try {
                sStyleTagId = module.getIdentifier("metro_style", "id",
                        HeaderResources.MODULE_PACKAGE);
            } catch (Throwable ignored) {
                sStyleTagId = 0;
            }
        }
        if (pageMargin != sConfiguredMargin || padV != sConfiguredPadV) {
            sConfiguredMargin = pageMargin;
            sConfiguredPadV = padV;
            sEpoch++;
        }
    }

    private static int appliedBits(View view) {
        if (sStyleTagId == 0) {
            return 0;
        }
        Object tag = view.getTag(sStyleTagId);
        if (!(tag instanceof Integer)) {
            return 0;
        }
        int value = (Integer) tag;
        return (value >> 5) == sEpoch ? value & STYLE_MASK : 0;
    }

    private static void markBits(View view, int bits) {
        if (sStyleTagId == 0) {
            return;
        }
        view.setTag(sStyleTagId, Integer.valueOf((sEpoch << 5) | (bits & STYLE_MASK)));
    }

    /** True when {@code bit} is already applied under the current epoch. */
    static boolean isApplied(View view, int bit) {
        return (appliedBits(view) & bit) != 0;
    }

    /** Records one more applied bit without disturbing the others. */
    static void markApplied(View view, int bit) {
        markBits(view, appliedBits(view) | bit);
    }

    /**
     * @param title     the row's title (may be null if the row layout is unusual)
     * @param hasSwitch true when the row carries a switch: keep the end inset (the switch
     *                  needs its cell)
     * @return true when at least one property changed
     */
    static boolean flattenRow(ViewGroup row, int pageMargin, int padV,
                              TextView title, boolean hasSwitch, int insetLimit) {
        try {
            boolean changed = false;
            int cached = appliedBits(row);
            if (!isBlack(row.getBackground())) {
                row.setBackgroundColor(BLACK);
                changed = true;
            }
            if (row.getPaddingLeft() != pageMargin || row.getPaddingRight() != pageMargin
                    || row.getPaddingTop() != padV || row.getPaddingBottom() != padV) {
                row.setPadding(pageMargin, padV, pageMargin, padV);
                changed = true;
            }
            try {
                if (row.getElevation() != 0f) {
                    row.setElevation(0f);
                    changed = true;
                }
            } catch (Throwable ignored) {
                // pre-L only, minSdk is 29
            }
            if (title != null) {
                if ((cached & BIT_START_INSETS) == 0) {
                    if (clearStartInsets(title, row, insetLimit)) {
                        changed = true;
                    }
                    cached |= BIT_START_INSETS;
                }
                if (!hasSwitch && (cached & BIT_END_INSETS) == 0) {
                    if (clearEndInsets(title, row, insetLimit)) {
                        changed = true;
                    }
                    cached |= BIT_END_INSETS;
                }
            }
            if ((cached & BIT_CARD_MARGINS) == 0) {
                if (clearCardMargins(row, pageMargin)) {
                    changed = true;
                }
                cached |= BIT_CARD_MARGINS;
            }
            markBits(row, cached);
            return changed;
        } catch (Throwable t) {
            Log.d(TAG, "row flatten skipped for " + row.getClass().getSimpleName() + ": " + t);
            return false;
        }
    }

    /**
     * @param clearGaps true for a short group (a category card); false for a page-height
     *                  container, whose margins are structure
     */
    static boolean flattenGroup(ViewGroup group, int gapLimit, boolean clearGaps) {
        try {
            boolean changed = false;
            if (!isBlack(group.getBackground())) {
                group.setBackgroundColor(BLACK);
                changed = true;
            }
            try {
                if (group.getElevation() != 0f) {
                    group.setElevation(0f);
                    changed = true;
                }
            } catch (Throwable ignored) {
                // pre-L only
            }
            if (clearGaps) {
                int cached = appliedBits(group);
                if ((cached & BIT_GROUP_GAPS) == 0) {
                    ViewGroup.LayoutParams params = group.getLayoutParams();
                    if (params instanceof MarginLayoutParams) {
                        MarginLayoutParams margins = (MarginLayoutParams) params;
                        boolean marginsChanged = false;
                        if (margins.topMargin != 0 && Math.abs(margins.topMargin) <= gapLimit) {
                            margins.topMargin = 0;
                            marginsChanged = true;
                        }
                        if (margins.bottomMargin != 0
                                && Math.abs(margins.bottomMargin) <= gapLimit) {
                            margins.bottomMargin = 0;
                            marginsChanged = true;
                        }
                        if (marginsChanged) {
                            group.setLayoutParams(margins);
                            changed = true;
                        }
                    }
                    markBits(group, cached | BIT_GROUP_GAPS);
                }
            }
            return changed;
        } catch (Throwable t) {
            Log.d(TAG, "group flatten skipped for " + group.getClass().getSimpleName() + ": " + t);
            return false;
        }
    }

    private static boolean clearCardMargins(ViewGroup row, int pageMargin) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (!(params instanceof MarginLayoutParams)) {
            return false;
        }
        MarginLayoutParams margins = (MarginLayoutParams) params;
        boolean changed = false;
        if (margins.leftMargin != 0 && Math.abs(margins.leftMargin) <= pageMargin) {
            margins.leftMargin = 0;
            changed = true;
        }
        if (margins.rightMargin != 0 && Math.abs(margins.rightMargin) <= pageMargin) {
            margins.rightMargin = 0;
            changed = true;
        }
        int verticalInset = pageMargin + pageMargin / 2;   // 36 dp at the 24 dp page margin
        if (margins.topMargin != 0 && Math.abs(margins.topMargin) <= verticalInset) {
            margins.topMargin = 0;
            changed = true;
        }
        if (margins.bottomMargin != 0 && Math.abs(margins.bottomMargin) <= verticalInset) {
            margins.bottomMargin = 0;
            changed = true;
        }
        if (changed) {
            row.setLayoutParams(margins);
        }
        return changed;
    }

    /** Clears the stock icon-cell inset between the row edge and the title. */
    private static boolean clearStartInsets(View title, ViewGroup row, int limit) {
        boolean changed = false;
        View current = title;
        while (current != null && current != row) {
            if (current instanceof ViewGroup
                    && current.getPaddingStart() > 0 && current.getPaddingStart() <= limit) {
                current.setPaddingRelative(0, current.getPaddingTop(),
                        current.getPaddingEnd(), current.getPaddingBottom());
                changed = true;
            }
            ViewGroup.LayoutParams params = current.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                if (margins.getMarginStart() > 0 && margins.getMarginStart() <= limit) {
                    margins.setMarginStart(0);
                    current.setLayoutParams(margins);
                    changed = true;
                }
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return changed;
    }

    /** Clears the stock end inset that reserved the disclosure chevron. */
    private static boolean clearEndInsets(View title, ViewGroup row, int limit) {
        boolean changed = false;
        View current = title;
        while (current != null && current != row) {
            if (current instanceof ViewGroup
                    && current.getPaddingEnd() > 0 && current.getPaddingEnd() <= limit) {
                current.setPaddingRelative(current.getPaddingStart(), current.getPaddingTop(),
                        0, current.getPaddingBottom());
                changed = true;
            }
            ViewGroup.LayoutParams params = current.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) params;
                if (margins.getMarginEnd() > 0 && margins.getMarginEnd() <= limit) {
                    margins.setMarginEnd(0);
                    current.setLayoutParams(margins);
                    changed = true;
                }
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return changed;
    }

    private static boolean isBlack(Drawable drawable) {
        return drawable instanceof ColorDrawable
                && ((ColorDrawable) drawable).getColor() == BLACK;
    }
}
