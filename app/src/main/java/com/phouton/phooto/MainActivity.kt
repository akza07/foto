package com.phouton.phooto

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.thumbnails.ExternalReloadSink
import com.phouton.phooto.data.thumbnails.SyncCoordinator
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.ui.GalleryScreen
import com.phouton.phooto.ui.PermissionGate
import com.phouton.phooto.ui.theme.PhootoTheme

class MainActivity : ComponentActivity() {
    private lateinit var syncCoordinator: SyncCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge: the app's black background extends behind the status bar
        // and the navigation bar/pill, with content inset via Compose insets.
        enableEdgeToEdge()

        // Initialize settings so the persisted "prefer 120Hz" flag is available to
        // the composition below (GalleryScreen also calls init — it's idempotent).
        SettingsRepository.init(this)

        // Set up the Coil image-loader-backed thumbnail cache before any Compose
        // code runs so the first gallery frame can already hit it.
        ThumbCache.init(this)

        // Whether the hardware can do better than 60Hz doesn't change at
        // runtime, so probe it once. The preference itself is observed below so
        // toggling it takes effect without restarting the app.
        val supportsHighRefreshRate = displaySupportsHighRefreshRate()

        setContent {
            val settings by SettingsRepository.settings.collectAsState()
            val preferHighRefreshRate =
                settings.preferHighRefreshRate && supportsHighRefreshRate

            // Window-level refresh hint, kept in sync with the setting. 0f means
            // "no preference". PermissionGate additionally votes at the Compose
            // level for surfaces inside the window.
            LaunchedEffect(preferHighRefreshRate) {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = if (preferHighRefreshRate) 120f else 0f
                }
            }

            PhootoTheme {
                PermissionGate(preferHighRefreshRate = preferHighRefreshRate)
            }
        }

        // Observe MediaStore so we can reconcile the thumbnail cache when files
        // are added or removed. The actual reload is plumbed by GalleryScreen
        // registering its `reload` lambda on ExternalReloadSink when composed.
        syncCoordinator = SyncCoordinator(onChange = {
            ExternalReloadSink.current?.invoke()
        })
        syncCoordinator.start(this)
    }

    override fun onDestroy() {
        if (::syncCoordinator.isInitialized) {
            syncCoordinator.stop(this)
        }
        ThumbCache.shutdown()
        super.onDestroy()
    }
}

/**
 * Returns true if the default display advertises any mode with a refresh rate
 * above ~60Hz. Used to avoid requesting a rate the hardware can't deliver. The
 * 0.5f margin filters rounding noise (e.g. 59.97Hz displays reporting 60Hz).
 */
private fun Context.displaySupportsHighRefreshRate(): Boolean {
    val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
    val display: Display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    return display.supportedModes.any { it.refreshRate > 60.5f }
}

private const val AmoledPreviewBackground = 0xFF000000

@Preview(
    name = "Permission gate",
    showBackground = true,
    backgroundColor = AmoledPreviewBackground,
)
@Composable
private fun PermissionGatePreview() {
    PhootoTheme {
        PermissionGate()
    }
}

@Preview(
    name = "Gallery",
    showBackground = true,
    backgroundColor = AmoledPreviewBackground,
    widthDp = 360,
    heightDp = 640,
)
@Composable
private fun GalleryPreview() {
    PhootoTheme {
        GalleryScreen()
    }
}
