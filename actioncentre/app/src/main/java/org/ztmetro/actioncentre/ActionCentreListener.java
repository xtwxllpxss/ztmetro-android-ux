package org.ztmetro.actioncentre;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optional notification listener. The user enables it in system settings;
 * a normal app cannot grant it itself. It only reads what the system already
 * shows in the shade and can cancel those notifications on user request.
 */
public class ActionCentreListener extends NotificationListenerService {
    private static volatile ActionCentreListener instance;
    private static final Map<String, String> APP_LABELS = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        NotificationRepository.setConnected(false);
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        NotificationRepository.setConnected(true);
        publishAll();
    }

    @Override
    public void onListenerDisconnected() {
        NotificationRepository.setConnected(false);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Re-read the active set instead of patching the cache: it keeps group
        // summaries correct when children arrive/leave and is cheap at shade
        // sizes (tens of notifications).
        publishAll();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        publishAll();
    }

    private void publishAll() {
        List<Notif> list = new ArrayList<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                Set<String> groupsWithChildren = new HashSet<>();
                for (StatusBarNotification sbn : active) {
                    Notification notification = sbn.getNotification();
                    if (notification != null
                            && (notification.flags & Notification.FLAG_GROUP_SUMMARY) == 0
                            && sbn.getGroupKey() != null) {
                        groupsWithChildren.add(sbn.getGroupKey());
                    }
                }
                for (StatusBarNotification sbn : active) {
                    Notification notification = sbn.getNotification();
                    boolean summary = notification != null
                            && (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                    // A summary that has real children would duplicate them in
                    // the flat list; a standalone summary is still shown.
                    if (summary && sbn.getGroupKey() != null
                            && groupsWithChildren.contains(sbn.getGroupKey())) {
                        continue;
                    }
                    Notif notif = convert(sbn);
                    if (notif != null) {
                        list.add(notif);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        NotificationRepository.setAll(list);
    }

    private Notif convert(StatusBarNotification sbn) {
        try {
            Notification notification = sbn.getNotification();
            if (notification == null) {
                return null;
            }
            Bundle extras = notification.extras;
            if (extras == null) {
                extras = new Bundle();
            }
            String app = appLabel(sbn.getPackageName());
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null || text.length() == 0) {
                text = extras.getCharSequence(Notification.EXTRA_TEXT);
            }
            CharSequence sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (sub == null || sub.length() == 0) {
                sub = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            }
            String sender = sub == null || sub.length() == 0 ? app : sub.toString();
            return new Notif(
                    sbn.getKey(),
                    sbn.getPackageName(),
                    app,
                    sender,
                    title == null ? "" : title.toString(),
                    text == null ? "" : text.toString(),
                    sbn.getPostTime(),
                    notification.contentIntent);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String appLabel(String pkg) {
        synchronized (APP_LABELS) {
            String cached = APP_LABELS.get(pkg);
            if (cached != null) {
                return cached;
            }
        }
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            CharSequence name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (name != null && name.length() > 0) {
                label = name.toString();
            }
        } catch (Throwable ignored) {
        }
        synchronized (APP_LABELS) {
            APP_LABELS.put(pkg, label);
        }
        return label;
    }

    /** Ask the system to rebind the service; harmless when access is not granted. */
    static void ensureRebind(Context context) {
        try {
            requestRebind(new ComponentName(context.getPackageName(),
                    ActionCentreListener.class.getName()));
        } catch (Throwable ignored) {
        }
    }

    static boolean isAccessGranted(Context context) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationListenerAccessGranted(
                    new ComponentName(context.getPackageName(),
                            ActionCentreListener.class.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean cancelAll() {
        ActionCentreListener service = instance;
        if (service == null) {
            return false;
        }
        try {
            service.cancelAllNotifications();
        } catch (Throwable ignored) {
            return false;
        }
        NotificationRepository.clear();
        return true;
    }

    static void cancelKey(String key) {
        ActionCentreListener service = instance;
        if (service != null && key != null) {
            try {
                service.cancelNotification(key);
            } catch (Throwable ignored) {
            }
        }
    }
}
