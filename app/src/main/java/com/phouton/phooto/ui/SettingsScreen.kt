package com.phouton.phooto.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.thumbnails.ExternalReloadSink
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.data.thumbnails.VideoFrameCache
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
import com.phouton.phooto.ui.theme.Surface as ThemeSurface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlinx.coroutines.launch

private val RowShape = RoundedCornerShape(12.dp)

@Composable
fun SettingsScreen(onBack: () -> Unit, onSettingsChanged: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cacheBytes = with(kotlinx.coroutines.Dispatchers.IO) { ThumbCache.totalCacheBytes() }
    }

    val refreshCacheSize = {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = ThumbCache.totalCacheBytes()
            cacheBytes = bytes
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
            }
            SettingsRepository.addTreeUri(uri.toString())
            onSettingsChanged()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        // Header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Settings",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Display ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Display") }

            item {
                SettingsToggleRow(
                    title = "Prefer 120Hz refresh rate",
                    subtitle = "Ask the system to run at up to 120Hz on supported displays for smoother scrolling and animations. Has no effect on devices that don't support it.",
                    checked = settings.preferHighRefreshRate,
                    onCheckedChange = {
                        SettingsRepository.setPreferHighRefreshRate(it)
                        onSettingsChanged()
                    },
                )
            }

            // ── Storage ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Storage", topPadding = 16.dp) }

            item {
                val subtitle = when {
                    clearing -> "Clearing…"
                    cacheBytes == null -> "Calculating…"
                    cacheBytes == 0L -> "Nothing cached"
                    else -> formatCacheSize(cacheBytes!!)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .clickable(enabled = !clearing && (cacheBytes ?: 0L) > 0L) {
                            showClearConfirm = true
                        },
                    shape = RowShape,
                    color = ThemeSurface,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (clearing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp),
                                    strokeWidth = 2.dp,
                                    color = TextPrimary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.width(20.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clear thumbnail cache",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = subtitle,
                                color = TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // ── Content Discovery ────────────────────────────────────
            item { SettingsSectionHeader(title = "Content Discovery", topPadding = 16.dp) }

            item {
                SettingsToggleRow(
                    title = "Respect .nomedia",
                    subtitle = "Hide MediaStore media in .nomedia folders. Folders you add are always shown.",
                    checked = settings.respectNoMedia,
                    onCheckedChange = {
                        SettingsRepository.setRespectNoMedia(it)
                        onSettingsChanged()
                    },
                )
            }

            item {
                SettingsToggleRow(
                    title = "Require explicit permission",
                    subtitle = if (settings.requireExplicit) {
                        "Show only media inside the directories you add (including .nomedia folders)."
                    } else {
                        "Show all media, except the directories you add."
                    },
                    checked = settings.requireExplicit,
                    onCheckedChange = {
                        SettingsRepository.setRequireExplicit(it)
                        onSettingsChanged()
                    },
                )
            }

            item {
                Text(
                    text = when {
                        settings.selectedTreeUris.isEmpty() -> {
                            if (settings.requireExplicit) {
                                "No directories added. Nothing will be shown."
                            } else {
                                "No directories added. All media is shown."
                            }
                        }
                        settings.requireExplicit -> {
                            "${settings.selectedTreeUris.size} director${
                                if (settings.selectedTreeUris.size == 1) "y" else "ies"
                            } selected — only these are shown."
                        }
                        else -> {
                            "${settings.selectedTreeUris.size} director${
                                if (settings.selectedTreeUris.size == 1) "y" else "ies"
                            } selected — these are hidden."
                        }
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(settings.selectedTreeUris) { uri ->
                DirectoryRow(
                    treeUri = uri,
                    onRemove = {
                        SettingsRepository.removeTreeUri(uri)
                        onSettingsChanged()
                    },
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .clickable { treePicker.launch(null) },
                    shape = RowShape,
                    color = ThemeSurface,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.width(20.dp),
                            )
                        }
                        Text(
                            text = "Add directory",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        ClearCacheConfirmDialog(
            onConfirm = {
                showClearConfirm = false
                clearing = true
                scope.launch {
                    ThumbCache.clear()
                    VideoFrameCache.clear(context)
                    clearing = false
                    cacheBytes = 0L
                    // Ask gallery to reload so progress bar reappears and tiles re-decode.
                    ExternalReloadSink.current?.invoke()
                    onSettingsChanged()
                    refreshCacheSize()
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    topPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        HorizontalDivider(
            color = Border,
            thickness = 1.dp,
        )
        Spacer(Modifier.height(4.dp))
    }
}

private fun formatCacheSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB cached", mb)
        kb >= 1 -> String.format("%.0f KB cached", kb)
        else -> "$bytes B cached"
    }
}

@Composable
private fun ClearCacheConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss, indication = null, interactionSource = null),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(16.dp),
            color = ThemeSurface,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Clear thumbnail cache?",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Cached previews will be deleted and regenerated the next time you scroll. This may take a moment.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss, indication = null, interactionSource = null),
                        shape = RoundedCornerShape(10.dp),
                        color = androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Cancel",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onConfirm, indication = null, interactionSource = null),
                        shape = RoundedCornerShape(10.dp),
                        color = TextPrimary,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Clear",
                                color = AmoledBlack,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape),
        shape = RowShape,
        color = ThemeSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AmoledBlack,
                    checkedTrackColor = TextPrimary,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Border,
                    uncheckedBorderColor = Border,
                ),
            )
        }
    }
}

@Composable
private fun DirectoryRow(treeUri: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape),
        shape = RowShape,
        color = ThemeSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = directoryName(treeUri),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = treeUri,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove",
                    tint = TextMuted,
                    modifier = Modifier.width(20.dp),
                )
            }
        }
    }
}

private fun directoryName(treeUri: String): String {
    return try {
        val docId = Uri.parse(treeUri).lastPathSegment?.substringAfter(":")
        val raw = docId?.substringAfterLast("/", "")?.replace("_", " ")
        raw?.takeIf { it.isNotBlank() } ?: "Selected directory"
    } catch (_: Exception) {
        "Selected directory"
    }
}
