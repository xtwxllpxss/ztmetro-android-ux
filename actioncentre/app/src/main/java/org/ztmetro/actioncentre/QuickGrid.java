package org.ztmetro.actioncentre;

import android.content.Context;
import android.view.ViewGroup;

/** Fixed 4-column grid of square quick-action tiles, 4 dp gutters. */
final class QuickGrid extends ViewGroup {
    private final int side;
    private final int gap;
    private final int padTop;
    private final int padBottom;
    private int cell;

    QuickGrid(Context context) {
        super(context);
        side = U.dp(context, 4);
        gap = U.dp(context, 4);
        padTop = U.dp(context, 8);
        padBottom = U.dp(context, 4);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        cell = (width - 2 * side - 3 * gap) / 4;
        int height = 2 * cell + gap + padTop + padBottom;
        setMeasuredDimension(width, height);
        int exact = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(exact, exact);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int row = i / 4;
            int col = i % 4;
            int left = side + col * (cell + gap);
            int top = padTop + row * (cell + gap);
            getChildAt(i).layout(left, top, left + cell, top + cell);
        }
    }
}
