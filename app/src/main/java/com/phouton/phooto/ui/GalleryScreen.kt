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
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.phouton.phooto.data.GalleryContent
import com.phouton.phooto.data.MediaFolder
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.MediaRepository
import com.phouton.phooto.data.SettingsRepository
import com.phouton.phooto.data.mediaCollectionUri
import com.phouton.phooto.data.thumbnails.ExternalReloadSink
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Border
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
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TileShape = RoundedCornerShape(12.dp)
private val GridSpacing = 8.dp

// Transition timings.
private const val ForwardDurationMs = 320
private const val ReverseDurationMs = 260
private const val StaggerCap = 20
private const val MaxStagger = 0.6f

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
) {
    val context = LocalContext.current
    SettingsRepository.init(context)
    var content by remember { mutableStateOf<GalleryContent?>(null) }
    var openFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var openSourceRect by remember { mutableStateOf(Rect.Zero) }
    var closing by remember { mutableStateOf(false) }
    var viewerItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }
    var viewerSourceRect by remember { mutableStateOf(Rect.Zero) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    var albumReloadKey by remember { mutableStateOf(0) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastKey by remember { mutableStateOf(0) }
    var pendingDeleteItem by remember { mutableStateOf<MediaItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        content = MediaRepository.load(context)
    }

    val reload = { reloadKey += 1 }

    // Surface the reload callback to MainActivity so its MediaStore observer
    // (debounced inside SyncCoordinator) can trigger gallery refreshes.
    DisposableEffect(Unit) {
        ExternalReloadSink.current = reload
        onDispose {
            if (ExternalReloadSink.current === reload) {
                ExternalReloadSink.current = null
            }
        }
    }

    // Pre-warm the thumbnail cache for everything currently in the gallery.
    // Folder previews are URIs only (we don't keep the full MediaItem around
    // after buildFolders), so we warm them via their URI; loose items carry
    // enough metadata to use the full key derivation.
    LaunchedEffect(content) {
        val snapshot = content ?: return@LaunchedEffect
        snapshot.folders.forEach { folder ->
            folder.previews.forEach { uri -> ThumbCache.enqueueIfMissingUri(uri) }
        }
        snapshot.looseItems.forEach { item -> ThumbCache.enqueueIfMissing(item) }
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
    }

    val openViewer = { list: List<MediaItem>, index: Int, rect: Rect ->
        viewerItems = list
        viewerIndex = index
        viewerSourceRect = rect
    }

    BackHandler(
        enabled = openFolder != null || viewerItems != null || showSettings,
        onBack = {
            if (showSettings) {
                showSettings = false
            } else if (viewerItems != null) {
                closeViewer()
            } else {
                closeFolder()
            }
        },
    )

    val removeDeleted = { deletedUri: android.net.Uri ->
        val current = content
        if (current != null) {
            content = current.copy(
                looseItems = current.looseItems.filterNot { it.uri == deletedUri },
            )
        }
        viewerItems = viewerItems?.filterNot { it.uri == deletedUri }
        albumReloadKey += 1
        closeViewer()
        // Refresh the root grid so folder counts/previews update after a delete.
        reload()
    }

    // System delete consent dialog (API 30+). On success, remove from grid + cache.
    val scope = rememberCoroutineScope()
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val viewing = viewerItems?.getOrNull(viewerIndex)
        if (result.resultCode == android.app.Activity.RESULT_OK && viewing != null) {
            scope.launch {
                MediaRepository.delete(context, viewing)
                removeDeleted(viewing.uri)
            }
        }
    }

    val requestDelete = { item: MediaItem ->
        scope.launch {
            // SAF items (from granted tree browsing) delete directly through the
            // document provider; their id is 0 and not a MediaStore id.
            val isSafItem = android.provider.DocumentsContract.isDocumentUri(context, item.uri)
            // Try direct delete first (works for app-owned files / API 29+).
            val direct = MediaRepository.delete(context, item)
            if (direct) {
                removeDeleted(item.uri)
            } else if (!isSafItem) {
                // Fallback: request user consent for other apps' files via the
                // system intent sender (RecoverableSecurityException path).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val deleteUri = mediaCollectionUri(item.isVideo, item.id)
                    try {
                        val pendingIntent = MediaStore.createDeleteRequest(
                            context.contentResolver,
                            listOf(deleteUri),
                        )
                        val request =
                            androidx.activity.result.IntentSenderRequest.Builder(pendingIntent).build()
                        deleteLauncher.launch(request)
                    } catch (e: Exception) {
                        // Last resort: nothing more we can do.
                    }
                } else {
                    // API 29: direct delete already attempted; nothing more.
                }
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

        val currentFolder = openFolder
        if (currentFolder != null) {
            AlbumGrid(
                folder = currentFolder,
                sourceRect = openSourceRect,
                reverse = closing,
                onBack = closeFolder,
                onItemClick = { list, index, rect -> openViewer(list, index, rect) },
                reloadKey = albumReloadKey,
            )
        }

        val viewing = viewerItems
        if (viewing != null && viewing.isNotEmpty()) {
            FullImageViewer(
                items = viewing,
                initialIndex = viewerIndex.coerceIn(0, viewing.lastIndex),
                sourceRect = viewerSourceRect,
                onDismiss = closeViewer,
                onPageChanged = { viewerIndex = it },
                onDelete = { pendingDeleteItem = it },
                onShare = { shareItem(it) },
                onInfo = { infoItem = it },
                onCrop = { showComingSoonToast() },
            )
        }

        val deleteTarget = pendingDeleteItem
        if (deleteTarget != null) {
            DeleteConfirmDialog(
                onConfirm = {
                    pendingDeleteItem = null
                    requestDelete(deleteTarget)
                },
                onDismiss = { pendingDeleteItem = null },
            )
        }

        val infoTarget = infoItem
        if (infoTarget != null) {
            InfoSheet(item = infoTarget, onClose = { infoItem = null })
        }

        toastMessage?.let { message ->
            Toast(message = message, key = toastKey, onDismiss = { toastMessage = null })
        }

        // Thumbnail sync bar overlays the top of the gallery so it stays visible
        // above the album grid, full-image viewer, and settings screen alike.
        ThumbnailSyncBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MainGalleryGrid(
    content: GalleryContent?,
    onFolderClick: (MediaFolder, Rect) -> Unit,
    onItemClick: (List<MediaItem>, Int, Rect) -> Unit,
    onSettingsClick: () -> Unit,
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
            Text(
                text = "Albums",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = TextPrimary,
                    modifier = Modifier.width(24.dp),
                )
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
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridSpacing),
                ) {
                    items(current.folders, key = { "folder:${it.bucketId}" }) { folder ->
                        FolderTile(folder = folder, onClick = { rect -> onFolderClick(folder, rect) })
                    }
                    items(current.looseItems, key = { "item:${it.uri}" }) { item ->
                        ImageTile(item, onClick = { rect ->
                            onItemClick(current.looseItems, current.looseItems.indexOf(item), rect)
                        })
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
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 56.dp + 12.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        end = 12.dp,
                        bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
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
                                onClick = { rect -> onItemClick(displayItems, index, rect) },
                            )
                        } else {
                            ImageTile(item, onClick = { rect -> onItemClick(displayItems, index, rect) })
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

@Composable
private fun FolderTile(folder: MediaFolder, onClick: (Rect) -> Unit) {
    var stackRect by remember { mutableStateOf(Rect.Zero) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(stackRect) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FolderStack(
            previews = folder.previews,
            modifier = Modifier.onGloballyPositioned { stackRect = it.boundsInWindow() },
        )
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
private fun ImageTile(item: MediaItem, onClick: (Rect) -> Unit) {
    var tileRect by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(TileShape)
            .background(SurfaceColor)
            .onGloballyPositioned { tileRect = it.boundsInWindow() }
            .clickable { onClick(tileRect) },
    ) {
        Thumb(
            item = item,
            modifier = Modifier.fillMaxSize(),
        )
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
        }
    }
}

/**
 * Loads a thumbnail via Coil, showing an animated shimmer skeleton until the
 * image is ready so tiles never appear as random gaps.
 *
 * The `uri`-only overload is used for folder-cover previews where we don't
 * have a full `MediaItem`. The `item` overload uses the thumbnail cache
 * (`ThumbCache`) so scroll is backed by Coil's persistent disk + memory cache.
 */
@Composable
private fun Thumb(uri: Uri, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    ) {
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            SkeletonShimmer()
        }
    }
}

/**
 * Item-aware variant: routes through `ThumbCache` so each tile shares Coil's
 * disk + memory cache. [animated] controls whether GIF / animated WebP / HEIF
 * tiles play inline (true) or stay on the static first frame (false, default —
 * cheaper for grid scrolling).
 */
@Composable
private fun Thumb(
    item: MediaItem,
    animated: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val key = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
        ThumbCache.keyFor(item)
    }
    val model = remember(item.uri, animated) {
        ThumbCache.buildRequest(ctx = context, item = item, key = key, animated = animated)
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    ) {
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            SkeletonShimmer()
        }
    }
}

/** An animated shimmer block used as a loading placeholder. */
@Composable
private fun SkeletonShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
        ),
        label = "shimmerX",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            SurfaceColor.copy(alpha = 0.6f),
            SurfaceColor.copy(alpha = 1f),
            SurfaceColor.copy(alpha = 0.6f),
        ),
        start = androidx.compose.ui.geometry.Offset(x * 1000f, 0f),
        end = androidx.compose.ui.geometry.Offset(x * 1000f + 400f, 0f),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(shimmerBrush),
    )
}

@Composable
private fun FlyInTile(
    item: MediaItem,
    index: Int,
    source: Rect,
    phase: Animatable<Float, *>,
    onClick: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    var targetRect by remember { mutableStateOf(Rect.Zero) }
    val captured = targetRect != Rect.Zero

    val (offX, offY) = collapsedOffsetDp(index)
    val offXPx = with(density) { offX.dp.toPx() }
    val offYPx = with(density) { offY.dp.toPx() }

    // Everything below reads the animated phase and is computed in the draw phase
    // (inside graphicsLayer) so per-frame updates never recompose the composable.
    val s = ((index.toFloat() / StaggerCap).coerceIn(0f, 1f)) * MaxStagger

    // zIndex: higher = on top. The first item (index 0) stays on top throughout,
    // matching the folder stack where the newest preview is the front cover.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .zIndex(100f - index)
            .onGloballyPositioned { targetRect = it.boundsInWindow() }
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
            .clickable { onClick(targetRect) },
    ) {
        Thumb(
            item = item,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Back cards darken toward their resting state, matching FolderStack's scrim.
                    val p = ((phase.value - s) / (1f - s)).coerceIn(0f, 1f)
                    val scrim = when (index) {
                        1 -> (1f - p) * 0.15f
                        2 -> (1f - p) * 0.35f
                        else -> 0f
                    }
                    alpha = 1f - scrim
                },
        )
    }
}

@Composable
private fun InfoSheet(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<com.phouton.phooto.data.MediaInfo?>(null) }

    LaunchedEffect(item) {
        info = MediaRepository.loadInfo(context, item)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.6f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceColor,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Info", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val d = info
                InfoRow("Name", d?.name ?: "…")
                InfoRow("Size", d?.let { formatSize(it.sizeBytes) } ?: "…")
                InfoRow("Date added", d?.let { formatDate(it.dateAdded) } ?: "…")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontSize = 14.sp)
    }
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}

private fun formatDate(epochSeconds: Long): String {
    val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return date.format(java.util.Date(epochSeconds * 1000))
}

/** Shadcn-style toast: a small rounded pill at the bottom center that auto-dismisses. */
@Composable
private fun Toast(message: String, key: Int, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        visible = true
        delay(2000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(50),
                color = TextPrimary,
                shadowElevation = 8.dp,
            ) {
                Text(
                    text = message,
                    color = AmoledBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private val DeleteRed = Color(0xFFEF4444)

/** Shadcn-style delete confirmation modal with a red-accented confirm button. */
@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            color = SurfaceColor,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Delete this photo?",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This action can't be undone.",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel (outlined).
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
                    // Confirm (red accent).
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onConfirm, indication = null, interactionSource = null),
                        shape = RoundedCornerShape(10.dp),
                        color = DeleteRed,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Delete",
                                color = Color.White,
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
