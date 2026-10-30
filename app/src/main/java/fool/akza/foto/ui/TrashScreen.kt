package fool.akza.foto.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.PrivateTrash
import fool.akza.foto.data.SystemTrash
import fool.akza.foto.data.TrashEntry
import fool.akza.foto.data.TrashRepository
import fool.akza.foto.data.TrashSource
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.RaisedSurface
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foto's Trash screen: Android's system trash plus the app-private SAF trash.
 *
 * MediaStore items are moved into the platform trash, which is instant and
 * shared with the system. SAF documents (for example media from added folders)
 * have no platform trash, so [PrivateTrash] keeps their bytes in app storage
 * until the user restores or permanently deletes them here.
 */
@Composable
fun TrashScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var previewItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var previewIndex by remember { mutableStateOf(0) }
    var previewSourceRect by remember { mutableStateOf(Rect.Zero) }
    val thumbRects = remember { mutableMapOf<android.net.Uri, Rect>() }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var pendingPrivateDelete by remember { mutableStateOf<TrashEntry?>(null) }
    // Private entries to remove after the system-trash delete prompt succeeds.
    var pendingPrivateEmpty by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    // True only for the "Empty" request, so the header spinner reads correctly
    // while a single restore/delete is in flight.
    var emptying by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    // URIs the pending provider dialog will act on (empty when nothing is pending).
    var pendingUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    BackHandler { onBack() }

    suspend fun refresh() {
        entries = TrashRepository.list(context)
    }

    LaunchedEffect(Unit) { refresh() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingUris
        val privateEmpty = pendingPrivateEmpty
        pendingUris = emptyList()
        pendingPrivateEmpty = emptyList()
        working = false
        emptying = false
        if (result.resultCode == Activity.RESULT_OK &&
            (uris.isNotEmpty() || privateEmpty.isNotEmpty())
        ) {
            scope.launch {
                if (privateEmpty.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        privateEmpty.forEach { PrivateTrash.delete(context, it) }
                    }
                }
                refresh()
                onChanged()
            }
        }
    }

    val run = { uris: List<android.net.Uri>, privateEmpty: List<TrashEntry>, request: (List<android.net.Uri>) -> android.content.IntentSender? ->
        pendingPrivateEmpty = privateEmpty
        val sender = request(uris)
        if (sender == null) {
            pendingPrivateEmpty = emptyList()
            working = false
            emptying = false
        } else {
            pendingUris = uris
            working = true
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    val restorePrivate: (TrashEntry) -> Unit = { entry ->
        if (!working) {
            working = true
            scope.launch {
                val restored = withContext(Dispatchers.IO) { PrivateTrash.restore(context, entry) }
                working = false
                if (restored) {
                    refresh()
                    onChanged()
                }
            }
        }
    }

    val deletePrivate: (TrashEntry) -> Unit = { entry ->
        if (!working) {
            working = true
            scope.launch {
                withContext(Dispatchers.IO) { PrivateTrash.delete(context, entry) }
                working = false
                refresh()
                onChanged()
            }
        }
    }

    val list = entries ?: emptyList()
    val totalBytes = remember(list) { list.sumOf { it.sizeBytes } }
    val openPreview: (TrashEntry) -> Unit = { entry ->
        TrashRepository.previewItem(context, entry)?.let { item ->
            previewSourceRect = thumbRects[entry.uri] ?: Rect.Zero
            previewItems = listOf(item)
            previewIndex = 0
        }
    }

    FrostedHeaderScaffold(
        background = AmoledBlack,
        header = {
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
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextPrimary,
                    )
                }
                Text(
                    text = stringResource(R.string.storage_trash_title),
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showEmptyConfirm = true },
                    enabled = list.isNotEmpty() && !working,
                ) {
                    if (emptying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.storage_emptying_trash),
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = if (list.isEmpty()) TextMuted else TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.action_empty),
                            color = if (list.isEmpty()) TextMuted else TextPrimary,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    ) { contentModifier ->
        if (entries != null && list.isEmpty()) {
            Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.storage_trash_empty),
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = contentModifier.navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = frostedContentTopPadding(8.dp),
                    end = 16.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (list.isNotEmpty()) {
                    item {
                        Text(
                            text = pluralStringResource(
                                R.plurals.trash_summary,
                                list.size,
                                list.size,
                                formatFileSize(totalBytes),
                            ),
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                items(list, key = { it.uri.toString() }) { entry ->
                    TrashRow(
                        entry = entry,
                        onOpen = { openPreview(entry) },
                        onThumbPositioned = { rect -> thumbRects[entry.uri] = rect },
                        onRestore = {
                            if (entry.source == TrashSource.Private) {
                                restorePrivate(entry)
                            } else {
                                run(listOf(entry.uri), emptyList()) { uris ->
                                    SystemTrash.restoreRequest(context, uris)
                                }
                            }
                        },
                        onDelete = {
                            if (entry.source == TrashSource.Private) {
                                pendingPrivateDelete = entry
                            } else {
                                run(listOf(entry.uri), emptyList()) { uris ->
                                    SystemTrash.deleteRequest(context, uris)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showEmptyConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.empty_trash_confirm_title),
            message = stringResource(R.string.empty_trash_confirm_message),
            confirmLabel = stringResource(R.string.action_empty),
            destructive = true,
            onConfirm = {
                showEmptyConfirm = false
                val privateEntries = list.filter { it.source == TrashSource.Private }
                val systemUris = list.filter { it.source == TrashSource.System }.map { it.uri }
                when {
                    systemUris.isNotEmpty() -> {
                        emptying = true
                        run(systemUris, privateEntries) { SystemTrash.deleteRequest(context, it) }
                    }
                    privateEntries.isNotEmpty() -> {
                        emptying = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                privateEntries.forEach { PrivateTrash.delete(context, it) }
                            }
                            emptying = false
                            refresh()
                            onChanged()
                        }
                    }
                    else -> working = false
                }
            },
            onDismiss = { showEmptyConfirm = false },
        )
    }

    val privateDeleteTarget = pendingPrivateDelete
    if (privateDeleteTarget != null) {
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_confirm_title),
            message = stringResource(R.string.trash_delete_confirm_message),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                pendingPrivateDelete = null
                deletePrivate(privateDeleteTarget)
            },
            onDismiss = { pendingPrivateDelete = null },
        )
    }

    val preview = previewItems
    if (preview != null && preview.isNotEmpty()) {
        FullImageViewer(
            items = preview,
            initialIndex = previewIndex.coerceIn(0, preview.lastIndex),
            sourceRect = previewSourceRect,
            onDismiss = { previewItems = null },
            onPageChanged = { previewIndex = it },
            onDelete = {},
            onShare = {},
            onInfo = {},
            onCrop = {},
            onWallpaper = {},
            previewOnly = true,
        )
    }
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    onOpen: () -> Unit,
    onThumbPositioned: (Rect) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Private entries have no MediaStore row; point the preview at the bytes
    // kept in Foto's private trash so thumbnails and the viewer still work.
    val preview = remember(entry) { TrashRepository.previewItem(context, entry) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = RaisedSurface,
        border = BorderStroke(1.dp, Border),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(ThemeSurface)
                    .onGloballyPositioned { onThumbPositioned(it.boundsInWindow()) },
                contentAlignment = Alignment.Center,
            ) {
                // Glyph sits behind the preview and shows through when a provider
                // cannot decode a trashed row.
                Icon(
                    imageVector = if (entry.isVideo) Icons.Filled.PlayArrow else Icons.Outlined.Image,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
                if (preview != null) {
                    if (preview.isVideo) {
                        VideoPosterThumb(
                            item = preview,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Small play affordance on top of the poster.
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(14.dp),
                        )
                    } else {
                        AsyncImage(
                            model = preview.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(entry.sizeBytes),
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(
                    imageVector = Icons.Outlined.Restore,
                    contentDescription = stringResource(R.string.trash_restore),
                    tint = TextPrimary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = fool.akza.foto.ui.theme.DeleteRed,
                )
            }
        }
    }
}
