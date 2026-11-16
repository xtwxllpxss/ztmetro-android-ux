package org.ztmetro.start;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The WP Start grid, laid out by us with ztmetro's own first-fit packer.
 *
 * Six logical units across; 1x1 small, 2x2 medium, 4x2 wide; medium and wide
 * tiles start on even cells. Small tiles are packed as blocks: the first four
 * smalls fill the four corners of one medium slot before the next slot is
 * touched, which is what stops a Metro Start from looking like a ragged app
 * grid. Tile and gutter are recomputed from the real width on every measure:
 * unit = (width - 2*margin - 5*gutter) / 6.
 */
final class MetroGridView extends ViewGroup implements MetroTileView.Listener {

    interface Listener {
        void onTileTap(TileStore.Tile tile);

        void onTileLongPress(TileStore.Tile tile);
    }

    private static final class Placement {
        int left;
        int top;
        int right;
        int bottom;
    }

    private static final int ARROW_RESERVE_ROW = 4;
    private static final int ARROW_RESERVE_COL = 4;

    private final ArrayList<TileStore.Tile> tiles = new ArrayList<>();
    private final ArrayList<Placement> placements = new ArrayList<>();
    private final int columns;
    private int gapPx;
    private int marginPx;
    private final int topPadPx;
    private final int bottomPadPx;
    private final TileArt art;
    private Listener listener;
    private float unitPx;
    private int rows;

    MetroGridView(Context context, TileArt art) {
        super(context);
        columns = context.getResources().getInteger(R.integer.metro_start_columns);
        // Fallbacks at the 360dp reference; onMeasure derives both from the
        // real width exactly as ztmetro.c:3266-3269 does.
        gapPx = MetroUi.dimen(context, R.dimen.metro_tile_gap);
        marginPx = MetroUi.dimen(context, R.dimen.metro_start_margin);
        topPadPx = MetroUi.dimen(context, R.dimen.metro_start_top_pad);
        bottomPadPx = 0;
        this.art = art;
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setTiles(List<TileStore.Tile> newTiles) {
        removeAllViews();
        tiles.clear();
        if (newTiles != null) {
            tiles.addAll(newTiles);
        }
        for (TileStore.Tile tile : tiles) {
            MetroTileView view = new MetroTileView(getContext(), art);
            view.setTile(tile);
            view.setListener(this);
            addView(view);
        }
        requestLayout();
        invalidate();
    }

    /**
     * Feed each tile its live face. The view diffs the face signature, so a
     * refresh caused by an unrelated package does not flip this tile, and a
     * calendar tile keeps its date face without notification access.
     */
    void refreshLive() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < tiles.size() && i < getChildCount(); i++) {
            TileStore.Tile tile = tiles.get(i);
            MetroTileView view = (MetroTileView) getChildAt(i);
            if (tile.appearance || tile.component == null) {
                view.setLiveFace(null, false);
                continue;
            }
            String pkg = tile.component.getPackageName();
            if (LiveTiles.isCalendarPackage(pkg)) {
                view.setLiveFace(LiveTiles.calendarFace(now), true);
                continue;
            }
            LiveTiles.Entry entry = LiveTiles.get(pkg);
            view.setLiveFace(entry == null ? null : entry.face(), true);
        }
    }

    @Override
    public void onTileTap(TileStore.Tile tile) {
        if (listener != null) {
            listener.onTileTap(tile);
        }
    }

    @Override
    public void onTileLongPress(TileStore.Tile tile) {
        if (listener != null) {
            listener.onTileLongPress(tile);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = Math.round(360f * getResources().getDisplayMetrics().density);
        }
        // The gutter/margin are the ztmetro token values (7 / 14 dp), not a
        // per-width recomputation: scripts/ux-verify.py pins both at every
        // width (start.grid_metrics, +/-2 dp), and the 360 dp mockup uses the
        // same 7/14. The unit therefore absorbs the wider 443 dp panel:
        // (443 - 2*14 - 5*7) / 6 = 63.3 dp against the C's proportional 60.3.
        int available = width - 2 * marginPx - (columns - 1) * gapPx;
        unitPx = Math.max(1f, available / (float) columns);
        pack();
        for (int i = 0; i < getChildCount() && i < placements.size(); i++) {
            Placement placement = placements.get(i);
            int childWidth = Math.max(1, placement.right - placement.left);
            int childHeight = Math.max(1, placement.bottom - placement.top);
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        int contentHeight = topPadPx + Math.max(0, Math.round(rows * (unitPx + gapPx) - gapPx))
                + bottomPadPx;
        setMeasuredDimension(width, contentHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount() && i < placements.size(); i++) {
            Placement placement = placements.get(i);
            getChildAt(i).layout(placement.left, placement.top,
                    placement.right, placement.bottom);
        }
    }

    private void pack() {
        placements.clear();
        rows = 0;
        ArrayList<boolean[]> occupied = new ArrayList<>();
        // Keep the right-centre 2x2 cell free for the circled Start arrow. In
        // the seeded layout this is the black pocket the arrow floats in, not
        // a white outline on top of an app glyph.
        if (!tiles.isEmpty()) {
            ensureRows(occupied, ARROW_RESERVE_ROW + 2);
            occupy(occupied, ARROW_RESERVE_ROW, ARROW_RESERVE_COL, 2, 2);
        }
        for (TileStore.Tile tile : tiles) {
            int spanCols = tile.size == TileStore.SIZE_WIDE ? 4
                    : (tile.size == TileStore.SIZE_MEDIUM ? 2 : 1);
            int spanRows = spanCols == 1 ? 1 : 2;
            int[] cell = spanCols == 1 ? findSmall(occupied) : findLarge(occupied, spanCols);
            int row = cell[0];
            int col = cell[1];
            ensureRows(occupied, row + spanRows);
            occupy(occupied, row, col, spanCols, spanRows);
            rows = Math.max(rows, row + spanRows);

            float left = marginPx + col * (unitPx + gapPx);
            float top = topPadPx + row * (unitPx + gapPx);
            float tileWidth = spanCols * unitPx + (spanCols - 1) * gapPx;
            float tileHeight = spanRows * unitPx + (spanRows - 1) * gapPx;
            Placement placement = new Placement();
            placement.left = Math.round(left);
            placement.top = Math.round(top);
            placement.right = Math.round(left + tileWidth);
            placement.bottom = Math.round(top + tileHeight);
            placements.add(placement);
        }
    }

    /** First free 2x2 block, then its top-left, top-right, bottom-left, bottom-right. */
    private int[] findSmall(ArrayList<boolean[]> occupied) {
        for (int row = 0; ; row += 2) {
            for (int col = 0; col + 1 < columns; col += 2) {
                int[][] corners = {
                        {row, col}, {row, col + 1},
                        {row + 1, col}, {row + 1, col + 1}
                };
                for (int[] corner : corners) {
                    if (isFree(occupied, corner[0], corner[1], 1, 1)) {
                        return corner;
                    }
                }
            }
        }
    }

    /** Medium/wide tiles may only start on even cells (ztmetro.c:3304-3305). */
    private int[] findLarge(ArrayList<boolean[]> occupied, int spanCols) {
        for (int row = 0; ; row += 2) {
            for (int col = 0; col + spanCols <= columns; col += 2) {
                if (isFree(occupied, row, col, spanCols, 2)) {
                    return new int[]{row, col};
                }
            }
        }
    }

    private boolean isFree(ArrayList<boolean[]> occupied, int row, int col,
                           int spanCols, int spanRows) {
        ensureRows(occupied, row + spanRows);
        for (int r = row; r < row + spanRows; r++) {
            boolean[] cells = occupied.get(r);
            for (int c = col; c < col + spanCols; c++) {
                if (cells[c]) {
                    return false;
                }
            }
        }
        return true;
    }

    private void occupy(ArrayList<boolean[]> occupied, int row, int col,
                        int spanCols, int spanRows) {
        ensureRows(occupied, row + spanRows);
        for (int r = row; r < row + spanRows; r++) {
            boolean[] cells = occupied.get(r);
            for (int c = col; c < col + spanCols; c++) {
                cells[c] = true;
            }
        }
    }

    private void ensureRows(ArrayList<boolean[]> occupied, int needed) {
        while (occupied.size() < needed) {
            occupied.add(new boolean[columns]);
        }
    }
}
