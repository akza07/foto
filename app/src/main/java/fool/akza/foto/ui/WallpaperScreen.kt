package fool.akza.foto.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pinch
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.ImageCropper
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.NormalizedCrop
import fool.akza.foto.data.WallpaperResult
import fool.akza.foto.data.WallpaperSetter
import fool.akza.foto.data.WallpaperTarget
import fool.akza.foto.data.fitWallpaperCrop
import fool.akza.foto.data.toCropRegion
import fool.akza.foto.data.transformWallpaperCrop
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.EmptySurface
import fool.akza.foto.ui.theme.Surface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoding cap for the wallpaper source, whatever the display reports. */
private const val MAX_WALLPAPER_SOURCE_DIMENSION = 2560

private val PreviewShape = RoundedCornerShape(30.dp)
private val PanelShape = RoundedCornerShape(16.dp)
private val TrackShape = RoundedCornerShape(12.dp)
private val TabShape = RoundedCornerShape(9.dp)
private val OptionShape = RoundedCornerShape(12.dp)

/** Targets offered by the picker, in tab order. */
private val WallpaperTargets = listOf(
    TargetTab(WallpaperTarget.Lock, R.string.wallpaper_target_lock, Icons.Outlined.Lock),
    TargetTab(WallpaperTarget.Home, R.string.wallpaper_target_home, Icons.Outlined.GridView),
)

private data class TargetTab(
    val target: WallpaperTarget,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** The orientation of the wallpaper frame, independent of the device's rotation. */
private enum class WallpaperOrientation(val labelRes: Int) {
    Portrait(R.string.wallpaper_orientation_portrait),
    Landscape(R.string.wallpaper_orientation_landscape),
}

/**
 * Full-screen wallpaper picker for one image.
 *
 * The preview is a device-shaped frame filled with the crop: the frame takes the
 * aspect of the screen in the chosen [WallpaperOrientation], and the user pans
 * and pinches to choose which part of the photo fills it. Tapping Preview drops
 * every piece of chrome and shows the same crop edge to edge. Nothing is written
 * to storage — the crop goes straight to the system wallpaper service.
 */
@Composable
internal fun WallpaperScreen(
    item: MediaItem,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    // Decode at the display's long edge so the applied wallpaper is native
    // resolution without holding the full photo in memory; the service has to
    // PNG-encode whatever it is given, which is slow and memory-hungry.
    val sourceDimension = remember(context) {
        val metrics = context.resources.displayMetrics
        max(metrics.widthPixels, metrics.heightPixels)
            .coerceIn(1024, MAX_WALLPAPER_SOURCE_DIMENSION)
    }

    val bitmapState = remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(item.uri) { mutableStateOf(false) }
    var orientation by remember(item.uri) { mutableStateOf(WallpaperOrientation.Portrait) }
    var target by remember(item.uri) { mutableStateOf(WallpaperTarget.Home) }
    var crop by remember(item.uri) { mutableStateOf(NormalizedCrop(0f, 0f, 1f, 1f)) }
    var previewing by remember(item.uri) { mutableStateOf(false) }
    var saving by remember(item.uri) { mutableStateOf(false) }
    var saveFailure by remember(item.uri) { mutableStateOf<String?>(null) }
    // The host drops us from the tree as soon as it is told to close, so the
    // exit animation has to play here and call back when it is done.
    var entered by remember { mutableStateOf(false) }
    var closing by remember(item.uri) { mutableStateOf(false) }
    var applied by remember(item.uri) { mutableStateOf(false) }

    // Long edge over short edge, so the frame aspect doesn't depend on how the
    // device happens to be rotated right now.
    val screenAspect = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val w = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
        val h = configuration.screenHeightDp.toFloat().coerceAtLeast(1f)
        max(w, h) / min(w, h)
    }
    val frameAspect = when (orientation) {
        WallpaperOrientation.Portrait -> 1f / screenAspect
        WallpaperOrientation.Landscape -> screenAspect
    }

    // Rotating the frame morphs between the two aspects. The crop is refitted to
    // the animated aspect every frame, so the photo is never stretched mid-morph
    // and lands exactly on the new frame shape.
    val frameAspectAnim = remember { Animatable(frameAspect) }
    LaunchedEffect(frameAspect) {
        frameAspectAnim.animateTo(frameAspect, tween(300, easing = FastOutSlowInEasing))
    }
    val morphing = frameAspectAnim.isRunning

    LaunchedEffect(item.uri) {
        val loaded = ImageCropper.loadEditableBitmap(
            context = context,
            uri = item.uri,
            maxDimension = sourceDimension,
        )
        bitmapState.value = loaded
        loadFailed = loaded == null
    }

    // A crop fitted to one frame shape can't be clamped into another, so refit
    // whenever the orientation (or the decoded image) changes.
    LaunchedEffect(bitmapState.value, frameAspect) {
        val source = bitmapState.value ?: return@LaunchedEffect
        crop = fitWallpaperCrop(frameAspect, source.width.toFloat() / source.height.toFloat())
        saveFailure = null
    }

    // Registered for as long as the picker is mounted, so a back press while the
    // wallpaper is being applied or while the close animation runs is swallowed
    // here. If this handler is disabled, the press falls through to the viewer's
    // handler underneath, which closes the viewer and dumps the user on the grid.
    // While the fullscreen preview is up, its own handler is composed later and
    // takes precedence.
    BackHandler {
        if (!saving && !closing) closing = true
    }

    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        delay(240)
        if (applied) onApplied() else onDismiss()
    }

    val bitmap = bitmapState.value
    val enabled = bitmap != null && !saving
    val renderedAspect = frameAspectAnim.value
    val renderedCrop = if (morphing) {
        bitmap?.let { source ->
            fitWallpaperCrop(
                renderedAspect,
                source.width.toFloat() / source.height.toFloat(),
            )
        } ?: crop
    } else {
        crop
    }
    val contentProgress by animateFloatAsState(
        targetValue = if (entered && !closing) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (closing) 200 else 300,
            easing = FastOutSlowInEasing,
        ),
        label = "contentEnter",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            // Own the pointer stream for the whole screen, so taps on empty
            // chrome can't reach the viewer underneath and toggle its chrome.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        pressed = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    alpha = contentProgress
                    translationY = (1f - contentProgress) * 64.dp.toPx()
                },
        ) {
            WallpaperTopBar(
                enabled = !saving,
                onDismiss = { if (!closing) closing = true },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = bitmap,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(
                            initialScale = 0.97f,
                            animationSpec = tween(280, easing = FastOutSlowInEasing),
                        )).togetherWith(fadeOut(tween(140)))
                    },
                    label = "previewContent",
                ) { source ->
                    when {
                        source != null -> WallpaperPreview(
                            bitmap = source,
                            crop = renderedCrop,
                            frameAspect = renderedAspect,
                            gesturesEnabled = !morphing,
                            onCropChange = {
                                crop = it
                                saveFailure = null
                            },
                        )
                        loadFailed -> Text(
                            text = stringResource(R.string.crop_load_failed),
                            color = TextMuted,
                            fontSize = 14.sp,
                        )
                        else -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = TextPrimary,
                            )
                            Text(
                                text = stringResource(R.string.crop_loading),
                                color = TextMuted,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }

            WallpaperControlPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                target = target,
                orientation = orientation,
                enabled = enabled,
                saving = saving,
                saveFailure = saveFailure,
                onTargetChange = {
                    target = it
                    saveFailure = null
                },
                onOrientationChange = {
                    orientation = it
                    saveFailure = null
                },
                onPreview = { previewing = true },
                onSet = set@{
                    val source = bitmapState.value ?: return@set
                    if (saving) return@set
                    saving = true
                    saveFailure = null
                    scope.launch {
                        val result = WallpaperSetter.apply(
                            context = context,
                            source = source,
                            region = crop.toCropRegion(source.width, source.height),
                            target = target,
                        )
                        saving = false
                        when (result) {
                            WallpaperResult.Applied -> {
                                applied = true
                                closing = true
                            }
                            is WallpaperResult.Failed -> saveFailure = result.reason ?: "unknown error"
                        }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = previewing && bitmap != null,
            enter = fadeIn(tween(200)) + scaleIn(
                initialScale = 1.04f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
            ),
            exit = fadeOut(tween(160)) + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ),
        ) {
            bitmap?.let { source ->
                FullscreenPreview(
                    bitmap = source,
                    crop = crop,
                    onDismiss = { previewing = false },
                )
            }
        }

        BusyOverlay(
            visible = saving,
            message = stringResource(R.string.wallpaper_setting),
        )
    }
}

/** Ghost cancel on the left, editor title centred. */
@Composable
private fun WallpaperTopBar(
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onDismiss,
                    indication = null,
                    interactionSource = null,
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = if (enabled) TextMuted else TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = stringResource(R.string.action_cancel),
                color = if (enabled) TextMuted else TextMuted.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            text = stringResource(R.string.wallpaper_title),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Device-shaped preview: the crop is drawn straight into the frame, so what the
 * frame shows is exactly what gets applied. The crop guides are painted on top
 * and the gesture hint sits under the frame.
 */
@Composable
private fun WallpaperPreview(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    frameAspect: Float,
    gesturesEnabled: Boolean,
    onCropChange: (NormalizedCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val latestCrop = rememberUpdatedState(crop)
    val latestFrameAspect = rememberUpdatedState(frameAspect)
    val latestOnCropChange = rememberUpdatedState(onCropChange)

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val gap = 10.dp
        val hintHeight = 26.dp
        val availableHeight = (maxHeight - hintHeight - gap).coerceAtLeast(120.dp)
        val frameWidth: Dp
        val frameHeight: Dp
        if (frameAspect <= maxWidth / availableHeight) {
            frameHeight = availableHeight
            frameWidth = availableHeight * frameAspect
        } else {
            frameWidth = maxWidth
            frameHeight = maxWidth / frameAspect
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(frameWidth, frameHeight)
                    .clip(PreviewShape)
                    .background(AmoledBlack)
                    .border(1.dp, Border, PreviewShape),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, gesturesEnabled) {
                            // Gestures are off while the frame morphs: the crop
                            // is being refitted every frame and would fight back.
                            if (!gesturesEnabled) return@pointerInput
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val current = latestCrop.value
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w <= 0f || h <= 0f) return@detectTransformGestures
                                // The centroid keeps moving once the finger
                                // leaves the frame; anchor on the closest point
                                // inside it, otherwise the focus saturates and
                                // the crop jumps to a bound.
                                val focusX = centroid.x.coerceIn(0f, w)
                                val focusY = centroid.y.coerceIn(0f, h)
                                // The image moves with the finger, so the crop
                                // window moves against it (hence the negated pan).
                                latestOnCropChange.value(
                                    transformWallpaperCrop(
                                        crop = current,
                                        frameAspect = latestFrameAspect.value,
                                        imageAspect = imageAspect,
                                        focusX = current.left + focusX / w * current.width,
                                        focusY = current.top + focusY / h * current.height,
                                        panX = -pan.x * current.width / w,
                                        panY = -pan.y * current.height / h,
                                        zoom = zoom,
                                    ),
                                )
                            }
                        },
                ) {
                    drawWallpaperCrop(image, bitmap.width, bitmap.height, crop)
                    drawCropGuides()
                }
            }

            Spacer(Modifier.height(gap))
            HintPill()
        }
    }
}

/**
 * Preview with no chrome at all: the crop fitted to the window, tap or back to
 * return. Swallows the whole gesture so it can't pan the frame underneath.
 */
@Composable
private fun FullscreenPreview(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    onDismiss: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BackHandler { onDismiss() }

    // The viewer underneath already runs the app in immersive mode (system bars
    // hidden) for as long as it is open, so the preview inherits that and must
    // not touch the bars itself: re-showing them on dismiss would flash them
    // over the still-open viewer.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var dragged = false
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > slop) {
                            dragged = true
                        }
                        change.consume()
                        if (!change.pressed) {
                            released = true
                            break
                        }
                    }
                    if (released && !dragged) onDismiss()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawWallpaperCrop(image, bitmap.width, bitmap.height, crop)
        }
    }
}

/**
 * Draws exactly the crop region, scaled to fit the canvas and centred, so a
 * landscape crop letterboxes on a portrait screen instead of distorting.
 */
private fun DrawScope.drawWallpaperCrop(
    image: ImageBitmap,
    imageWidth: Int,
    imageHeight: Int,
    crop: NormalizedCrop,
) {
    if (imageWidth <= 0 || imageHeight <= 0) return
    if (size.width <= 0f || size.height <= 0f) return

    val srcX = (crop.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
    val srcY = (crop.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
    val srcWidth = (crop.width * imageWidth).roundToInt().coerceIn(1, imageWidth - srcX)
    val srcHeight = (crop.height * imageHeight).roundToInt().coerceIn(1, imageHeight - srcY)

    val srcAspect = srcWidth.toFloat() / srcHeight
    val canvasAspect = size.width / size.height
    val dstWidth: Float
    val dstHeight: Float
    if (srcAspect >= canvasAspect) {
        dstWidth = size.width
        dstHeight = size.width / srcAspect
    } else {
        dstHeight = size.height
        dstWidth = size.height * srcAspect
    }

    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(
            ((size.width - dstWidth) / 2f).roundToInt(),
            ((size.height - dstHeight) / 2f).roundToInt(),
        ),
        dstSize = IntSize(
            dstWidth.roundToInt().coerceAtLeast(1),
            dstHeight.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.High,
    )
}

/** Thirds grid and corner brackets, drawn inside the preview frame. */
private fun DrawScope.drawCropGuides() {
    val inset = 10.dp.toPx()
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    if (right <= left || bottom <= top) return

    val gridColor = Color.White.copy(alpha = 0.20f)
    val stroke = 1.dp.toPx()
    for (step in 1..2) {
        val x = left + (right - left) * step / 3f
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), stroke)
        val y = top + (bottom - top) * step / 3f
        drawLine(gridColor, Offset(left, y), Offset(right, y), stroke)
    }

    val arm = 16.dp.toPx()
    val thickness = 2.dp.toPx()
    val corner = Color.White.copy(alpha = 0.92f)
    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(corner, Offset(x, y), Offset(x + dx * arm, y), thickness, StrokeCap.Round)
        drawLine(corner, Offset(x, y), Offset(x, y + dy * arm), thickness, StrokeCap.Round)
    }
    bracket(left, top, 1f, 1f)
    bracket(right, top, -1f, 1f)
    bracket(left, bottom, 1f, -1f)
    bracket(right, bottom, -1f, -1f)
}

@Composable
private fun HintPill() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Surface)
            .border(1.dp, Border, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Pinch,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(R.string.wallpaper_hint),
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * One card: target tabs, aspect/preview options, and the primary action, so the
 * picker reads as a single tool instead of a stack of chip rows.
 */
@Composable
private fun WallpaperControlPanel(
    target: WallpaperTarget,
    orientation: WallpaperOrientation,
    enabled: Boolean,
    saving: Boolean,
    saveFailure: String?,
    onTargetChange: (WallpaperTarget) -> Unit,
    onOrientationChange: (WallpaperOrientation) -> Unit,
    onPreview: () -> Unit,
    onSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(PanelShape)
            .background(Surface)
            .border(1.dp, Border, PanelShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TargetTabs(
            target = target,
            enabled = enabled,
            onChange = onTargetChange,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PanelOption(
                icon = Icons.Outlined.AspectRatio,
                label = stringResource(
                    R.string.wallpaper_aspect,
                    stringResource(orientation.labelRes),
                ),
                enabled = enabled,
                onClick = {
                    onOrientationChange(
                        if (orientation == WallpaperOrientation.Portrait) {
                            WallpaperOrientation.Landscape
                        } else {
                            WallpaperOrientation.Portrait
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            )
            PanelOption(
                icon = Icons.Outlined.Visibility,
                label = stringResource(R.string.wallpaper_preview),
                enabled = enabled,
                onClick = onPreview,
                modifier = Modifier.weight(1f),
            )
        }

        // The reason line is deliberately small and muted: it is there so a
        // device-only failure (OOM, denied permission, OEM lock screen refusing
        // the bitmap) can be reported instead of guessed at.
        AnimatedVisibility(
            visible = saveFailure != null,
            enter = fadeIn(tween(160)) +
                expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) +
                shrinkVertically(tween(160, easing = FastOutSlowInEasing)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.wallpaper_set_failed),
                    color = DeleteRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                val reason = saveFailure
                if (!reason.isNullOrBlank()) {
                    Text(
                        text = reason,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SetWallpaperButton(
            enabled = enabled,
            saving = saving,
            onClick = onSet,
        )
    }
}

@Composable
private fun TargetTabs(
    target: WallpaperTarget,
    enabled: Boolean,
    onChange: (WallpaperTarget) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TrackShape)
            .background(EmptySurface)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        val gap = 4.dp
        val segmentWidth = ((maxWidth - gap) / 2).coerceAtLeast(0.dp)
        val index = WallpaperTargets.indexOfFirst { it.target == target }.coerceAtLeast(0)
        // One pill slides between the tabs instead of each tab fading its own
        // background, so a switch reads as a single movement.
        val indicatorX by animateDpAsState(
            targetValue = (segmentWidth + gap) * index,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            label = "tabIndicator",
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorX)
                .width(segmentWidth)
                .height(36.dp)
                .clip(TabShape)
                .background(Border),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            WallpaperTargets.forEach { option ->
                val selected = option.target == target
                val content by animateColorAsState(
                    targetValue = when {
                        selected -> TextPrimary
                        enabled -> TextMuted
                        else -> TextMuted.copy(alpha = 0.45f)
                    },
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "tabContent",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(TabShape)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onChange(option.target) },
                            indication = null,
                            interactionSource = null,
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(option.labelRes),
                        color = content,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Press-down scale, driven by the same interaction source as the clickable. */
@Composable
private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun PanelOption(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content by animateColorAsState(
        targetValue = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.45f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "optionContent",
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(40.dp)
            .pressScale(interaction)
            .clip(OptionShape)
            .background(EmptySurface)
            .border(1.dp, Border, OptionShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                indication = null,
                interactionSource = interaction,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) TextMuted else TextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = content,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SetWallpaperButton(
    enabled: Boolean,
    saving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (enabled) TextPrimary else EmptySurface,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "setContainer",
    )
    val content by animateColorAsState(
        targetValue = if (enabled) Surface else TextMuted,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "setContent",
    )
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .pressScale(interaction)
            .clip(OptionShape)
            .clickable(
                enabled = enabled && !saving,
                role = Role.Button,
                onClick = onClick,
                indication = null,
                interactionSource = interaction,
            ),
        shape = OptionShape,
        color = container,
        border = if (enabled) null else BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.wallpaper_set),
                color = content,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
