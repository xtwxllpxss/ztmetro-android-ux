package org.ztmetro.actioncentre;

import android.content.Context;
import android.view.ViewGroup;

/**
 * 4-column quick-action grid with ztmetro's own numbers
 * ({@code ztmetro.c:6230-6245}, the mockup action-centre.html repeats them):
 *
 * <pre>
 *   SHADE_TOP 50   side 4   gap 3
 *   w = (width - 2*4 - 3*3) / 4        (85.75 at 360 dp)
 *   h = round(w * 0.66)                (57 at 360 dp)
 * </pre>
 *
 * <p>v3 shipped square cells, a 4 dp gutter and an 8 dp top pad, which made the
 * grid 37..245 dp on the 432 dp panel instead of 50..190. The rows are laid out
 * on their own height so the cells are the mockup's 1:0.66 rectangles.
 */
final class QuickGrid extends ViewGroup {
    private final int side;
    private final int gap;
    private int cellW;
    private int cellH;

    QuickGrid(Context context) {
        super(context);
        side = U.dp(context, 4);
        gap = U.dp(context, 3);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        cellW = (width - 2 * side - 3 * gap) / 4;
        cellH = Math.round(cellW * 0.66f);
        int height = 2 * cellH + gap;
        setMeasuredDimension(width, height);
        int wSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY);
        int hSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(wSpec, hSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int row = i / 4;
            int col = i % 4;
            int left = side + col * (cellW + gap);
            int top = row * (cellH + gap);
            getChildAt(i).layout(left, top, left + cellW, top + cellH);
        }
    }
}
