package org.ztmetro.start;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WP app list: alphabetical letterboard, 56dp rows, 45dp accent square with a
 * white silhouette, ztmetro hollow-square section heads with a lowercase
 * letter, live filtering and the section map used by the right-edge A-Z strip.
 * Recycled ListView children keep a 300-app list cheap on the 2GB device.
 */
public final class AppListView extends ListView
        implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    public interface Listener {
        void onAppTap(TileStore.App app);

        void onAppLongPress(TileStore.App app);

        void onSectionsChanged(List<String> letters);
    }

    private final ArrayList<TileStore.App> allApps = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();
    private final ArrayList<String> sectionLetters = new ArrayList<>();
    private final ArrayList<Integer> sectionRows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private final TileArt art;
    private final int sidePadding;
    private final int indexReserve;
    private final int rowHeight;
    private Listener listener;
    private String query = "";

    public AppListView(Context context, TileArt art) {
        super(context);
        this.art = art;
        sidePadding = MetroUi.dimen(context, R.dimen.metro_page_margin);
        indexReserve = MetroUi.dimen(context, R.dimen.metro_round_button)
                + 2 * MetroUi.dimen(context, R.dimen.metro_tile_gap);
        rowHeight = MetroUi.dimen(context, R.dimen.metro_v3_list_row);
        setAdapter(adapter);
        setOnItemClickListener(this);
        setOnItemLongClickListener(this);
        setDivider(null);
        setDividerHeight(0);
        setSelector(new ColorDrawable(Color.TRANSPARENT));
        setCacheColorHint(Color.BLACK);
        setBackgroundColor(Color.BLACK);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setPadding(0, 0, 0, 0);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setApps(List<TileStore.App> apps) {
        allApps.clear();
        if (apps != null) {
            allApps.addAll(apps);
        }
        applyFilter(false);
    }

    /** Case-insensitive label filter; an empty query restores the full list. */
    public void setFilter(String text) {
        String next = text == null ? "" : text.trim().toLowerCase(Locale.getDefault());
        if (next.equals(query)) {
            return;
        }
        query = next;
        applyFilter(true);
    }

    public List<String> getSectionLetters() {
        return sectionLetters;
    }

    public void jumpToLetter(String letter) {
        if (letter == null) {
            return;
        }
        for (int i = 0; i < sectionLetters.size(); i++) {
            if (sectionLetters.get(i).equalsIgnoreCase(letter)) {
                setSelectionFromTop(sectionRows.get(i), 0);
                return;
            }
        }
    }

    private void applyFilter(boolean resetToTop) {
        int first = getFirstVisiblePosition();
        View firstView = getChildAt(0);
        int top = firstView == null ? 0 : firstView.getTop();

        rows.clear();
        sectionLetters.clear();
        sectionRows.clear();
        String letter = null;
        for (TileStore.App app : allApps) {
            if (!query.isEmpty() && !app.searchKey.contains(query)) {
                continue;
            }
            String appLetter = letterOf(app.label);
            if (!appLetter.equals(letter)) {
                sectionLetters.add(appLetter);
                sectionRows.add(rows.size());
                rows.add(appLetter);
                letter = appLetter;
            }
            rows.add(app);
        }
        adapter.notifyDataSetChanged();
        if (resetToTop) {
            setSelection(0);
        } else if (!rows.isEmpty()) {
            setSelectionFromTop(Math.min(first, rows.size() - 1), top);
        }
        if (listener != null) {
            listener.onSectionsChanged(sectionLetters);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Object row = rows.get(position);
        if (row instanceof TileStore.App && listener != null) {
            listener.onAppTap((TileStore.App) row);
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= rows.size()) {
            return false;
        }
        Object row = rows.get(position);
        if (row instanceof TileStore.App && listener != null) {
            listener.onAppLongPress((TileStore.App) row);
            return true;
        }
        return false;
    }

    private static String letterOf(String label) {
        if (label == null || label.length() == 0) {
            return "#";
        }
        char c = Character.toUpperCase(label.charAt(0));
        return Character.isLetter(c) ? String.valueOf(c) : "#";
    }

    private View buildRow(int position, View convertView) {
        Object row = rows.get(position);
        if (row instanceof String) {
            MetroSectionHeaderView header;
            if (convertView instanceof MetroSectionHeaderView) {
                header = (MetroSectionHeaderView) convertView;
            } else {
                header = new MetroSectionHeaderView(getContext());
                header.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            }
            header.setLetter((String) row);
            return header;
        }

        MetroListRowView rowView;
        if (convertView instanceof MetroListRowView) {
            rowView = (MetroListRowView) convertView;
        } else {
            rowView = new MetroListRowView(getContext(), art, indexReserve);
            rowView.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        }
        rowView.setApp((TileStore.App) row);
        return rowView;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return buildRow(position, convertView);
        }
    }
}
