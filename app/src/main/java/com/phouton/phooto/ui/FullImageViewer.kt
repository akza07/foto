package com.phouton.phooto.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch

private val SheetRadius = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val DeleteRed = Color(0xFFEF4444)

/**
 * Full-image viewer as a horizontal pager.
 * - Swipe left/right: next/previous image (pager slide).
 * - Double-tap: animated zoom in/out around the tap point.
 * - Pinch: zoom/pan.
 * - Long-press / swipe up: slides the action drawer up from the bottom.
 * - Swipe down (at 1x): dismisses.
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

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )

    // Entry/exit progress: 0 = scaled to source tile, 1 = full screen.
    val progress = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    var drawerVisible by remember { mutableStateOf(false) }
    var currentPageZoomed by remember { mutableStateOf(false) }

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

    val backgroundAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    )

    val swipeThreshold = with(density) { 56.dp.toPx() }
    val currentIndex = pagerState.settledPage
    val currentItem = items.getOrNull(currentIndex)

    // Preload adjacent pages so page swipes never flash black while loading.
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(currentIndex) {
        val imageLoader = coil3.SingletonImageLoader.get(context)
        listOf(currentIndex - 1, currentIndex, currentIndex + 1)
            .filter { it in items.indices }
            .forEach { i ->
                val req = ImageRequest.Builder(context)
                    .data(items[i].uri)
                    .build()
                imageLoader.enqueue(req)
            }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .background(AmoledBlack.copy(alpha = backgroundAlpha))
            // Vertical drags (at 1x): swipe up -> drawer, swipe down -> dismiss.
            .pointerInput(currentPageZoomed, drawerVisible, leaving) {
                if (!currentPageZoomed && !drawerVisible && !leaving) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { acc = 0f },
                        onDragCancel = { acc = 0f },
                    ) { _, dragAmount ->
                        acc += dragAmount
                        if (acc < -swipeThreshold) {
                            drawerVisible = true
                            acc = 0f
                        } else if (acc > swipeThreshold) {
                            close()
                        }
                    }
                }
            },
    ) {
        // Scale-in from the tapped tile.
        val p = progress.value
        val containerW = containerSize.width.toFloat().coerceAtLeast(1f)
        val containerH = containerSize.height.toFloat().coerceAtLeast(1f)
        val fromScaleX = sourceRect.width.toFloat() / containerW
        val fromScaleY = sourceRect.height.toFloat() / containerH

        // The tile has 12dp rounded corners; as the pager shrinks back to it, grow
        // the clip radius so the transition lands flush on the rounded thumbnail.
        val tileCorner = with(density) { 12.dp.toPx() }
        val cornerRadius = tileCorner * (1f - p)
        val pagerShape = RoundedCornerShape(cornerRadius)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clip(pagerShape)
                .graphicsLayer {
                    scaleX = lerp(fromScaleX, 1f, p)
                    scaleY = lerp(fromScaleY, 1f, p)
                    translationX = lerp(sourceRect.center.x - containerW / 2f, 0f, p)
                    translationY = lerp(sourceRect.center.y - containerH / 2f, 0f, p)
                    alpha = 1f
                },
            userScrollEnabled = !currentPageZoomed,
        ) { page ->
            ZoomableImage(
                item = items[page],
                onZoomedChange = { zoomed -> currentPageZoomed = zoomed },
                onLongPress = { drawerVisible = true },
            )
        }

        // Top bar: back button + counter pill (top-left, shadcn style).
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .graphicsLayer {
                    // Fade out with the exit animation so it never lingers over the grid.
                    alpha = if (leaving) p else 1f
                    translationY = if (leaving) -12.dp.toPx() * (1f - p) else 0f
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = close),
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
    onZoomedChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    val scope = rememberCoroutineScope()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .onSizeChanged { containerSize = it },
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
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
                    )
                }
                .transformable(state = transformableState, canPan = { scale != 1f })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
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
                    ActionItem(Icons.Outlined.Delete, "Delete", DeleteRed, onDelete)
                    ActionItem(Icons.Outlined.Share, "Share", TextPrimary, onShare)
                    ActionItem(Icons.Outlined.Info, "Info", TextPrimary, onInfo)
                    ActionItem(Icons.Outlined.Crop, "Crop", TextPrimary, onCrop)
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
