// ztmetro-style launcher — non-building skeleton (see README.md). MIT licence.
package org.ztmetro.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One Start-screen tile. */
data class Tile(
    val id: String,          // component name or a synthetic id for folders/settings
    val label: String,
    val packageName: String? = null,
    val size: TileSize = TileSize.SMALL,
    val unreadCount: Int = 0,
)

enum class TileSize { SMALL, MEDIUM, WIDE }

/**
 * Enumerates launchable apps and pinned tiles, and merges live-tile counts pushed by
 * [LiveTileListenerService].
 *
 * API notes:
 *  - `LauncherApps` is the public API intended for launchers (it also covers work
 *    profile apps through `getProfiles()`); `PackageManager.queryIntentActivities`
 *    with the LAUNCHER intent is the simpler fallback.
 *  - Package visibility on Android 11+: a launcher needs a `<queries>` element for
 *    the LAUNCHER intent in its manifest, or QUERY_ALL_PACKAGES (Play policy applies
 *    to store distribution; irrelevant for a system/preinstalled launcher).
 *  - Live counts come from a user-granted NotificationListenerService; the launcher
 *    itself must not poll notification content from other apps.
 */
class TileRepository(private val context: Context) {

    private val _tiles = MutableStateFlow<List<Tile>>(emptyList())
    val tiles: StateFlow<List<Tile>> = _tiles

    private val launcherApps: LauncherApps? =
        context.getSystemService(LauncherApps::class.java)

    init {
        // TODO(stage-1): read pinned tiles from DataStore, then merge installed apps.
        _tiles.value = installedApps().map { app ->
            Tile(id = app.first, label = app.second, packageName = app.first)
        }
    }

    /** (componentName, label) for every launchable activity of the current user. */
    private fun installedApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val component = "${activity.packageName}/${activity.name}"
            component to info.loadLabel(pm).toString()
        }
    }

    /** Called by the listener; a real implementation would debounce and persist. */
    fun onUnreadCounts(counts: Map<String, Int>) {
        _tiles.value = _tiles.value.map { tile ->
            val unread = tile.packageName?.let { counts[it] } ?: 0
            tile.copy(unreadCount = unread)
        }
    }
}
