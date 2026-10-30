package fool.akza.foto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SheetRadius = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** A gesture must be this much more vertical than horizontal to count as vertical. */
private const val VerticalGestureRatio = 2.0f

/** Slide duration when a delete moves the viewer to the adjacent item. */
private const val DeleteSlideDurationMs = 280

// Pan momentum glide after a flick. Lower friction = a longer coast; the
// velocity threshold decides when a drag was slow enough to not fling at all.
private const val PAN_FLING_FRICTION = 0.6f
private const val PAN_FLING_VELOCITY_THRESHOLD = 0.5f
private const val PAN_FLING_MIN_VELOCITY = 250f

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
 * Once past touch slop, only a gesture at least [VerticalGestureRatio] times
 * more vertical than horizontal counts as vertical. Everything else is left to
 * the pager. Vertical consumes both axes and drains the rest of the gesture, so
 * an angled swipe can never slide the page and open the drawer at the same time.
 */
internal suspend fun PointerInputScope.detectVerticalSwipe(
    threshold: Float,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var totalDx = 0f
        var totalDy = 0f
        var vertical = false
        var axisDecided = false
        while (true) {
            // Initial pass: once this is clearly a vertical drag, consume both
            // axes before the pager sees them. That keeps an angled swipe from
            // sliding the page and opening the action drawer at the same time.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) return@awaitEachGesture
            val drag = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            // position - previousPosition == movement since the last event.
            // (The positionChange() extension is shadowed by an internal member with
            // the same name, so use the raw offsets instead.)
            val dx = drag.position.x - drag.previousPosition.x
            val dy = drag.position.y - drag.previousPosition.y
            totalDx += dx
            totalDy += dy

            if (!axisDecided && kotlin.math.hypot(totalDx, totalDy) > touchSlop) {
                // Only a steep gesture counts as vertical. Anything diagonal is
                // handed to the pager, so bottom-right → top-left swipes page
                // instead of opening the action drawer.
                if (kotlin.math.abs(totalDy) < kotlin.math.abs(totalDx) * VerticalGestureRatio) {
                    return@awaitEachGesture
                }
                axisDecided = true
                vertical = true
            }

            if (vertical) {
                if (totalDy <= -threshold) {
                    onSwipeUp()
                    // The drawer opens mid-gesture. Keep consuming the rest of
                    // this pointer stream so the pager cannot page with the
                    // leftover diagonal movement.
                    drag.consume()
                    drainUntilUp(down.id)
                    return@awaitEachGesture
                } else if (totalDy >= threshold) {
                    onSwipeDown()
                    drag.consume()
                    drainUntilUp(down.id)
                    return@awaitEachGesture
                }
                drag.consume()
            }
        }
    }
}

/** Consume every remaining change for [pointerId] until it lifts. */
private suspend fun AwaitPointerEventScope.drainUntilUp(pointerId: androidx.compose.ui.input.pointer.PointerId) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        change.consume()
        if (!change.pressed) break
    }
}

/**
 * Full-image viewer as a horizontal pager.
 * - Swipe left/right: next/previous item (pager slide). On video pages,
 *   swiping is locked while the video plays — pause first to navigate.
 * - Images: double-tap zoom, pinch zoom/pan. While two fingers are down,
 *   single-finger gestures yield to pinch-zoom.
 * - Long-press (images + videos): radial action wheel at the finger;
 *   videos offer Delete/Info/Share (no Crop). Swipe up: bottom action sheet.
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
    onCrop: (MediaItem) -> Unit,
    loopVideos: Boolean = false,
    /** View/play only: no action wheel, drawer, delete, share, info or crop. */
    previewOnly: Boolean = false,
    pendingRemoval: MediaItem? = null,
    /** A file delete has been accepted but has not yet completed. */
    deleteInProgress: Boolean = false,
    onRemovalAnimationEnd: () -> Unit = {},
    /** URI to animate the pager back to after an Undo restore. */
    restoreUri: android.net.Uri? = null,
    onRestored: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )

    // One ExoPlayer + preload manager for the whole viewer session. Built only
    // when the list actually contains a video; image-only viewers stay free of
    // a decoder and its background thread. The controller preloads videos near
    // the settled page so the first frame is warm before a swipe finishes.
    val playback = remember {
        if (items.any { it.isVideo }) {
            VideoPlaybackController(context.applicationContext, loopVideos)
        } else {
            null
        }
    }
    DisposableEffect(playback) {
        onDispose { playback?.release() }
    }
    // Pause on background; no auto-resume (gallery convention).
    LifecycleStartEffect(playback?.player) {
        onStopOrDispose { playback?.player?.pause() }
    }
    LaunchedEffect(playback, items) {
        playback?.updateItems(items)
    }
    LaunchedEffect(playback, pagerState.settledPage, items) {
        playback?.onSettled(pagerState.settledPage)
    }
    // Keep the player in sync if the preference changes while the viewer is open.
    LaunchedEffect(playback, loopVideos) {
        playback?.setLoop(loopVideos)
    }

    // Entry/exit progress: 0 = scaled to source tile, 1 = full screen.
    val progress = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var exitSourceRect by remember { mutableStateOf<Rect?>(null) }
    val transitionRect = exitSourceRect ?: sourceRect
    // True while a deferred delete is sliding the pager to the neighbour. Gates
    // user input so a manual swipe can't fight the programmatic scroll.
    var removing by remember { mutableStateOf(false) }

    // Measured size of the viewer. The entry zoom can't be computed until we know
    // the container, so the animation is started once (and the pager only composed
    // after) the first layout pass reports a non-zero size. With no source rect
    // (e.g. opened from a share intent) the viewer starts settled.
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val animateFromSource = transitionRect != Rect.Zero
    LaunchedEffect(containerSize) {
        if (containerSize != IntSize.Zero && !leaving) {
            if (animateFromSource) {
                progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            } else {
                progress.snapTo(1f)
            }
        }
    }

    var drawerVisible by remember { mutableStateOf(false) }
    // Long-press radial menu: summoned at the finger and resolved in one motion.
    var radialPresent by remember { mutableStateOf(false) }
    var radialCenter by remember { mutableStateOf(Offset.Zero) }
    var radialHovered by remember { mutableStateOf<ViewerAction?>(null) }
    // True from the moment the radial opens until its gesture ends, so the page's
    // tap handler can ignore the release that ran an action.
    var radialActive by remember { mutableStateOf(false) }
    // 0..1 spring driver for the wheel's bloom; also fades the background blur.
    val radialProgress = remember { Animatable(0f) }
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
            exitSourceRect = sourceRect
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

    // Deferred delete: slide to a neighbour BEFORE the list shrinks, so the
    // transition reads as the item leaving and the adjacent image taking its
    // place. Direction follows availability: next (slide left) when there is a
    // successor, else the previous item (slide right). The actual list mutation
    // happens in onRemovalAnimationEnd, and page keys let the pager remap the
    // now-visible neighbour from its old index to the removed one.
    LaunchedEffect(pendingRemoval) {
        val removed = pendingRemoval ?: return@LaunchedEffect
        if (leaving) return@LaunchedEffect
        val index = items.indexOfFirst { it.uri == removed.uri }
        val target = when {
            index < 0 -> -1
            index < items.lastIndex -> index + 1
            else -> index - 1
        }
        if (target < 0 || target > items.lastIndex || target == index) {
            onRemovalAnimationEnd()
            return@LaunchedEffect
        }
        removing = true
        try {
            pagerState.animateScrollToPage(
                target,
                animationSpec = tween(DeleteSlideDurationMs, easing = FastOutSlowInEasing),
            )
        } finally {
            removing = false
        }
        onRemovalAnimationEnd()
    }

    // Undo restore: slide the pager back to the item that was just re-inserted,
    // the reverse of the delete slide above.
    LaunchedEffect(restoreUri, items) {
        val uri = restoreUri ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.uri == uri }
        if (index < 0) return@LaunchedEffect
        removing = true
        try {
            pagerState.animateScrollToPage(
                index,
                animationSpec = tween(DeleteSlideDurationMs, easing = FastOutSlowInEasing),
            )
        } finally {
            removing = false
        }
        onRestored()
    }

    // Preload adjacent pages so page swipes never flash black while loading.
    // Images go through Coil; VIDEO posters go through VideoPosterMemory (the
    // viewer composes the incoming page at swipe start and needs its poster
    // synchronously), so video neighbors are warmed into that cache instead.
    val ctx = context
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

    // Maps a radial-menu choice onto the viewer's action callbacks.
    // Videos have no Crop sector, so a stale Crop hover on a video page is a no-op.
    val onRadialAction: (ViewerAction) -> Unit = { action ->
        currentItem?.let { item ->
            when (action) {
                ViewerAction.Delete -> if (!deleteInProgress) onDelete(item)
                ViewerAction.Info -> onInfo(item)
                ViewerAction.Share -> onShare(item)
                ViewerAction.Crop -> if (!item.isVideo) onCrop(item)
            }
        }
    }
    // The long-press pointer-input coroutine outlives page recompositions when
    // adjacent pages share a media type. Read the current callback when the finger
    // lifts so a radial action always applies to the page being viewed now.
    val latestOnRadialAction = rememberUpdatedState(onRadialAction)
    // Sectors offered by the wheel: videos drop Crop (Delete stays on top).
    val radialActions = remember(currentPageIsVideo) {
        if (currentPageIsVideo) {
            listOf(ViewerAction.Delete, ViewerAction.Info, ViewerAction.Share)
        } else {
            ViewerAction.entries
        }
    }
    val latestRadialActions = rememberUpdatedState(radialActions)
    val latestIsVideo = rememberUpdatedState(currentPageIsVideo)
    val latestPlayback = rememberUpdatedState(playback)

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
            .pointerInput(
                currentPageZoomed,
                drawerVisible,
                radialPresent,
                leaving,
                multiTouchActive,
                removing,
                previewOnly,
            ) {
                if (!currentPageZoomed && !drawerVisible && !radialPresent && !leaving &&
                    !multiTouchActive && !removing
                ) {
                    detectVerticalSwipe(
                        threshold = swipeThreshold,
                        onSwipeUp = {
                            if (!previewOnly) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                drawerVisible = true
                            }
                        },
                        onSwipeDown = { close() },
                    )
                }
            }
            // Long-press anywhere summons the radial menu at the finger;
            // keep holding and slide to a sector, release to run it. The same
            // gesture resolves everything in one motion. The video page no
            // longer owns a long-press of its own, so this detector serves
            // images and videos alike; opening the wheel pauses video so the
            // action applies to a frozen frame (no auto-resume).
            .pointerInput(drawerVisible, leaving, multiTouchActive, removing, previewOnly) {
                if (previewOnly || drawerVisible || leaving || multiTouchActive || removing) {
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Freeze the sector set for this gesture: a swipe across a
                    // page boundary mid-press must not remap the wheel under it.
                    val openActions = latestRadialActions.value.toList()
                    if (latestIsVideo.value) latestPlayback.value?.player?.pause()
                    radialCenter = clampRadialCenter(longPress.position, containerSize, density)
                    radialHovered = null
                    radialActive = true
                    radialPresent = true
                    longPress.consume()
                    scope.launch {
                        radialProgress.animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                    var last = longPress.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                        val hovered = radialActionAt(last, radialCenter, density, openActions)
                        if (hovered != radialHovered) {
                            if (hovered != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            radialHovered = hovered
                        }
                        change.consume()
                    }
                    val action = radialHovered
                    radialHovered = null
                    radialActive = false
                    // Serialize: let the wheel spring closed BEFORE the action's
                    // UI (e.g. the delete ConfirmDialog) appears. Firing it
                    // immediately hard-cuts the dialog in over the collapsing
                    // wheel, which reads as a visual jump between two languages.
                    scope.launch {
                        radialProgress.animateTo(
                            0f,
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                        radialPresent = false
                        action?.let { latestOnRadialAction.value(it) }
                    }
                }
            },
    ) {
        // Scale-in from the tapped tile.
        val containerW = containerSize.width.toFloat().coerceAtLeast(1f)
        val containerH = containerSize.height.toFloat().coerceAtLeast(1f)
        val fromScaleX = if (animateFromSource) transitionRect.width / containerW else 0.94f
        val fromScaleY = if (animateFromSource) transitionRect.height / containerH else 0.94f

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
                    // Blur the photo behind the radial menu, driven by its spring.
                    .blur(
                        radius = (RADIAL_BLUR_DP * radialProgress.value).dp,
                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                    )
                    // No backdrop of its own: the host's fading black layer is the
                    // single source of black, so page gaps and letterboxes stay
                    // covered while settled and fade away cleanly on exit.
                    .clip(pagerShape)
                    .graphicsLayer {
                        scaleX = lerp(fromScaleX, 1f, p)
                        scaleY = lerp(fromScaleY, 1f, p)
                        translationX = if (animateFromSource) {
                            lerp(transitionRect.center.x - containerW / 2f, 0f, p)
                        } else 0f
                        translationY = if (animateFromSource) {
                            lerp(transitionRect.center.y - containerH / 2f, 0f, p)
                        } else 0f
                        alpha = if (animateFromSource) 1f else p
                    },
                // Video pages lock pager swiping while playing; paused (or image
                // pages) allow it. Zoom, multi-touch, and an in-flight delete
                // slide keep their existing gates.
                userScrollEnabled = (!currentPageIsVideo || !videoPlaying) &&
                    !currentPageZoomed &&
                    !multiTouchActive &&
                    !radialPresent &&
                    !removing &&
                    !deleteInProgress,
                // Stable page identity: lets the pager remap a page to its new
                // index when a delete shrinks the list, instead of flashing the
                // wrong item during the commit.
                key = { items[it].uri },
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    // Video page: real playback via the viewer-owned ExoPlayer,
                    // no zoom gestures. Only the settled page is "active" (it
                    // attaches the shared player to its surface); adjacent video
                    // pages show their cached poster.
                    val controller = playback
                    if (controller != null) {
                        VideoPlayerPage(
                            player = controller.player,
                            item = item,
                            active = page == currentIndex,
                            // The veil covers the surface until this item's first
                            // frame has rendered on the shared player.
                            contentReady = {
                                controller.renderedMediaId == item.uri.toString()
                            },
                            onControlsVisibilityChanged = { visible ->
                                if (page == currentIndex) videoControlsVisible = visible
                            },
                            onPlayingChanged = { playing ->
                                if (page == currentIndex) videoPlaying = playing
                            },
                            wheelOpen = radialPresent && currentPageIsVideo,
                            // Live entry/exit progress (0 = tile, 1 = fullscreen) so
                            // the video page morphs the tile's cropped poster into the
                            // fitted player and back on close. Must stay live on exit
                            // too: pinning it to 1f left the pager's non-uniform scale
                            // as the only transform, so the tile's square crop did not
                            // line up.
                            entryProgress = { progress.value },
                            sourceRect = transitionRect,
                            containerSize = containerSize,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Defensive: no controller means the viewer opened with no
                        // video at all, which cannot happen for a video page. Fall
                        // back to the poster rather than crash.
                        Box(modifier = Modifier.fillMaxSize()) {
                            VideoPosterThumb(
                                item = item,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else {
                    ZoomableImage(
                        item = item,
                        sourceRect = transitionRect,
                        onZoomedChange = { zoomed -> currentPageZoomed = zoomed },
                        // Skip the release that resolved a long-press radial action.
                        onTap = { if (!radialActive) toggleImageControls() },
                        multiTouchActive = multiTouchActive,
                        // Live entry/exit progress so the page morphs the grid tile's
                        // square crop into the full-resolution image and back on
                        // close, instead of popping when the original decodes. Must
                        // stay live on exit: pinning it to 1f disabled the correction
                        // and left the pager's non-uniform scale to squash the image.
                        entryProgress = { progress.value },
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
                        role = Role.Button,
                        onClick = close,
                    ),
                shape = CircleShape,
                color = ThemeSurface,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
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
                    text = stringResource(R.string.viewer_position, currentIndex + 1, items.size),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Long-press radial menu: 4 sectors on images, Delete/Info/Share on video.
        if (radialPresent && !previewOnly) {
            ViewerActionWheel(
                center = radialCenter,
                hovered = radialHovered,
                containerSize = containerSize,
                density = density,
                progress = radialProgress.value,
                actions = radialActions,
            )
        }

        // Sliding bottom action drawer. The full-screen scrim fades on its own;
        // only the sheet slides, so opening the drawer never looks like a second
        // black page sliding over the viewer.
        ActionSheet(
            visible = drawerVisible && !previewOnly,
            item = currentItem,
            onCloseDrawer = { drawerVisible = false },
            onDelete = {
                drawerVisible = false
                if (!deleteInProgress) currentItem?.let(onDelete)
            },
            onShare = {
                drawerVisible = false
                currentItem?.let(onShare)
            },
            onInfo = {
                drawerVisible = false
                currentItem?.let(onInfo)
            },
            onCrop = {
                drawerVisible = false
                currentItem?.let(onCrop)
            },
        )
    }
}

@Composable
private fun ZoomableImage(
    item: MediaItem,
    sourceRect: Rect,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
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
        fool.akza.foto.data.thumbnails.ThumbCache.buildFullRequest(ctx, item)
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
        fool.akza.foto.data.thumbnails.ThumbCache.keyFor(item)
    }
    val thumb = remember(thumbKey) {
        fool.akza.foto.data.thumbnails.ThumbCache.loadThumb(thumbKey)
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

    // ── Momentum glide ──────────────────────────────────────────────────────
    // Panning stays 1:1 with the finger (precise); on release the offset coasts
    // with a low-friction decay, clamped to the same pan bounds.
    val liveScale = rememberUpdatedState(scale)
    val liveOffset = rememberUpdatedState(offset)
    val liveContainer = rememberUpdatedState(containerSize)
    val flingJob = remember { mutableStateOf<Job?>(null) }

    val startPanFling: (Velocity) -> Unit = { velocity ->
        flingJob.value?.cancel()
        val s = liveScale.value
        val c = liveContainer.value
        if (s > 1.01f && c != IntSize.Zero) {
            val halfW = c.width * (s - 1f) / 2f
            val halfH = c.height * (s - 1f) / 2f
            val from = liveOffset.value
            flingJob.value = scope.launch {
                val decay = exponentialDecay<Float>(
                    frictionMultiplier = PAN_FLING_FRICTION,
                    absVelocityThreshold = PAN_FLING_VELOCITY_THRESHOLD,
                )
                val animX = Animatable(from.x).apply { updateBounds(-halfW, halfW) }
                val animY = Animatable(from.y).apply { updateBounds(-halfH, halfH) }
                launch {
                    animX.animateDecay(velocity.x, decay) {
                        offset = Offset(value, offset.y)
                    }
                }
                launch {
                    animY.animateDecay(velocity.y, decay) {
                        offset = Offset(offset.x, value)
                    }
                }
            }
        }
    }

    // Cancel any coasting as soon as a pinch starts or the page changes.
    LaunchedEffect(item.uri, multiTouchActive) {
        if (multiTouchActive) flingJob.value?.cancel()
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
                // Re-keyed on multiTouchActive so taps are dead while a two-finger
                // pinch is in effect (only pinch-zoom should act then).
                .pointerInput(multiTouchActive) {
                    if (multiTouchActive) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            flingJob.value?.cancel()
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
                // Velocity observer for the pan fling. Reads at the Final pass so
                // transformable/taps handle the gesture first, and never consumes.
                .pointerInput(multiTouchActive) {
                    if (multiTouchActive) return@pointerInput
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        flingJob.value?.cancel()
                        tracker.resetTracking()
                        var singlePointer = true
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount > 1) {
                                // A pinch is in play: no single-finger fling.
                                singlePointer = false
                                tracker.resetTracking()
                            }
                            if (singlePointer) {
                                event.changes.lastOrNull()?.let { change ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                            }
                            if (pressedCount == 0) break
                        }
                        if (singlePointer) {
                            val velocity = tracker.calculateVelocity()
                            val speed = kotlin.math.hypot(velocity.x, velocity.y)
                            if (speed > PAN_FLING_MIN_VELOCITY) {
                                startPanFling(velocity)
                            }
                        }
                    }
                }
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
    visible: Boolean,
    item: MediaItem?,
    onCloseDrawer: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onCrop: () -> Unit,
) {
    val target = item ?: return
    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop: a full-screen fade only. It must never be part of the
        // sheet's slide, otherwise the tint reads as a page sliding up.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack.copy(alpha = 0.55f))
                    .clickable(
                        onClick = onCloseDrawer,
                        indication = null,
                        interactionSource = null,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(260, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(180)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
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
                        ActionItem(Icons.Outlined.Share, stringResource(R.string.action_share), TextPrimary, onShare)
                        ActionItem(Icons.Outlined.Info, stringResource(R.string.info_title), TextPrimary, onInfo)
                        ActionItem(Icons.Outlined.Delete, stringResource(R.string.action_delete), DeleteRed, onDelete)
                        // Videos have no crop action.
                        if (!target.isVideo) {
                            ActionItem(Icons.Outlined.Crop, stringResource(R.string.action_crop), TextPrimary, onCrop)
                        }
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
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
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
