package org.ztmetro.start;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Installed apps (MAIN/LAUNCHER) plus the small pinned-tile store.
 *
 * The store is a JSON array in SharedPreferences: [{pkg,cls,size}]. Labels are
 * resolved from PackageManager on every load, so a renamed or updated app shows
 * its current label; tiles whose component disappeared are dropped silently.
 */
public final class TileStore {

    public static final int SIZE_MEDIUM = 0; // 2 x 2 tile units
    public static final int SIZE_WIDE = 1;   // 4 x 2 tile units
    public static final int SIZE_SMALL = 2;  // 1 x 1 tile unit

    private static final String PREFS = "start_tiles";
    private static final String KEY_TILES = "tiles";
    private static final String KEY_SEED_VERSION = "seed_version";
    private static final int SEED_VERSION = 3;
    private static final int MAX_SEED = 15;
    private static final int[] SEED_SIZES = {
            SIZE_MEDIUM, SIZE_MEDIUM, SIZE_MEDIUM, SIZE_WIDE, SIZE_MEDIUM,
            SIZE_SMALL, SIZE_SMALL, SIZE_MEDIUM, SIZE_SMALL, SIZE_SMALL,
            SIZE_MEDIUM, SIZE_SMALL, SIZE_SMALL, SIZE_MEDIUM, SIZE_MEDIUM
    };

    public static final class App {
        public final ComponentName component;
        public final String label;
        public final ApplicationInfo info;
        public final String key;
        public final String searchKey;

        App(ComponentName component, String label, ApplicationInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
            this.key = component.flattenToString();
            this.searchKey = label.toLowerCase(java.util.Locale.getDefault());
        }
    }

    public static final class Tile {
        public final ComponentName component;
        public final String label;
        public final int size;
        public final boolean appearance;

        Tile(ComponentName component, String label, int size) {
            this(component, label, size, false);
        }

        private Tile(ComponentName component, String label, int size, boolean appearance) {
            this.component = component;
            this.label = label;
            this.size = size;
            this.appearance = appearance;
        }

        /** Stable key for icon and live-data maps; the appearance tile has no component. */
        public String key() {
            return appearance ? "org.ztmetro.start/appearance" : component.flattenToString();
        }
    }

    /** The always-last tile that opens the appearance / settings sheet. */
    public static Tile appearanceTile(String label) {
        return new Tile(null, label, SIZE_WIDE, true);
    }

    private static final class RawTile {
        final ComponentName component;
        int size;

        RawTile(ComponentName component, int size) {
            this.component = component;
            this.size = size;
        }
    }

    private final Context context;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    public TileStore(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every launchable, exported activity of every other package, sorted by label. */
    public List<App> loadApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);
        ArrayList<App> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (resolved == null) {
            return result;
        }
        for (ResolveInfo ri : resolved) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !ai.exported || !ai.enabled) {
                continue;
            }
            if (context.getPackageName().equals(ai.packageName)) {
                continue;
            }
            ComponentName component = new ComponentName(ai.packageName, ai.name);
            String key = component.flattenToString();
            if (!seen.add(key)) {
                continue;
            }
            ApplicationInfo info = ai.applicationInfo;
            if (info == null) {
                try {
                    info = pm.getApplicationInfo(ai.packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
            }
            CharSequence label = ri.loadLabel(pm);
            String text = (label == null || label.length() == 0) ? ai.packageName : label.toString();
            result.add(new App(component, text, info));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                int byLabel = collator.compare(a.label, b.label);
                return byLabel != 0 ? byLabel : a.key.compareTo(b.key);
            }
        });
        return result;
    }

    /**
     * First run: pin the first apps with a WP size mix (one wide, eight medium,
     * six small) so all three tile shapes are visible out of the box and the
     * page is full on a 360x640 screen.
     *
     * Upgrades from v1/v2 keep their existing pins; v3 only appends four small
     * tiles once, because the old store could not express 1x1 at all and a v3
     * Start with no small tile does not demonstrate the WP block packing.
     */
    public void seedIfNeeded(List<App> apps) {
        if (apps.isEmpty()) {
            return;
        }
        int version = prefs.getInt(KEY_SEED_VERSION, 0);
        if (version >= SEED_VERSION) {
            return;
        }
        ArrayList<RawTile> tiles = readRaw();
        if (tiles.isEmpty()) {
            int count = Math.min(MAX_SEED, apps.size());
            for (int i = 0; i < count; i++) {
                tiles.add(new RawTile(apps.get(i).component, SEED_SIZES[i]));
            }
        } else {
            HashSet<String> pinned = new HashSet<>();
            for (RawTile tile : tiles) {
                pinned.add(tile.component.flattenToString());
            }
            int added = 0;
            for (App app : apps) {
                if (added >= 4) {
                    break;
                }
                if (pinned.add(app.key)) {
                    tiles.add(new RawTile(app.component, SIZE_SMALL));
                    added++;
                }
            }
            // v2 seeds were 8 tiles. Top the first v3 Start up to the v3 seed
            // count with mediums so an upgrade does not leave a half-empty page.
            for (App app : apps) {
                if (tiles.size() >= MAX_SEED) {
                    break;
                }
                if (pinned.add(app.key)) {
                    tiles.add(new RawTile(app.component, SIZE_MEDIUM));
                }
            }
        }
        writeRaw(tiles);
        prefs.edit().putInt(KEY_SEED_VERSION, SEED_VERSION).apply();
    }

    /** Pinned tiles validated against the current app list, in stored order. */
    public List<Tile> loadTiles(List<App> apps) {
        HashMap<String, App> byKey = new HashMap<>();
        for (App app : apps) {
            byKey.put(app.key, app);
        }
        ArrayList<Tile> result = new ArrayList<>();
        for (RawTile raw : readRaw()) {
            App app = byKey.get(raw.component.flattenToString());
            if (app != null) {
                result.add(new Tile(app.component, app.label, raw.size));
            }
        }
        return result;
    }

    public boolean isPinned(ComponentName component) {
        for (RawTile tile : readRaw()) {
            if (tile.component.equals(component)) {
                return true;
            }
        }
        return false;
    }

    /** Pin, or resize an already pinned tile. */
    public void pin(ComponentName component, int size) {
        ArrayList<RawTile> tiles = readRaw();
        for (RawTile tile : tiles) {
            if (tile.component.equals(component)) {
                tile.size = size;
                writeRaw(tiles);
                return;
            }
        }
        tiles.add(new RawTile(component, size));
        writeRaw(tiles);
    }

    public void setTileSize(ComponentName component, int size) {
        pin(component, size);
    }

    public void unpin(ComponentName component) {
        ArrayList<RawTile> tiles = readRaw();
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).component.equals(component)) {
                tiles.remove(i);
                writeRaw(tiles);
                return;
            }
        }
    }

    private ArrayList<RawTile> readRaw() {
        ArrayList<RawTile> result = new ArrayList<>();
        String json = prefs.getString(KEY_TILES, null);
        if (json == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pkg = o.optString("pkg", "");
                String cls = o.optString("cls", "");
                if (pkg.length() == 0 || cls.length() == 0) {
                    continue;
                }
                int raw = o.optInt("size", SIZE_MEDIUM);
                int size = raw == SIZE_WIDE ? SIZE_WIDE
                        : (raw == SIZE_SMALL ? SIZE_SMALL : SIZE_MEDIUM);
                result.add(new RawTile(new ComponentName(pkg, cls), size));
            }
        } catch (JSONException ignored) {
            // Corrupt store: behave like "nothing pinned" and let the user pin again.
        }
        return result;
    }

    private void writeRaw(List<RawTile> tiles) {
        JSONArray array = new JSONArray();
        for (RawTile tile : tiles) {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", tile.component.getPackageName());
                o.put("cls", tile.component.getClassName());
                o.put("size", tile.size);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(o);
        }
        prefs.edit().putString(KEY_TILES, array.toString()).apply();
    }
}
