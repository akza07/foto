package com.phouton.phooto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.phouton.phooto.data.GalleryContent
import com.phouton.phooto.data.DeleteResult
import com.phouton.phooto.data.GalleryEvents
import com.phouton.phooto.data.MediaFolder
import com.phouton.phooto.data.MediaInfo
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.MediaRepository
import com.phouton.phooto.data.isAnimatedImage
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.mediaCollectionUri
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
import com.phouton.phooto.ui.theme.DeleteRed
import com.phouton.phooto.ui.theme.Surface as SurfaceColor
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TileShape = RoundedCornerShape(12.dp)
private val GridSpacing = 8.dp
// Height of the selection action bar so grids can reserve space for it.
private val ActionBarHeightDp = 96.dp

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

@Composable
fun GalleryScreen(
    modifier: Modifier = Modifier,
    openUri: Uri? = null,
    onOpenUriHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    SettingsRepository.init(context)
    val appSettings by SettingsRepository.settings.collectAsState()
    var content by remember { mutableStateOf<GalleryContent?>(null) }
    var openFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var openSourceRect by remember { mutableStateOf(Rect.Zero) }
    var closing by remember { mutableStateOf(false) }
    var viewerItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }
    var viewerSourceRect by remember { mutableStateOf(Rect.Zero) }
    // Item awaiting the viewer's delete slide-out. While set, viewerItems still
    // contains it so the pager can animate to the neighbor; the actual list
    // mutation happens in commitViewerRemoval once the slide ends.
    var pendingViewerRemoval by remember { mutableStateOf<MediaItem?>(null) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    var albumReloadKey by remember { mutableStateOf(0) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastKey by remember { mutableStateOf(0) }
    var pendingDeleteItem by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<SelectionState>(SelectionState()) }
    var showSearch by remember { mutableStateOf(false) }
    // Full library (folders + loose), used for name search. Populated by the
    // same pass that warms the thumbnail cache.
    var allItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(reloadKey) {
        content = MediaRepository.load(context)
    }

    val reload = { reloadKey += 1 }

    // Surface MediaStore changes so the gallery reloads when files
    // appear or disappear. MainActivity's SyncCoordinator (debounced) emits;
    // the composed gallery collects.
    LaunchedEffect(Unit) {
        GalleryEvents.reload.collect { reload() }
    }

    // Pre-warm the thumbnail cache for everything currently in the gallery.
    // Folder previews are URIs only (we don't keep the full MediaItem around
    // after buildFolders), so we warm them via their URI; loose items carry
    // enough metadata to use the full key derivation. Folder contents are then
    // AoT-warmed so opening any album is instant — all of this drives the same
    // "Caching previews · N%" progress pill (only real work is counted).
    LaunchedEffect(content) {
        val snapshot = content ?: return@LaunchedEffect
        snapshot.folders.forEach { folder ->
            folder.previews.forEach { uri -> ThumbCache.enqueueIfMissingUri(uri) }
        }
        snapshot.looseItems.forEach { item -> ThumbCache.enqueueIfMissing(item) }

        // AoT warm every item in every folder (deduped, chunked, capped).
        val loaded = MediaRepository.loadAllItems(context, snapshot).distinctBy { it.uri }
        allItems = loaded
        ThumbCache.enqueueAllIfMissing(loaded.take(CACHE_WARM_CAP))
    }

    val doClose = {
        openFolder = null
        openSourceRect = Rect.Zero
        closing = false
    }

    val closeFolder = {
        if (openFolder != null && !closing) {
            closing = true
        }
    }

    // Wait for the reverse animation to finish, then actually swap.
    LaunchedEffect(closing) {
        if (closing) {
            delay(ReverseDurationMs.toLong() + 100L)
            doClose()
        }
    }

    val closeViewer = {
        viewerItems = null
        viewerSourceRect = Rect.Zero
        pendingViewerRemoval = null
    }

    val openViewer = { list: List<MediaItem>, index: Int, rect: Rect ->
        viewerItems = list
        viewerIndex = index
        viewerSourceRect = rect
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
            toastMessage = "Couldn't open file"
        }
    }

    // Selection helpers ------------------------------------------------------
    val clearSelection = {
        selection = SelectionState()
    }

    val isSelecting = selection.active
    val selectionList = selection.selected
    val selectedFolders = selection.selectedFolders
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
        selection = if (selection.active) {
            selection.copy(
                selectedFolders = if (folder in selectedFolders) {
                    selectedFolders - folder
                } else {
                    selectedFolders + folder
                },
            )
        } else {
            // Entering selection mode starts with exactly this folder.
            SelectionState(active = true, selectedFolders = listOf(folder))
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
            val remaining = viewerItems?.filterNot { it.uri == removed.uri }
            if (remaining.isNullOrEmpty()) {
                closeViewer()
            } else {
                viewerItems = remaining
                viewerIndex = viewerIndex.coerceIn(0, remaining.lastIndex)
            }
        }
    }

    // Single-item delete (from the full-image viewer). The viewer stays open and
    // slides to the neighbour; the list mutation is deferred to
    // [commitViewerRemoval] so the pager can animate before the item disappears.
    // Only closes immediately when the deleted item was the last one.
    val removeDeleted = { deletedUri: android.net.Uri ->
        val current = content
        if (current != null) {
            content = current.copy(
                looseItems = current.looseItems.filterNot { it.uri == deletedUri },
            )
        }
        val list = viewerItems
        val removed = list?.firstOrNull { it.uri == deletedUri }
        if (removed != null) {
            if (list.size <= 1) {
                // Nothing to slide to: use the normal scale-out exit.
                closeViewer()
            } else {
                pendingViewerRemoval = removed
                toastKey += 1
                toastMessage = "Deleted"
            }
        }
        albumReloadKey += 1
        // Refresh the root grid so folder counts/previews update after a delete.
        reload()
    }

    // Batch variant: removes a set of URIs in one pass and reloads once.
    // Same stay-in-viewer contract as the single delete.
    val removeDeletedBatch = { deletedUris: Set<android.net.Uri> ->
        val current = content
        if (current != null) {
            content = current.copy(
                looseItems = current.looseItems.filterNot { it.uri in deletedUris },
            )
        }
        val remaining = viewerItems?.filterNot { it.uri in deletedUris }
        if (remaining.isNullOrEmpty()) {
            closeViewer()
        } else {
            viewerItems = remaining
            viewerIndex = viewerIndex.coerceIn(0, remaining.lastIndex)
            toastKey += 1
            toastMessage = if (deletedUris.size == 1) "Deleted" else "Deleted ${deletedUris.size} items"
        }
        albumReloadKey += 1
        reload()
    }

    // ── Delete consent ──────────────────────────────────────────────────────
    // Some MediaStore items need explicit user consent to delete. API 30+ uses a
    // single MediaStore.createDeleteRequest for the whole batch; API 29 hands
    // back a per-item RecoverableSecurityException intent sender, processed one
    // at a time through this queue.
    val scope = rememberCoroutineScope()
    var pendingConsent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // True when the in-flight consent intent covers every entry of
    // [pendingConsent] (API 30+ batch); false for API 29's per-item flow.
    var consentCoversAll by remember { mutableStateOf(false) }
    // True when the in-flight consent is a trash (API 30+) rather than a delete.
    var consentIsTrash by remember { mutableStateOf(false) }
    // Items moved to the trash that can still be restored (API 31+).
    var undoItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var undoKey by remember { mutableStateOf(0) }
    // Sentinel consumed by the LaunchedEffect below to launch the next API 29
    // consent dialog without the launcher referencing itself.
    var nextConsentLaunch by remember { mutableStateOf<androidx.activity.result.IntentSenderRequest?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val queue = pendingConsent
        val head = queue.firstOrNull()
        if (result.resultCode != android.app.Activity.RESULT_OK || head == null) {
            pendingConsent = emptyList()
            return@rememberLauncherForActivityResult
        }
        if (consentCoversAll) {
            // One dialog covered the whole batch: everything was deleted/trashed.
            removeDeletedBatch(queue.map { it.uri }.toSet())
            pendingConsent = emptyList()
            clearSelection()
            if (consentIsTrash) {
                undoKey += 1
                undoItems = queue
                consentIsTrash = false
            }
        } else {
            removeDeletedBatch(setOf(head.uri))
            val rest = queue.drop(1)
            pendingConsent = rest
            if (rest.isEmpty()) {
                clearSelection()
            } else {
                scope.launch {
                    // API 29: re-attempt the next item to recover its sender.
                    when (val r = MediaRepository.delete(context, rest.first())) {
                        is DeleteResult.Consent -> nextConsentLaunch =
                            androidx.activity.result.IntentSenderRequest.Builder(r.intentSender).build()
                        else -> {
                            pendingConsent = emptyList()
                            clearSelection()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(nextConsentLaunch) {
        nextConsentLaunch?.let {
            consentLauncher.launch(it)
            nextConsentLaunch = null
        }
    }

    // Restores trashed items (API 31+); the system shows its own confirmation.
    var pendingRestore by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val items = pendingRestore
        pendingRestore = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK && items.isNotEmpty()) {
            undoItems = null
            reload()
            toastKey += 1
            toastMessage = "Restored"
        }
    }

    val undoTrash: () -> Unit = {
        val items = undoItems
        if (items != null) {
            val sender = com.phouton.phooto.data.restoreConsentSender(context, items)
            if (sender != null) {
                pendingRestore = items
                restoreLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                )
            } else {
                undoItems = null
            }
        }
    }

    // API 30+ consent path: a single system dialog that moves the item(s) to the
    // system trash, so the action is non-destructive and (API 31+) undoable.
    val requestConsent: (List<MediaItem>) -> Unit = { items ->
        val sender = com.phouton.phooto.data.trashConsentSender(context, items)
        if (sender != null) {
            pendingConsent = items
            consentCoversAll = true
            consentIsTrash = true
            consentLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
            )
        } else {
            toastKey += 1
            toastMessage = "Couldn't delete"
        }
    }

    val requestDelete = { item: MediaItem ->
        scope.launch {
            when (val result = MediaRepository.delete(context, item)) {
                DeleteResult.Deleted -> removeDeleted(item.uri)
                is DeleteResult.Consent -> {
                    // API 29: the provider returned the consent intent directly.
                    pendingConsent = listOf(item)
                    consentCoversAll = false
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(result.intentSender).build(),
                    )
                }
                DeleteResult.Failed -> {
                    val isSafItem = android.provider.DocumentsContract.isDocumentUri(context, item.uri)
                    if (isSafItem) {
                        toastKey += 1
                        toastMessage = "Couldn't delete"
                    } else {
                        requestConsent(listOf(item))
                    }
                }
            }
        }
    }

    // ── Selection resolution ────────────────────────────────────────────────
    // Expands the current selection into a flat list of MediaItems: selected
    // folders contribute every item inside them (loaded on demand), selected
    // loose items contribute themselves. Used by delete/copy/move/share.
    val resolveSelectionItems: suspend () -> List<MediaItem> = {
        buildList {
            addAll(selectionList)
            for (folder in selectedFolders) {
                addAll(MediaRepository.loadBucket(context, folder))
            }
        }
    }

    // ── Multi-delete ────────────────────────────────────────────────────────
    // When multiple items are deleted, the system consent dialog (API 30+)
    // covers the whole batch; API 29 processes consent one item at a time.
    var pendingBatchDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    val batchDeleteScope = rememberCoroutineScope()

    val requestBatchDelete = { items: List<MediaItem> ->
        batchDeleteScope.launch {
            // Separate SAF items (deleted directly) from MediaStore items.
            val saf = items.filter {
                android.provider.DocumentsContract.isDocumentUri(context, it.uri)
            }
            val media = items.filterNot { it in saf }

            val deletedUris = mutableSetOf<android.net.Uri>()
            val needsConsent = mutableListOf<MediaItem>()

            // Direct deletes first (works for app-owned files / SAF grants).
            saf.forEach { item ->
                if (MediaRepository.delete(context, item) == DeleteResult.Deleted) {
                    deletedUris += item.uri
                }
            }
            media.forEach { item ->
                when (MediaRepository.delete(context, item)) {
                    DeleteResult.Deleted -> deletedUris += item.uri
                    is DeleteResult.Consent, DeleteResult.Failed -> needsConsent += item
                }
            }

            if (deletedUris.isNotEmpty()) removeDeletedBatch(deletedUris)

            if (needsConsent.isEmpty()) {
                clearSelection()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestConsent(needsConsent)
            } else {
                // API 29: one consent dialog per item, processed in order. The
                // first item was already attempted; recover its sender.
                pendingConsent = needsConsent
                consentCoversAll = false
                val sender = (MediaRepository.delete(context, needsConsent.first()) as? DeleteResult.Consent)
                    ?.intentSender
                if (sender != null) {
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                    )
                } else {
                    pendingConsent = emptyList()
                    clearSelection()
                }
            }
        }
    }

    // ── Copy / Move ────────────────────────────────────────────────────────
    var pendingCopy by remember { mutableStateOf<List<MediaItem>?>(null) }
    var pendingMove by remember { mutableStateOf<List<MediaItem>?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
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
            busyMessage = "Copying $count item${if (count == 1) "" else "s"}…"
            transferScope.launch {
                val done = MediaRepository.copyTo(context, items, uri)
                busyMessage = null
                clearSelection()
                if (done > 0) {
                    toastKey += 1
                    toastMessage = "Copied $done item${if (done == 1) "" else "s"}"
                    reload()
                } else {
                    toastKey += 1
                    toastMessage = "Couldn't copy"
                }
            }
        } else if (toMove != null && toMove.isNotEmpty()) {
            val items = toMove
            val count = items.size
            busyMessage = "Moving $count item${if (count == 1) "" else "s"}…"
            transferScope.launch {
                val result = MediaRepository.moveTo(context, items, uri)
                busyMessage = null
                clearSelection()
                when {
                    result.total == 0 -> {
                        toastKey += 1
                        toastMessage = "Couldn't move"
                    }
                    result.copiedOnly > 0 -> {
                        // Copied, but the source couldn't be removed (system
                        // consent needed), so the user now has a duplicate.
                        toastKey += 1
                        toastMessage = "Moved ${result.moved}, copied ${result.copiedOnly} " +
                            "(couldn't remove original)"
                        reload()
                    }
                    else -> {
                        toastKey += 1
                        toastMessage = "Moved ${result.moved} item${if (result.moved == 1) "" else "s"}"
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
        toastMessage = "Coming soon"
    }

    // The root Albums grid stays composed underneath the album, so when the album
    // fades on back, the folder stack is already exactly where the collapsing tiles
    // land — the "after" state is the real top-level view, never a swap-in.
    Box(modifier = modifier.fillMaxSize()) {
        MainGalleryGrid(
            content = content,
            onFolderClick = { folder, rect ->
                openFolder = folder
                openSourceRect = rect
            },
            onItemClick = { list, index, rect -> openViewer(list, index, rect) },
            onSettingsClick = { showSettings = true },
            onSearchClick = { showSearch = true },
            minTileSize = appSettings.gridSize.minTileDp.dp,
            selection = selection,
            onToggleSelect = toggleSelect,
            onLongSelect = toggleSelect,
            onToggleFolder = toggleSelectFolder,
            onLongSelectFolder = toggleSelectFolder,
            onSelectAll = {
                val loose = content?.looseItems.orEmpty()
                val folders = content?.folders.orEmpty()
                val allSelected = selection.selected.toSet() == loose.toSet() &&
                    selection.selectedFolders.toSet() == folders.toSet()
                if (selection.active && allSelected && (loose.isNotEmpty() || folders.isNotEmpty())) {
                    // Already all selected: deselect everything.
                    SelectionState(active = true)
                } else {
                    SelectionState(active = true, selected = loose, selectedFolders = folders)
                }
            },
            onExitSelection = clearSelection,
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SettingsScreen(
                onBack = { showSettings = false },
                onSettingsChanged = reload,
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
            )
        }

        val currentFolder = openFolder
        if (currentFolder != null) {
            AlbumGrid(
                folder = currentFolder,
                sourceRect = openSourceRect,
                reverse = closing,
                onBack = closeFolder,
                onItemClick = { list, index, rect -> openViewer(list, index, rect) },
                reloadKey = albumReloadKey,
                minTileSize = appSettings.gridSize.minTileDp.dp,
                selection = selection,
                onToggleSelect = toggleSelect,
                onLongSelect = toggleSelect,
                onSelectAll = { items ->
                    selection = if (selection.active && items.isNotEmpty() &&
                        selection.selected.toSet() == items.toSet()
                    ) {
                        // Already all selected: deselect everything.
                        SelectionState(active = true)
                    } else {
                        SelectionState(active = true, selected = items)
                    }
                },
                onExitSelection = clearSelection,
            )
        }

        val viewing = viewerItems
        if (viewing != null && viewing.isNotEmpty()) {
            FullImageViewer(
                items = viewing,
                initialIndex = viewerIndex.coerceIn(0, viewing.lastIndex),
                sourceRect = viewerSourceRect,
                pendingRemoval = pendingViewerRemoval,
                onRemovalAnimationEnd = commitViewerRemoval,
                onDismiss = closeViewer,
                onPageChanged = { viewerIndex = it },
                onDelete = {
                    // Ignore further deletes while a slide-out is already pending.
                    if (pendingViewerRemoval == null) {
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
            ConfirmDialog(
                title = "Delete this ${if (deleteTarget.isVideo) "video" else "photo"}?",
                message = "This action can't be undone.",
                confirmLabel = "Delete",
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
            ConfirmDialog(
                title = "Delete ${batch.size} item${if (batch.size == 1) "" else "s"}?",
                message = "This action can't be undone.",
                confirmLabel = "Delete",
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
            Toast(message = message, key = toastKey, onDismiss = { toastMessage = null })
        }

        if (undoItems != null) {
            UndoBar(
                key = undoKey,
                onUndo = undoTrash,
                onDismiss = { undoItems = null },
            )
        }

        busyMessage?.let { message ->
            BusyOverlay(message = message)
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        // Reserved header, same height as the album's nav back header, so both
        // views have identical geometry (no grid/stack offset jump between them).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection.active) {
                IconButton(onClick = onExitSelection) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Exit selection",
                        tint = TextPrimary,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${selection.size} selected",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = "Select all",
                        tint = TextPrimary,
                    )
                }
            } else {
                Text(
                    text = "Albums",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = TextPrimary,
                        modifier = Modifier.width(24.dp),
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = TextPrimary,
                        modifier = Modifier.width(24.dp),
                    )
                }
            }
        }

        val current = content
        when {
            current == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextPrimary,
                    )
                }
            }
            current.folders.isEmpty() && current.looseItems.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No media found",
                        color = TextMuted,
                        fontSize = 14.sp,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minTileSize),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 12.dp,
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
    sourceRect: Rect,
    reverse: Boolean,
    onBack: () -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    reloadKey: Int,
    minTileSize: Dp,
    selection: SelectionState,
    onToggleSelect: (MediaItem) -> Unit,
    onLongSelect: (MediaItem) -> Unit,
    onSelectAll: (List<MediaItem>) -> Unit,
    onExitSelection: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }

    LaunchedEffect(folder.bucketId, reloadKey) {
        items = MediaRepository.loadBucket(context, folder)
    }

    // Fade the whole album out on reverse so the root Albums view shows through.
    val albumAlpha by animateFloatAsState(
        targetValue = if (reverse) 0f else 1f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    )

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
    // Render the stack previews immediately (same URIs as the top items) so the
    // fly-to-grid animation can start without waiting for the query.
    val displayItems = realItems ?: folder.previews.mapIndexed { index, uri ->
        MediaItem(
            id = -(index + 1).toLong(),
            uri = uri,
            bucketId = folder.bucketId,
            bucketName = folder.name,
            relativePath = null,
            dateAdded = 0L,
            isVideo = false,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = albumAlpha)),
    ) {
        // The grid fills the whole screen; the header overlays it (Box) so content
        // scrolls underneath the frosted bar.
        Box(modifier = Modifier.fillMaxSize()) {
            val hazeState = rememberHazeState()

            if (displayItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No items", color = TextMuted, fontSize = 14.sp)
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
                    itemsIndexed(
                        displayItems,
                        key = { _, item -> "album:${item.uri}" },
                    ) { index, item ->
                        if (animate) {
                            FlyInTile(
                                item = item,
                                index = index,
                                source = sourceRect,
                                phase = phase,
                                selection = selection,
                                onToggleSelect = onToggleSelect,
                                onLongSelect = onLongSelect,
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        } else {
                            ImageTile(
                                item = item,
                                selection = selection,
                                onToggleSelect = onToggleSelect,
                                onLongSelect = onLongSelect,
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
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(AmoledBlack.copy(alpha = 0.6f), AmoledBlack.copy(alpha = 0.25f)),
                        ),
                    )
                    .then(
                        if (hazeEnabled) {
                            Modifier.hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    blurRadius = 18.dp,
                                    tints = listOf(HazeTint(AmoledBlack.copy(alpha = 0.35f))),
                                ),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .statusBarsPadding()
                    .height(56.dp)
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
                            contentDescription = "Exit selection",
                            tint = TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${selection.selected.size} selected",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onSelectAll(displayItems) }) {
                        Icon(
                            imageVector = Icons.Outlined.SelectAll,
                            contentDescription = "Select all",
                            tint = TextPrimary,
                        )
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
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
                            text = "${folder.count} items",
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
private fun FolderTile(
    folder: MediaFolder,
    onClick: (Rect) -> Unit,
    selection: SelectionState = SelectionState(),
    onToggleSelect: (MediaFolder) -> Unit = {},
    onLongSelect: (MediaFolder) -> Unit = {},
) {
    var stackRect by remember { mutableStateOf(Rect.Zero) }
    val isSelecting = selection.active
    val isSelected = folder in selection.selectedFolders
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${folder.name}, ${folder.count} items"
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
            text = "${folder.count} items",
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderStack(previews: List<Uri>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        // Front cover is the first (newest) preview, on top; the others peek from
        // behind with flush (untilted) offsets — identical to the collapsed pile.
        if (previews.size >= 3) {
            StackLayer(
                uri = previews[2],
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = 8.dp.toPx()
                        translationY = 8.dp.toPx()
                    },
                scrim = 0.35f,
            )
            StackLayer(
                uri = previews[1],
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
            uri = previews.firstOrNull(),
            modifier = Modifier.fillMaxSize(),
            scrim = 0f,
        )
    }
}

@Composable
internal fun ImageTile(
    item: MediaItem,
    onClick: (Rect) -> Unit,
    selection: SelectionState = SelectionState(),
    onToggleSelect: (MediaItem) -> Unit = {},
    onLongSelect: (MediaItem) -> Unit = {},
) {
    // Layout coordinates are kept in a non-observable holder: writing window
    // bounds into state would recompose every tile on every scroll frame.
    val coordsRef = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val isSelecting = selection.active
    val isSelected = item in selection.selected
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(TileShape)
            .background(SurfaceColor)
            .onGloballyPositioned { coordsRef[0] = it }
            .semantics {
                contentDescription = buildString {
                    append(item.displayName ?: if (item.isVideo) "Video" else "Photo")
                    if (isSelecting) {
                        append(if (isSelected) ", selected" else ", not selected")
                    }
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
private fun StackLayer(uri: Uri?, modifier: Modifier, scrim: Float) {
    Box(
        modifier = modifier
            .clip(TileShape)
            .background(SurfaceColor)
            .border(1.dp, Border, TileShape),
    ) {
        if (uri != null) {
            Thumb(
                uri = uri,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - scrim },
            )
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
    selection: SelectionState = SelectionState(),
    onToggleSelect: (MediaItem) -> Unit = {},
    onLongSelect: (MediaItem) -> Unit = {},
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

    // zIndex: higher = on top. The first item (index 0) stays on top throughout,
    // matching the folder stack where the newest preview is the front cover.
    Box(
        modifier = Modifier
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
