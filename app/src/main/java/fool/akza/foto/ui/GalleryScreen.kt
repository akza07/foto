package fool.akza.foto.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import fool.akza.foto.data.GallerySnapshot
import fool.akza.foto.data.MediaRepository
import fool.akza.foto.data.MediaSharing
import fool.akza.foto.data.ShareResult
import fool.akza.foto.data.buildDirectoryHierarchy
import fool.akza.foto.data.isExcluded
import fool.akza.foto.data.MetadataIndexer
import fool.akza.foto.data.MetadataStore
import fool.akza.foto.data.RenameResult
import fool.akza.foto.data.PrivateTrash
import fool.akza.foto.data.SettingsRepository
import fool.akza.foto.data.attention.AttentionRepository
import fool.akza.foto.data.attention.AttentionTile
import fool.akza.foto.data.attention.attentionKey
import fool.akza.foto.data.SystemTrash
import fool.akza.foto.data.mediaCollectionUri
import fool.akza.foto.data.MediaSort
import fool.akza.foto.data.itemComparator
import fool.akza.foto.data.sortTimestampMs
import fool.akza.foto.data.GridSize
import fool.akza.foto.data.SelectionActionPlacement
import fool.akza.foto.data.canonicalPath
import fool.akza.foto.data.buildDirectoryHierarchy
import fool.akza.foto.data.previewStamp
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.EmptySurface
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TileShape = RoundedCornerShape(12.dp)
private val GridSpacing = 8.dp

/** A viewer item removed by a pending delete, with its index for a slide-back Undo. */
private data class ViewerUndo(val item: MediaItem, val index: Int)

/** Bulk-delete progress for the blocking modal (private SAF copies). */
private data class DeleteProgress(val done: Int, val total: Int)

/** The source tile for one level of directory navigation. */
private data class DirectoryLevel(val path: String, val sourceRect: Rect)

/** Root surfaces: the chronological library or the folder grid. */
private enum class RootTab { PHOTOS, ALBUMS }

/** One row in the Photos timeline: a month header, a day header or a tile. */
private sealed interface TimelineEntry {
    val key: String

    data class Month(val label: String, override val key: String) : TimelineEntry
    data class Day(val label: String, override val key: String) : TimelineEntry
    data class Media(val item: MediaItem, val index: Int) : TimelineEntry {
        override val key: String get() = "timeline:${item.uri}"
    }
}

/** Groups [items] (already sorted) into month/day headers for the Photos tab. */
@Composable
private fun rememberTimelineEntries(
    items: List<MediaItem>,
    chronological: Boolean,
): List<TimelineEntry> {
    val todayLabel = stringResource(R.string.timeline_today)
    val yesterdayLabel = stringResource(R.string.timeline_yesterday)
    return remember(items, chronological, todayLabel, yesterdayLabel) {
        if (!chronological) {
            // Name sorts are shown as a flat grid; date headers would jump.
            items.mapIndexed { index, item -> TimelineEntry.Media(item, index) }
        } else {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val yesterday = today.minusDays(1)
            val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
            val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            buildList(items.size + 32) {
                var lastMonth: YearMonth? = null
                var lastDay: LocalDate? = null
                items.forEachIndexed { index, item ->
                    val date = Instant.ofEpochMilli(item.sortTimestampMs())
                        .atZone(zone)
                        .toLocalDate()
                    val month = YearMonth.from(date)
                    if (month != lastMonth) {
                        add(TimelineEntry.Month(date.format(monthFormat), "month:$month"))
                        lastMonth = month
                        lastDay = null
                    }
                    if (date != lastDay) {
                        val label = when (date) {
                            today -> todayLabel
                            yesterday -> yesterdayLabel
                            else -> date.format(dayFormat)
                        }
                        add(TimelineEntry.Day(label, "day:$date"))
                        lastDay = date
                    }
                    add(TimelineEntry.Media(item, index))
                }
            }
        }
    }
}

/** A rename waiting on the system write-consent dialog. */
private data class PendingRename(
    val item: MediaItem,
    val newName: String,
    val afterConsent: Boolean = false,
)

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

/**
 * URI the platform's trash/delete requests accept. MediaStore requests only
 * accept URIs from the item-specific collections (Images/Video), never the
 * generic Files collection, and SAF documents are not MediaStore rows at all.
 */
private fun MediaItem.trashRequestUri(): Uri =
    if (safTreeUri == null && id != 0L) mediaCollectionUri(isVideo, id) else uri

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
    // Side rails need a tall window: on a landscape phone the rail and the end
    // space it reserves would fill the grid, so short windows fall back to the
    // original full-width bar regardless of the stored preference.
    val windowHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val selectionPlacement = appSettings.selectionActionPlacement.takeIf { placement ->
        placement == SelectionActionPlacement.BOTTOM ||
            windowHeightDp >= SelectionRailMinWindowHeight
    } ?: SelectionActionPlacement.BOTTOM
    // Seeded with the snapshot prefetched in `MainActivity` (the disk read
    // started before this composition), so a returning launch paints the last
    // library on the very first frame; the effect below is only the fallback for
    // a first run / a prefetch that has not landed yet.
    var content by remember { mutableStateOf(GallerySnapshot.prefetched()) }
    var refreshing by remember { mutableStateOf(false) }
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
    // Image currently open in the full-screen crop editor (images only).
    var cropItem by remember { mutableStateOf<MediaItem?>(null) }
    // Image currently open in the full-screen wallpaper picker (images only).
    var wallpaperItem by remember { mutableStateOf<MediaItem?>(null) }
    // Just-saved crop while the gallery reloads and its preview is prepared.
    var pendingCropItem by remember { mutableStateOf<MediaItem?>(null) }
    var cropUpdating by remember { mutableStateOf(false) }
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
    var showInsights by remember { mutableStateOf(false) }
    // Root surface: the chronological Photos timeline is the default gallery
    // view; Albums keeps the folder-first grid (and the Insights tile).
    var rootTab by remember { mutableStateOf(RootTab.PHOTOS) }
    // Smart-tile snapshot, refreshed whenever a viewing session is written or
    // the history is cleared, so the tile appears/disappears on its own.
    val attentionVersion by AttentionRepository.version.collectAsState()
    var insightsTile by remember { mutableStateOf<AttentionTile?>(null) }
    LaunchedEffect(attentionVersion) {
        insightsTile = AttentionRepository.tile(context)
    }
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
    val storageFolderLabel = stringResource(R.string.folder_storage)
    // Hold a loader after saving a crop until the reload actually returns the new
    // item. This is the gap where the file exists but the directory still shows
    // the old contents (and its thumbnail has not been prepared yet).
    LaunchedEffect(visibleContent, pendingCropItem) {
        val target = pendingCropItem ?: return@LaunchedEffect
        val targetPath = target.canonicalPath()
        val found = visibleContent?.allItems?.firstOrNull { candidate ->
            (target.id != 0L && candidate.id == target.id) ||
                (targetPath != null && candidate.canonicalPath() == targetPath) ||
                candidate.uri == target.uri
        }
        if (found != null) {
            ThumbCache.enqueueIfMissing(found)
            pendingCropItem = null
            cropUpdating = false
            toastKey += 1
            toastMessage = UiText.Res(R.string.crop_saved)
        }
    }
    LaunchedEffect(pendingCropItem) {
        val target = pendingCropItem ?: return@LaunchedEffect
        delay(10_000)
        if (pendingCropItem == target) {
            pendingCropItem = null
            cropUpdating = false
            toastKey += 1
            toastMessage = UiText.Res(R.string.crop_saved)
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
            storageFolderLabel,
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
        val cached = visibleContent ?: return@remember null
        // A snapshot already *is* the album grid; deriving it from an item list
        // that is still loading would blank every count.
        if (cached.provisional) return@remember cached
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
        // Snapshot exactly what the albums grid shows — the hierarchy-derived
        // folders, not the flat directory list — so the next launch paints the
        // same tiles instead of re-flowing them a second later.
        val selectedRoots = if (appSettings.requireExplicit) {
            appSettings.selectedTreeUris.mapNotNull { treeUri ->
                MediaRepository.treeUriToRelativePath(context, treeUri)
            }
        } else {
            emptyList()
        }
        val levels = buildDirectoryHierarchy(
            loaded.allItems,
            appSettings.sortOrder,
            selectedRoots,
            context.getString(R.string.folder_storage),
        )
        GallerySnapshot.write(
            context,
            // `copy` keeps the timeline items the scan just returned: without
            // them the next launch would start the default Photos tab empty and
            // only fill in once MediaStore answers.
            loaded.copy(
                folders = levels.directories.map { it.folder },
                looseItems = levels.rootItems,
            ),
        )
        if (refreshing) {
            // Keep the pull indicator up long enough to read even when the
            // provider answers instantly.
            delay(300)
            refreshing = false
        }
    }

    // Paint the last gallery immediately (when the prefetch above did not land
    // before the first composition): a returning launch should not wait for
    // MediaStore to answer before showing albums and photos whose previews are
    // already cached on disk. The real load replaces this snapshot moments later.
    LaunchedEffect(Unit) {
        if (content == null) {
            GallerySnapshot.read(context)?.let { cached ->
                if (content == null) content = cached
            }
        }
    }

    val reload = { reloadKey += 1 }
    val refresh = {
        if (!refreshing) {
            refreshing = true
            reload()
        }
    }

    // Pre-warm the thumbnail cache for everything currently visible. Folder
    // covers and loose items carry full MediaItems, so the warm pass uses the
    // same identity-derived keys the UI reads. Folder contents are then
    // AoT-warmed so opening any album is instant — all of this drives the same
    // "Caching previews" progress pill (only real work is counted).
    val metadataIndexVersion by MetadataStore.version.collectAsState()
    LaunchedEffect(visibleContent, metadataIndexVersion) {
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
        // `load` already returned every item it grouped (folders only keep their
        // three newest covers), so re-querying each bucket here was pure waste —
        // one query per album, on the thread thumbnails needed.
        val loaded = snapshot.allItems
            .filterNot { it.isExcluded(removedUris, removedPaths) }
            .distinctBy { it.uri }
        allItems = loaded
        ThumbCache.enqueueAllIfMissing(loaded.take(CACHE_WARM_CAP))

        // Keep the metadata search index in step with the library. Incremental
        // and throttled: only files whose stamp changed are read.
        MetadataIndexer.sync(context, loaded)
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

    // Glide selection. One hold-and-drag is a single direction, decided by the
    // tile it starts on: starting on an unselected tile adds every covered tile,
    // starting on an already selected one removes them (so a second glide can
    // undo the first). Fixing the direction at the start keeps a tile from
    // flipping back and forth as the finger crosses it. Ticks the haptics engine
    // whenever the count changes so the glide is felt, not only seen.
    val glideHaptics = LocalHapticFeedback.current
    val glideTick = { before: Int ->
        if (selection.itemCount != before) {
            glideHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val glideItem = { item: MediaItem, deselect: Boolean ->
        val before = selection.itemCount
        selection = if (deselect) {
            if (item in selection.selected) {
                selection.copy(selected = selection.selected - item)
            } else {
                selection
            }
        } else if (selection.active) {
            if (item in selection.selected) selection else selection.copy(selected = selection.selected + item)
        } else {
            SelectionState(active = true, selected = listOf(item))
        }
        glideTick(before)
    }
    val glideFolder = { folder: MediaFolder, deselect: Boolean ->
        val before = selection.itemCount
        val directory = findDirectory(folder.bucketId)
        selection = if (deselect) {
            if (selectedFolders.any { it.bucketId == folder.bucketId } ||
                selectedDirectories.any { it.folder.bucketId == folder.bucketId }
            ) {
                selection.copy(
                    selectedFolders = selectedFolders.filterNot { it.bucketId == folder.bucketId },
                    selectedDirectories = selectedDirectories.filterNot {
                        it.folder.bucketId == folder.bucketId
                    },
                )
            } else {
                selection
            }
        } else if (selection.active) {
            if (directory != null) {
                if (selectedDirectories.any { it.folder.bucketId == folder.bucketId }) selection
                else selection.copy(selectedDirectories = selectedDirectories + directory)
            } else {
                if (folder in selectedFolders) selection else selection.copy(selectedFolders = selectedFolders + folder)
            }
        } else {
            if (directory != null) SelectionState(active = true, selectedDirectories = listOf(directory))
            else SelectionState(active = true, selectedFolders = listOf(folder))
        }
        glideTick(before)
    }

    // BackHandler: selection mode owns the back button.
    // The full-image viewer is intentionally absent here: it registers its own
    // BackHandler so system back runs the animated scale-out instead of removing
    // the viewer in a single frame.
    BackHandler(
        enabled = isSelecting || openFolder != null || showSettings || showSearch || showInsights,
        onBack = {
            if (showSearch) {
                showSearch = false
            } else if (showInsights) {
                showInsights = false
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

    // ── Delete: Android's system trash ─────────────────────────────────────
    // MediaStore items go through the platform trash (API 30+): the provider
    // flips the row's trashed state instead of this app copying every byte, so
    // a delete costs a round-trip rather than a full file copy — and the file
    // is still recoverable from the system trash even if Foto is uninstalled.
    // SAF documents have no trash, so they are deleted outright.
    val scope = rememberCoroutineScope()
    // Batches waiting on the consent dialog opened by [consentLauncher].
    var pendingTrashItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var pendingRestoreItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // API 29 has no system trash; its consent is per item (RecoverableSecurityException).
    var pendingDeleteItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    // Rename waiting on a system write-consent dialog, plus its retry trigger.
    var pendingRename by remember { mutableStateOf<PendingRename?>(null) }
    var renameAfterConsent by remember { mutableStateOf<PendingRename?>(null) }
    // Viewer item currently shown in the rename dialog.
    var renameItem by remember { mutableStateOf<MediaItem?>(null) }
    // Items the Undo bar can put back into the library.
    var undoItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var undoKey by remember { mutableStateOf(0) }
    // Sentinel consumed by the LaunchedEffect below to launch the next API 29
    // consent dialog without the launcher referencing itself.
    var nextConsentLaunch by remember { mutableStateOf<androidx.activity.result.IntentSenderRequest?>(null) }
    // Progress for a blocking bulk-delete modal; null when no delete is running.
    var deleteProgress by remember { mutableStateOf<DeleteProgress?>(null) }
    // Bumped once per bulk-delete run so the modal starts its bar from zero for
    // a new batch instead of animating back from the previous run's fraction.
    var deleteProgressRun by remember { mutableIntStateOf(0) }
    // Shared busy overlay for single-item deletes and copy/move transfers.
    var busyMessage by remember { mutableStateOf<UiText?>(null) }
    // Last non-null busy message, kept so the overlay can animate out.
    var lastBusyMessage by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(busyMessage) {
        if (busyMessage != null) lastBusyMessage = busyMessage
    }

    // Surface MediaStore changes so the gallery reloads when files appear or
    // disappear. MainActivity's SyncCoordinator (debounced) emits; the composed
    // gallery collects. A running bulk delete is skipped: it trashes files one
    // by one and each removal pings MediaStore, so reloading mid-batch competes
    // with the progress UI (the bar stalls, then catches up). The batch reloads
    // itself once it finishes.
    LaunchedEffect(Unit) {
        GalleryEvents.reload.collect {
            if (deleteProgress == null) reload()
        }
    }

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
        // Insights should not keep ranking files that no longer exist (and the
        // smart tile must not use a deleted file as its preview).
        scope.launch {
            AttentionRepository.forget(context, deletedItems.map { it.attentionKey() })
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

    // Lifts this session's delete suppression. A trashed item keeps its URI and
    // path, so the suppression would otherwise hide the restored file forever.
    val clearSuppression: (List<MediaItem>) -> Unit = { items ->
        val uris = items.map { it.uri }.toSet()
        val paths = items.mapNotNull { it.canonicalPath() }.toSet()
        if (uris.isNotEmpty()) removedUris = removedUris - uris
        if (paths.isNotEmpty()) removedPaths = removedPaths - paths
    }

    // Puts restored items back: lifts suppression, slides the viewer back to the
    // item it was showing, and reloads so counts and covers catch up.
    val finishRestore: (List<MediaItem>) -> Unit = { items ->
        clearSuppression(items)
        viewerUndo?.let { undo ->
            if (items.any { it.uri == undo.item.uri }) restoreViewerItem(undo)
        }
        viewerUndo = null
        reload()
    }

    // Tells the user a delete did not complete (these paths used to be silent).
    val reportDeleteFailure: () -> Unit = {
        viewerDeleteInFlightUri = null
        toastKey += 1
        toastMessage = UiText.Res(R.string.error_delete)
    }

    // Applies a successful rename: swap the viewer item, drop the old derived
    // preview if the URI changed, migrate the Insights history to the new
    // identity, and reload the library.
    val applyRename = { oldItem: MediaItem, renamed: MediaItem ->
        if (oldItem.uri != renamed.uri) {
            ThumbCache.evict(oldItem.uri)
            VideoPosterMemory.remove(oldItem.uri)
        }
        scope.launch {
            AttentionRepository.rekey(context, oldItem.attentionKey(), renamed)
        }
        viewerItems = viewerItems?.map { if (it.uri == oldItem.uri) renamed else it }
        if (viewerOpenerUri == oldItem.uri) viewerOpenerUri = renamed.uri
        if (pendingViewerRemoval?.uri == oldItem.uri) pendingViewerRemoval = renamed
        toastKey += 1
        toastMessage = UiText.Res(R.string.renamed)
        reload()
    }

    // API 29 only: MediaStore requires a per-item RecoverableSecurityException
    // consent, and there is no trash to fall back on. Walk [queue] deleting
    // directly; the first item that needs approval arms the launcher.
    suspend fun runApi29Deletes(queue: List<MediaItem>) {
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
            reportDeleteFailure()
        }
        if (round.deleted.isNotEmpty()) {
            removeDeletedBatch(round.deleted)
        }
        val pendingSender = sender
        if (pendingSender != null) {
            pendingDeleteItems = round.pending
            nextConsentLaunch = androidx.activity.result.IntentSenderRequest.Builder(pendingSender).build()
        } else {
            pendingDeleteItems = emptyList()
            clearSelection()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val trashing = pendingTrashItems
        val restoring = pendingRestoreItems
        val api29 = pendingDeleteItems
        val renaming = pendingRename
        pendingTrashItems = emptyList()
        pendingRestoreItems = emptyList()
        pendingDeleteItems = emptyList()
        pendingRename = null
        // The app-side copy work (if any) is done once the provider dialog returns.
        deleteProgress = null
        busyMessage = null
        val approved = result.resultCode == android.app.Activity.RESULT_OK
        if (!approved) {
            viewerDeleteInFlightUri = null
            if (trashing.isNotEmpty() || api29.isNotEmpty()) {
                reportDeleteFailure()
                clearSelection()
            }
            if (renaming != null) {
                toastKey += 1
                toastMessage = UiText.Res(R.string.rename_canceled)
            }
            return@rememberLauncherForActivityResult
        }
        when {
            // The write consent was granted: retry the rename once. A second
            // consent request is treated as a failure instead of looping.
            renaming != null -> renameAfterConsent = renaming.copy(afterConsent = true)
            // The provider has already moved these into the system trash.
            trashing.isNotEmpty() -> {
                removeDeletedBatch(trashing)
                // A mixed delete may already have put SAF items in private
                // trash before this system prompt was shown; keep both batches
                // restorable from the same Undo bar.
                undoItems = (undoItems.orEmpty() + trashing).distinctBy { it.uri }
                undoKey += 1
                clearSelection()
            }
            // Restore: the provider has untrashed them in place.
            restoring.isNotEmpty() -> {
                finishRestore(restoring)
                toastKey += 1
                toastMessage = UiText.Res(R.string.restored)
            }
            // API 29: the consent applied to the item at the head of the queue.
            api29.isNotEmpty() -> scope.launch { runApi29Deletes(api29) }
            else -> clearSelection()
        }
    }

    LaunchedEffect(nextConsentLaunch) {
        nextConsentLaunch?.let {
            consentLauncher.launch(it)
            nextConsentLaunch = null
        }
    }

    // Rename a viewer item. MediaStore may first return a write-consent request;
    // the launcher above arms [renameAfterConsent] so exactly one retry follows.
    suspend fun performRename(target: MediaItem, newName: String, afterConsent: Boolean) {
        when (val result = MediaRepository.rename(context, target, newName)) {
            is RenameResult.Renamed -> applyRename(target, result.item)
            is RenameResult.Consent -> {
                if (afterConsent) {
                    toastKey += 1
                    toastMessage = UiText.Res(R.string.error_rename)
                } else {
                    pendingRename = PendingRename(target, newName)
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(result.intentSender).build(),
                    )
                }
            }
            RenameResult.Failed -> {
                toastKey += 1
                toastMessage = UiText.Res(R.string.error_rename)
            }
        }
    }

    LaunchedEffect(renameAfterConsent) {
        val request = renameAfterConsent ?: return@LaunchedEffect
        renameAfterConsent = null
        performRename(request.item, request.newName, afterConsent = true)
    }

    // Undo restores both kinds of trash: SAF documents come back from Foto's
    // private trash, MediaStore items from Android's system trash.
    val undoTrash: () -> Unit = {
        val items = undoItems
        if (items != null) {
            undoItems = null
            scope.launch {
                val privateItems = items.filter { it.safTreeUri != null }
                val systemItems = items.filterNot { it.safTreeUri != null }
                val restoredPrivate = if (privateItems.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        privateItems.filter { PrivateTrash.restore(context, it) }
                    }
                } else emptyList()
                if (restoredPrivate.isNotEmpty()) {
                    finishRestore(restoredPrivate)
                    toastKey += 1
                    toastMessage = UiText.Res(R.string.restored)
                }
                if (restoredPrivate.size != privateItems.size) {
                    toastKey += 1
                    toastMessage = UiText.Res(R.string.error_restore)
                }

                val sender = if (systemItems.isNotEmpty()) {
                    SystemTrash.restoreRequest(context, systemItems.map { it.trashRequestUri() })
                } else null
                if (sender != null) {
                    pendingRestoreItems = systemItems
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                    )
                } else if (restoredPrivate.isEmpty()) {
                    viewerUndo = null
                }
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────
    // MediaStore items go to the system trash (no copy, no per-item query).
    // SAF documents have no platform trash, so their bytes are moved into
    // Foto's private trash first; only if that fails do we permanently delete.
    // Nothing here touches the UI thread.
    val trashAndDelete: (List<MediaItem>) -> Unit = { items ->
        if (items.isEmpty()) {
            clearSelection()
        } else scope.launch {
            val saf = items.filter { android.provider.DocumentsContract.isDocumentUri(context, it.uri) }
            val media = items.filterNot { android.provider.DocumentsContract.isDocumentUri(context, it.uri) }
            val bulk = items.size > 1
            if (saf.isNotEmpty()) {
                if (bulk) {
                    deleteProgressRun += 1
                    deleteProgress = DeleteProgress(done = 0, total = items.size)
                } else {
                    busyMessage = UiText.Res(R.string.deleting)
                }
                val trashed = mutableListOf<MediaItem>()
                val deleted = mutableListOf<MediaItem>()
                saf.forEachIndexed { index, item ->
                    if (PrivateTrash.trash(context, item)) {
                        trashed += item
                        deleted += item
                    } else if (MediaRepository.delete(context, item) == DeleteResult.Deleted) {
                        // No tree metadata to restore to (e.g. a document
                        // handed over by another app): permanent fallback.
                        deleted += item
                    }
                    if (bulk) {
                        deleteProgress = DeleteProgress(done = index + 1, total = items.size)
                    }
                }
                if (bulk) {
                    if (media.isEmpty()) {
                        // Let the bar ease to 100% before the modal exits, so a
                        // fast batch still reads as continuous progress rather
                        // than a single jump.
                        deleteProgress = DeleteProgress(done = items.size, total = items.size)
                        delay(360)
                    }
                    deleteProgress = null
                } else {
                    busyMessage = null
                }
                if (deleted.isNotEmpty()) removeDeletedBatch(deleted)
                if (trashed.isNotEmpty()) {
                    undoItems = (undoItems.orEmpty() + trashed).distinctBy { it.uri }
                    undoKey += 1
                }
                if (deleted.size != saf.size) reportDeleteFailure()
            }
            if (media.isEmpty()) {
                clearSelection()
                return@launch
            }
            if (SystemTrash.supported) {
                // One system prompt covers the whole batch; the provider does
                // the work, so large videos trash as fast as small photos.
                val sender = SystemTrash.trashRequest(context, media.map { it.trashRequestUri() })
                if (sender != null) {
                    pendingTrashItems = media
                    consentLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                    )
                } else {
                    deleteProgress = null
                    busyMessage = null
                    reportDeleteFailure()
                    clearSelection()
                }
            } else {
                deleteProgress = null
                runApi29Deletes(media)
            }
        }
    }
    val requestDelete = { item: MediaItem ->
        // This is only used by the full viewer. Lock it before the delete starts
        // so its current page cannot diverge from the item being deleted.
        viewerDeleteInFlightUri = item.uri
        trashAndDelete(listOf(item))
    }

    // True when the in-app confirm dialog adds information the system won't.
    // The provider's trash prompt already lists MediaStore files and asks for
    // approval, so asking twice only makes a delete feel slow; SAF documents
    // still get the app dialog because their private trash has no system prompt.
    val needsAppConfirm: (List<MediaItem>) -> Boolean = { items ->
        appSettings.confirmBeforeDelete && items.any { item ->
            item.safTreeUri != null ||
                !SystemTrash.supported ||
                android.provider.DocumentsContract.isDocumentUri(context, item.uri)
        }
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
    // Preparing a metadata-free copy reads and rewrites the whole image, so it
    // runs off the main thread behind the same busy overlay as copy/move. The
    // setting applies to photos; videos keep their original container bytes.
    val prepareAndShare: suspend (List<MediaItem>) -> Unit = { items ->
        if (items.isNotEmpty() && appSettings.allowSharing) {
            val stripMetadata = appSettings.stripShareMetadata
            val preparing = stripMetadata && items.any { !it.isVideo }
            if (preparing) busyMessage = UiText.Res(R.string.share_preparing)
            try {
                when (val result = MediaSharing.prepare(context, items, stripMetadata)) {
                    is ShareResult.Ready ->
                        context.startActivity(Intent.createChooser(result.send, null))
                    ShareResult.Failed -> {
                        toastKey += 1
                        toastMessage = UiText.Res(R.string.share_metadata_failed)
                    }
                }
            } catch (e: CancellationException) {
                // Leaving the gallery while a copy is being prepared is not a
                // share failure; let the coroutine cancel normally.
                throw e
            } catch (_: Exception) {
                // A missing chooser or a provider that vanished mid-share
                // should read as a normal failure, not take the app down.
                toastKey += 1
                toastMessage = UiText.Res(R.string.share_metadata_failed)
            } finally {
                busyMessage = null
            }
        }
    }

    val shareItems: (List<MediaItem>) -> Unit = { items ->
        transferScope.launch { prepareAndShare(items) }
    }

    val shareSelection: () -> Unit = {
        transferScope.launch { prepareAndShare(resolveSelectionItems()) }
    }

    val shareItem = { item: MediaItem -> shareItems(listOf(item)) }

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
            onSearchClick = { showSearch = true },
            tab = rootTab,
            onTabChange = { rootTab = it },
            insights = insightsTile,
            onInsightsClick = { if (directoryStack.isEmpty()) showInsights = true },
            minTileSize = appSettings.gridSize.minTileDp.dp,
            sortOrder = appSettings.sortOrder,
            gridSize = appSettings.gridSize,
            selectionActionPlacement = selectionPlacement,
            onSortOrderChange = { SettingsRepository.setSortOrder(it) },
            onGridSizeChange = { SettingsRepository.setGridSize(it) },
            isRefreshing = refreshing,
            onRefresh = refresh,
            // A swipe only switches tabs on the root surface itself: not while a
            // folder level, selection or a full-screen panel is on top.
            tabSwipeEnabled = directoryStack.isEmpty() &&
                !showSettings && !showSearch && !showInsights,
            selection = selection,
            onToggleSelect = { if (directoryStack.isEmpty()) toggleSelect(it) },
            onToggleFolder = { if (directoryStack.isEmpty()) toggleSelectFolder(it) },
            onSelectAll = {
                if (rootTab == RootTab.PHOTOS) {
                    val items = rootGalleryContent?.allItems.orEmpty()
                    if (selection.active && items.isNotEmpty() &&
                        selection.selected.toSet() == items.toSet()
                    ) {
                        SelectionState(active = true)
                    } else {
                        SelectionState(active = true, selected = items)
                    }
                } else {
                    val loose = visibleContent?.looseItems.orEmpty()
                    val folders = rootGalleryContent?.folders.orEmpty()
                    val allSelected = selection.selected.toSet() == loose.toSet() &&
                        selection.selectedDirectories.map { it.folder.bucketId }.toSet() ==
                        folders.map { it.bucketId }.toSet()
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
                }
            },
            onExitSelection = clearSelection,
            onTilePositioned = registerTileRect,
            onTileDisposed = unregisterTileRect,
            onGlideItem = glideItem,
            onGlideFolder = glideFolder,
            // Keep the root header frosted for the viewer's whole lifetime. It is
            // hidden behind the opaque viewer, but keeping the haze warm means the
            // entry and exit never reveal a transparent-then-blurred header.
            frostEnabled = (directoryStack.isEmpty() || directoryStack.size == 1 && closing) &&
                !showSettings && !showSearch && !showInsights,
        )

        androidx.compose.animation.AnimatedVisibility(
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

        androidx.compose.animation.AnimatedVisibility(
            visible = showInsights,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            InsightsScreen(
                onBack = { showInsights = false },
                // The viewer draws above Insights, so closing it reveals the
                // ranked list again; no source rect means a clean fade in/out.
                onOpen = { list, index -> openViewer(list, index, Rect.Zero) },
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
                    onSearchClick = { showSearch = true },
                    onItemClick = { list, index, rect ->
                        if (isTopLevel && !closing) openViewer(list, index, rect)
                    },
                    items = directory.directItems,
                    onToggleFolder = { if (isTopLevel) toggleSelectFolder(it) },
                    minTileSize = appSettings.gridSize.minTileDp.dp,
                    selection = selection,
                    selectionActionPlacement = selectionPlacement,
                    onToggleSelect = { if (isTopLevel) toggleSelect(it) },
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
                    onGlideItem = glideItem,
                    onGlideFolder = glideFolder,
                    onGlideDirectory = { dir, deselect -> glideFolder(dir.folder, deselect) },
                    sortOrder = appSettings.sortOrder,
                    gridSize = appSettings.gridSize,
                    onSortOrderChange = { SettingsRepository.setSortOrder(it) },
                    onGridSizeChange = { SettingsRepository.setGridSize(it) },
                    isRefreshing = refreshing,
                    onRefresh = refresh,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
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

        val viewing = viewerItems
        if (viewing != null && viewing.isNotEmpty()) {
            // Only morph back to the tile for the page actually being viewed.
            // A page reached by swiping may have no composed tile in the grid;
            // in that case the viewer fades out instead of flying to its opener.
            val currentViewerUri =
                viewing.getOrNull(viewerIndex.coerceIn(0, viewing.lastIndex))?.uri
            // While Insights covers the gallery its tiles are still composed
            // (and publishing bounds), but they are not visible: fade instead of
            // morphing into a tile that sits behind the opaque cover.
            val visibleTileRect = if (showInsights) {
                null
            } else {
                currentViewerUri?.let { tileRects[it] }?.takeIf { rect ->
                    rect.right > 0f && rect.bottom > 0f &&
                        rect.left < rootView.width.toFloat() &&
                        rect.top < rootView.height.toFloat()
                }
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
                    // Video deletes always confirm: the wheel's Delete sector is
                    // easy to land on mid-gesture, so the setting never skips it.
                    if (pendingViewerRemoval == null && viewerDeleteInFlightUri == null) {
                        if (it.isVideo || needsAppConfirm(listOf(it))) {
                            pendingDeleteItem = it
                        } else {
                            requestDelete(it)
                        }
                    }
                },
                onShare = { shareItem(it) },
                onInfo = { infoItem = it },
                onRename = { renameItem = it },
                onCrop = { item -> cropItem = item },
                onWallpaper = { item -> wallpaperItem = item },
                loopVideos = appSettings.loopVideos,
                shareEnabled = appSettings.allowSharing,
            )

            // Crop is a full-screen editor layered above the viewer. Saving writes
            // a new copy, so swap the viewer's page to the result and reload the
            // grid; the original photo remains untouched.
            val cropTarget = cropItem
            if (cropTarget != null) {
                CropScreen(
                    item = cropTarget,
                    onDismiss = { cropItem = null },
                    onSaved = { cropped ->
                        cropItem = null
                        viewerItems = viewerItems?.map { current ->
                            if (current.uri == cropTarget.uri) cropped else current
                        }
                        pendingCropItem = cropped
                        cropUpdating = true
                        reload()
                    },
                )
            }

            // Wallpaper is a full-screen picker layered above the viewer too. It
            // writes nothing to the library: the crop goes straight to the system
            // wallpaper service, so there is nothing to reload on success.
            val wallpaperSource = wallpaperItem
            if (wallpaperSource != null) {
                WallpaperScreen(
                    item = wallpaperSource,
                    onDismiss = { wallpaperItem = null },
                    onApplied = {
                        wallpaperItem = null
                        toastKey += 1
                        toastMessage = UiText.Res(R.string.wallpaper_set_done)
                    },
                )
            }
        }

        val deleteTarget = pendingDeleteItem
        if (deleteTarget != null) {
            val canUndoDelete = deleteTarget.safTreeUri != null ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !android.provider.DocumentsContract.isDocumentUri(context, deleteTarget.uri))
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

        // Multi-select action bar + selection mode state. The placement setting
        // decides which edge it animates in from: side rails keep the actions
        // inside a thumb's reach when the phone is held one-handed.
        AnimatedVisibility(
            visible = isSelecting,
            enter = when (selectionPlacement) {
                SelectionActionPlacement.BOTTOM ->
                    slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160))
                SelectionActionPlacement.LEFT ->
                    slideInHorizontally(tween(220)) { -it } + fadeIn(tween(160))
                SelectionActionPlacement.RIGHT ->
                    slideInHorizontally(tween(220)) { it } + fadeIn(tween(160))
            },
            exit = when (selectionPlacement) {
                SelectionActionPlacement.BOTTOM ->
                    slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(120))
                SelectionActionPlacement.LEFT ->
                    slideOutHorizontally(tween(160)) { -it } + fadeOut(tween(120))
                SelectionActionPlacement.RIGHT ->
                    slideOutHorizontally(tween(160)) { it } + fadeOut(tween(120))
            },
            modifier = Modifier.align(selectionPlacement.anchor()),
        ) {
            SelectionActionBar(
                placement = selectionPlacement,
                onDelete = {
                    if (selectedCount > 0) {
                        batchDeleteScope.launch {
                            val items = resolveSelectionItems()
                            if (items.isNotEmpty()) {
                                // Bulk deletes always ask first, even when the system
                                // trash prompt would follow: one mis-tap in selection
                                // mode can remove a whole album, so the count and the
                                // undo story are shown before anything disappears.
                                pendingBatchDelete = items
                            }
                        }
                    }
                },
                onCopy = launchCopy,
                onMove = launchMove,
                onShare = if (appSettings.allowSharing) shareSelection else null,
            )
        }

        // Batch-delete confirmation dialog.
        val batch = pendingBatchDelete
        if (batch != null && batch.isNotEmpty()) {
            val canUndoDelete = batch.all { item ->
                item.safTreeUri != null ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !android.provider.DocumentsContract.isDocumentUri(context, item.uri))
            }
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

        val renameTarget = renameItem
        if (renameTarget != null) {
            RenameDialog(
                item = renameTarget,
                onDismiss = { renameItem = null },
                onRename = { newName ->
                    renameItem = null
                    scope.launch { performRename(renameTarget, newName, afterConsent = false) }
                },
            )
        }

        toastMessage?.let { message ->
            Toast(message = message.resolve(), key = toastKey, onDismiss = { toastMessage = null })
        }

        if (undoItems != null) {
            UndoBar(
                key = undoKey,
                onUndo = undoTrash,
                onDismiss = {
                    undoItems = null
                    viewerUndo = null
                },
            )
        }

        val shownBusy = busyMessage ?: lastBusyMessage
        if (shownBusy != null) {
            BusyOverlay(
                visible = busyMessage != null,
                message = shownBusy.resolve(),
            )
        }
        BusyOverlay(
            visible = cropUpdating,
            message = stringResource(R.string.crop_preparing_preview),
        )

        val progress = deleteProgress
        DeleteProgressOverlay(
            visible = progress != null,
            runId = deleteProgressRun,
            done = progress?.done ?: 0,
            total = progress?.total ?: 0,
        )

        // Background-work indicator, positioned once at the root so it covers
        // both the Albums grid and the open album grid. The search screen reports
        // the same work inline (with its own notice) and Settings reports it in
        // its Storage rows, so the pill stays out of the way of both.
        if (!showSearch && !showSettings) {
            BackgroundWorkIndicator(modifier = Modifier.fillMaxSize())
        }
    }
}

/** Top bar while items are selected: close, live count, select-all. */
@Composable
private fun SelectionHeader(
    count: Int,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExitSelection) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.selection_exit),
                tint = TextPrimary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = pluralStringResource(R.plurals.selection_count, count, count),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(
                imageVector = Icons.Outlined.SelectAll,
                contentDescription = stringResource(R.string.select_all),
                tint = TextPrimary,
            )
        }
    }
}

/** Top bar while browsing: title plus filter/sort, search and settings. */
@Composable
private fun RootTabsHeader(
    tab: RootTab,
    onTabChange: (RootTab) -> Unit,
    /** Live pager position between the tabs, 0 = Photos, 1 = Albums. */
    tabFraction: Float = if (tab == RootTab.PHOTOS) 0f else 1f,
    sortOrder: MediaSort,
    gridSize: GridSize,
    onSortOrderChange: (MediaSort) -> Unit,
    onGridSizeChange: (GridSize) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val haptics = LocalHapticFeedback.current
        val segmentWidth = 76.dp
        val photosSelected = tab == RootTab.PHOTOS
        // Driven by the pager offset, not by a tween: while a swipe is in flight
        // the pill sits between the two labels, 1:1 with the finger.
        val indicatorOffset = segmentWidth * tabFraction
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceColor)
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                // One toggle for the whole control: tapping either label (or the
                // gap between them) flips to the other view.
                .toggleable(
                    value = photosSelected,
                    role = Role.Switch,
                    onValueChange = { isPhotos ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabChange(if (isPhotos) RootTab.PHOTOS else RootTab.ALBUMS)
                    },
                )
                .padding(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmptySurface)
                    .border(1.dp, Border, RoundedCornerShape(8.dp)),
            )
            Row {
                RootTab.entries.forEach { entry ->
                    val selected = entry == tab
                    val textColor by animateColorAsState(
                        targetValue = if (selected) TextPrimary else TextMuted,
                        animationSpec = tween(180),
                        label = "rootTabText",
                    )
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .height(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (entry == RootTab.PHOTOS) R.string.photos_title else R.string.albums,
                            ),
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        FilterMenuButton(
            sortOrder = sortOrder,
            gridSize = gridSize,
            onSortOrderChange = onSortOrderChange,
            onGridSizeChange = onGridSizeChange,
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

/** Filter icon + dropdown shared by the Albums and album headers. */
@Composable
private fun FilterMenuButton(
    sortOrder: MediaSort,
    gridSize: GridSize,
    onSortOrderChange: (MediaSort) -> Unit,
    onGridSizeChange: (GridSize) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.FilterList,
                contentDescription = stringResource(R.string.action_filter),
                tint = TextPrimary,
                modifier = Modifier.width(24.dp),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            MenuSectionLabel(stringResource(R.string.display_sort_title))
            MediaSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes), color = TextPrimary) },
                    onClick = {
                        onSortOrderChange(sort)
                        showMenu = false
                    },
                    trailingIcon = {
                        if (sort == sortOrder) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = TextPrimary)
                        }
                    },
                )
            }
            HorizontalDivider()
            MenuSectionLabel(stringResource(R.string.display_grid_title))
            GridSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(stringResource(size.labelRes), color = TextPrimary) },
                    onClick = {
                        onGridSizeChange(size)
                        showMenu = false
                    },
                    trailingIcon = {
                        if (size == gridSize) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = TextPrimary)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Pull-to-refresh wrapper for the grid screens, with the spinner below the app bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = AppBarHeight + 8.dp),
                containerColor = SurfaceColor,
                color = TextPrimary,
            )
        },
    ) {
        content()
    }
}

/**
 * Chronological Photos grid: month/day headers interleaved with the same
 * [ImageTile]s, long-press glide and viewer hand-off used by the album grids.
 * The tile keys are `timeline:<uri>` so hit-testing stays isolated from the
 * album grid's `item:`/`album:` keys.
 */
@Composable
private fun TimelineGrid(
    items: List<MediaItem>,
    selection: SelectionState,
    selectionActionPlacement: SelectionActionPlacement,
    minTileSize: Dp,
    sortOrder: MediaSort,
    gridState: LazyGridState,
    onToggleSelect: (MediaItem) -> Unit,
    onGlideItem: (MediaItem, Boolean) -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
) {
    val chronological = sortOrder == MediaSort.DATE_NEWEST || sortOrder == MediaSort.DATE_OLDEST
    val entries = rememberTimelineEntries(items, chronological)
    // A sort change rebuilds the headers around different anchors; keeping the
    // old pixel offset lands the user in an unrelated month, so return to the
    // newest (or first) item instead.
    LaunchedEffect(sortOrder) { gridState.scrollToItem(0) }
    val tileBounds = remember { mutableMapOf<String, Rect>() }
    val haptics = LocalHapticFeedback.current
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val glideDeselectsAt: (Offset) -> Boolean = glideDeselectsAt@{ position ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideDeselectsAt false
        val item = items.firstOrNull { "timeline:${it.uri}" == key } ?: return@glideDeselectsAt false
        item in selection.selected
    }
    val glideAt: (Offset, Boolean) -> Unit = glideAt@{ position, deselect ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideAt
        val item = items.firstOrNull { "timeline:${it.uri}" == key } ?: return@glideAt
        onGlideItem(item, deselect)
    }
    val glidePosition by rememberUpdatedState(glideAt)
    val glideDeselects by rememberUpdatedState(glideDeselectsAt)
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = minTileSize),
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { gridCoordinates = it }
            .pointerInput(Unit) {
                // One owner for long-press + glide, with the press-time tile
                // deciding select vs deselect (see MainGalleryGrid).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val deselect = glideDeselects(longPress.position)
                    glidePosition(longPress.position, deselect)
                    var pointerId = longPress.id
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull { it.pressed }
                            ?: break
                        pointerId = change.id
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        glidePosition(change.position, deselect)
                    }
                }
            },
        contentPadding = PaddingValues(
            start = 12.dp,
            top = statusBarTop + AppBarHeight + 12.dp,
            end = 12.dp,
            bottom = if (selection.active) {
                selectionActionPlacement.reservedEndSpace() + 12.dp +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            } else {
                12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            },
        ),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
    ) {
        itemsIndexed(
            items = entries,
            key = { _, entry -> entry.key },
            span = { _, entry ->
                if (entry is TimelineEntry.Media) GridItemSpan(1) else GridItemSpan(maxLineSpan)
            },
        ) { _, entry ->
            when (entry) {
                is TimelineEntry.Month -> Text(
                    text = entry.label,
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                )
                is TimelineEntry.Day -> Text(
                    text = entry.label,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
                is TimelineEntry.Media -> ImageTile(
                    item = entry.item,
                    selection = selection,
                    onToggleSelect = onToggleSelect,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(180),
                        fadeOutSpec = tween(180),
                    ),
                    onTilePositioned = { uri, rect ->
                        onTilePositioned(uri, rect)
                        tileBounds["timeline:$uri"] = rect
                    },
                    onTileDisposed = { uri ->
                        onTileDisposed(uri)
                        tileBounds.remove("timeline:$uri")
                    },
                    onClick = { rect -> onItemClick(items, entry.index, rect) },
                )
            }
        }
    }
}

/**
 * The launch/refresh placeholder shown while the library has nothing to paint
 * yet. Shared by the timeline, the albums grid and the "nothing loaded" case so
 * every empty-but-loading state looks the same.
 */
@Composable
private fun GalleryLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = TextPrimary,
        )
    }
}

/** Centered "no media" label for a library that really is empty. */
@Composable
private fun NoMediaMessage() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_media_found),
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun MainGalleryGrid(
    content: GalleryContent?,
    onFolderClick: (MediaFolder, Rect) -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    tab: RootTab,
    onTabChange: (RootTab) -> Unit,
    minTileSize: Dp,
    selection: SelectionState,
    selectionActionPlacement: SelectionActionPlacement = SelectionActionPlacement.BOTTOM,
    onToggleSelect: (MediaItem) -> Unit,
    onToggleFolder: (MediaFolder) -> Unit,
    onSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
    frostEnabled: Boolean = true,
    onGlideItem: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onGlideFolder: (MediaFolder, Boolean) -> Unit = { _, _ -> },
    onGlideDirectory: (GalleryDirectory, Boolean) -> Unit = { _, _ -> },
    sortOrder: MediaSort = MediaSort.DATE_NEWEST,
    gridSize: GridSize = GridSize.MEDIUM,
    onSortOrderChange: (MediaSort) -> Unit = {},
    onGridSizeChange: (GridSize) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Whether a horizontal swipe switches tabs (off behind overlays/albums). */
    tabSwipeEnabled: Boolean = true,
    insights: AttentionTile? = null,
    onInsightsClick: () -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val timelineGridState = rememberLazyGridState()
    // Root tabs as a pager: a swipe moves the neighbour in 1:1 with the finger.
    val tabPagerState = rememberPagerState(initialPage = tab.ordinal) { RootTab.entries.size }
    // Tapping the header pill scrolls the pager to that tab (animated, so the
    // drag-tracking indicator glides along with it).
    LaunchedEffect(tab) {
        if (tabPagerState.currentPage != tab.ordinal) {
            tabPagerState.animateScrollToPage(tab.ordinal)
        }
    }
    // A swipe that settles on the other page reports back, so everything keyed on
    // the tab (header state, select-all scope, selection rail) follows it.
    LaunchedEffect(tabPagerState.settledPage) {
        val settled = RootTab.entries[tabPagerState.settledPage]
        if (settled != tab) onTabChange(settled)
    }
    // Live 0..1 position of the pager, fed to the header pill so it tracks the
    // finger instead of jumping on settle.
    val tabFraction =
        (tabPagerState.currentPage + tabPagerState.currentPageOffsetFraction)
            .coerceIn(0f, (RootTab.entries.size - 1).toFloat())
    val currentContent by rememberUpdatedState(content)
    val glideItemState by rememberUpdatedState(onGlideItem)
    val glideFolderState by rememberUpdatedState(onGlideFolder)
    // Window-space bounds of every composed tile, keyed the same way as the
    // LazyGrid keys. Hit-testing against real bounds keeps glide selection exact
    // regardless of content padding, scroll offset and grid spacing.
    val tileBounds = remember { mutableMapOf<String, Rect>() }
    val haptics = LocalHapticFeedback.current
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // One continuous glide is either a select sweep or a deselect sweep: the
    // state of the tile under the finger at press time decides which. The
    // direction is fixed for the whole gesture, so a tile never flips back and
    // forth as the finger crosses it.
    val glideDeselectsAt: (Offset) -> Boolean = glideDeselectsAt@{ position ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideDeselectsAt false
        val content = currentContent ?: return@glideDeselectsAt false
        when {
            key.startsWith("folder:") -> content.folders
                .firstOrNull { "folder:${it.bucketId}" == key }
                ?.let { folder ->
                    // A folder can sit in either list depending on whether the
                    // directory was known when it was picked.
                    selection.selectedFolders.any { it.bucketId == folder.bucketId } ||
                        selection.selectedDirectories.any {
                            it.folder.bucketId == folder.bucketId
                        }
                }
                ?: false
            key.startsWith("item:") -> content.looseItems
                .firstOrNull { "item:${it.uri}" == key }
                ?.let { item -> item in selection.selected }
                ?: false
            else -> false
        }
    }
    val glideAt: (Offset, Boolean) -> Unit = glideAt@{ position, deselect ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideAt
        val content = currentContent ?: return@glideAt
        when {
            key.startsWith("folder:") -> content.folders
                .firstOrNull { "folder:${it.bucketId}" == key }
                ?.let { glideFolderState(it, deselect) }
            key.startsWith("item:") -> content.looseItems
                .firstOrNull { "item:${it.uri}" == key }
                ?.let { glideItemState(it, deselect) }
        }
    }
    val glidePosition by rememberUpdatedState(glideAt)
    val glideDeselects by rememberUpdatedState(glideDeselectsAt)

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
                // Cross-fades between browse and selection modes. The weight is
                // on AnimatedContent — not on the children — because its content
                // is not laid out by the Row itself.
                AnimatedContent(
                    targetState = selection.active,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "appBarMode",
                ) { isSelecting ->
                    if (isSelecting) {
                        SelectionHeader(
                            count = selection.itemCount,
                            onExitSelection = onExitSelection,
                            onSelectAll = onSelectAll,
                        )
                    } else {
                        RootTabsHeader(
                            tab = tab,
                            tabFraction = tabFraction,
                            onTabChange = onTabChange,
                            sortOrder = sortOrder,
                            gridSize = gridSize,
                            onSortOrderChange = onSortOrderChange,
                            onGridSizeChange = onGridSizeChange,
                            onSearchClick = onSearchClick,
                            onSettingsClick = onSettingsClick,
                        )
                    }
                }
            }
        },
    ) { gridModifier ->
        GalleryPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = gridModifier,
        ) {
            val current = content
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            // The two root views are pages of a pager, so a swipe drags the next
            // view in 1:1 with the finger (the header pill reads the same offset)
            // instead of playing a cross-fade after the gesture ends.
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxSize(),
                // Off while a swipe must not change the view: selection mode, an
                // open album level, Settings/Search/Insights.
                userScrollEnabled = tabSwipeEnabled && !selection.active,
                // Compose the neighbour ahead of the drag, so the first frame of
                // a swipe already has real content to slide in.
                beyondViewportPageCount = 1,
            ) { page ->
            when {
                current == null -> GalleryLoading()
                page == RootTab.PHOTOS.ordinal -> {
                    val timelineItems = remember(current.allItems, sortOrder) {
                        current.allItems.sortedWith(itemComparator(sortOrder))
                    }
                    when {
                        timelineItems.isNotEmpty() -> TimelineGrid(
                            items = timelineItems,
                            selection = selection,
                            selectionActionPlacement = selectionActionPlacement,
                            minTileSize = minTileSize,
                            sortOrder = sortOrder,
                            gridState = timelineGridState,
                            onToggleSelect = onToggleSelect,
                            onGlideItem = onGlideItem,
                            onItemClick = onItemClick,
                            onTilePositioned = onTilePositioned,
                            onTileDisposed = onTileDisposed,
                        )
                        // A snapshot written before the timeline preview existed
                        // (or whose preview was dropped) has no Photos items yet
                        // while the scan is still running: show the spinner
                        // rather than claiming the library is empty.
                        current.provisional -> GalleryLoading()
                        else -> NoMediaMessage()
                    }
                }
                current.folders.isEmpty() && current.looseItems.isEmpty() ->
                    if (current.provisional) GalleryLoading() else NoMediaMessage()
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = minTileSize),
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { gridCoordinates = it }
                        .pointerInput(Unit) {
                            // The grid owns long-press so the gesture is not lost to
                            // the tile's own click detector: whichever timer fires
                            // first used to win, so gliding was a race. Here the
                            // long press selects the tile under the finger and the
                            // drag that follows keeps adding (or removing) tiles
                            // until release.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPress =
                                    awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                // One glide, one direction: pressing an already
                                // selected tile makes the whole sweep deselect.
                                val deselect = glideDeselects(longPress.position)
                                glidePosition(longPress.position, deselect)
                                // Keep the gesture for the glide by consuming in the
                                // Initial pass: the grid's own scroll detector runs
                                // deeper in the tree (Main pass), so without this a
                                // fast drag would scroll the grid and cancel us.
                                var pointerId = longPress.id
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: event.changes.firstOrNull { it.pressed }
                                        ?: break
                                    pointerId = change.id
                                    if (!change.pressed) {
                                        // Consume the up event too: otherwise the
                                        // tile's tap detector fires on release and
                                        // immediately toggles the just-selected item
                                        // back off.
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                    glidePosition(change.position, deselect)
                                }
                            }
                        },
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = statusBarTop + AppBarHeight + 12.dp,
                        end = 12.dp,
                        bottom = if (selection.active) {
                            selectionActionPlacement.reservedEndSpace() + 12.dp +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        } else {
                            12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        },
                    ),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridSpacing),
                ) {
                    insights?.takeIf { !selection.active }?.let { tile ->
                        item(key = "insights") {
                            InsightsTile(
                                tile = tile,
                                onClick = onInsightsClick,
                                // Deletes/reloads reflow the grid instead of jumping.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(180),
                                    fadeOutSpec = tween(180),
                                ),
                            )
                        }
                    }
                    items(current.folders, key = { "folder:${it.bucketId}" }) { folder ->
                        FolderTile(
                            folder = folder,
                            selection = selection,
                            onToggleSelect = onToggleFolder,
                            onClick = { rect -> onFolderClick(folder, rect) },
                            modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                            onBoundsChanged = { tileBounds["folder:${folder.bucketId}"] = it },
                        )
                    }
                    items(current.looseItems, key = { "item:${it.uri}" }) { item ->
                        ImageTile(
                            item = item,
                            selection = selection,
                            onToggleSelect = onToggleSelect,
                            // Deletes reflow the grid instead of jumping.
                            modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                            onTilePositioned = { uri, rect ->
                                onTilePositioned(uri, rect)
                                tileBounds["item:$uri"] = rect
                            },
                            onTileDisposed = { uri ->
                                onTileDisposed(uri)
                                tileBounds.remove("item:$uri")
                            },
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
    onSearchClick: () -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onToggleFolder: (MediaFolder) -> Unit,
    items: List<MediaItem>?,
    minTileSize: Dp,
    selection: SelectionState,
    selectionActionPlacement: SelectionActionPlacement = SelectionActionPlacement.BOTTOM,
    onToggleSelect: (MediaItem) -> Unit,
    onSelectAll: (List<MediaItem>) -> Unit,
    onExitSelection: () -> Unit,
    onTilePositioned: (Uri, Rect) -> Unit,
    onTileDisposed: (Uri) -> Unit,
    sortOrder: MediaSort = MediaSort.DATE_NEWEST,
    onGlideItem: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onGlideFolder: (MediaFolder, Boolean) -> Unit = { _, _ -> },
    onGlideDirectory: (GalleryDirectory, Boolean) -> Unit = { _, _ -> },
    gridSize: GridSize = GridSize.MEDIUM,
    onSortOrderChange: (MediaSort) -> Unit = {},
    onGridSizeChange: (GridSize) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val density = LocalDensity.current
    val gridState = rememberLazyGridState()
    val glideItemState by rememberUpdatedState(onGlideItem)
    val glideFolderState by rememberUpdatedState(onGlideFolder)
    val glideDirectoryState by rememberUpdatedState(onGlideDirectory)

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
    // Window-space tile bounds, keyed like the LazyGrid keys (see MainGalleryGrid).
    val tileBounds = remember { mutableMapOf<String, Rect>() }
    val haptics = LocalHapticFeedback.current
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // See MainGalleryGrid: a glide that starts on an already selected tile
    // deselects for its whole duration; one that starts on an unselected tile
    // selects.
    val glideDeselectsAt: (Offset) -> Boolean = glideDeselectsAt@{ position ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideDeselectsAt false
        when {
            key.startsWith("directory:") -> childDirectories
                .firstOrNull { "directory:${it.folder.bucketId}" == key }
                ?.let { directory ->
                    selection.selectedDirectories.any {
                        it.folder.bucketId == directory.folder.bucketId
                    } || selection.selectedFolders.any {
                        it.bucketId == directory.folder.bucketId
                    }
                }
                ?: false
            key.startsWith("album:") -> displayItems
                .firstOrNull { "album:${it.uri}" == key }
                ?.let { item -> item in selection.selected }
                ?: false
            else -> false
        }
    }
    val glideAt: (Offset, Boolean) -> Unit = glideAt@{ position, deselect ->
        val key = hitTestTile(gridCoordinates, tileBounds, position) ?: return@glideAt
        when {
            key.startsWith("directory:") -> childDirectories
                .firstOrNull { "directory:${it.folder.bucketId}" == key }
                ?.let { glideDirectoryState(it, deselect) }
            key.startsWith("album:") -> displayItems
                .firstOrNull { "album:${it.uri}" == key }
                ?.let { glideItemState(it, deselect) }
        }
    }
    val glidePosition by rememberUpdatedState(glideAt)
    val glideDeselects by rememberUpdatedState(glideDeselectsAt)

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

            GalleryPullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
            ) {
                if (displayItems.isEmpty() && childDirectories.isEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_items),
                                    color = TextMuted,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = minTileSize),
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { gridCoordinates = it }
                        .pointerInput(Unit) {
                            // See MainGalleryGrid: one owner for long-press + glide,
                            // with the press-time tile deciding select vs deselect.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPress =
                                    awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val deselect = glideDeselects(longPress.position)
                                glidePosition(longPress.position, deselect)
                                var pointerId = longPress.id
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: event.changes.firstOrNull { it.pressed }
                                        ?: break
                                    pointerId = change.id
                                    if (!change.pressed) {
                                        // Consume the up event too: otherwise the
                                        // tile's tap detector fires on release and
                                        // immediately toggles the just-selected item
                                        // back off.
                                        change.consume()
                                        break
                                    }
                                    change.consume()
                                    glidePosition(change.position, deselect)
                                }
                            }
                        },
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 56.dp + 12.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        end = 12.dp,
                        bottom = if (selection.active) {
                            selectionActionPlacement.reservedEndSpace() + 12.dp +
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
                        val boundsKey = "directory:${child.folder.bucketId}"
                        if (animate) {
                            FlyInFolderTile(
                                folder = child.folder,
                                index = childDirectories.indexOf(child),
                                source = sourceRect,
                                phase = phase,
                                selection = selection,
                                isSelected = selected,
                                onToggleSelect = onToggleFolder,
                                onClick = { rect -> onDirectoryClick(child, rect) },
                                modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                                onBoundsChanged = { tileBounds[boundsKey] = it },
                            )
                        } else {
                            FolderTile(
                                folder = child.folder,
                                selection = selection,
                                isSelectedOverride = selected,
                                onToggleSelect = onToggleFolder,
                                onClick = { rect -> onDirectoryClick(child, rect) },
                                modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                                onBoundsChanged = { tileBounds[boundsKey] = it },
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
                                    // Deletes reflow the grid instead of jumping.
                                modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                                onTilePositioned = { uri, rect ->
                                    onTilePositioned(uri, rect)
                                    tileBounds["album:$uri"] = rect
                                },
                                onTileDisposed = { uri ->
                                    onTileDisposed(uri)
                                    tileBounds.remove("album:$uri")
                                },
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        } else {
                            ImageTile(
                                item = item,
                                selection = selection,
                                onToggleSelect = onToggleSelect,
                                    modifier = Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(180)),
                                onTilePositioned = { uri, rect ->
                                    onTilePositioned(uri, rect)
                                    tileBounds["album:$uri"] = rect
                                },
                                onTileDisposed = { uri ->
                                    onTileDisposed(uri)
                                    tileBounds.remove("album:$uri")
                                },
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        }
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
                AnimatedContent(
                    targetState = selection.active,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "albumAppBarMode",
                ) { isSelecting ->
                    if (isSelecting) {
                        SelectionHeader(
                            count = selection.itemCount,
                            onExitSelection = onExitSelection,
                            onSelectAll = { onSelectAll(displayItems) },
                        )
                    } else {
                        // Its own Row: AnimatedContent's content is not RowScope,
                        // so the title's weight needs a Row of its own here.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = TextPrimary,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.name,
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.item_count,
                                        folder.count,
                                        folder.count,
                                    ),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = onSearchClick) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.action_search),
                                    tint = TextPrimary,
                                    modifier = Modifier.width(24.dp),
                                )
                            }
                            FilterMenuButton(
                                sortOrder = sortOrder,
                                gridSize = gridSize,
                                onSortOrderChange = onSortOrderChange,
                                onGridSizeChange = onGridSizeChange,
                            )
                        }
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
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit = {},
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
        modifier = modifier
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
        onBoundsChanged = onBoundsChanged,
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
    onBoundsChanged: (Rect) -> Unit = {},
) {
    var stackRect by remember { mutableStateOf(Rect.Zero) }
    val isSelecting = selection.active
    val isSelected = isSelectedOverride ?: (folder in selection.selectedFolders ||
        selection.selectedDirectories.any { it.folder.bucketId == folder.bucketId })
    val a11yLabel = folder.name + ", " +
        pluralStringResource(R.plurals.item_count, folder.count, folder.count)

    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .onGloballyPositioned { onBoundsChanged(it.boundsInWindow()) }
            .semantics {
                contentDescription = a11yLabel
            }
            // Tap only: the long press that enters selection lives on the grid,
            // so a glide is never cancelled by a second long-press detector.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (isSelecting) onToggleSelect(folder) else onClick(stackRect)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            FolderStack(
                previews = folder.previews,
                modifier = Modifier.onGloballyPositioned { stackRect = it.boundsInWindow() },
            )
            // Selection badge (top-left), shown in selection mode; reflects state.
            androidx.compose.animation.AnimatedVisibility(
                visible = isSelecting,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .zIndex(2f),
                enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = 0.5f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.5f),
            ) {
                SelectionBadge(selected = isSelected)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isSelecting && !isSelected,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
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
    onTilePositioned: (Uri, Rect) -> Unit = { _, _ -> },
    onTileDisposed: (Uri) -> Unit = {},
) {
    // Layout coordinates are kept in a non-observable holder: writing window
    // bounds into state would recompose every tile on every scroll frame.
    val coordsRef = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val isSelecting = selection.active
    val isSelected = item in selection.selected
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
            // Tap only: the grid owns long-press + glide selection.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (isSelecting) {
                    onToggleSelect(item)
                } else {
                    onClick(coordsRef[0]?.boundsInWindow() ?: Rect.Zero)
                }
            },
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
        androidx.compose.animation.AnimatedVisibility(
            visible = isSelecting,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .zIndex(2f),
            enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = 0.5f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.5f),
        ) {
            SelectionBadge(selected = isSelected)
        }
        // Dimmed overlay when selection mode is active but this item isn't chosen.
        androidx.compose.animation.AnimatedVisibility(
            visible = isSelecting && !isSelected,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
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
            // Tap only: the grid owns long-press + glide selection.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (isSelecting) {
                    onToggleSelect(item)
                } else {
                    onClick(coordsRef[0]?.boundsInWindow() ?: targetRect)
                }
            },
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

/**
 * Key of the tile whose window bounds contain [position], where [position] is
 * local to the grid (the gesture's coordinate space). Measured bounds are used
 * rather than LazyGrid `layoutInfo` offsets so content padding, scroll and item
 * spacing can never shift the hit target.
 */
private fun hitTestTile(
    coordinates: LayoutCoordinates?,
    bounds: Map<String, Rect>,
    position: Offset,
): String? {
    val layout = coordinates?.takeIf { it.isAttached } ?: return null
    val point = layout.localToWindow(position)
    return bounds.entries.firstOrNull { it.value.contains(point) }?.key
}
