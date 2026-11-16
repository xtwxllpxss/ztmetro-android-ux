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

    Notif(String key, String packageName, String appLabel, String sender, String title,
          String text, long time, PendingIntent contentIntent) {
        this.key = key;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.sender = sender;
        this.title = title;
        this.text = text;
        this.time = time;
        this.contentIntent = contentIntent;
    }
}
