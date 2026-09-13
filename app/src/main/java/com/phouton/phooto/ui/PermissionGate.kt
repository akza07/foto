package com.phouton.phooto.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phouton.phooto.R
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
import com.phouton.phooto.ui.theme.Surface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary

private val ShadcnRadius = RoundedCornerShape(12.dp)

@Composable
fun PermissionGate(
    preferHighRefreshRate: Boolean = false,
    openUri: android.net.Uri? = null,
    onOpenUriHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(allGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = allGranted(context)
    }

    // Re-check whenever we return to the foreground (permissions can be revoked
    // in Settings, and "don't ask again" + settings round-trips need a refresh).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = allGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Per-composable vote for a higher refresh rate. The platform aggregates
    // these votes with any other surfaces in the window and picks the highest
    // compatible rate. Applying it here propagates to GalleryScreen,
    // FullImageViewer, and SettingsScreen without further changes.
    val rootModifier = if (preferHighRefreshRate) {
        Modifier.preferredFrameRate(120f)
    } else {
        Modifier
    }

    if (granted) {
        Box(modifier = rootModifier.fillMaxSize()) {
            GalleryScreen(
                modifier = Modifier.fillMaxSize(),
                openUri = openUri,
                onOpenUriHandled = onOpenUriHandled,
            )
            val settings by SettingsRepository.settings.collectAsState()
            if (!settings.tutorialSeen) {
                TutorialScreen(onDone = { SettingsRepository.setTutorialSeen(true) })
            }
        }
    } else {
        val permissions = requiredPermissions()
        val shouldShowRationale = permissions.any { permission ->
            val activity = context as? android.app.Activity
            activity != null && activity.shouldShowRequestPermissionRationale(permission)
        }

        Box(
            modifier = rootModifier
                .fillMaxSize()
                .background(AmoledBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            PermissionCard(
                shouldShowRationale = shouldShowRationale,
                onRequest = { launcher.launch(permissions.toTypedArray()) },
                onOpenSettings = { context.openAppSettings() },
            )
        }
    }
}

@Composable
private fun PermissionCard(
    shouldShowRationale: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .background(Surface, ShadcnRadius),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AmoledBlack, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                text = stringResource(R.string.permission_title),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.permission_message),
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = ShadcnRadius,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = Surface,
                ),
            ) {
                Text(
                    text = stringResource(R.string.permission_grant),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (shouldShowRationale) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = ShadcnRadius,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                ) {
                    Text(
                        text = stringResource(R.string.permission_open_settings),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun requiredPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun allGranted(context: android.content.Context): Boolean {
    return requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private fun android.content.Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
