package fool.akza.foto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import fool.akza.foto.AppLog
import fool.akza.foto.data.GalleryContent
import fool.akza.foto.data.GalleryDirectory
import fool.akza.foto.data.DeleteAttempt
import fool.akza.foto.data.DeleteResult
import fool.akza.foto.data.GalleryEvents
import fool.akza.foto.data.driveDeleteQueue
import fool.akza.foto.R
import fool.akza.foto.data.MediaFolder
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.MediaRepository
import fool.akza.foto.data.SettingsRepository
import fool.akza.foto.data.TrashEntry
import fool.akza.foto.data.TrashStore
import fool.akza.foto.data.canonicalPath
import fool.akza.foto.data.buildDirectoryHierarchy
import fool.akza.foto.data.previewStamp
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as SurfaceColor
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.IntentSender
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TileShape = RoundedCornerShape(12.dp)
private val GridSpacing = 8.dp
// Height of the selection action bar so grids can reserve space for it.
private val ActionBarHeightDp = 96.dp

/** A viewer item removed by a pending delete, with its index for a slide-back Undo. */
private data class ViewerUndo(val item: MediaItem, val index: Int)

/** The source tile for one level of directory navigation. */
private data class DirectoryLevel(val path: String, val sourceRect: Rect)

// Transition timings.
private const val ForwardDurationMs = 320
private const val ReverseDurationMs = 260
private const val StaggerCap = 20
private const val MaxStagger = 0.6f

// Max items AoT-warmed into the thumbnail cache per gallery load (newest
// first). Caps the first-run decode cost for very large libraries.
private const val CACHE_WARM_CAP = 600

// Where each album tile sits in the collapsed pile (matches FolderStack exactly).
// First item (index 0) is the front cover on top; back cards peek at 4/8dp.
private fun collapsedOffsetDp(index: Int): Pair<Float, Float> = when (index) {
    0 -> 0f to 0f
    1 -> 4f to 4f
    2 -> 8f to 8f
    else -> {
        val x = ((index * 7) % 5 - 2).toFloat()
        val y = ((index * 11) % 5 - 2).toFloat()
        x to y
    }
}

/**
 * True when this item was pinned as deleted earlier this session. Matched by
 * URI, or by canonical path so a lingering MediaStore twin of a SAF-deleted
 * file is suppressed too. Mirrors `MediaRepository.isExcluded`; kept here so
 * the UI can filter what it renders without waiting for the async reload.
 */
private fun MediaItem.isSuppressed(uris: Set<Uri>, paths: Set<String>): Boolean =
    uri in uris || (canonicalPath()?.let { it in paths } == true)

@Composable
fun GalleryScreen(
    modifier: Modifier = Modifier,
    openUri: Uri? = null,
    onOpenUriHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    SettingsRepository.init(context)
    val appSettings by SettingsRepository.settings.collectAsState()
    var content by remember { mutableStateOf<GalleryContent?>(null) }
    var openFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var directoryStack by remember { mutableStateOf<List<DirectoryLevel>>(emptyList()) }
    var closing by remember { mutableStateOf(false) }
    var viewerItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }
    var viewerSourceRect by remember { mutableStateOf(Rect.Zero) }
    var viewerOpenerUri by remember { mutableStateOf<Uri?>(null) }
    // Item awaiting the viewer's delete slide-out. While set, viewerItems still
    // contains it so the pager can animate to the neighbor; the actual list
    // mutation happens in commitViewerRemoval once the slide ends.
    var pendingViewerRemoval by remember { mutableStateOf<MediaItem?>(null) }
    // The viewer item whose delete request is copying to trash or awaiting
    // system consent. This starts as soon as Delete is confirmed, rather than
    // after the asynchronous file operation completes, so the pager cannot
    // move away from the item that the request targets.
    var viewerDeleteInFlightUri by remember { mutableStateOf<Uri?>(null) }
    // The viewer item removed by the pending delete, with its list index, so an
    // Undo can slide the pager back to it (the reverse of the delete slide).
    var viewerUndo by remember { mutableStateOf<ViewerUndo?>(null) }
    // Set when the pager should animate back to a restored item.
    var viewerRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    // URIs this session has deleted. MediaStore can keep serving the row for a
    // while after the file is gone (SAF deletes especially), which would otherwise
    // resurrect the item in folder covers/counts on the next reload — so every
    // query filters them out until the provider catches up.
    var removedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    // The same deletion by canonical path, so a lingering MediaStore twin of a
    // SAF-deleted file can't resurrect it in another folder's cover/count.
    var removedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var toastMessage by remember { mutableStateOf<UiText?>(null) }
    var toastKey by remember { mutableStateOf(0) }
    var pendingDeleteItem by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<SelectionState>(SelectionState()) }
    var showSearch by remember { mutableStateOf(false) }
    // Full library (folders + loose), used for name search. Populated by the
    // same pass that warms the thumbnail cache.
    var allItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // Live window-space rect of each composed grid tile, keyed by URI. A plain map
    // (not state): it is written on every scroll frame and only read when the
    // viewer opens/closes, so it must never drive recomposition. Lets the viewer's
    // exit morph target the tile of the page being viewed, not the one first opened.
    val tileRects = remember { mutableMapOf<Uri, Rect>() }
    val registerTileRect: (Uri, Rect) -> Unit = { uri, rect -> tileRects[uri] = rect }
    val unregisterTileRect: (Uri) -> Unit = { uri -> tileRects.remove(uri) }

    // The content the UI actually renders. Deletions suppressed this session are
    // filtered here rather than only inside the async reload: MediaStore/SAF can
    // keep serving a deleted row for a while, and a reload that disagrees would
    // otherwise resurrect the item as a folder cover for the rest of the session
    // (only a process restart cleared it before). Filtering makes suppression
    // authoritative regardless of what the provider returns.
    val visibleContent = remember(content, removedUris, removedPaths) {
        val c = content
        when {
            c == null -> null
            removedUris.isEmpty() && removedPaths.isEmpty() -> c
            else -> c.copy(
                looseItems = c.looseItems.filterNot { it.isSuppressed(removedUris, removedPaths) },
                allItems = c.allItems.filterNot { it.isSuppressed(removedUris, removedPaths) },
                folders = c.folders.map { folder ->
                    val kept = folder.previews.filterNot {
                        it.isSuppressed(removedUris, removedPaths)
                    }
                    if (kept.size == folder.previews.size) folder else folder.copy(previews = kept)
                },
            )
        }
    }
    val hierarchy = remember(
        visibleContent,
        appSettings.sortOrder,
        appSettings.requireExplicit,
        appSettings.selectedTreeUris,
    ) {
        val selectedRoots = if (appSettings.requireExplicit) {
            appSettings.selectedTreeUris.mapNotNull { treeUri ->
                MediaRepository.treeUriToRelativePath(context, treeUri)
            }
        } else emptyList()
        buildDirectoryHierarchy(
            visibleContent?.allItems.orEmpty(),
            appSettings.sortOrder,
            selectedRoots,
            context.getString(R.string.folder_storage),
        )
    }
    fun findDirectory(path: String, dirs: List<GalleryDirectory> = hierarchy.directories): GalleryDirectory? {
        for (directory in dirs) {
            if (directory.folder.bucketId == path) return directory
            findDirectory(path, directory.children)?.let { return it }
        }
        return null
    }
    val rootGalleryContent = remember(hierarchy, visibleContent) {
        if (visibleContent == null) return@remember null
        GalleryContent(
            folders = hierarchy.directories.map { it.folder },
            looseItems = hierarchy.rootItems,
            allItems = visibleContent?.allItems.orEmpty(),
        )
    }

    LaunchedEffect(reloadKey) {
        val loaded = MediaRepository.load(context, removedUris, removedPaths)
        // Diagnostic: if the provider still returns a suppressed item, the UI
        // filter above is what keeps it hidden. Logged so this is verifiable.
        val leaked = loaded.folders
            .flatMap { it.previews }
            .count { it.isSuppressed(removedUris, removedPaths) } +
            loaded.looseItems.count { it.isSuppressed(removedUris, removedPaths) }
        if (leaked > 0) {
            AppLog.w("delete: reload re-queued $leaked suppressed item(s)")
        }
        content = loaded
    }

    val reload = { reloadKey += 1 }

    // Surface MediaStore changes so the gallery reloads when files
    // appear or disappear. MainActivity's SyncCoordinator (debounced) emits;
    // the composed gallery collects.
    LaunchedEffect(Unit) {
        GalleryEvents.reload.collect { reload() }
    }

    // Pre-warm the thumbnail cache for everything currently visible. Folder
    // covers and loose items carry full MediaItems, so the warm pass uses the
    // same identity-derived keys the UI reads. Folder contents are then
    // AoT-warmed so opening any album is instant — all of this drives the same
    // "Caching previews · N%" progress pill (only real work is counted).
    LaunchedEffect(visibleContent) {
        val snapshot = visibleContent ?: return@LaunchedEffect
        snapshot.folders.forEach { folder ->
            folder.previews.forEach { item -> ThumbCache.enqueueIfMissing(item) }
        }
        snapshot.looseItems.forEach { item -> ThumbCache.enqueueIfMissing(item) }

        // Keep the open album in sync with the reloaded content so its header
        // count and cover previews reflect a delete immediately.
        openFolder?.let { f ->
            snapshot.folders.firstOrNull { it.bucketId == f.bucketId }?.let { openFolder = it }
        }

        // AoT warm every item in every folder (deduped, chunked, capped).
        val loaded = MediaRepository.loadAllItems(context, snapshot, removedUris, removedPaths)
            .distinctBy { it.uri }
        allItems = loaded
        ThumbCache.enqueueAllIfMissing(loaded.take(CACHE_WARM_CAP))
    }

    val closeFolder = {
        if (openFolder != null && !closing) {
            closing = true
        }
    }

    // The parent stays mounted behind the current level until the reverse
    // animation lands on the tile that opened it.
    LaunchedEffect(closing) {
        if (closing) {
            delay(ReverseDurationMs.toLong() + 24L)
            directoryStack = directoryStack.dropLast(1)
            openFolder = directoryStack.lastOrNull()?.path?.let(::findDirectory)?.folder
            closing = false
        }
    }

    val closeViewer = {
        viewerItems = null
        viewerSourceRect = Rect.Zero
        viewerOpenerUri = null
        pendingViewerRemoval = null
    }

    val openViewer = { list: List<MediaItem>, index: Int, rect: Rect ->
        viewerItems = list
        viewerIndex = index
        viewerSourceRect = rect
        viewerOpenerUri = list.getOrNull(index)?.uri
        pendingViewerRemoval = null
    }

    // Open a media Uri handed to us by another app (VIEW/SEND) in the viewer.
    // A single-item list with no source rect: the viewer opens without the
    // tile-zoom animation.
    LaunchedEffect(openUri) {
        val uri = openUri ?: return@LaunchedEffect
        val item = MediaRepository.loadItem(context, uri)
        onOpenUriHandled()
        if (item != null) {
            openViewer(listOf(item), 0, Rect.Zero)
        } else {
            toastKey += 1
            toastMessage = UiText.Res(R.string.error_open_file)
        }
    }

    // Selection helpers ------------------------------------------------------
    val clearSelection = {
        selection = SelectionState()
    }

    val isSelecting = selection.active
    val selectionList = selection.selected
    val selectedFolders = selection.selectedFolders
    val selectedDirectories = selection.selectedDirectories
    val selectedCount = selection.size

    val toggleSelect = { item: MediaItem ->
        selection = if (selection.active) {
            selection.copy(
                selected = if (item in selectionList) {
                    selectionList - item
                } else {
                    selectionList + item
                },
            )
        } else {
            // Entering selection mode starts with exactly this item.
            SelectionState(active = true, selected = listOf(item))
        }
    }

    val toggleSelectFolder = { folder: MediaFolder ->
        val directory = findDirectory(folder.bucketId)
        selection = if (selection.active) {
            if (directory != null) selection.copy(
                selectedDirectories = if (selectedDirectories.any { it.folder.bucketId == folder.bucketId }) {
                    selectedDirectories.filterNot { it.folder.bucketId == folder.bucketId }
                } else selectedDirectories + directory,
            ) else selection.copy(selectedFolders = selectedFolders + folder)
        } else {
            if (directory != null) SelectionState(active = true, selectedDirectories = listOf(directory))
            else SelectionState(active = true, selectedFolders = listOf(folder))
        }
    }

    // BackHandler: selection mode owns the back button.
    // The full-image viewer is intentionally absent here: it registers its own
    // BackHandler so system back runs the animated scale-out instead of removing
    // the viewer in a single frame.
    BackHandler(
        enabled = isSelecting || openFolder != null || showSettings || showSearch,
        onBack = {
            if (showSearch) {
                showSearch = false
            } else if (isSelecting) {
                clearSelection()
            } else if (showSettings) {
                showSettings = false
            } else {
                closeFolder()
            }
        },
    )

    // Commit a deferred viewer removal once its slide-out finishes. Idempotent:
    // no-ops if the viewer already closed or the item is gone.
    val commitViewerRemoval = {
        val removed = pendingViewerRemoval
        pendingViewerRemoval = null
        if (removed != null) {
            val list = viewerItems
            val removedIndex = list?.indexOfFirst { it.uri == removed.uri } ?: -1
            val remaining = list?.filterNot { it.uri == removed.uri }
            if (remaining.isNullOrEmpty()) {
                closeViewer()
            } else {
                viewerItems = remaining
                // The neighbour slid into the removed item's slot; keep the viewer
                // (and the exit morph target) on that slot, not the coerced old index.
                viewerIndex = if (removedIndex >= 0) {
                    removedIndex.coerceIn(0, remaining.lastIndex)
                } else {
                    viewerIndex.coerceIn(0, remaining.lastIndex)
                }
            }
        }
    }

    // Starts the viewer's delete slide for [item] (no-op when the viewer is
    // closed or does not contain it), recording its index for a possible Undo.
    val startViewerRemoval: (MediaItem) -> Unit = { item ->
        val list = viewerItems
        val index = list?.indexOfFirst { it.uri == item.uri } ?: -1
        if (list != null && index >= 0) {
            viewerUndo = ViewerUndo(item, index)
            pendingViewerRemoval = item
        }
    }

    // Re-inserts a restored item at its old index and asks the pager to slide
    // back to it (the reverse of the delete slide).
    val restoreViewerItem: (ViewerUndo) -> Unit = { undo ->
        val list = viewerItems
        if (list != null && list.none { it.uri == undo.item.uri }) {
            val at = undo.index.coerceIn(0, list.size)
            viewerItems = list.toMutableList().apply { add(at, undo.item) }
            viewerIndex = at
            viewerRestoreUri = undo.item.uri
        }
    }

    // ── Delete: move into Foto's own trash ────────────────────────────────
    // A deleted file is first copied into the app-private trash ([TrashStore]),
    // then its original is removed. Deletes are isolated from Android's system
    // trash: the Undo bar and the Trash screen only ever touch our copies.
    val scope = rememberCoroutineScope()
    // MediaStore items whose original still needs a system delete consent.
    var pendingDeleteItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var pendingDeleteEntries by remember {
        mutableStateOf<Map<android.net.Uri, TrashEntry>>(emptyMap())
    }
    // True when the in-flight consent covers the whole batch (API 30+); false for
    // API 29's per-item RecoverableSecurityException flow.
    var consentCoversAll by remember { mutableStateOf(false) }
    // Trash entries the Undo bar can restore.
    var undoTrashEntries by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var undoKey by remember { mutableStateOf(0) }
    // Sentinel consumed by the LaunchedEffect below to launch the next API 29
    // consent dialog without the launcher referencing itself.
    var nextConsentLaunch by remember { mutableStateOf<androidx.activity.result.IntentSenderRequest?>(null) }

    // Removes deleted items from the visible content + viewer and reloads.
    val removeDeletedBatch = { deletedItems: List<MediaItem> ->
        val deletedUris = deletedItems.map { it.uri }.toSet()
        if (viewerDeleteInFlightUri in deletedUris) {
            viewerDeleteInFlightUri = null
        }
        val deletedPaths = deletedItems.mapNotNull { it.canonicalPath() }.toSet()
        removedUris = removedUris + deletedUris
        removedPaths = removedPaths + deletedPaths
        AppLog.w("delete: suppressing ${deletedUris.size} uri(s) / ${deletedPaths.size} path(s)")
        // Drop the deleted files' cached previews. Without this a file later
        // created at the same URI (SAF document URIs are path-stable) would
        // render the deleted content, and the cache would hold orphans.
        deletedItems.forEach { item ->
            ThumbCache.evict(item.uri)
            VideoPosterMemory.remove(item.uri)
        }
        // A deleted video's poster must not survive in the process-wide memory
        // cache; clearing the whole small cache guarantees it (covers re-seed
        // from VideoFrameCache on the next composition).
        if (deletedItems.any { it.isVideo }) VideoPosterMemory.clear()
        val current = content
        if (current != null) {
            content = current.copy(
                looseItems = current.looseItems.filterNot {
                    it.isSuppressed(deletedUris, deletedPaths)
                },
                // Drop the deleted frames from folder covers immediately. Without
                // this the root Albums grid keeps showing the deleted file as the
                // folder's cover until (and unless) the async reload lands.
                folders = current.folders.map { folder ->
                    val kept = folder.previews.filterNot {
                        it.isSuppressed(deletedUris, deletedPaths)
                    }
                    if (kept.size == folder.previews.size) folder else folder.copy(previews = kept)
                },
            )
        }
        val viewerItem = viewerItems?.firstOrNull { it.uri in deletedUris }
        if (viewerItem != null && deletedUris.size == 1) {
            // Slide the viewer to the neighbour; Undo slides it back.
            startViewerRemoval(viewerItem)
        } else if (viewerItem != null) {
            val remaining = viewerItems?.filterNot { it.uri in deletedUris }
            if (remaining.isNullOrEmpty()) closeViewer() else viewerItems = remaining
        }
        // Drop it from the visible album immediately; the reload refreshes the
        // rest (order, counts) once the provider query returns.
        reload()
    }

    // Lifts this session's delete suppression for restored items. TrashStore
    // restores to the same canonical path (and a new MediaStore URI), so the
    // path-based suppression would otherwise hide the restored file forever.
    val clearSuppression: (List<TrashEntry>) -> Unit = { entries ->
        val uris = entries.mapNotNull { entry ->
            runCatching { Uri.parse(entry.originalUri) }.getOrNull()
        }.toSet()
        val paths = entries.mapNotNull { entry ->
            entry.relativePath?.let { rel ->
                (rel.trimEnd('/') + "/" + entry.name).lowercase()
            }
        }.toSet()
        if (uris.isNotEmpty()) removedUris = removedUris - uris
        if (paths.isNotEmpty()) removedPaths = removedPaths - paths
    }

    // Arms the Undo bar with the entries whose originals were removed.
    val finishTrashDelete: (Map<android.net.Uri, TrashEntry>, List<MediaItem>) -> Unit =
        { entries, deletedItems ->
            removeDeletedBatch(deletedItems)
            val deletedUris = deletedItems.map { it.uri }.toSet()
            val restored = entries.filterKeys { it in deletedUris }.values.toList()
            if (restored.isNotEmpty()) {
                undoTrashEntries = restored
                undoKey += 1
            }
        }

    // Drops trash copies we made for a delete that didn't go through.
    val rollbackTrash: (Map<android.net.Uri, TrashEntry>) -> Unit = { entries ->
        scope.launch {
            withContext(Dispatchers.IO) {
                entries.values.forEach { TrashStore.remove(context, it) }
            }
        }
    }

    // Tells the user a delete did not complete (these paths used to be silent).
    val reportDeleteFailure: () -> Unit = {
        viewerDeleteInFlightUri = null
        toastKey += 1
        toastMessage = UiText.Res(R.string.error_delete)
    }

    // API 29 drives MediaStore deletes through a per-item consent dialog. Walk
    // [queue] calling MediaRepository.delete: direct deletes are recorded,
    // failures roll back their trash copy, and the first item the system wants
    // the user to approve arms the launcher. A successful direct delete must be
    // recorded — treating it as "no consent path" would drop the file and stop
    // the batch.
    suspend fun runApi29Deletes(queue: List<MediaItem>, entries: Map<Uri, TrashEntry>) {
        var sender: IntentSender? = null
        val round = driveDeleteQueue(queue) { item ->
            when (val result = MediaRepository.delete(context, item)) {
                DeleteResult.Deleted -> DeleteAttempt.Deleted
                is DeleteResult.Consent -> {
                    sender = result.intentSender
                    DeleteAttempt.NeedsConsent
                }
                DeleteResult.Failed -> DeleteAttempt.Failed
            }
        }
        if (round.failed.isNotEmpty()) {
            round.failed.forEach { item ->
                entries[item.uri]?.let { rollbackTrash(mapOf(item.uri to it)) }
            }
            reportDeleteFailure()
        }
        if (round.deleted.isNotEmpty()) {
            finishTrashDelete(entries, round.deleted)
        }
        val pendingSender = sender
        if (pendingSender != null) {
            pendingDeleteItems = round.pending
            pendingDeleteEntries = entries
            nextConsentLaunch = androidx.activity.result.IntentSenderRequest.Builder(pendingSender).build()
        } else {
            pendingDeleteItems = emptyList()
            pendingDeleteEntries = emptyMap()
            clearSelection()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val queue = pendingDeleteItems
        val entries = pendingDeleteEntries
        val ok = result.resultCode == android.app.Activity.RESULT_OK
        if (queue.isEmpty()) {
            clearSelection()
            return@rememberLauncherForActivityResult
        }
        if (!ok) {
            // Denied: drop only the copies still waiting on this consent. Copies
            // for items already deleted before the prompt must be preserved.
            val pending = entries.filterKeys { uri -> queue.any { it.uri == uri } }
            pendingDeleteItems = emptyList()
            pendingDeleteEntries = emptyMap()
            rollbackTrash(pending)
            reportDeleteFailure()
            clearSelection()
            return@rememberLauncherForActivityResult
        }
        if (consentCoversAll) {
            // API 30+: the system already deleted the whole batch.
            pendingDeleteItems = emptyList()
            pendingDeleteEntries = emptyMap()
            scope.launch {
                finishTrashDelete(entries, queue)
                clearSelection()
            }
        } else {
            // API 29: the consent applied to the item at the head of the queue.
            // Continue through the remaining items; the next one that still
            // needs consent re-arms the launcher.
            scope.launch { runApi29Deletes(queue, entries) }
        }
    }

    LaunchedEffect(nextConsentLaunch) {
        nextConsentLaunch?.let {
            consentLauncher.launch(it)
            nextConsentLaunch = null
        }
    }

    // Restores the last deleted items from Foto's trash.
    val undoTrash: () -> Unit = {
        val entries = undoTrashEntries
        if (entries != null) {
            undoTrashEntries = null
            scope.launch {
                withContext(Dispatchers.IO) {
                    entries.forEach { TrashStore.restore(context, it) }
                }
                // Let the restored items reappear before reloading.
                clearSuppression(entries)
                // Slide the viewer back to the restored item, if it was deleted there.
                viewerUndo?.let { restoreViewerItem(it) }
                viewerUndo = null
                // The open album owns its own list; refresh it too.
                reload()
                toastKey += 1
                toastMessage = UiText.Res(R.string.restored)
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────
    // Copies each item into the app-private trash, then removes its original.
    // MediaStore items on API 30+ also need a one-shot system delete consent for
    // the originals; that consent is what the launcher above resolves.
    val trashAndDelete: (List<MediaItem>) -> Unit = { items ->
        scope.launch {
            // 1. Copy the bytes into our trash first, so nothing is lost.
            val entries: Map<android.net.Uri, TrashEntry> = withContext(Dispatchers.IO) {
                items.mapNotNull { item -> TrashStore.add(context, item)?.let { item.uri to it } }
                    .toMap()
            }
            val trashed = items.filter { entries.containsKey(it.uri) }
            if (trashed.isEmpty()) {
                toastKey += 1
                toastMessage = UiText.Res(R.string.error_delete)
                return@launch
            }

            // 2. Remove the originals.
            val saf = trashed.filter { android.provider.DocumentsContract.isDocumentUri(context, it.uri) }
            val media = trashed.filterNot { it in saf }
            val deleted = mutableSetOf<android.net.Uri>()
            saf.forEach { item ->
                if (MediaRepository.delete(context, item) == DeleteResult.Deleted) deleted += item.uri
            }
            val needsConsent = mutableListOf<MediaItem>()
            media.forEach { item ->
                when (MediaRepository.delete(context, item)) {
                    DeleteResult.Deleted -> deleted += item.uri
                    is DeleteResult.Consent, DeleteResult.Failed -> needsConsent += item
                }
            }

            if (deleted.isNotEmpty()) {
                finishTrashDelete(entries, trashed.filter { it.uri in deleted })
            }

            if (needsConsent.isEmpty()) {
                // Nothing pending: drop copies whose original couldn't be removed,
                // and surface the failure instead of silently doing nothing.
                val unresolved = entries.filterKeys { it !in deleted }
                if (unresolved.isNotEmpty()) {
                    rollbackTrash(unresolved)
                    reportDeleteFailure()
                }
                clearSelection()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = fool.akza.foto.data.deleteConsentSender(context, needsConsent)
                if (sender != null) {
                    pendingDeleteItems = needsConsent
                    pendingDeleteEntries = entries
                    consentCoversAll = true
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                    )
                } else {
                    rollbackTrash(entries.filterKeys { it in needsConsent.map { m -> m.uri } })
                    reportDeleteFailure()
                    clearSelection()
                }
            } else {
                // API 29: per-item RecoverableSecurityException consent. The
                // driver deletes what it can, records those, and arms the
                // launcher for the first item that still needs consent.
                pendingDeleteEntries = entries
                consentCoversAll = false
                runApi29Deletes(needsConsent, entries)
            }
        }
    }

    val requestDelete = { item: MediaItem ->
        // This is only used by the full viewer. Lock it before the coroutine
        // copies bytes or asks Android for delete consent, so its current page
        // cannot diverge from the item being deleted.
        viewerDeleteInFlightUri = item.uri
        trashAndDelete(listOf(item))
    }

    // ── Selection resolution ────────────────────────────────────────────────
    // Expands the current selection into a flat list of MediaItems: selected
    // folders contribute every item inside them (loaded on demand), selected
    // loose items contribute themselves. Used by delete/copy/move/share.
    val resolveSelectionItems: suspend () -> List<MediaItem> = {
        buildList {
            addAll(selectionList)
            selectedDirectories.forEach { directory ->
                addAll(findDirectory(directory.folder.bucketId)?.items ?: directory.items)
            }
            for (folder in selectedFolders) {
                addAll(MediaRepository.loadBucket(context, folder))
            }
        }
    }

    // ── Multi-delete ────────────────────────────────────────────────────────
    var pendingBatchDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    val batchDeleteScope = rememberCoroutineScope()

    val requestBatchDelete = { items: List<MediaItem> -> trashAndDelete(items) }

    // ── Copy / Move ────────────────────────────────────────────────────────
    var pendingCopy by remember { mutableStateOf<List<MediaItem>?>(null) }
    var pendingMove by remember { mutableStateOf<List<MediaItem>?>(null) }
    var busyMessage by remember { mutableStateOf<UiText?>(null) }
    val transferScope = rememberCoroutineScope()

    // Shared: pick a destination tree, then run the transfer in the background.
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
        }

        val toCopy = pendingCopy
        val toMove = pendingMove
        pendingCopy = null
        pendingMove = null
        if (toCopy != null && toCopy.isNotEmpty()) {
            val items = toCopy
            val count = items.size
            busyMessage = UiText.Qty(R.plurals.copying_items, count)
            transferScope.launch {
                val done = MediaRepository.copyTo(context, items, uri)
                busyMessage = null
                clearSelection()
                if (done > 0) {
                    toastKey += 1
                    toastMessage = UiText.Qty(R.plurals.copied_items, done)
                    reload()
                } else {
                    toastKey += 1
                    toastMessage = UiText.Res(R.string.error_copy)
                }
            }
        } else if (toMove != null && toMove.isNotEmpty()) {
            val items = toMove
            val count = items.size
            busyMessage = UiText.Qty(R.plurals.moving_items, count)
            transferScope.launch {
                val result = MediaRepository.moveTo(context, items, uri)
                busyMessage = null
                clearSelection()
                when {
                    result.total == 0 -> {
                        toastKey += 1
                        toastMessage = UiText.Res(R.string.error_move)
                    }
                    result.copiedOnly > 0 -> {
                        // Copied, but the source couldn't be removed (system
                        // consent needed), so the user now has a duplicate.
                        toastKey += 1
                        toastMessage = UiText.Res(
                            R.string.moved_partial,
                            listOf(result.moved, result.copiedOnly),
                        )
                        reload()
                    }
                    else -> {
                        toastKey += 1
                        toastMessage = UiText.Qty(R.plurals.moved_items, result.moved)
                        reload()
                    }
                }
            }
        }
    }

    val launchCopy: () -> Unit = {
        transferScope.launch {
            val items = resolveSelectionItems()
            if (items.isNotEmpty()) {
                pendingCopy = items
                treePicker.launch(null)
            }
        }
    }

    val launchMove: () -> Unit = {
        transferScope.launch {
            val items = resolveSelectionItems()
            if (items.isNotEmpty()) {
                pendingMove = items
                treePicker.launch(null)
            }
        }
    }

    // ── Share ───────────────────────────────────────────────────────────────
    val shareSelection: () -> Unit = {
        transferScope.launch {
            val items = resolveSelectionItems()
            if (items.isEmpty()) return@launch
            if (items.size == 1) {
                val item = items.first()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (item.isVideo) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            } else {
                val uris = items.map { it.uri }
                // Use the broadest MIME type that covers the batch so video+image
                // mixes share cleanly (recipients filter per URI).
                val anyVideo = items.any { it.isVideo }
                val anyImage = items.any { !it.isVideo }
                val shareType = when {
                    anyVideo && anyImage -> "*/*"
                    anyVideo -> "video/*"
                    else -> "image/*"
                }
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = shareType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        }
    }

    val shareItem = { item: MediaItem ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    val showComingSoonToast = {
        toastKey += 1
        toastMessage = UiText.Res(R.string.coming_soon)
    }

    // The root Albums grid stays composed underneath the album, so when the album
    // fades on back, the folder stack is already exactly where the collapsing tiles
    // land — the "after" state is the real top-level view, never a swap-in.
    Box(modifier = modifier.fillMaxSize()) {
        MainGalleryGrid(
            content = rootGalleryContent,
            onFolderClick = { folder, rect ->
                if (directoryStack.isEmpty()) {
                    openFolder = folder
                    directoryStack = listOf(DirectoryLevel(folder.bucketId, rect))
                }
            },
            onItemClick = { list, index, rect ->
                if (directoryStack.isEmpty()) openViewer(list, index, rect)
            },
            onSettingsClick = { if (directoryStack.isEmpty()) showSettings = true },
            onSearchClick = { if (directoryStack.isEmpty()) showSearch = true },
            minTileSize = appSettings.gridSize.minTileDp.dp,
            selection = selection,
            onToggleSelect = { if (directoryStack.isEmpty()) toggleSelect(it) },
            onLongSelect = { if (directoryStack.isEmpty()) toggleSelect(it) },
            onToggleFolder = { if (directoryStack.isEmpty()) toggleSelectFolder(it) },
            onLongSelectFolder = { if (directoryStack.isEmpty()) toggleSelectFolder(it) },
            onSelectAll = {
                val loose = visibleContent?.looseItems.orEmpty()
                val folders = rootGalleryContent?.folders.orEmpty()
                val allSelected = selection.selected.toSet() == loose.toSet() &&
                    selection.selectedDirectories.map { it.folder.bucketId }.toSet() == folders.map { it.bucketId }.toSet()
                if (selection.active && allSelected && (loose.isNotEmpty() || folders.isNotEmpty())) {
                    // Already all selected: deselect everything.
                    SelectionState(active = true)
                } else {
                    SelectionState(
                        active = true,
                        selected = loose,
                        selectedDirectories = hierarchy.directories,
                    )
                }
            },
            onExitSelection = clearSelection,
            onTilePositioned = registerTileRect,
            onTileDisposed = unregisterTileRect,
            // Skip the (expensive) blur while an opaque screen covers the root.
            frostEnabled = (directoryStack.isEmpty() || directoryStack.size == 1 && closing) &&
                viewerItems == null &&
                !showSettings && !showSearch,
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SettingsScreen(
                onBack = { showSettings = false },
                onSettingsChanged = {
                    // Settings can restore media from Trash or change which
                    // folders are visible; drop this session's delete
                    // suppression so restored files become visible again.
                    removedUris = emptySet()
                    removedPaths = emptySet()
                    reload()
                },
            )
        }

        AnimatedVisibility(
            visible = showSearch,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SearchScreen(
                items = allItems,
                onBack = { showSearch = false },
                onItemClick = { list, index, rect -> openViewer(list, index, rect) },
                minTileSize = appSettings.gridSize.minTileDp.dp,
                onTilePositioned = registerTileRect,
                onTileDisposed = unregisterTileRect,
            )
        }

        directoryStack.forEachIndexed { levelIndex, level ->
            val directory = findDirectory(level.path) ?: return@forEachIndexed
            val isTopLevel = levelIndex == directoryStack.lastIndex
            key(level.path) {
                AlbumGrid(
                    folder = directory.folder,
                    childDirectories = directory.children,
                    onDirectoryClick = { child, rect ->
                        if (isTopLevel && !closing) {
                            directoryStack = directoryStack + DirectoryLevel(child.folder.bucketId, rect)
                            openFolder = child.folder
                        }
                    },
                    sourceRect = level.sourceRect,
                    reverse = isTopLevel && closing,
                    onBack = { if (isTopLevel) closeFolder() },
                    onItemClick = { list, index, rect ->
                        if (isTopLevel && !closing) openViewer(list, index, rect)
                    },
                    items = directory.directItems,
                    onToggleFolder = { if (isTopLevel) toggleSelectFolder(it) },
                    minTileSize = appSettings.gridSize.minTileDp.dp,
                    selection = selection,
                    onToggleSelect = { if (isTopLevel) toggleSelect(it) },
                    onLongSelect = { if (isTopLevel) toggleSelect(it) },
                    onSelectAll = { items ->
                        if (isTopLevel) {
                            selection = if (selection.active && items.isNotEmpty() &&
                                selection.selected.toSet() == items.toSet()
                            ) {
                                SelectionState(active = true)
                            } else {
                                SelectionState(active = true, selected = items)
                            }
                        }
                    },
                    onExitSelection = { if (isTopLevel) clearSelection() },
                    onTilePositioned = registerTileRect,
                    onTileDisposed = unregisterTileRect,
                )
            }
        }

        val viewing = viewerItems
        if (viewing != null && viewing.isNotEmpty()) {
            // Only morph back to the tile for the page actually being viewed.
            // A page reached by swiping may have no composed tile in the grid;
            // in that case the viewer fades out instead of flying to its opener.
            val currentViewerUri =
                viewing.getOrNull(viewerIndex.coerceIn(0, viewing.lastIndex))?.uri
            val visibleTileRect = currentViewerUri?.let { tileRects[it] }?.takeIf { rect ->
                rect.right > 0f && rect.bottom > 0f &&
                    rect.left < rootView.width.toFloat() &&
                    rect.top < rootView.height.toFloat()
            }
            val viewerExitRect = visibleTileRect ?: if (currentViewerUri == viewerOpenerUri) {
                viewerSourceRect
            } else {
                Rect.Zero
            }
            FullImageViewer(
                items = viewing,
                initialIndex = viewerIndex.coerceIn(0, viewing.lastIndex),
                sourceRect = viewerExitRect,
                pendingRemoval = pendingViewerRemoval,
                deleteInProgress = viewerDeleteInFlightUri != null,
                onRemovalAnimationEnd = commitViewerRemoval,
                restoreUri = viewerRestoreUri,
                onRestored = { viewerRestoreUri = null },
                onDismiss = closeViewer,
                onPageChanged = { viewerIndex = it },
                onDelete = {
                    // Ignore further deletes while a slide-out is already pending.
                    if (pendingViewerRemoval == null && viewerDeleteInFlightUri == null) {
                        if (appSettings.confirmBeforeDelete) {
                            pendingDeleteItem = it
                        } else {
                            requestDelete(it)
                        }
                    }
                },
                onShare = { shareItem(it) },
                onInfo = { infoItem = it },
                onCrop = { showComingSoonToast() },
                loopVideos = appSettings.loopVideos,
            )
        }

        val deleteTarget = pendingDeleteItem
        if (deleteTarget != null) {
            val canUndoDelete = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !android.provider.DocumentsContract.isDocumentUri(context, deleteTarget.uri)
            ConfirmDialog(
                title = stringResource(
                    if (deleteTarget.isVideo) R.string.delete_confirm_video
                    else R.string.delete_confirm_photo,
                ),
                message = stringResource(
                    if (canUndoDelete) R.string.delete_confirm_message_trash
                    else R.string.delete_confirm_message_undoable,
                ),
                confirmLabel = stringResource(R.string.action_delete),
                destructive = true,
                onConfirm = {
                    pendingDeleteItem = null
                    requestDelete(deleteTarget)
                },
                onDismiss = { pendingDeleteItem = null },
            )
        }

        // Multi-select action bar + selection mode state.
        if (isSelecting) {
            SelectionActionBar(
                count = selectedCount,
                onClose = clearSelection,
                onDelete = {
                    if (selectedCount > 0) {
                        batchDeleteScope.launch {
                            val items = resolveSelectionItems()
                            if (items.isNotEmpty()) {
                                if (appSettings.confirmBeforeDelete) {
                                    pendingBatchDelete = items
                                } else {
                                    requestBatchDelete(items)
                                    clearSelection()
                                }
                            }
                        }
                    }
                },
                onCopy = launchCopy,
                onMove = launchMove,
                onShare = shareSelection,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Batch-delete confirmation dialog.
        val batch = pendingBatchDelete
        if (batch != null && batch.isNotEmpty()) {
            val canUndoDelete = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                batch.any { !android.provider.DocumentsContract.isDocumentUri(context, it.uri) }
            ConfirmDialog(
                title = pluralStringResource(
                    R.plurals.delete_confirm_count,
                    batch.size,
                    batch.size,
                ),
                message = stringResource(
                    if (canUndoDelete) R.string.delete_confirm_message_trash
                    else R.string.delete_confirm_message_undoable,
                ),
                confirmLabel = stringResource(R.string.action_delete),
                destructive = true,
                onConfirm = {
                    pendingBatchDelete = null
                    requestBatchDelete(batch)
                },
                onDismiss = { pendingBatchDelete = null },
            )
        }

        val infoTarget = infoItem
        if (infoTarget != null) {
            InfoSheet(item = infoTarget, onClose = { infoItem = null })
        }

        toastMessage?.let { message ->
            Toast(message = message.resolve(), key = toastKey, onDismiss = { toastMessage = null })
        }

        if (undoTrashEntries != null) {
            UndoBar(
                key = undoKey,
                onUndo = undoTrash,
                onDismiss = {
                    undoTrashEntries = null
                    viewerUndo = null
                },
            )
        }

        busyMessage?.let { message ->
            BusyOverlay(message = message.resolve())
        }

        // Floating pill overlay, centered below the app bar (in safe area).
        // Positioned once at the root so it covers both the Albums grid and
        // the open album grid without z-fighting.
        ThumbnailSyncBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        )
    }
}

@Composable
private fun MainGalleryGrid(
    content: GalleryContent?,
    onFolderClick: (MediaFolder, Rect) -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    minTileSize: Dp,
    selection: SelectionState,
    onToggleSelect: (MediaItem) -> Unit,
    onLongSelect: (MediaItem) -> Unit,
    onToggleFolder: (MediaFolder) -> Unit,
    onLongSelectFolder: (MediaFolder) -> Unit,
    onSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
    frostEnabled: Boolean = true,
) {
    // The grid fills the whole screen and the header overlays it, so content
    // scrolls underneath the frosted bar (same treatment as the album view).
    FrostedHeaderScaffold(
        enabled = frostEnabled,
        header = {
            // Reserved header, same height as the album's nav back header, so both
            // views have identical geometry (no grid/stack offset jump between them).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(AppBarHeight)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selection.active) {
                    IconButton(onClick = onExitSelection) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.selection_exit),
                            tint = TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.selection_count, selection.size, selection.size),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onSelectAll) {
                        Icon(
                            imageVector = Icons.Outlined.SelectAll,
                            contentDescription = stringResource(R.string.select_all),
                            tint = TextPrimary,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.albums),
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.action_search),
                            tint = TextPrimary,
                            modifier = Modifier.width(24.dp),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = TextPrimary,
                            modifier = Modifier.width(24.dp),
                        )
                    }
                }
            }
        },
    ) { gridModifier ->
        val current = content
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        when {
            current == null -> {
                Box(
                    modifier = gridModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextPrimary,
                    )
                }
            }
            current.folders.isEmpty() && current.looseItems.isEmpty() -> {
                Box(modifier = gridModifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_media_found),
                        color = TextMuted,
                        fontSize = 14.sp,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minTileSize),
                    modifier = gridModifier,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = statusBarTop + AppBarHeight + 12.dp,
                        end = 12.dp,
                        bottom = if (selection.active) {
                            ActionBarHeightDp + 12.dp +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        } else {
                            12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        },
                    ),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridSpacing),
                ) {
                    items(current.folders, key = { "folder:${it.bucketId}" }) { folder ->
                        FolderTile(
                            folder = folder,
                            selection = selection,
                            onToggleSelect = onToggleFolder,
                            onLongSelect = onLongSelectFolder,
                            onClick = { rect -> onFolderClick(folder, rect) },
                        )
                    }
                    items(current.looseItems, key = { "item:${it.uri}" }) { item ->
                        ImageTile(
                            item = item,
                            selection = selection,
                            onToggleSelect = onToggleSelect,
                            onLongSelect = onLongSelect,
                            // Deletes reflow the grid instead of jumping.
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            onTilePositioned = onTilePositioned,
                            onTileDisposed = onTileDisposed,
                            onClick = { rect ->
                                onItemClick(current.looseItems, current.looseItems.indexOf(item), rect)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    folder: MediaFolder,
    childDirectories: List<GalleryDirectory>,
    onDirectoryClick: (GalleryDirectory, Rect) -> Unit,
    sourceRect: Rect,
    reverse: Boolean,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onToggleFolder: (MediaFolder) -> Unit,
    items: List<MediaItem>?,
    minTileSize: Dp,
    selection: SelectionState,
    onToggleSelect: (MediaItem) -> Unit,
    onLongSelect: (MediaItem) -> Unit,
    onSelectAll: (List<MediaItem>) -> Unit,
    onExitSelection: () -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
) {
    val density = LocalDensity.current

    // One shared phase drives every tile: 1f = settled expanded grid, 0f = collapsed
    // into the folder stack. Tiles derive their pose from it, so scrolling/recomposing
    // never replays the animation and nothing is ever left half-animated.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(reverse) {
        if (reverse) {
            phase.animateTo(0f, tween(ReverseDurationMs, easing = FastOutSlowInEasing))
        } else {
            phase.animateTo(1f, tween(ForwardDurationMs, easing = FastOutSlowInEasing))
        }
    }

    // Header derives from the shared phase: slides/fades in on expand (phase 0->1),
    // slides up and fades out on back (phase 1->0).
    val headerAlpha = phase.value
    val headerOffsetY = (phase.value - 1f) * with(density) { 8.dp.toPx() }

    val animate = sourceRect != Rect.Zero
    val realItems = items
    // Render the stack previews immediately (same items as the top of the album)
    // so the fly-to-grid animation can start without waiting for the query.
    val displayItems = realItems ?: folder.previews

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Reveal the actual parent during expansion and collapse. The whole
            // child fades near the end of reverse so its stacked tiles hand off
            // cleanly to the parent folder cover.
            .graphicsLayer { alpha = if (reverse) phase.value else 1f }
            .background(AmoledBlack.copy(alpha = phase.value)),
    ) {
        // The grid fills the whole screen; the header overlays it (Box) so content
        // scrolls underneath the frosted bar.
        Box(modifier = Modifier.fillMaxSize()) {
            val hazeState = rememberHazeState()

            if (displayItems.isEmpty() && childDirectories.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.no_items), color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minTileSize),
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 56.dp + 12.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        end = 12.dp,
                        bottom = if (selection.active) {
                            ActionBarHeightDp + 12.dp +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        } else {
                            12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        },
                    ),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridSpacing),
                ) {
                    items(childDirectories, key = { "directory:${it.folder.bucketId}" }) { child ->
                        val selected = selection.selectedDirectories.any {
                            it.folder.bucketId == child.folder.bucketId
                        }
                        if (animate) {
                            FlyInFolderTile(
                                folder = child.folder,
                                index = childDirectories.indexOf(child),
                                source = sourceRect,
                                phase = phase,
                                selection = selection,
                                isSelected = selected,
                                onToggleSelect = onToggleFolder,
                                onLongSelect = onToggleFolder,
                                onClick = { rect -> onDirectoryClick(child, rect) },
                            )
                        } else {
                            FolderTile(
                                folder = child.folder,
                                selection = selection,
                                isSelectedOverride = selected,
                                onToggleSelect = onToggleFolder,
                                onLongSelect = onToggleFolder,
                                onClick = { rect -> onDirectoryClick(child, rect) },
                            )
                        }
                    }
                    itemsIndexed(
                        displayItems,
                        key = { _, item -> "album:${item.uri}" },
                    ) { index, item ->
                        if (animate) {
                            FlyInTile(
                                item = item,
                                index = index + childDirectories.size,
                                source = sourceRect,
                                phase = phase,
                                selection = selection,
                                onToggleSelect = onToggleSelect,
                                onLongSelect = onLongSelect,
                                // Deletes reflow the grid instead of jumping.
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                onTilePositioned = onTilePositioned,
                                onTileDisposed = onTileDisposed,
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        } else {
                            ImageTile(
                                item = item,
                                selection = selection,
                                onToggleSelect = onToggleSelect,
                                onLongSelect = onLongSelect,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                onTilePositioned = onTilePositioned,
                                onTileDisposed = onTileDisposed,
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        }
                    }
                }
            }

            // Frosted header overlaying the grid. Haze (backdrop blur) is expensive
            // during the fly-in animation, so it's only enabled once the grid settles
            // (phase ~= 1). During the transition a plain translucent scrim is used.
            val hazeEnabled = phase.value >= 0.98f
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(brush = FrostedBarScrim)
                    .then(
                        if (hazeEnabled) {
                            Modifier.hazeEffect(state = hazeState, style = FrostedHazeStyle)
                        } else {
                            Modifier
                        },
                    )
                    .statusBarsPadding()
                    .height(AppBarHeight)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        alpha = headerAlpha
                        translationY = headerOffsetY
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selection.active) {
                    // Selection mode: the header's back button exits selection.
                    IconButton(onClick = onExitSelection) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.selection_exit),
                            tint = TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.selection_count, selection.selected.size, selection.selected.size),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSelectAll(displayItems) }) {
                        Icon(
                            imageVector = Icons.Outlined.SelectAll,
                            contentDescription = stringResource(R.string.select_all),
                            tint = TextPrimary,
                        )
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = folder.name,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.item_count, folder.count, folder.count),
                            color = TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlyInFolderTile(
    folder: MediaFolder,
    index: Int,
    source: Rect,
    phase: Animatable<Float, *>,
    selection: SelectionState,
    isSelected: Boolean,
    onToggleSelect: (MediaFolder) -> Unit,
    onLongSelect: (MediaFolder) -> Unit,
    onClick: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    var targetRect by remember { mutableStateOf(Rect.Zero) }
    val (offsetX, offsetY) = collapsedOffsetDp(index)
    val offsetXPx = with(density) { offsetX.dp.toPx() }
    val offsetYPx = with(density) { offsetY.dp.toPx() }
    val stagger = ((index.toFloat() / StaggerCap).coerceIn(0f, 1f)) * MaxStagger

    FolderTile(
        folder = folder,
        onClick = onClick,
        selection = selection,
        isSelectedOverride = isSelected,
        onToggleSelect = onToggleSelect,
        onLongSelect = onLongSelect,
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                if (targetRect == Rect.Zero || phase.value < 1f) {
                    targetRect = coordinates.boundsInWindow()
                }
            }
            .graphicsLayer {
                val p = ((phase.value - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                translationX = (source.left + offsetXPx - targetRect.left) * (1f - p)
                translationY = (source.top + offsetYPx - targetRect.top) * (1f - p)
                alpha = if (targetRect == Rect.Zero) 0f else p
            },
    )
}

@Composable
private fun FolderTile(
    folder: MediaFolder,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    selection: SelectionState = SelectionState(),
    isSelectedOverride: Boolean? = null,
    onToggleSelect: (MediaFolder) -> Unit = {},
    onLongSelect: (MediaFolder) -> Unit = {},
) {
    var stackRect by remember { mutableStateOf(Rect.Zero) }
    val isSelecting = selection.active
    val isSelected = isSelectedOverride ?: (folder in selection.selectedFolders ||
        selection.selectedDirectories.any { it.folder.bucketId == folder.bucketId })
    val haptics = LocalHapticFeedback.current
    val a11yLabel = folder.name + ", " +
        pluralStringResource(R.plurals.item_count, folder.count, folder.count)

    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .semantics {
                contentDescription = a11yLabel
            }
            .then(
                if (isSelecting) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleSelect(folder) }
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick(stackRect) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongSelect(folder)
                        },
                    )
                },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            FolderStack(
                previews = folder.previews,
                modifier = Modifier.onGloballyPositioned { stackRect = it.boundsInWindow() },
            )
            // Selection badge (top-left), shown in selection mode; reflects state.
            if (isSelecting) {
                SelectionBadge(
                    selected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .zIndex(2f),
                )
            }
            if (isSelecting && !isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(TileShape)
                        .background(AmoledBlack.copy(alpha = 0.4f)),
                )
            }
        }
        Text(
            text = folder.name,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.item_count, folder.count, folder.count),
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderStack(previews: List<MediaItem>, modifier: Modifier = Modifier) {
    // Rebuild the whole stack whenever the cover set — or any cover's bytes —
    // changes, so no thumbnail/poster state can survive from the previous cover.
    val coverKey = previews.joinToString("|") { "${it.uri}#${it.previewStamp()}" }
    key(coverKey) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            // Front cover is the first (newest) preview, on top; the others peek
            // from behind with flush (untilted) offsets — same as the collapsed pile.
            if (previews.size >= 3) {
                StackLayer(
                    item = previews[2],
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = 8.dp.toPx()
                            translationY = 8.dp.toPx()
                        },
                    scrim = 0.35f,
                )
                StackLayer(
                    item = previews[1],
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = 4.dp.toPx()
                            translationY = 4.dp.toPx()
                        },
                    scrim = 0.15f,
                )
            }
            StackLayer(
                item = previews.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
                scrim = 0f,
            )
        }
    }
}

@Composable
internal fun ImageTile(
    item: MediaItem,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    selection: SelectionState = SelectionState(),
    onToggleSelect: (MediaItem) -> Unit = {},
    onLongSelect: (MediaItem) -> Unit = {},
    onTilePositioned: (Uri, Rect) -> Unit = { _, _ -> },
    onTileDisposed: (Uri) -> Unit = {},
) {
    // Layout coordinates are kept in a non-observable holder: writing window
    // bounds into state would recompose every tile on every scroll frame.
    val coordsRef = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val isSelecting = selection.active
    val isSelected = item in selection.selected
    val haptics = LocalHapticFeedback.current
    // Resolved here: the semantics lambda below is not composable.
    val kindLabel = stringResource(if (item.isVideo) R.string.video_badge else R.string.media_photo)
    val selectionSuffix = stringResource(
        if (isSelected) R.string.a11y_selected else R.string.a11y_not_selected,
    )

    // Pairs with onTilePositioned so an off-screen tile never leaves a stale rect.
    DisposableEffect(item.uri) {
        onDispose { onTileDisposed(item.uri) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(TileShape)
            .background(SurfaceColor)
            .onGloballyPositioned { coords ->
                coordsRef[0] = coords
                onTilePositioned(item.uri, coords.boundsInWindow())
            }
            .semantics {
                contentDescription = buildString {
                    append(item.displayName ?: kindLabel)
                    if (isSelecting) append(selectionSuffix)
                }
            }
            .then(
                if (isSelecting) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleSelect(item) }
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick(coordsRef[0]?.boundsInWindow() ?: Rect.Zero) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongSelect(item)
                        },
                    )
                },
            ),
    ) {
        if (item.isVideo) {
            // Fast poster: MediaStore system thumbnail / private frame cache /
            // Coil fallback. Never a black or shimmering gap for long.
            VideoPosterThumb(
                item = item,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Thumbnail base for images (static formats load in ms; animated
            // formats play inline via Coil).
            Thumb(
                item = item,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            // Always show a prominent play badge, drawn last so it's on top.
            VideoBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .zIndex(1f),
            )
        }
        // Selection badge (top-left), shown in selection mode; reflects state.
        if (isSelecting) {
            SelectionBadge(
                selected = isSelected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .zIndex(2f),
            )
        }
        // Dimmed overlay when selection mode is active but this item isn't chosen.
        if (isSelecting && !isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack.copy(alpha = 0.4f)),
            )
        }
    }
}

@Composable
private fun StackLayer(item: MediaItem?, modifier: Modifier, scrim: Float) {
    Box(
        modifier = modifier
            .clip(TileShape)
            .background(SurfaceColor)
            .border(1.dp, Border, TileShape),
    ) {
        if (item != null) {
            val thumbModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - scrim }
            if (item.isVideo) {
                VideoPosterThumb(item = item, modifier = thumbModifier)
            } else {
                Thumb(item = item, modifier = thumbModifier)
            }
            if (item.isVideo) {
                VideoBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .zIndex(1f),
                )
            }
        } else {
            // No cover to show: lifted zinc surface + broken-image glyph so the
            // empty stack reads as "no preview" rather than a black tile.
            EmptyPreview(modifier = Modifier.fillMaxSize())
        }
    }
}


@Composable
private fun FlyInTile(
    item: MediaItem,
    index: Int,
    source: Rect,
    phase: Animatable<Float, *>,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    selection: SelectionState = SelectionState(),
    onToggleSelect: (MediaItem) -> Unit = {},
    onLongSelect: (MediaItem) -> Unit = {},
    onTilePositioned: (Uri, Rect) -> Unit = { _, _ -> },
    onTileDisposed: (Uri) -> Unit = {},
) {
    val density = LocalDensity.current
    var targetRect by remember { mutableStateOf(Rect.Zero) }
    val captured = targetRect != Rect.Zero
    // Live coordinates for the click handler, without driving recomposition on
    // scroll once the entry animation has settled.
    val coordsRef = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }

    val (offX, offY) = collapsedOffsetDp(index)
    val offXPx = with(density) { offX.dp.toPx() }
    val offYPx = with(density) { offY.dp.toPx() }

    // Everything below reads the animated phase and is computed in the draw phase
    // (inside graphicsLayer) so per-frame updates never recompose the composable.
    val s = ((index.toFloat() / StaggerCap).coerceIn(0f, 1f)) * MaxStagger

    val isSelecting = selection.active
    val isSelected = item in selection.selected
    val haptics = LocalHapticFeedback.current

    // Pairs with onTilePositioned so an off-screen tile never leaves a stale rect.
    DisposableEffect(item.uri) {
        onDispose { onTileDisposed(item.uri) }
    }

    // zIndex: higher = on top. The first item (index 0) stays on top throughout,
    // matching the folder stack where the newest preview is the front cover.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .zIndex(100f - index)
            .onGloballyPositioned { coords ->
                coordsRef[0] = coords
                // Only chase the target rect while the fly-in is animating;
                // after it settles, updating it would recompose on every scroll.
                if (targetRect == Rect.Zero || phase.value < 1f) {
                    targetRect = coords.boundsInWindow()
                }
                // Publish the live bounds (not the frozen fly-in target): the grid
                // reflows when an item is deleted, and the viewer's exit morph must
                // land on the tile's new slot, not the one it held before removal.
                // This map is plain state, so this never triggers recomposition.
                onTilePositioned(item.uri, coords.boundsInWindow())
            }
            .graphicsLayer {
                val p = ((phase.value - s) / (1f - s)).coerceIn(0f, 1f)
                val dx = (source.left + offXPx - targetRect.left) * (1f - p)
                val dy = (source.top + offYPx - targetRect.top) * (1f - p)
                val isStackCard = index < 3
                val cardAlpha = if (isStackCard) 1f else (p * 1.5f).coerceIn(0f, 1f)
                translationX = dx
                translationY = dy
                alpha = if (captured) cardAlpha else 0f
            }
            .clip(TileShape)
            .background(SurfaceColor)
            .then(
                if (isSelecting) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleSelect(item) }
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick(coordsRef[0]?.boundsInWindow() ?: targetRect) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongSelect(item)
                        },
                    )
                },
            ),
    ) {
        val thumbAlphaMod = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = ((phase.value - s) / (1f - s)).coerceIn(0f, 1f)
                val scrim = when (index) {
                    1 -> (1f - p) * 0.15f
                    2 -> (1f - p) * 0.35f
                    else -> 0f
                }
                alpha = 1f - scrim
            }
        // Static poster base for everything (fast, no blank tiles).
        if (item.isVideo) {
            VideoPosterThumb(item = item, modifier = thumbAlphaMod)
        } else {
            Thumb(item = item, modifier = thumbAlphaMod)
        }
        if (item.isVideo) {
            VideoBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .zIndex(1f),
            )
        }
        // Selection badge (top-left), shown in selection mode; reflects state.
        if (isSelecting) {
            SelectionBadge(
                selected = isSelected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .zIndex(2f),
            )
        }
        if (isSelecting && !isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack.copy(alpha = 0.4f)),
            )
        }
    }
}
