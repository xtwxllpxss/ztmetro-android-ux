package org.ztmetro.start;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Per-package live-tile model fed by the system notification listener.
 *
 * <p>The service is only bound after the user grants notification access
 * ({@code Settings > Apps > Special access > Notification access} or
 * {@code adb shell cmd notification allow_listener
 * org.ztmetro.start/org.ztmetro.start.StartNotificationListener}). Without the
 * grant the system never binds it, the map stays empty, and every tile keeps
 * its static glyph face.
 *
 * <p>Nothing is persisted: one bounded in-memory map of package -> newest
 * title/text/time plus the set of active notification keys used for the unread
 * count. Group summaries are ignored (their children carry the count), and
 * ongoing/foreground notifications are not "unread" — except media, whose
 * notification exists exactly so the tile can show the track.
 *
 * <p>The model is intentionally narrow: messaging/email count + latest line,
 * media track title, and the calendar date (the launcher derives the date
 * itself, no notification needed). Any other package stays glyph-only.
 */
public final class StartNotificationListener extends NotificationListenerService {

    /** Upper bounds for a process that lives as long as the launcher. */
    private static final int MAX_KEYS_PER_PACKAGE = 48;

    private static final String[] MESSAGE_HINTS = {
            "messag", "sms", "mms", "messenger", "whatsapp", "telegram", "signal",
            "viber", "discord", "slack", "chat", "wechat", "talk"
    };

    private static final class Latest {
        long time;
        int kind = LiveTiles.KIND_NONE;
        String title = "";
        String text = "";
    }

    /** package -> notification key -> post time. Only keys are retained. */
    private final HashMap<String, HashMap<String, Long>> active = new HashMap<>();
    /** package -> newest displayed content. Holds at most MAX_PACKAGES. */
    private final HashMap<String, Latest> latest = new HashMap<>();

    /**
     * The live binding, or null. Pass 2 releases the listener when Start has been
     * hidden for two minutes ({@link ListenerWindow}); this reference is the only
     * way to call {@code requestUnbind()} without keeping a context alive.
     */
    private static volatile StartNotificationListener sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    /**
     * Pass 2: release the system binding so the launcher process stops being kept
     * alive by the listener. Returns false when the listener is not bound (no
     * grant, or already released), which makes the caller's log line honest.
     */
    static boolean requestUnbindIfConnected() {
        StartNotificationListener instance = sInstance;
        if (instance == null) {
            return false;
        }
        try {
            instance.requestUnbind();
            return true;
        } catch (Throwable t) {
            Log.w(ListenerWindow.TAG, "requestUnbind failed", t);
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        rebuild();
        Log.i(ListenerWindow.TAG, "notification listener connected: "
                + latest.size() + " live package(s), " + active.size() + " active key set(s)");
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
        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();

        if (isMedia(notification)) {
            // Music is content, not an unread item: keep the track, add no count.
            recordLatest(pkg, sbn, LiveTiles.KIND_MUSIC);
        } else {
            HashMap<String, Long> keys = active.get(pkg);
            if (keys == null) {
                keys = new HashMap<>();
                active.put(pkg, keys);
            }
            keys.put(sbn.getKey(), sbn.getPostTime());
            recordLatest(pkg, sbn, kindOf(sbn, notification));
        }
        prune();
        publish();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        // The newest remaining notification may belong to another key and the
        // count must drop exactly: one binder read of the active set rebuilds
        // both. Dismiss-all triggers a few of these; each is O(active).
        rebuild();
    }

    /**
     * Full rebuild from the system's active set. Also the first fill at
     * onListenerConnected, which is how a launcher process restart recovers
     * the live faces without persisting anything.
     */
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
                if (sbn == null || !isTileNotification(sbn)) {
                    continue;
                }
                Notification notification = sbn.getNotification();
                if (isMedia(notification)) {
                    recordLatest(sbn.getPackageName(), sbn, LiveTiles.KIND_MUSIC);
                    continue;
                }
                HashMap<String, Long> keys = active.get(sbn.getPackageName());
                if (keys == null) {
                    keys = new HashMap<>();
                    active.put(sbn.getPackageName(), keys);
                }
                keys.put(sbn.getKey(), sbn.getPostTime());
                recordLatest(sbn.getPackageName(), sbn, kindOf(sbn, notification));
            }
        }
        prune();
        publish();
    }

    private void recordLatest(String pkg, StatusBarNotification sbn, int kind) {
        Latest current = latest.get(pkg);
        if (current != null && sbn.getPostTime() < current.time) {
            return;
        }
        if (kind == LiveTiles.KIND_NONE && current != null
                && current.kind != LiveTiles.KIND_NONE) {
            // A plain notification from the same app (no message/email/media
            // category) must not erase the face of an older unread message.
            return;
        }
        Latest next = new Latest();
        next.time = sbn.getPostTime();
        next.kind = kind;
        Bundle extras = sbn.getNotification() == null ? null : sbn.getNotification().extras;
        if (extras != null) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (isEmpty(title)) {
                title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            }
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (isEmpty(text)) {
                text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            }
            if (isEmpty(text)) {
                text = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
            }
            if (isEmpty(text) && sbn.getNotification() != null) {
                text = sbn.getNotification().tickerText;
            }
            next.title = clean(title, 80);
            next.text = clean(text, 140);
        }
        latest.put(pkg, next);
    }

    /** Message/email/media classification; everything else stays glyph-only. */
    private static int kindOf(StatusBarNotification sbn, Notification notification) {
        if (notification == null) {
            return LiveTiles.KIND_NONE;
        }
        if (Notification.CATEGORY_MESSAGE.equals(notification.category)) {
            return LiveTiles.KIND_MESSAGE;
        }
        if (Notification.CATEGORY_EMAIL.equals(notification.category)) {
            return LiveTiles.KIND_EMAIL;
        }
        String pkg = sbn.getPackageName() == null
                ? "" : sbn.getPackageName().toLowerCase(Locale.US);
        if (pkg.equals("com.google.android.gm") || pkg.contains("mail")
                || pkg.contains("outlook") || pkg.contains("k9")) {
            return LiveTiles.KIND_EMAIL;
        }
        for (String hint : MESSAGE_HINTS) {
            if (pkg.contains(hint)) {
                return LiveTiles.KIND_MESSAGE;
            }
        }
        return LiveTiles.KIND_NONE;
    }

    /** MediaStyle / media session notification: the tile can show the track. */
    private static boolean isMedia(Notification notification) {
        if (notification == null) {
            return false;
        }
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)) {
            return true;
        }
        Bundle extras = notification.extras;
        return extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    /**
     * Our own notifications never count. Group summaries are skipped because
     * their children are the unread items. Ongoing/foreground notifications are
     * services, not unread messages, unless they are media.
     */
    private boolean isTileNotification(StatusBarNotification sbn) {
        if (getPackageName().equals(sbn.getPackageName())) {
            return false;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return false;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return false;
        }
        if (isMedia(notification)) {
            return true;
        }
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0
                && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0;
    }

    /** Keeps the map small: newest MAX_PACKAGES and at most MAX_KEYS_PER_PACKAGE. */
    private void prune() {
        for (Map.Entry<String, HashMap<String, Long>> group : active.entrySet()) {
            HashMap<String, Long> keys = group.getValue();
            while (keys.size() > MAX_KEYS_PER_PACKAGE) {
                String oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<String, Long> key : keys.entrySet()) {
                    if (key.getValue() < oldestTime) {
                        oldestTime = key.getValue();
                        oldest = key.getKey();
                    }
                }
                if (oldest == null) {
                    break;
                }
                keys.remove(oldest);
            }
        }
        for (Iterator<Map.Entry<String, HashMap<String, Long>>> it =
                active.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
        while (latest.size() > LiveTiles.MAX_PACKAGES) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Latest> entry : latest.entrySet()) {
                if (entry.getValue().time < oldestTime) {
                    oldestTime = entry.getValue().time;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            latest.remove(oldest);
            active.remove(oldest);
        }
    }

    /** Current counts plus the newest content per package; no binder calls here. */
    private void publish() {
        HashMap<String, LiveTiles.Entry> entries = new HashMap<>();
        for (Map.Entry<String, Latest> group : latest.entrySet()) {
            Latest content = group.getValue();
            if (content == null || content.kind == LiveTiles.KIND_NONE) {
                continue;
            }
            HashMap<String, Long> keys = active.get(group.getKey());
            int count = keys == null ? 0 : keys.size();
            if (content.kind != LiveTiles.KIND_MUSIC && count == 0) {
                continue;
            }
            entries.put(group.getKey(), new LiveTiles.Entry(
                    content.kind, content.kind == LiveTiles.KIND_MUSIC ? 0 : count,
                    content.title, content.text, content.time));
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
