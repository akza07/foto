package com.phouton.phooto

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.ui.GalleryScreen
import com.phouton.phooto.ui.PermissionGate
import com.phouton.phooto.ui.theme.PhootoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge: the app's black background extends behind the status bar
        // and the navigation bar/pill, with content inset via Compose insets.
        enableEdgeToEdge()

        // Initialize settings so the persisted "prefer 120Hz" flag is loaded before
        // we read it below (GalleryScreen also calls init — it's idempotent).
        SettingsRepository.init(this)

        // Read settings once here so the window-level hint is in place before the
        // first frame is composed. The Compose tree still receives the flag via a
        // parameter so per-composable hints stay in sync if the user toggles the
        // setting later.
        val settings = SettingsRepository.settings.value
        if (settings.preferHighRefreshRate && this.displaySupportsHighRefreshRate()) {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = 120f
            }
        }

        setContent {
            PhootoTheme {
                PermissionGate(preferHighRefreshRate = settings.preferHighRefreshRate)
            }
        }
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
