package fool.akza.foto.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.graphics.Shadow
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import fool.akza.foto.R
import fool.akza.foto.data.MediaItem
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MoreMenuShape = RoundedCornerShape(16.dp)

/**
 * Frosted glass for the viewer's action rail. The blur is deliberately much
 * stronger than the app bars (18dp) so the photo stays recognisable through the
 * controls instead of turning into a dark slab.
 */
private val MoreMenuHazeStyle = HazeStyle(
    blurRadius = 50.dp,
    tints = listOf(HazeTint(AmoledBlack.copy(alpha = 0.18f))),
)

/** Keeps white labels readable over a bright, strongly blurred photo. */
private val ActionLabelShadow = Shadow(
    color = AmoledBlack.copy(alpha = 0.9f),
    offset = Offset(0f, 1f),
    blurRadius = 3f,
)

/** Attention sampling cadence; small enough for 1s rules, cheap on the frame budget. */
private const val SAMPLE_INTERVAL_MS = 250L

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
 * instant a second finger lands, so a two-finger pinch can never reveal the chrome
 * or dismiss the viewer.
 *
 * Once past touch slop, only a gesture at least [VerticalGestureRatio] times
 * more vertical than horizontal counts as vertical. Everything else is left to
 * the pager. Vertical consumes both axes and drains the rest of the gesture, so
 * an angled swipe can never slide the page and reveal the chrome at the same time.
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
            // sliding the page and revealing the chrome at the same time.
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
                // instead of revealing the chrome.
                if (kotlin.math.abs(totalDy) < kotlin.math.abs(totalDx) * VerticalGestureRatio) {
                    return@awaitEachGesture
                }
                axisDecided = true
                vertical = true
            }

            if (vertical) {
                if (totalDy <= -threshold) {
                    onSwipeUp()
                    // The chrome reveals mid-gesture. Keep consuming the rest of
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

/**
 * Tracks a single-finger horizontal drag and fires [onSwipe] once it travels
 * past [threshold] px, **without consuming anything**: the pager owns the slide
 * and must still get every event. This is the observer that lets a left/right
 * page swipe also flip the chrome, the way a tap does.
 *
 * A gesture that is not clearly horizontal is left alone (the vertical detector
 * handles reveal/dismiss), and the drag is abandoned the instant a second finger
 * lands so a pinch never toggles the chrome.
 */
internal suspend fun PointerInputScope.detectHorizontalSwipe(
    threshold: Float,
    onSwipe: () -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var totalDx = 0f
        var totalDy = 0f
        var axisDecided = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) return@awaitEachGesture
            val drag = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            totalDx += drag.position.x - drag.previousPosition.x
            totalDy += drag.position.y - drag.previousPosition.y

            if (!axisDecided && kotlin.math.hypot(totalDx, totalDy) > touchSlop) {
                // Steep drags belong to the vertical detector (reveal / dismiss),
                // so only a clearly horizontal drag counts as a page swipe here.
                if (kotlin.math.abs(totalDx) * VerticalGestureRatio < kotlin.math.abs(totalDy)) {
                    return@awaitEachGesture
                }
                axisDecided = true
            }
            if (axisDecided && kotlin.math.abs(totalDx) >= threshold) {
                onSwipe()
                // One toggle per gesture: the rest of the drag is the pager's.
                // `awaitEachGesture` waits for the pointers to lift before a new
                // gesture can start.
                return@awaitEachGesture
            }
            if (!drag.pressed) return@awaitEachGesture
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
 * - Swipe left/right: next/previous item (pager slide), and the same swipe flips
 *   the viewer chrome the way a tap does, so browsing stays unobstructed. On video
 *   pages, swiping is locked while the video plays — pause first to navigate.
 * - Images: double-tap zoom, pinch zoom/pan. While two fingers are down,
 *   single-finger gestures yield to pinch-zoom.
 * - Long-press (images + videos): radial action wheel at the finger;
 *   videos offer Delete/Info/Share (no Crop).
 * - Tap an image (or swipe up): frosted action panel. It follows the Selection
 *   actions placement — a floating side rail at ~3/4 height by default, or the bottom
 *   bar when that edge was chosen. Share is omitted entirely when the Allow
 *   sharing setting is off.
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
    onRename: (MediaItem) -> Unit,
    onCrop: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit,
    loopVideos: Boolean = false,
    /** Whether the wheel offers Share. */
    shareEnabled: Boolean = true,
    /** View/play only: no action wheel, delete, share, info, crop or wallpaper. */
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
    // Backdrop source for the "More" card; the pager samples through a strong
    // blur behind the compact menu.
    val hazeState = rememberHazeState()

    // Viewing-attention tracking for Insights. Read-only previews (Trash) are
    // excluded so opening a trashed item never counts as watching it.
    val attention = remember(previewOnly) {
        if (previewOnly) null else ViewerAttentionTracker(context.applicationContext)
    }
    DisposableEffect(attention) {
        onDispose { attention?.finish() }
    }
    // Only sample while the viewer is actually in the foreground; a backgrounded
    // viewer must not keep a 4 Hz main-thread wakeup stream alive.
    var attentionRunning by remember { mutableStateOf(false) }
    LifecycleStartEffect(attention) {
        attention?.setForeground(true)
        attentionRunning = attention != null
        onStopOrDispose {
            attention?.setForeground(false)
            attentionRunning = false
        }
    }
    LaunchedEffect(attention, attentionRunning) {
        if (attention == null || !attentionRunning) return@LaunchedEffect
        while (true) {
            delay(SAMPLE_INTERVAL_MS)
            attention.sample()
        }
    }

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

    // Long-press radial menu: summoned at the finger and resolved in one motion.
    var radialPresent by remember { mutableStateOf(false) }
    var radialCenter by remember { mutableStateOf(Offset.Zero) }
    var radialHovered by remember { mutableStateOf<ViewerAction?>(null) }
    // Low-frequency actions live in a compact card at the wheel's centre.
    var moreMenuVisible by remember { mutableStateOf(false) }
    var moreMenuCenter by remember { mutableStateOf(Offset.Zero) }
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
            imageControlsVisible = false
        }
    }

    // Tap toggles the whole chrome: top bar, action rail and system bars. This is
    // the classic immersive viewer gesture; the rail is part of the chrome, not a
    // modal panel, so there is nothing to open or dismiss separately.
    var systemBarsHidden by remember { mutableStateOf(false) }
    val toggleChrome = {
        // The chrome also auto-hides after a few seconds, which leaves
        // systemBarsHidden false but imageControlsVisible false. Treat either
        // hidden state as "show everything" so the first tap after auto-hide
        // brings the UI back instead of hiding it a second time.
        val show = !imageControlsVisible || systemBarsHidden
        if (show) {
            systemBarsHidden = false
            showImageControls()
        } else {
            systemBarsHidden = true
            imageHideJob.value?.cancel()
            imageControlsVisible = false
        }
    }
    val showChrome = {
        systemBarsHidden = false
        showImageControls()
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
            // Stop counting before the exit animation, so the shrink back to the
            // tile is not mistaken for viewing time.
            attention?.setLeaving(true)
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
    // one is the most recent and wins while the viewer is open. An open action
    // panel consumes back first.
    BackHandler {
        if (moreMenuVisible) moreMenuVisible = false else close()
    }

    val swipeThreshold = with(density) { 56.dp.toPx() }
    val currentIndex = pagerState.settledPage
    val currentItem = items.getOrNull(currentIndex)

    val currentPageIsVideo = currentItem?.isVideo == true

    // A visit starts when a page settles and ends when the next one settles (or
    // the viewer closes); the tracker flushes the previous item itself.
    LaunchedEffect(currentItem?.uri) {
        attention?.begin(currentItem)
    }
    LaunchedEffect(currentItem?.uri, videoPlaying) {
        attention?.setPlaying(videoPlaying)
    }

    // System bars: visible on open, then toggled with the rest of the viewer
    // chrome. The insets guard keeps the layout still while the platform
    // animates the bars away and back.
    val view = LocalView.current
    val insetsGuard = LocalWindowInsetsGuard.current
    val window = remember(view) { view.context.findActivity()?.window }
    val barsController = remember(window, view) {
        window?.let { WindowInsetsControllerCompat(it, view) }
    }
    DisposableEffect(view, barsController) {
        barsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            barsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsGuard?.unfreeze()
        }
    }
    LaunchedEffect(systemBarsHidden, barsController) {
        if (systemBarsHidden) {
            insetsGuard?.freeze(ViewCompat.getRootWindowInsets(view))
            barsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            barsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsGuard?.unfreeze()
        }
    }

    // Video pages never hold image zoom; image pages never hold video state.
    LaunchedEffect(currentPageIsVideo, currentIndex) {
        if (currentPageIsVideo) {
            videoControlsVisible = true
            currentPageZoomed = false
        } else {
            videoPlaying = false
            // Arriving on a photo must not reveal the chrome: the swipe already
            // decided its state (see `detectHorizontalSwipe`), and forcing it
            // back on would half-show the UI and fight the toggle. Only re-arm
            // the auto-hide while the chrome is already on screen.
            if (imageControlsVisible) showImageControls()
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

    // Maps a radial-menu choice onto the viewer's action callbacks. More opens
    // the compact card holding Crop/Rename/Wallpaper; videos get Rename as the
    // fourth sector because they have no Crop/Wallpaper.
    val onRadialAction: (ViewerAction) -> Unit = { action ->
        currentItem?.let { item ->
            when (action) {
                ViewerAction.Delete -> if (!deleteInProgress) onDelete(item)
                ViewerAction.Info -> onInfo(item)
                ViewerAction.Share -> if (shareEnabled) onShare(item)
                ViewerAction.More -> {
                    moreMenuCenter = radialCenter
                    moreMenuVisible = true
                }
                ViewerAction.Crop -> if (!item.isVideo) onCrop(item)
                ViewerAction.Rename -> onRename(item)
                ViewerAction.Wallpaper -> if (!item.isVideo) onWallpaper(item)
            }
        }
    }
    // The long-press pointer-input coroutine outlives page recompositions when
    // adjacent pages share a media type. Read the current callback when the finger
    // lifts so a radial action always applies to the page being viewed now.
    val latestOnRadialAction = rememberUpdatedState(onRadialAction)
    // Four sectors at most: the three most-used actions plus More for images
    // (Crop/Rename/Wallpaper) or Rename for videos. Share disappears entirely
    // when the privacy setting disables it.
    val radialActions = remember(currentPageIsVideo, shareEnabled) {
        val actions = if (currentPageIsVideo) {
            listOf(ViewerAction.Delete, ViewerAction.Info, ViewerAction.Share, ViewerAction.Rename)
        } else {
            listOf(ViewerAction.Delete, ViewerAction.Info, ViewerAction.Share, ViewerAction.More)
        }
        if (shareEnabled) actions else actions.filterNot { it == ViewerAction.Share }
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
            // Any touch revives the attention tracker's idle window (observed in
            // the Initial pass without consuming, so no gesture is affected).
            .pointerInput(attention) {
                if (attention == null) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        attention.onTouch()
                    }
                }
            }
            // Multi-touch watch: as soon as a second finger lands, every single-finger
            // gesture yields to the pinch-zoom on the image below.
            .pointerInput(Unit) {
                detectMultiTouch(
                    onMultiTouchStart = { multiTouchActive = true },
                    onMultiTouchEnd = { multiTouchActive = false },
                )
            }
            // Vertical drags (at 1x): swipe up -> reveal chrome, swipe down -> dismiss.
            // Abandoned the instant a second finger joins, so a pinch can't open the
            // chrome. Re-armed (via key change) once the multi-touch gesture ends.
            // Applies to images and videos alike: the video pill only covers the
            // bottom, so a swipe starting on the video surface reaches this
            // detector cleanly.
            .pointerInput(
                currentPageZoomed,
                radialPresent,
                leaving,
                multiTouchActive,
                removing,
                previewOnly,
                moreMenuVisible,
            ) {
                if (!currentPageZoomed && !radialPresent && !leaving &&
                    !multiTouchActive && !removing && !moreMenuVisible
                ) {
                    detectVerticalSwipe(
                        threshold = swipeThreshold,
                        onSwipeUp = {
                            if (!previewOnly) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showChrome()
                            }
                        },
                        onSwipeDown = { close() },
                    )
                }
            }
            // Horizontal drags on a photo page flip the whole chrome, exactly
            // like a tap: a swipe to the next photo is then seen clean, and one
            // more swipe brings the UI back. The detector only observes (it
            // never consumes), so the pager still slides. Images only: video
            // pages keep their own HUD and lock swiping while playing.
            .pointerInput(
                currentPageZoomed,
                currentPageIsVideo,
                radialPresent,
                leaving,
                multiTouchActive,
                removing,
                previewOnly,
                moreMenuVisible,
            ) {
                if (!currentPageZoomed && !currentPageIsVideo && !radialPresent && !leaving &&
                    !multiTouchActive && !removing && !previewOnly && !moreMenuVisible
                ) {
                    detectHorizontalSwipe(
                        threshold = swipeThreshold,
                        onSwipe = { toggleChrome() },
                    )
                }
            }
            // Long-press anywhere summons the radial menu at the finger;
            // keep holding and slide to a sector, release to run it. The same
            // gesture resolves everything in one motion. The video page no
            // longer owns a long-press of its own, so this detector serves
            // images and videos alike; opening the wheel pauses video so the
            // action applies to a frozen frame (no auto-resume).
            .pointerInput(leaving, multiTouchActive, removing, previewOnly, moreMenuVisible) {
                if (previewOnly || leaving || multiTouchActive || removing || moreMenuVisible) {
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
                    // The action panel samples this layer for its frosted glass.
                    .hazeSource(state = hazeState)
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
                    !deleteInProgress &&
                    !moreMenuVisible,
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
                        onTap = {
                            if (!radialActive && !previewOnly) toggleChrome()
                        },
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

        // The wheel's More sector opens this compact card at the wheel centre;
        // the primary three stay a single radial gesture.
        if (moreMenuVisible && !previewOnly) {
            ViewerMoreMenu(
                center = moreMenuCenter,
                containerSize = containerSize,
                hazeState = hazeState,
                onDismiss = { moreMenuVisible = false },
                onAction = { action ->
                    moreMenuVisible = false
                    onRadialAction(action)
                },
            )
        }
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
    // Keep the tile-resolution bridge fully opaque until the entry zoom has
    // settled. Fading it as soon as the original decoded made the sharp image
    // appear mid-morph, which read as a small thumbnail-to-photo swap.
    val bridgeAlpha = remember(item.uri) { Animatable(1f) }
    val entrySettled = entryProgress() >= 0.999f
    LaunchedEffect(item.uri, fullLoaded, entrySettled) {
        if (!fullLoaded || !entrySettled) {
            bridgeAlpha.snapTo(1f)
        } else {
            bridgeAlpha.animateTo(0f, tween(220))
        }
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

                // Tile-res bridge above the original, kept opaque through the
                // entry zoom and only faded once the morph has settled.
                if (thumb != null) {
                    if (bridgeAlpha.value > 0.001f) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            // Fit (not Crop): the shared correction layer performs
                            // the crop, so bridge and original share one geometry.
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = bridgeAlpha.value },
                        )
                    }
                }
            }
        }
    }
}

private val MoreMenuActions = listOf(
    ViewerAction.Crop,
    ViewerAction.Rename,
    ViewerAction.Wallpaper,
)

/**
 * Compact frosted card for the wheel's More sector. It appears at the wheel
 * centre (so the thumb is already there), holds the low-frequency actions, and
 * dims nothing: tapping outside just dismisses it.
 */
@Composable
private fun ViewerMoreMenu(
    center: Offset,
    containerSize: IntSize,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onAction: (ViewerAction) -> Unit,
) {
    if (containerSize == IntSize.Zero) return
    Box(modifier = Modifier.fillMaxSize()) {
        // Transparent catcher: closes on outside taps and keeps the image tap
        // from toggling the chrome while the card is open.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    onClick = onDismiss,
                    indication = null,
                    interactionSource = null,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset {
                    IntOffset(
                        (center.x - containerSize.width / 2f).roundToInt(),
                        (center.y - containerSize.height / 2f).roundToInt(),
                    )
                }
                .width(200.dp)
                .clip(MoreMenuShape)
                .hazeEffect(state = hazeState, style = MoreMenuHazeStyle)
                .border(1.dp, Border.copy(alpha = 0.45f), MoreMenuShape)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 6.dp),
        ) {
            MoreMenuActions.forEach { action ->
                MoreMenuRow(action = action, onClick = { onAction(action) })
            }
        }
    }
}

@Composable
private fun MoreMenuRow(action: ViewerAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(action.labelRes()),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(shadow = ActionLabelShadow),
        )
    }
}

/** Unwraps [this] context chain until the hosting [Activity] is found. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
