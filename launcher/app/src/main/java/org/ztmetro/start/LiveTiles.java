package org.ztmetro.start;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-process snapshot of the live-tile data, filled by
 * {@link StartNotificationListener}.
 *
 * <p>The listener is OFF by default: with no grant the map stays empty and every
 * tile keeps its static glyph face. Nothing is persisted — this is one bounded
 * {@link HashMap} in the launcher process, rebuilt from the system's active
 * notification set whenever the listener reconnects.
 *
 * <p>A tile face is one of four shapes, decided here so the view only draws:
 * <ul>
 *   <li>{@link #KIND_MESSAGE} / {@link #KIND_EMAIL}: unread count + latest
 *       title/text line;</li>
 *   <li>{@link #KIND_MUSIC}: track title while a media notification exists;</li>
 *   <li>{@link #KIND_CALENDAR}: today's date, local clock, no permission.</li>
 * </ul>
 */
public final class LiveTiles {

    public static final int KIND_NONE = 0;
    public static final int KIND_MESSAGE = 1;
    public static final int KIND_EMAIL = 2;
    public static final int KIND_MUSIC = 3;
    public static final int KIND_CALENDAR = 4;

    /** Hard bound: the launcher never keeps more than this many app models. */
    static final int MAX_PACKAGES = 24;

    /** One package's live data. Time is the newest post time, used for ordering. */
    public static final class Entry {
        public final int kind;
        public final int count;
        public final String title;
        public final String text;
        public final long time;

        Entry(int kind, int count, String title, String text, long time) {
            this.kind = kind;
            this.count = count;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.time = time;
        }

        public Face face() {
            return new Face(kind, count, title, text, 0L);
        }
    }

    /** What a tile's second face shows; immutable, so the view can diff it. */
    public static final class Face {
        public final int kind;
        public final int count;
        public final String title;
        public final String text;
        /** Calendar day start in millis for KIND_CALENDAR, 0 otherwise. */
        public final long date;

        Face(int kind, int count, String title, String text, long date) {
            this.kind = kind;
            this.count = count;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.date = date;
        }

        /**
         * Cheap identity of what is on screen. Only displayed values are in it,
         * so a newer post time with the same text does not flip the tile.
         */
        public String signature() {
            if (kind == KIND_CALENDAR) {
                return "calendar:" + date;
            }
            return kind + ":" + count + ":" + title + ":" + text;
        }

        public boolean hasContent() {
            if (kind == KIND_CALENDAR) {
                return date > 0L;
            }
            if (kind == KIND_MUSIC) {
                return title.length() > 0 || text.length() > 0;
            }
            return count > 0;
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

    /**
     * The package a tile can show a live face for. Calendar is contentless from
     * the notification side: the tile can always draw its own date face.
     */
    public static boolean isCalendarPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        String lower = pkg.toLowerCase(Locale.US);
        return lower.contains("calendar") || lower.equals("com.google.android.calendar");
    }

    /** Today's date face, local timezone, at local midnight. */
    public static Face calendarFace(long now) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return new Face(KIND_CALENDAR, 0, "", "", day.getTimeInMillis());
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
        HashMap<String, Entry> bounded = next;
        if (next.size() > MAX_PACKAGES) {
            List<Map.Entry<String, Entry>> ordered = new ArrayList<>(next.entrySet());
            Collections.sort(ordered, new Comparator<Map.Entry<String, Entry>>() {
                @Override
                public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                    return Long.compare(b.getValue().time, a.getValue().time);
                }
            });
            bounded = new HashMap<>();
            for (int i = 0; i < MAX_PACKAGES && i < ordered.size(); i++) {
                bounded.put(ordered.get(i).getKey(), ordered.get(i).getValue());
            }
        }
        synchronized (ENTRIES) {
            ENTRIES.clear();
            ENTRIES.putAll(bounded);
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
