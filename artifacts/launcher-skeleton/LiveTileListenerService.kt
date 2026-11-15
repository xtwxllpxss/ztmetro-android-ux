// ztmetro-style launcher — non-building skeleton (see README.md). MIT licence.
package org.ztmetro.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * "Live tiles" data source.
 *
 * Android facts this sketch relies on:
 *  - The user must grant notification access in Settings; without it the service is
 *    never bound (Android shows a prominent warning — respect that, do not try to
 *    hide it).
 *  - Only content the user's notifications expose is available; there is no API to
 *    read arbitrary app state for a tile.
 *  - The service runs in this app's process; keep it cheap (no bitmaps, no polling).
 *  - A launcher cannot draw over the system status bar or replace the shade; it can
 *    only render its own tiles and optional overlay banners (see docs/04).
 */
class LiveTileListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val key = sbn.packageName ?: return
        // TODO(stage-1): maintain per-package counts/ranked content in a bounded map,
        // persist to DataStore, and push to TileRepository via a process-scoped flow.
        // Do not store notification text longer than needed.
        debugCount(key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // TODO: decrement/remove the live-tile data for sbn.packageName.
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // The system reconnects when possible; do not fight it.
    }

    private fun debugCount(packageName: String) = Unit

    /** Helper to read an app's active notifications on demand. */
    @Suppress("unused")
    private fun currentNotifications(): List<StatusBarNotification> = activeNotifications?.toList().orEmpty()

    /** Example of what a tile could show: number of active notifications. */
    @Suppress("unused")
    private fun countFor(packageName: String): Int = currentNotifications().count { it.packageName == packageName }

    @Suppress("unused")
    private fun titleOf(notification: Notification): CharSequence? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)
}
