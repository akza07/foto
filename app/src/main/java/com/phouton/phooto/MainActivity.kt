package com.phouton.phooto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.phouton.phooto.ui.GalleryScreen
import com.phouton.phooto.ui.PermissionGate
import com.phouton.phooto.ui.theme.PhootoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge: the app's black background extends behind the status bar
        // and the navigation bar/pill, with content inset via Compose insets.
        enableEdgeToEdge()
        setContent {
            PhootoTheme {
                PermissionGate()
            }
        }
    }
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
