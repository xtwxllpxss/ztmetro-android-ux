package org.ztmetro.start;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * In-process snapshot of active notifications, filled by StartNotificationListener.
 * The listener is OFF by default: with no grant the map stays empty and tiles are
 * static. Only the latest title/summary per package and a small key set are kept.
 */
public final class LiveTiles {

    public static final class Entry {
        public final int count;
        public final String title;
        public final String text;

        Entry(int count, String title, String text) {
            this.count = count;
            this.title = title;
            this.text = text;
        }

        /** Cheap change detector for the tile refresh path. */
        public String signature() {
            return count + "\u0000" + title + "\u0000" + text;
        }
    }

    public interface Listener {
        void onLiveTilesChanged();
    }

    private static final HashMap<String, Entry> ENTRIES = new HashMap<>();
    private static final ArrayList<Listener> LISTENERS = new ArrayList<>();

    private LiveTiles() {
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context.getPackageName(),
                StartNotificationListener.class.getName());
    }

    /** True when this package is in Settings.Secure enabled_notification_listeners. */
    public static boolean isEnabled(Context context) {
        String enabled;
        try {
            enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
        } catch (Exception e) {
            return false;
        }
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        ComponentName ours = component(context);
        for (String part : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(part);
            if (parsed != null && parsed.equals(ours)) {
                return true;
            }
        }
        return false;
    }

    public static Entry get(String pkg) {
        if (pkg == null) {
            return null;
        }
        synchronized (ENTRIES) {
            return ENTRIES.get(pkg);
        }
    }

    public static int badgeTotal() {
        int total = 0;
        synchronized (ENTRIES) {
            for (Entry entry : ENTRIES.values()) {
                total += entry.count;
            }
        }
        return total;
    }

    public static void register(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(listener)) {
                LISTENERS.add(listener);
            }
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    static void publish(HashMap<String, Entry> next) {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            ENTRIES.putAll(next);
        }
        Listener[] snapshot;
        synchronized (LISTENERS) {
            snapshot = LISTENERS.toArray(new Listener[0]);
        }
        for (Listener listener : snapshot) {
            listener.onLiveTilesChanged();
        }
    }

    static void clear() {
        publish(new HashMap<String, Entry>());
    }
}
