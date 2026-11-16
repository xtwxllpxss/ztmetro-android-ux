package org.ztmetro.actioncentre;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process cache shared by the NotificationListenerService and the panel.
 * The service is the only writer; the activity only reads and calls the
 * listener's cancel methods.
 */
final class NotificationRepository {
    interface Listener {
        void onNotificationsChanged();
    }

    private static final Object LOCK = new Object();
    private static final List<Notif> ITEMS = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Comparator<Notif> NEWEST_FIRST = new Comparator<Notif>() {
        @Override
        public int compare(Notif a, Notif b) {
            return Long.compare(b.time, a.time);
        }
    };
    private static volatile boolean connected;

    private NotificationRepository() {
    }

    static void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            notifyChanged();
        }
    }

    static boolean isConnected() {
        return connected;
    }

    static void setAll(List<Notif> incoming) {
        synchronized (LOCK) {
            ITEMS.clear();
            if (incoming != null) {
                ITEMS.addAll(incoming);
            }
            Collections.sort(ITEMS, NEWEST_FIRST);
        }
        notifyChanged();
    }

    static void upsert(Notif notif) {
        if (notif == null) {
            return;
        }
        synchronized (LOCK) {
            for (int i = 0; i < ITEMS.size(); i++) {
                if (ITEMS.get(i).key.equals(notif.key)) {
                    ITEMS.remove(i);
                    break;
                }
            }
            ITEMS.add(notif);
            Collections.sort(ITEMS, NEWEST_FIRST);
        }
        notifyChanged();
    }

    static void removeKey(String key) {
        if (key == null) {
            return;
        }
        boolean removed = false;
        synchronized (LOCK) {
            for (int i = 0; i < ITEMS.size(); i++) {
                if (key.equals(ITEMS.get(i).key)) {
                    ITEMS.remove(i);
                    removed = true;
                    break;
                }
            }
        }
        if (removed) {
            notifyChanged();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            ITEMS.clear();
        }
        notifyChanged();
    }

    static List<Notif> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(ITEMS);
        }
    }

    static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyChanged() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : LISTENERS) {
                    try {
                        listener.onNotificationsChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
