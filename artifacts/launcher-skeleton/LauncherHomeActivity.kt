// ztmetro-style launcher — non-building skeleton (see README.md in this folder).
// Licence: MIT. API surface sketched for Android 16 (API 36); not compiled or tested.
package org.ztmetro.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Stage-1 home screen: Start page with tiles + entry to the all-apps list.
 *
 * Android roles used here:
 *  - HOME activity: declared in AndroidManifest with MAIN + CATEGORY_HOME, so the
 *    system/user can select this app as the default home app.
 *  - No privileged permissions: tiles are drawn by this app; live counts come from
 *    a separate NotificationListenerService bound by the user's grant.
 */
class LauncherHomeActivity : ComponentActivity() {

    private val repository = TileRepository(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15/16: apps are edge-to-edge by default for targetSdk 35+.
        // Use the platform helper and consume insets (see docs/02).
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StartScreen(repository = repository)
                }
            }
        }
    }

    @Composable
    private fun StartScreen(repository: TileRepository) {
        val tiles by repository.tiles.collectAsState()

        Scaffold { innerPadding ->
            // TODO(stage-1): replace Scaffold with the ztmetro status/nav metrics once
            // the real system bars strategy is decided (see docs/07 mapping table).
            LazyVerticalGrid(
                columns = GridCells.Fixed(4), // 4 small tiles per row at 480dp width
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(tiles, key = { it.id }) { tile ->
                    // TODO: Tile composable — square, flat, accent only when selected.
                    Text(text = tile.label, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }

    /** Optional: open the launcher's own "action centre" activity. */
    @Suppress("unused")
    private fun openActionCentre() {
        startActivity(Intent(this, ActionCentreActivity::class.java))
    }
}

/**
 * Separate activity (not a service!) that shows the notification list in a WP-like
 * layout. It cannot replace the system shade; it is opened from a Start tile.
 */
class ActionCentreActivity : ComponentActivity()
