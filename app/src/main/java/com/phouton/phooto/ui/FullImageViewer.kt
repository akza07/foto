package com.phouton.phooto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Surface as ThemeSurface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SheetRadius = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val DeleteRed = Color(0xFFEF4444)

/**
 * Watches pointer count and reports when the gesture becomes multi-touch
 * ([onMultiTouchStart], as soon as a second finger lands) and when every pointer
 * has lifted ([onMultiTouchEnd]). While multi-touch is active, single-finger
 * gestures (vertical swipe, pager slide, taps) must yield to pinch-zoom.
 */
internal suspend fun PointerInputScope.detectMultiTouch(
    onMultiTouchStart: () -> Unit,
    onMultiTouchEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var multi = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (!multi) {
                if (pressed >= 2) {
                    multi = true
                    onMultiTouchStart()
                } else if (pressed == 0) {
                    break
                }
            } else if (pressed == 0) {
                onMultiTouchEnd()
                break
            }
        }
    }
}

/**
 * Tracks a single-finger vertical drag and fires [onSwipeUp] / [onSwipeDown] once
 * it travels past [threshold] px in either direction. The drag is abandoned the
 * instant a second finger lands, so a two-finger pinch can never open the drawer
 * or dismiss the viewer.
 *
 * Horizontal travel past the touch slop is treated as a horizontal swipe (the
 * pager's domain) and the gesture is abandoned so the HorizontalPager below
 * gets a clean, unconsumed x-delta. Only the y-component is consumed so that
 * vertical motion still wins over sibling tap detectors.
 */
internal suspend fun PointerInputScope.detectVerticalSwipe(
    threshold: Float,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var acc = 0f
        var horizontalTravel = 0f
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) return@awaitEachGesture
            val drag = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            // position - previousPosition == movement since the last event.
            // (The positionChange() extension is shadowed by an internal member with
            // the same name, so use the raw offsets instead.)
            val dx = drag.position.x - drag.previousPosition.x
            val dy = drag.position.y - drag.previousPosition.y
            acc += dy
            horizontalTravel += dx
            if (acc <= -threshold) {
                onSwipeUp()
                return@awaitEachGesture
            } else if (acc >= threshold) {
                onSwipeDown()
                return@awaitEachGesture
            }
            // Once the gesture is clearly horizontal, hand off to the pager so
            // its own touch slop / fling detection can take over.
            if (kotlin.math.abs(horizontalTravel) > touchSlop) {
                return@awaitEachGesture
            }
            // Consume only when we have vertical movement, so the pager still
            // sees the unconsumed x-delta and can scroll the page.
            if (dy != 0f) drag.consume()
        }
    }
}

/**
 * Full-image viewer as a horizontal pager.
 * - Swipe left/right: next/previous item (pager slide). On video pages,
 *   swiping is locked while the video plays — pause first to navigate.
 * - Images: double-tap zoom, pinch zoom/pan. While two fingers are down,
 *   single-finger gestures yield to pinch-zoom.
 * - Long-press / swipe up (images + videos): action sheet from the bottom.
 * - Swipe down (at 1x / settled video): dismisses.
 * - Counter pill (top-left): current/total.
 * - Back button (top-left): dismisses.
 */
@Composable
fun FullImageViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    sourceRect: Rect,
    onDismiss: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onInfo: (MediaItem) -> Unit,
    onCrop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )

    // Entry/exit progress: 0 = scaled to source tile, 1 = full screen.
    val progress = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }

    // Measured size of the viewer. The entry zoom can't be computed until we know
    // the container, so the animation is started once (and the pager only composed
    // after) the first layout pass reports a non-zero size.
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(containerSize) {
        if (containerSize != IntSize.Zero && !leaving) {
            progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        }
    }

    var drawerVisible by remember { mutableStateOf(false) }
    var currentPageZoomed by remember { mutableStateOf(false) }
    var videoControlsVisible by remember { mutableStateOf(true) }
    var imageControlsVisible by remember { mutableStateOf(true) }
    val imageHideJob = remember { mutableStateOf<Job?>(null) }

    fun showImageControls() {
        imageControlsVisible = true
        imageHideJob.value?.cancel()
        imageHideJob.value = scope.launch {
            delay(3000L)
            if (!drawerVisible) {
                imageControlsVisible = false
            }
        }
    }

    fun hideImageControls() {
        imageHideJob.value?.cancel()
        imageControlsVisible = false
    }

    fun toggleImageControls() {
        if (imageControlsVisible) {
            hideImageControls()
        } else {
            showImageControls()
        }
    }

    // True while the current video page is actively playing. While playing,
    // pager swiping is locked so a swipe can never skip to the next item;
    // pausing re-enables it (user decision: swipe navigates only when paused).
    var videoPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.settledPage) {
        onPageChanged(pagerState.settledPage)
    }

    val close = {
        if (!leaving) {
            leaving = true
            scope.launch {
                progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                onDismiss()
            }
        }
    }

    // System back (button / gesture) must run the same animated scale-out as the
    // on-screen back arrow. Composed after GalleryScreen's BackHandler, so this
    // one is the most recent and wins while the viewer is open.
    BackHandler { close() }

    val swipeThreshold = with(density) { 56.dp.toPx() }
    val currentIndex = pagerState.settledPage
    val currentItem = items.getOrNull(currentIndex)

    val currentPageIsVideo = currentItem?.isVideo == true

    // Video pages never hold image zoom; image pages never hold video state.
    LaunchedEffect(currentPageIsVideo, currentIndex) {
        if (currentPageIsVideo) {
            videoControlsVisible = true
            currentPageZoomed = false
        } else {
            videoPlaying = false
            showImageControls()
        }
    }

    // Stay on a valid page when the list shrinks (e.g. delete keeps the viewer
    // open on the next file instead of exiting to the grid).
    LaunchedEffect(items.size) {
        val target = pagerState.currentPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }

    // Preload adjacent pages so page swipes never flash black while loading.
    // Images go through Coil; VIDEO posters go through VideoPosterMemory (the
    // viewer composes the incoming page at swipe start and needs its poster
    // synchronously), so video neighbors are warmed into that cache instead.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(currentIndex) {
        listOf(currentIndex - 1, currentIndex + 1)
            .filter { it in items.indices }
            .forEach { i ->
                val neighbor = items[i]
                if (neighbor.isVideo) {
                    preloadVideoPoster(ctx, neighbor)
                } else {
                    val req = ImageRequest.Builder(ctx)
                        .data(neighbor.uri)
                        .build()
                    coil3.SingletonImageLoader.get(ctx).enqueue(req)
                }
            }
    }

    // True while two or more fingers are down. While true, single-finger gestures
    // (swipe up/down, pager slide, taps) are suppressed and only pinch-zoom applies.
    var multiTouchActive by remember { mutableStateOf(false) }

    // Entry/exit progress sampled once per frame: 0 = scaled to the source tile,
    // 1 = settled full screen. Also drives the backdrop alpha so the grid is
    // revealed in lockstep with the scale-out rather than popping in after it.
    val p = progress.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            // Single fading backdrop for the whole viewer. Pages are transparent,
            // so this is the only black layer. It fades with the shared progress:
            // 0 at the tile (grid visible through the growing image) and 1 when
            // settled. Entry and exit are therefore the same continuous motion.
            .background(AmoledBlack.copy(alpha = p))
            // Multi-touch watch: as soon as a second finger lands, every single-finger
            // gesture yields to the pinch-zoom on the image below.
            .pointerInput(Unit) {
                detectMultiTouch(
                    onMultiTouchStart = { multiTouchActive = true },
                    onMultiTouchEnd = { multiTouchActive = false },
                )
            }
            // Vertical drags (at 1x): swipe up -> drawer, swipe down -> dismiss.
            // Abandoned the instant a second finger joins, so a pinch can't open the
            // drawer. Re-armed (via key change) once the multi-touch gesture ends.
            // Applies to images and videos alike: the video pill only covers the
            // bottom, so a swipe starting on the video surface reaches this
            // detector cleanly.
            .pointerInput(currentPageZoomed, drawerVisible, leaving, multiTouchActive) {
                if (!currentPageZoomed && !drawerVisible && !leaving && !multiTouchActive) {
                    detectVerticalSwipe(
                        threshold = swipeThreshold,
                        onSwipeUp = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            drawerVisible = true
                        },
                        onSwipeDown = { close() },
                    )
                }
            },
    ) {
        // Scale-in from the tapped tile.
        val containerW = containerSize.width.toFloat().coerceAtLeast(1f)
        val containerH = containerSize.height.toFloat().coerceAtLeast(1f)
        val fromScaleX = sourceRect.width.toFloat() / containerW
        val fromScaleY = sourceRect.height.toFloat() / containerH

        // The tile has 12dp rounded corners; as the pager shrinks back to it, grow
        // the clip radius so the transition lands flush on the rounded thumbnail.
        val tileCorner = with(density) { 12.dp.toPx() }
        val cornerRadius = tileCorner * (1f - p)
        val pagerShape = RoundedCornerShape(cornerRadius)

        // Only compose the pager once the container has been measured: before that
        // there is no valid target scale, and showing it would flash a mis-scaled
        // frame. The pre-measure frame renders just the grid through the
        // transparent backdrop, matching the tile exactly.
        if (containerSize != IntSize.Zero) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // No backdrop of its own: the host's fading black layer is the
                    // single source of black, so page gaps and letterboxes stay
                    // covered while settled and fade away cleanly on exit.
                    .clip(pagerShape)
                    .graphicsLayer {
                        scaleX = lerp(fromScaleX, 1f, p)
                        scaleY = lerp(fromScaleY, 1f, p)
                        translationX = lerp(sourceRect.center.x - containerW / 2f, 0f, p)
                        translationY = lerp(sourceRect.center.y - containerH / 2f, 0f, p)
                        alpha = 1f
                    },
                // Video pages lock pager swiping while playing; paused (or image
                // pages) allow it. Zoom and multi-touch keep their existing gates.
                userScrollEnabled = (!currentPageIsVideo || !videoPlaying) &&
                    !currentPageZoomed &&
                    !multiTouchActive,
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    // Video page: real playback via ExoPlayer, no zoom gestures.
                    // Only the settled page is "active" (owns a player/decoder);
                    // adjacent video pages show their cached poster.
                    VideoPlayerPage(
                        item = item,
                        active = page == currentIndex,
                        onControlsVisibilityChanged = { visible ->
                            if (page == currentIndex) videoControlsVisible = visible
                        },
                        onPlayingChanged = { playing ->
                            if (page == currentIndex) videoPlaying = playing
                        },
                        onLongPress = { drawerVisible = true },
                        onDelete = { onDelete(item) },
                        // Live entry-zoom progress (0 = tile, 1 = fullscreen) so the
                        // video page can morph the tile's cropped poster into the
                        // player's fitted poster without a pop/flicker. Pinned to 1f
                        // while leaving so the exit geometry matches the image path.
                        entryProgress = { if (leaving) 1f else progress.value },
                        sourceRect = sourceRect,
                        containerSize = containerSize,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ZoomableImage(
                        item = item,
                        sourceRect = sourceRect,
                        onZoomedChange = { zoomed -> currentPageZoomed = zoomed },
                        onTap = { toggleImageControls() },
                        onLongPress = { drawerVisible = true },
                        multiTouchActive = multiTouchActive,
                        // Live entry-zoom progress so the page can bridge the grid
                        // tile's cropped thumbnail into the full-resolution image
                        // instead of popping in when the original finishes decoding.
                        // Pinned to 1f while leaving so the bridge stays hidden on
                        // the exit (the settled image just shrinks, as before).
                        entryProgress = { if (leaving) 1f else progress.value },
                    )
                }
            }
        }

        val topChromeAlpha by animateFloatAsState(
            targetValue = if (currentPageIsVideo) {
                if (videoControlsVisible) 1f else 0f
            } else {
                if (imageControlsVisible) 1f else 0f
            },
            animationSpec = tween(200),
            label = "top chrome alpha",
        )

        // Keep the top chrome readable over bright backgrounds, and fade it
        // with the controls / HUD. Also scaled by the entry/exit progress so the
        // gradient slides in with the image rather than appearing instantly.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(112.dp)
                .graphicsLayer { alpha = p * topChromeAlpha },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to AmoledBlack.copy(alpha = 0.62f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }

        // Top bar: back button + counter pill (top-left, shadcn style).
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .graphicsLayer {
                    // Fade/slide in with the entry zoom and out with the exit zoom,
                    // so the chrome never pops over a half-grown image or lingers
                    // over the grid.
                    alpha = p * topChromeAlpha
                    translationY = -12.dp.toPx() * (1f - p)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        enabled = if (currentPageIsVideo) videoControlsVisible else imageControlsVisible,
                        onClick = close,
                    ),
                shape = CircleShape,
                color = ThemeSurface,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = ThemeSurface,
                shadowElevation = 0.dp,
            ) {
                Text(
                    text = "${currentIndex + 1}/${items.size}",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Bottom-right: Delete button for image viewer within thumb reach (fades on tap/auto-hide).
        if (!currentPageIsVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp)
                    .graphicsLayer {
                        alpha = p * topChromeAlpha
                        translationY = 12.dp.toPx() * (1f - p)
                    },
            ) {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            enabled = imageControlsVisible,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (currentItem != null) {
                                onDelete(currentItem)
                            }
                        },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.68f),
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = DeleteRed,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // Sliding bottom action drawer.
        AnimatedVisibility(
            visible = drawerVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            if (currentItem != null) {
                ActionSheet(
                    item = currentItem,
                    onCloseDrawer = { drawerVisible = false },
                    onDelete = {
                        drawerVisible = false
                        onDelete(currentItem)
                    },
                    onShare = {
                        drawerVisible = false
                        onShare(currentItem)
                    },
                    onInfo = {
                        drawerVisible = false
                        onInfo(currentItem)
                    },
                    onCrop = {
                        drawerVisible = false
                        onCrop()
                    },
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    item: MediaItem,
    sourceRect: Rect,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    multiTouchActive: Boolean,
    entryProgress: () -> Float,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) { onZoomedChange(scale > 1.01f) }

    val transformableState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        // centroid is reported in this modifier's local coords (origin at the
        // top-left of the AsyncImage), but graphicsLayer applies scaleX/scaleY
        // around the layer's center by default. Compensate relative to the
        // center so the image point under the pinch midpoint stays anchored.
        val center = Offset(
            containerSize.width / 2f,
            containerSize.height / 2f,
        )
        if (newScale != scale) {
            offset = offset + (centroid - center - offset) * (1f - zoomChange)
        }
        scale = newScale
        offset = offset + panChange
        val halfW = containerSize.width * (scale - 1f) / 2f
        val halfH = containerSize.height * (scale - 1f) / 2f
        offset = Offset(
            offset.x.coerceIn(-halfW, halfW),
            offset.y.coerceIn(-halfH, halfH),
        )
    }

    // Load the ORIGINAL file so pinch-zoom reveals full detail.
    // Grid tiles use a 320px ThumbCache entry; the viewer must not.
    val model = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
        com.phouton.phooto.data.thumbnails.ThumbCache.buildFullRequest(ctx, item)
    }

    // Entry bridge: the exact grid-tile pixels, read synchronously from our own
    // thumbnail cache. The tile shows this image CROPPED to a square while the
    // settled viewer FITS it (letterboxed), and the pager also squishes the page
    // non-uniformly into the tile rect. Those two poses differ, so a plain
    // crossfade between them flickers. Instead both layers are drawn with the
    // same transform and a shared "entry correction" morphs the fitted content
    // into the tile's square crop (and back to neutral), so the crossfade is
    // pixel-identical and invisible.
    val thumbKey = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
        com.phouton.phooto.data.thumbnails.ThumbCache.keyFor(item)
    }
    val thumb = remember(thumbKey) {
        com.phouton.phooto.data.thumbnails.ThumbCache.loadThumb(thumbKey)
    }
    // Intrinsic size of the image, used to derive the crop/fit scales. Prefer the
    // decoded original; fall back to the cached thumbnail (same aspect) so the
    // morph is available before the full-resolution file finishes decoding.
    var fullImageSize by remember(item.uri) { mutableStateOf<IntSize?>(null) }
    var fullLoaded by remember(item.uri) { mutableStateOf(false) }
    val loadFade = remember(item.uri) { Animatable(1f) }
    LaunchedEffect(fullLoaded) {
        if (fullLoaded) loadFade.animateTo(0f, tween(220)) else loadFade.snapTo(1f)
    }

    // Geometry of the entry morph. The pager maps this page (W x H) onto the
    // source tile (S x S) at progress 0 via scaleX = S/W, scaleY = S/H. The tile
    // displays the image with Crop (uniform scale c = max(S/Iw, S/Ih)); the
    // settled viewer displays it with Fit (uniform scale f = min(W/Iw, H/Ih)).
    // A centered correction layer of (lerp(c,f,p)/(pagerScaleX(p)*f),
    // lerp(c,f,p)/(pagerScaleY(p)*f)) makes the composed result equal the tile
    // crop at p=0 and neutral at p=1, so the image grows continuously from the
    // tile's crop into the fitted full view with no dimension/crop mismatch.
    val intrinsic = fullImageSize ?: thumb?.let { IntSize(it.width, it.height) }
    val iw = intrinsic?.width?.toFloat()
    val ih = intrinsic?.height?.toFloat()
    val correct = containerSize != IntSize.Zero &&
        iw != null && ih != null && iw > 0f && ih > 0f && sourceRect.width > 0f
    val ep = entryProgress()
    val correctionX: Float
    val correctionY: Float
    if (correct) {
        val w = containerSize.width.toFloat()
        val h = containerSize.height.toFloat()
        val s = sourceRect.width.toFloat()
        val fit = minOf(w / iw!!, h / ih!!)
        val crop = maxOf(s / iw, s / ih)
        val target = lerp(crop, fit, ep)
        val pagerX = lerp(s / w, 1f, ep)
        val pagerY = lerp(s / h, 1f, ep)
        correctionX = if (fit > 0f && pagerX > 0f) target / (pagerX * fit) else 1f
        correctionY = if (fit > 0f && pagerY > 0f) target / (pagerY * fit) else 1f
    } else {
        correctionX = 1f
        correctionY = 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Transparent page: the viewer host owns the (fading) backdrop so the
            // exit scale-out reveals the grid instead of a page-sized black slab.
            .onSizeChanged { containerSize = it },
    ) {
        // The fitted original and the cropped entry bridge share one transform so
        // pinch-zoom/pan and the entry bridge stay perfectly registered.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Re-keyed on multiTouchActive so taps / long-press are dead while a
                // two-finger pinch is in effect (only pinch-zoom should act then).
                .pointerInput(multiTouchActive) {
                    if (multiTouchActive) return@pointerInput
                    detectTapGestures(
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                        onDoubleTap = { tap ->
                            scope.launch {
                                val w = containerSize.width.toFloat().coerceAtLeast(1f)
                                val h = containerSize.height.toFloat().coerceAtLeast(1f)
                                if (scale > 1f) {
                                    // Animated reset.
                                    val start = scale
                                    val target = 1f
                                    val startOffset = offset
                                    val anim = Animatable(start)
                                    anim.animateTo(
                                        target,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ) {
                                        scale = value
                                        val t = ((value - start) / (target - start)).coerceIn(0f, 1f)
                                        offset = Offset(
                                            lerp(startOffset.x, 0f, t),
                                            lerp(startOffset.y, 0f, t),
                                        )
                                    }
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // Animated zoom-in anchored at the tap point.
                                    val target = 3f
                                    val start = scale
                                    val targetOffset = Offset(
                                        (tap.x - w / 2f) * (1f - target),
                                        (tap.y - h / 2f) * (1f - target),
                                    )
                                    val startOffset = offset
                                    val anim = Animatable(start)
                                    anim.animateTo(
                                        target,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ) {
                                        scale = value
                                        val t = ((value - start) / (target - start)).coerceIn(0f, 1f)
                                        offset = Offset(
                                            lerp(startOffset.x, targetOffset.x, t),
                                            lerp(startOffset.y, targetOffset.y, t),
                                        )
                                    }
                                    scale = target
                                    offset = targetOffset
                                }
                            }
                        },
                        onTap = {
                            onTap()
                        },
                    )
                }
                .transformable(state = transformableState, canPan = { scale != 1f })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            // Shared entry correction: morphs the fitted content into the tile's
            // square crop and back to neutral. Both the original and the bridge
            // bitmap use ContentScale.Fit inside it, so they are geometrically
            // identical at every frame and their crossfade cannot flicker.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = correctionX
                        scaleY = correctionY
                    },
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        fullImageSize = IntSize(state.result.image.width, state.result.image.height)
                        fullLoaded = true
                    },
                    onError = { fullLoaded = true },
                    modifier = Modifier.fillMaxSize(),
                )

                // Tile-res bridge above the original, fading out once the entry
                // zoom finishes AND the original has decoded (whichever is later),
                // so there is never a black frame during the transition.
                if (thumb != null) {
                    val entryAlpha = (1f - ep).coerceIn(0f, 1f)
                    val bridgeAlpha = maxOf(entryAlpha, loadFade.value)
                    if (bridgeAlpha > 0.001f) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            // Fit (not Crop): the shared correction layer performs
                            // the crop, so bridge and original share one geometry.
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = bridgeAlpha },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSheet(
    item: MediaItem,
    onCloseDrawer: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onCrop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack.copy(alpha = 0.55f))
            .clickable(onClick = onCloseDrawer, indication = null, interactionSource = null),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = SheetRadius,
            color = ThemeSurface,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle.
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextMuted.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionItem(Icons.Outlined.Share, "Share", TextPrimary, onShare)
                    ActionItem(Icons.Outlined.Info, "Info", TextPrimary, onInfo)
                    ActionItem(Icons.Outlined.Delete, "Delete", DeleteRed, onDelete)
                    // Videos have no crop action.
                    if (!item.isVideo) {
                        ActionItem(Icons.Outlined.Crop, "Crop", TextPrimary, onCrop)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AmoledBlack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
