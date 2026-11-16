package org.ztmetro.actioncentre;

import android.app.PendingIntent;

/** Immutable snapshot of one posted notification for the panel list. */
final class Notif {
    final String key;
    final String packageName;
    final String appLabel;
    final String sender;
    final String title;
    final String text;
    final long time;
    final PendingIntent contentIntent;
    /** First notification action; the tap fallback when contentIntent is missing. */
    final PendingIntent actionIntent;
    /** Title of that first action, for the log line only. */
    final String actionTitle;

    Notif(String key, String packageName, String appLabel, String sender, String title,
          String text, long time, PendingIntent contentIntent,
          PendingIntent actionIntent, String actionTitle) {
        this.key = key;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.sender = sender;
        this.title = title;
        this.text = text;
        this.time = time;
        this.contentIntent = contentIntent;
        this.actionIntent = actionIntent;
        this.actionTitle = actionTitle == null ? "" : actionTitle;
    }
}
