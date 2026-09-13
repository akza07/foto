package com.phouton.phooto.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.R
import com.phouton.phooto.data.AppSettings
import com.phouton.phooto.data.GalleryEvents
import com.phouton.phooto.data.GridSize
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.MediaRepository
import com.phouton.phooto.data.MediaSort
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.emptyTrashConsentSender
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
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showGridDialog by remember { mutableStateOf(false) }
    // System trash (API 30+). null while loading, empty when nothing is trashed.
    var trashItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var emptyingTrash by remember { mutableStateOf(false) }
    var pendingEmptyTrash by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    val emptyTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingEmptyTrash
        pendingEmptyTrash = emptyList()
        emptyingTrash = false
        if (result.resultCode == android.app.Activity.RESULT_OK && pending.isNotEmpty()) {
            trashItems = emptyList()
            GalleryEvents.requestReload()
            onSettingsChanged()
        }
    }

    LaunchedEffect(Unit) {
        cacheBytes = with(Dispatchers.IO) { ThumbCache.totalCacheBytes() }
        trashItems = MediaRepository.trashedItems(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        SettingsHeader(title = stringResource(R.string.settings_title), onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Display ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = stringResource(R.string.section_display)) }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = stringResource(R.string.display_refresh_title),
                        subtitle = stringResource(R.string.display_refresh_subtitle),
                        checked = settings.preferHighRefreshRate,
                        onCheckedChange = { SettingsRepository.setPreferHighRefreshRate(it) },
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.display_sort_title),
                        subtitle = stringResource(R.string.display_sort_subtitle),
                        trailingText = stringResource(settings.sortOrder.labelRes),
                        onClick = { showSortDialog = true },
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.display_grid_title),
                        subtitle = stringResource(R.string.display_grid_subtitle),
                        trailingText = stringResource(settings.gridSize.labelRes),
                        onClick = { showGridDialog = true },
                    )
                }
            }

            // ── Playback ─────────────────────────────────────────────
            item { SettingsSectionHeader(title = stringResource(R.string.section_playback), topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = stringResource(R.string.playback_loop_title),
                        subtitle = stringResource(R.string.playback_loop_subtitle),
                        checked = settings.loopVideos,
                        onCheckedChange = { SettingsRepository.setLoopVideos(it) },
                    )
                }
            }

            // ── Library ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = stringResource(R.string.section_library), topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        title = stringResource(R.string.library_nomedia_title),
                        subtitle = stringResource(R.string.library_nomedia_subtitle),
                        checked = settings.respectNoMedia,
                        onCheckedChange = {
                            SettingsRepository.setRespectNoMedia(it)
                            onSettingsChanged()
                        },
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = stringResource(R.string.library_require_explicit_title),
                        subtitle = if (settings.requireExplicit) {
                            stringResource(R.string.library_require_explicit_on)
                        } else {
                            stringResource(R.string.library_require_explicit_off)
                        },
                        checked = settings.requireExplicit,
                        onCheckedChange = {
                            SettingsRepository.setRequireExplicit(it)
                            onSettingsChanged()
                        },
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.library_manage_folders),
                        trailingText = folderCountLabel(settings.selectedTreeUris.size),
                        onClick = onManageFolders,
                    )
                }
            }

            // ── Storage ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = stringResource(R.string.section_storage), topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsActionRow(
                        title = stringResource(R.string.storage_clear_cache_title),
                        subtitle = when {
                            clearing -> stringResource(R.string.storage_clearing)
                            cacheBytes == null -> stringResource(R.string.storage_calculating)
                            cacheBytes == 0L -> stringResource(R.string.storage_nothing_cached)
                            else -> formatCacheSize(cacheBytes!!)
                        },
                        enabled = !clearing && (cacheBytes ?: 0L) > 0L,
                        showChevron = !clearing,
                        loading = clearing,
                        onClick = { showClearConfirm = true },
                    )
                    // The system trash only exists on API 30+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        SettingsDivider()
                        SettingsActionRow(
                            title = stringResource(R.string.storage_empty_trash_title),
                            subtitle = when {
                                emptyingTrash -> stringResource(R.string.storage_emptying_trash)
                                trashItems == null -> stringResource(R.string.storage_checking_trash)
                                trashItems!!.isEmpty() -> stringResource(R.string.storage_trash_empty)
                                else -> {
                                    val n = trashItems!!.size
                                    pluralStringResource(R.plurals.item_count, n, n)
                                }
                            },
                            enabled = !emptyingTrash && (trashItems?.isNotEmpty() == true),
                            showChevron = !emptyingTrash,
                            loading = emptyingTrash,
                            onClick = { showEmptyTrashConfirm = true },
                        )
                    }
                }
            }
            // ── About ────────────────────────────────────────────────
            item { SettingsSectionHeader(title = stringResource(R.string.section_about), topPadding = 16.dp) }
            item {
                SettingsGroup {
                    SettingsActionRow(
                        title = stringResource(R.string.about_app_name),
                        subtitle = stringResource(R.string.about_version, appVersion),
                        showChevron = false,
                        onClick = {},
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.settings_replay_tutorial),
                        subtitle = stringResource(R.string.settings_replay_tutorial_subtitle),
                        showChevron = false,
                        onClick = { SettingsRepository.setTutorialSeen(false) },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_cache_confirm_title),
            message = stringResource(R.string.clear_cache_confirm_message),
            confirmLabel = stringResource(R.string.action_clear),
            destructive = false,
            onConfirm = {
                showClearConfirm = false
                clearing = true
                scope.launch {
                    ThumbCache.clear()
                    VideoFrameCache.clear(context)
                    clearing = false
                    cacheBytes = 0L
                    // Ask gallery to reload so progress bar reappears and tiles re-decode.
                    GalleryEvents.requestReload()
                    onSettingsChanged()
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showEmptyTrashConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.empty_trash_confirm_title),
            message = stringResource(R.string.empty_trash_confirm_message),
            confirmLabel = stringResource(R.string.action_empty),
            destructive = true,
            onConfirm = {
                showEmptyTrashConfirm = false
                val items = trashItems.orEmpty()
                val sender = emptyTrashConsentSender(context, items)
                if (sender != null) {
                    pendingEmptyTrash = items
                    emptyingTrash = true
                    emptyTrashLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                    )
                }
            },
            onDismiss = { showEmptyTrashConfirm = false },
        )
    }

    if (showSortDialog) {
        OptionPickerDialog(
            title = stringResource(R.string.display_sort_title),
            options = MediaSort.entries,
            current = settings.sortOrder,
            labelRes = { it.labelRes },
            onSelect = { order ->
                showSortDialog = false
                SettingsRepository.setSortOrder(order)
                onSettingsChanged()
            },
            onDismiss = { showSortDialog = false },
        )
    }

    if (showGridDialog) {
        OptionPickerDialog(
            title = stringResource(R.string.display_grid_title),
            options = GridSize.entries,
            current = settings.gridSize,
            labelRes = { it.labelRes },
            onSelect = { size ->
                showGridDialog = false
                SettingsRepository.setGridSize(size)
            },
            onDismiss = { showGridDialog = false },
        )
    }
}

/** Simple single-choice dialog reused by the sort-order and grid-size rows. */
@Composable
private fun <T> OptionPickerDialog(
    title: String,
    options: List<T>,
    current: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
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
                    text = title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(option) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(labelRes(option)),
                            color = if (option == current) TextPrimary else TextMuted,
                            fontSize = 15.sp,
                            fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.selection_selected),
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
        SettingsHeader(title = stringResource(R.string.folders_title), onBack = onBack)

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
                        stringResource(R.string.folders_allow_hint)
                    } else {
                        stringResource(R.string.folders_deny_hint)
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
                            text = stringResource(R.string.folders_empty),
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
                        title = stringResource(R.string.folders_add_directory),
                        showChevron = false,
                        trailingIcon = Icons.Outlined.Add,
                        onClick = { treePicker.launch(null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun folderCountLabel(count: Int): String =
    pluralStringResource(R.plurals.folder_count, count, count)

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
private fun DirectoryRow(treeUri: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = directoryName(treeUri) ?: stringResource(R.string.folder_name_fallback),
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
                contentDescription = stringResource(R.string.action_remove),
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun directoryName(treeUri: String): String? {
    return try {
        val docId = Uri.parse(treeUri).lastPathSegment?.substringAfter(":")
        val raw = docId?.substringAfterLast("/", "")?.replace("_", " ")
        raw?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
