package com.phouton.phooto.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.data.AppSettings
import com.phouton.phooto.data.MediaSort
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.thumbnails.ExternalReloadSink
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.data.thumbnails.VideoFrameCache
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Surface as ThemeSurface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings, grouped by intent:
 *
 * - Display: window/refresh behavior.
 * - Playback: how videos play.
 * - Library: which media is shown and where it comes from. Changes here affect
 *   the media list, so they call [onSettingsChanged] to reload the gallery.
 * - Storage: cache management.
 *
 * The folder list lives in a nested [FoldersScreen] so this root stays short.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onSettingsChanged: () -> Unit) {
    val settings by SettingsRepository.settings.collectAsState()
    var showFolders by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        SettingsRoot(
            settings = settings,
            onBack = onBack,
            onSettingsChanged = onSettingsChanged,
            onManageFolders = { showFolders = true },
        )

        AnimatedVisibility(
            visible = showFolders,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            FoldersScreen(
                settings = settings,
                onBack = { showFolders = false },
                onSettingsChanged = onSettingsChanged,
            )
        }
    }
}

@Composable
private fun SettingsRoot(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
    onManageFolders: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cacheBytes = with(Dispatchers.IO) { ThumbCache.totalCacheBytes() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        SettingsHeader(title = "Settings", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Display ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Display") }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = "Prefer 120Hz refresh rate",
                        subtitle = "Run the display at up to 120Hz on supported devices.",
                        checked = settings.preferHighRefreshRate,
                        onCheckedChange = { SettingsRepository.setPreferHighRefreshRate(it) },
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Sort order",
                        subtitle = "How media and folders are ordered.",
                        trailingText = settings.sortOrder.label,
                        onClick = { showSortDialog = true },
                    )
                }
            }

            // ── Playback ─────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Playback", topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = "Loop videos",
                        subtitle = "Restart a video when it ends.",
                        checked = settings.loopVideos,
                        onCheckedChange = { SettingsRepository.setLoopVideos(it) },
                    )
                }
            }

            // ── Library ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Library", topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = "Respect .nomedia",
                        subtitle = "Hide media in folders marked .nomedia.",
                        checked = settings.respectNoMedia,
                        onCheckedChange = {
                            SettingsRepository.setRespectNoMedia(it)
                            onSettingsChanged()
                        },
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Only show selected folders",
                        subtitle = if (settings.requireExplicit) {
                            "Show only media inside the folders you add."
                        } else {
                            "Show all media except the folders you add."
                        },
                        checked = settings.requireExplicit,
                        onCheckedChange = {
                            SettingsRepository.setRequireExplicit(it)
                            onSettingsChanged()
                        },
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Manage folders",
                        trailingText = folderCountLabel(settings.selectedTreeUris.size),
                        onClick = onManageFolders,
                    )
                }
            }

            // ── Storage ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "Storage", topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsActionRow(
                        title = "Clear thumbnail cache",
                        subtitle = when {
                            clearing -> "Clearing…"
                            cacheBytes == null -> "Calculating…"
                            cacheBytes == 0L -> "Nothing cached"
                            else -> formatCacheSize(cacheBytes!!)
                        },
                        enabled = !clearing && (cacheBytes ?: 0L) > 0L,
                        showChevron = !clearing,
                        loading = clearing,
                        onClick = { showClearConfirm = true },
                    )
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
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showSortDialog) {
        SortOrderDialog(
            current = settings.sortOrder,
            onSelect = { order ->
                showSortDialog = false
                SettingsRepository.setSortOrder(order)
                onSettingsChanged()
            },
            onDismiss = { showSortDialog = false },
        )
    }
}

@Composable
private fun SortOrderDialog(
    current: MediaSort,
    onSelect: (MediaSort) -> Unit,
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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Sort order",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                MediaSort.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(option) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.label,
                            color = if (option == current) TextPrimary else TextMuted,
                            fontSize = 15.sp,
                            fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldersScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
) {
    val context = LocalContext.current

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

    // System back returns to the settings root; GalleryScreen's BackHandler is
    // composed earlier, so this one wins while the sub-screen is open.
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        SettingsHeader(title = "Folders", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = if (settings.requireExplicit) {
                        "Only media inside these folders is shown."
                    } else {
                        "Media inside these folders is hidden. Everything else is shown."
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (settings.selectedTreeUris.isEmpty()) {
                item {
                    SettingsGroup {
                        Text(
                            text = "No folders added",
                            color = TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        settings.selectedTreeUris.forEachIndexed { index, uri ->
                            if (index > 0) SettingsDivider()
                            DirectoryRow(
                                treeUri = uri,
                                onRemove = {
                                    SettingsRepository.removeTreeUri(uri)
                                    onSettingsChanged()
                                },
                            )
                        }
                    }
                }
            }

            item {
                SettingsGroup {
                    SettingsActionRow(
                        title = "Add directory",
                        showChevron = false,
                        trailingIcon = Icons.Outlined.Add,
                        onClick = { treePicker.launch(null) },
                    )
                }
            }
        }
    }
}

private fun folderCountLabel(count: Int): String = when (count) {
    0 -> "None"
    1 -> "1 folder"
    else -> "$count folders"
}

private fun formatCacheSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB", mb)
        kb >= 1 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
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
                        color = Color.Transparent,
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
private fun DirectoryRow(treeUri: String, onRemove: () -> Unit) {
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
                modifier = Modifier.size(20.dp),
            )
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
