package fool.akza.foto

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import fool.akza.foto.data.GalleryEvents
import fool.akza.foto.data.GallerySnapshot
import fool.akza.foto.data.SettingsRepository
import fool.akza.foto.data.thumbnails.SyncCoordinator
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.ui.GalleryScreen
import fool.akza.foto.ui.LocalWindowInsetsGuard
import fool.akza.foto.ui.PermissionGate
import fool.akza.foto.ui.WindowInsetsGuard
import fool.akza.foto.ui.theme.FotoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var syncCoordinator: SyncCoordinator

    /** Pins the bar insets while the full-screen viewer runs immersive. */
    private val insetsGuard = WindowInsetsGuard()

    /** A media Uri handed to us by another app (VIEW/SEND), consumed by the UI. */
    private val pendingOpenUri = MutableStateFlow<Uri?>(null)

    /** Off-main-thread scope for the launch snapshot prefetch. */
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge: the app's black background extends behind the status bar
        // and the navigation bar/pill, with content inset via Compose insets.
        enableEdgeToEdge()
        // The UI is always AMOLED-black, so the status-bar content must stay light
        // (white) even when the device is in light mode. `enableEdgeToEdge()` picks
        // the icon tint from the system light/dark theme, which would otherwise
        // draw dark icons on the app's black chrome. The full-screen viewer goes
        // further and hides the bars entirely (immersive mode).
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        // Keep the last Java crash on disk so a device-only failure can be
        // shared from Settings instead of guessed at.
        CrashLog.install(this)

        // Initialize settings so the persisted "prefer 120Hz" flag is available to
        // the composition below (GalleryScreen also calls init — it's idempotent).
        SettingsRepository.init(this)

        // Set up the Coil image-loader-backed thumbnail cache before any Compose
        // code runs so the first gallery frame can already hit it.
        ThumbCache.init(this)

        // Start reading the last gallery off disk before the composition is
        // built, so the gallery's first frame can already show it instead of a
        // spinner. The read is a few tens of KB of tab-separated records.
        snapshotScope.launch { GallerySnapshot.prefetch(this@MainActivity) }

        // Media handed over by another app at launch.
        pendingOpenUri.value = extractOpenUri(intent)

        // Whether the hardware can do better than 60Hz doesn't change at
        // runtime, so probe it once. The preference itself is observed below so
        // toggling it takes effect without restarting the app.
        val supportsHighRefreshRate = displaySupportsHighRefreshRate()

        setContent {
            val settings by SettingsRepository.settings.collectAsState()
            val preferHighRefreshRate =
                settings.preferHighRefreshRate && supportsHighRefreshRate
            val openUri by pendingOpenUri.collectAsState()

            // Window-level refresh hint, kept in sync with the setting. 0f means
            // "no preference". PermissionGate additionally votes at the Compose
            // level for surfaces inside the window.
            LaunchedEffect(preferHighRefreshRate) {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = if (preferHighRefreshRate) 120f else 0f
                }
            }

            CompositionLocalProvider(LocalWindowInsetsGuard provides insetsGuard) {
                FotoTheme {
                    PermissionGate(
                        preferHighRefreshRate = preferHighRefreshRate,
                        openUri = openUri,
                        onOpenUriHandled = { pendingOpenUri.value = null },
                    )
                }
            }
        }

        // Report the window's bar insets to the guard. Hiding the system bars
        // animates the real insets to zero; the guard substitutes the pre-hide
        // values while the viewer is open so nothing in the layout moves.
        findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
                insetsGuard.record(insets)
                insetsGuard.apply(insets)
            }
        }

        // Observe MediaStore so we can reconcile the thumbnail cache when files
        // are added or removed. The debounced signal is emitted through
        // GalleryEvents and collected by the composed gallery.
        syncCoordinator = SyncCoordinator(onChange = {
            GalleryEvents.requestReload()
        })
        syncCoordinator.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenUri.value = extractOpenUri(intent)
    }

    override fun onDestroy() {
        if (::syncCoordinator.isInitialized) {
            syncCoordinator.stop(this)
        }
        snapshotScope.cancel()
        ThumbCache.shutdown()
        super.onDestroy()
    }
}

/** The single media Uri carried by a VIEW or SEND intent, if any. */
private fun extractOpenUri(intent: Intent?): Uri? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
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
    FotoTheme {
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
    FotoTheme {
        GalleryScreen()
    }
}
