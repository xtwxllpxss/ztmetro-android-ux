package org.ztmetro.start;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps posted notifications to app tiles: one entry per package with a count of
 * active notifications and the newest title/summary. The service is only bound
 * after the user grants notification access; without it Start stays static.
 */
public final class StartNotificationListener extends NotificationListenerService {

    private static final class Latest {
        long time;
        String title = "";
        String text = "";
    }

    /** package -> notification key -> post time. Only keys are retained. */
    private final HashMap<String, HashMap<String, Long>> active = new HashMap<>();
    private final HashMap<String, Latest> latest = new HashMap<>();

    @Override
    public void onListenerConnected() {
        rebuild();
    }

    @Override
    public void onListenerDisconnected() {
        active.clear();
        latest.clear();
        LiveTiles.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isTileNotification(sbn)) {
            return;
        }
        String pkg = sbn.getPackageName();
        HashMap<String, Long> keys = active.get(pkg);
        if (keys == null) {
            keys = new HashMap<>();
            active.put(pkg, keys);
        }
        keys.put(sbn.getKey(), sbn.getPostTime());
        Latest current = latest.get(pkg);
        if (current == null || sbn.getPostTime() >= current.time) {
            latest.put(pkg, latestOf(sbn));
        }
        publish();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        HashMap<String, Long> keys = active.get(sbn.getPackageName());
        if (keys != null) {
            keys.remove(sbn.getKey());
        }
        // The newest remaining notification may belong to another key, and the
        // count must drop exactly: one binder read of the active set rebuilds both.
        rebuild();
    }

    private void rebuild() {
        active.clear();
        latest.clear();
        StatusBarNotification[] notifications;
        try {
            notifications = getActiveNotifications();
        } catch (Exception e) {
            notifications = null;
        }
        if (notifications != null) {
            for (StatusBarNotification sbn : notifications) {
                if (!isTileNotification(sbn)) {
                    continue;
                }
                String pkg = sbn.getPackageName();
                HashMap<String, Long> keys = active.get(pkg);
                if (keys == null) {
                    keys = new HashMap<>();
                    active.put(pkg, keys);
                }
                keys.put(sbn.getKey(), sbn.getPostTime());
                Latest current = latest.get(pkg);
                if (current == null || sbn.getPostTime() >= current.time) {
                    latest.put(pkg, latestOf(sbn));
                }
            }
        }
        publish();
    }

    private static Latest latestOf(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n == null ? null : n.extras;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (isEmpty(text) && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if (isEmpty(text) && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        }
        if (isEmpty(text) && n != null) {
            text = n.tickerText;
        }
        Latest result = new Latest();
        result.time = sbn.getPostTime();
        result.title = clean(title, 80);
        result.text = clean(text, 120);
        return result;
    }

    private boolean isTileNotification(StatusBarNotification sbn) {
        if (getPackageName().equals(sbn.getPackageName())) {
            return false;
        }
        Notification n = sbn.getNotification();
        if (n == null) {
            return false;
        }
        // Group summaries duplicate the count of their children.
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    /** Current counts plus the newest content per package; no binder calls here. */
    private void publish() {
        HashMap<String, LiveTiles.Entry> entries = new HashMap<>();
        for (Map.Entry<String, HashMap<String, Long>> group : active.entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;
            }
            Latest content = latest.get(group.getKey());
            entries.put(group.getKey(), new LiveTiles.Entry(
                    group.getValue().size(),
                    content == null ? "" : content.title,
                    content == null ? "" : content.text));
        }
        LiveTiles.publish(entries);
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private static String clean(CharSequence value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.toString().replace('\n', ' ').replace('\r', ' ').trim();
        while (text.contains("  ")) {
            text = text.replace("  ", " ");
        }
        return text.length() > max ? text.substring(0, max).trim() : text;
    }
}
