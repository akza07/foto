package fool.akza.foto.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.GridGoldenratio
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pinch
import androidx.compose.material.icons.outlined.RestartAlt
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PreviewShape = RoundedCornerShape(30.dp)
private val PanelShape = RoundedCornerShape(16.dp)
private val TrackShape = RoundedCornerShape(12.dp)
private val OptionShape = RoundedCornerShape(10.dp)
private val PrimaryShape = RoundedCornerShape(12.dp)

/** Panel surfaces, one step above the pure-black page background. */
private val PanelColor = Color(0xFF1C1B1D)
private val PanelTrack = Color(0xFF0E0E10)
private val PanelRaised = Color(0xFF2A2A2C)

/** Target tabs, ordered the way the picker presents them. */
private val WallpaperTargets = listOf(
    WallpaperTarget.Lock,
    WallpaperTarget.Home,
    WallpaperTarget.Both,
)

/** The orientation of the wallpaper frame, independent of the device's rotation. */
private enum class WallpaperOrientation(val labelRes: Int) {
    Portrait(R.string.wallpaper_orientation_portrait),
    Landscape(R.string.wallpaper_orientation_landscape),
}

private val WallpaperTarget.tabLabelRes: Int
    get() = when (this) {
        WallpaperTarget.Home -> R.string.wallpaper_target_home
        WallpaperTarget.Lock -> R.string.wallpaper_target_lock
        WallpaperTarget.Both -> R.string.wallpaper_target_both
    }

private val WallpaperTarget.tabIcon: ImageVector
    get() = when (this) {
        WallpaperTarget.Home -> Icons.Outlined.GridView
        WallpaperTarget.Lock -> Icons.Outlined.Lock
        WallpaperTarget.Both -> Icons.Outlined.Devices
    }

/**
 * Full-screen wallpaper picker for one image.
 *
 * The preview is a device-shaped frame filled with the crop: the frame takes the
 * aspect of the screen in the chosen [WallpaperOrientation], and the user pans
 * and pinches to choose which part of the photo fills it. A simulated lock/home
 * screen sits on top so the [WallpaperTarget] choice is visible before it is
 * applied. Nothing is written to storage — the crop goes straight to the system
 * wallpaper service.
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

    val bitmapState = remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(item.uri) { mutableStateOf(false) }
    var orientation by remember(item.uri) { mutableStateOf(WallpaperOrientation.Portrait) }
    var target by remember(item.uri) { mutableStateOf(WallpaperTarget.Home) }
    var crop by remember(item.uri) { mutableStateOf(NormalizedCrop(0f, 0f, 1f, 1f)) }
    var gridVisible by remember(item.uri) { mutableStateOf(true) }
    var saving by remember(item.uri) { mutableStateOf(false) }
    var saveFailed by remember(item.uri) { mutableStateOf(false) }

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

    LaunchedEffect(item.uri) {
        val loaded = ImageCropper.loadEditableBitmap(context, item.uri)
        bitmapState.value = loaded
        loadFailed = loaded == null
    }

    // A crop fitted to one frame shape can't be clamped into another, so refit
    // whenever the orientation (or the decoded image) changes.
    LaunchedEffect(bitmapState.value, frameAspect) {
        val source = bitmapState.value ?: return@LaunchedEffect
        crop = fitWallpaperCrop(frameAspect, source.width.toFloat() / source.height.toFloat())
        saveFailed = false
    }

    BackHandler(enabled = !saving) { onDismiss() }

    val bitmap = bitmapState.value
    val enabled = bitmap != null && !saving

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
                .navigationBarsPadding(),
        ) {
            WallpaperTopBar(
                enabled = !saving,
                canReset = bitmap != null,
                onDismiss = onDismiss,
                onReset = {
                    bitmap?.let { source ->
                        crop = fitWallpaperCrop(
                            frameAspect,
                            source.width.toFloat() / source.height.toFloat(),
                        )
                    }
                    saveFailed = false
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> WallpaperPreview(
                        bitmap = bitmap,
                        crop = crop,
                        frameAspect = frameAspect,
                        target = target,
                        gridVisible = gridVisible,
                        onCropChange = {
                            crop = it
                            saveFailed = false
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

            WallpaperControlPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                target = target,
                orientation = orientation,
                gridVisible = gridVisible,
                enabled = enabled,
                saving = saving,
                saveFailed = saveFailed,
                onTargetChange = {
                    target = it
                    saveFailed = false
                },
                onOrientationChange = {
                    orientation = it
                    saveFailed = false
                },
                onToggleGrid = { gridVisible = !gridVisible },
                onSet = set@{
                    val source = bitmapState.value ?: return@set
                    if (saving) return@set
                    saving = true
                    saveFailed = false
                    scope.launch {
                        val result = WallpaperSetter.apply(
                            context = context,
                            source = source,
                            region = crop.toCropRegion(source.width, source.height),
                            target = target,
                        )
                        saving = false
                        when (result) {
                            WallpaperResult.Applied -> onApplied()
                            is WallpaperResult.Failed -> saveFailed = true
                        }
                    }
                },
            )
        }

        BusyOverlay(
            visible = saving,
            message = stringResource(R.string.wallpaper_setting),
        )
    }
}

/**
 * Cancel on the left, editor title in the middle, reset on the right. The title
 * stays optically centred because the side slots share one weight.
 */
@Composable
private fun WallpaperTopBar(
    enabled: Boolean,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
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
                .clip(RoundedCornerShape(10.dp))
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
                color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            text = stringResource(R.string.wallpaper_title),
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = enabled && canReset,
                    role = Role.Button,
                    onClick = onReset,
                    indication = null,
                    interactionSource = null,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.RestartAlt,
                contentDescription = stringResource(R.string.wallpaper_reset),
                tint = when {
                    !enabled -> TextMuted.copy(alpha = 0.3f)
                    canReset -> TextPrimary
                    else -> TextMuted.copy(alpha = 0.45f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Device-shaped preview: the crop is drawn straight into the frame, so what the
 * frame shows is exactly what gets applied. The simulated lock/home screen and
 * the crop grid are painted on top; the hint sits under the frame.
 */
@Composable
private fun WallpaperPreview(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    frameAspect: Float,
    target: WallpaperTarget,
    gridVisible: Boolean,
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
                    .border(1.dp, Color.White.copy(alpha = 0.10f), PreviewShape),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, frameAspect) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val current = latestCrop.value
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w <= 0f || h <= 0f) return@detectTransformGestures
                                // The image moves with the finger, so the crop
                                // window moves against it (hence the negated pan).
                                latestOnCropChange.value(
                                    transformWallpaperCrop(
                                        crop = current,
                                        frameAspect = latestFrameAspect.value,
                                        imageAspect = imageAspect,
                                        focusX = current.left + centroid.x / w * current.width,
                                        focusY = current.top + centroid.y / h * current.height,
                                        panX = -pan.x * current.width / w,
                                        panY = -pan.y * current.height / h,
                                        zoom = zoom,
                                    ),
                                )
                            }
                        },
                ) {
                    drawWallpaperCrop(image, bitmap.width, bitmap.height, crop)
                    drawPreviewChrome(gridVisible)
                }

                WallpaperHud(
                    target = target,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            }

            Spacer(Modifier.height(gap))
            HintPill()
        }
    }
}

/** Draws exactly the crop region, scaled to fill the preview frame. */
private fun DrawScope.drawWallpaperCrop(
    image: ImageBitmap,
    imageWidth: Int,
    imageHeight: Int,
    crop: NormalizedCrop,
) {
    if (imageWidth <= 0 || imageHeight <= 0) return
    val dstWidth = size.width.roundToInt()
    val dstHeight = size.height.roundToInt()
    if (dstWidth <= 0 || dstHeight <= 0) return

    val srcX = (crop.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
    val srcY = (crop.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
    val srcWidth = (crop.width * imageWidth).roundToInt().coerceIn(1, imageWidth - srcX)
    val srcHeight = (crop.height * imageHeight).roundToInt().coerceIn(1, imageHeight - srcY)

    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(dstWidth, dstHeight),
        filterQuality = FilterQuality.High,
    )
}

/** Crop grid (thirds + corner brackets) and the HUD legibility vignette. */
private fun DrawScope.drawPreviewChrome(gridVisible: Boolean) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.32f),
            0.28f to Color.Transparent,
            0.72f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.32f),
        ),
    )
    if (!gridVisible) return

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

/**
 * Simulated screen content: a clock for the lock screen, a faint app grid and
 * dock for the home screen, and both (home dimmed) for the pair.
 */
@Composable
private fun WallpaperHud(
    target: WallpaperTarget,
    frameWidth: Dp,
    frameHeight: Dp,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (target != WallpaperTarget.Lock) {
            HomeScreenHud(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (target == WallpaperTarget.Both) 0.35f else 1f),
            )
        }
        if (target != WallpaperTarget.Home) {
            LockScreenHud(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun LockScreenHud(
    frameWidth: Dp,
    frameHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val now = remember { LocalDateTime.now() }
    val time = remember(now) { now.format(DateTimeFormatter.ofPattern("HH:mm")) }
    val date = remember(now) {
        now.format(DateTimeFormatter.ofPattern("EEE, MMM d")).uppercase()
    }
    // A landscape frame is short: keep the clock proportional to its height and
    // drop the date so the mock status block never crowds the photo.
    val compact = frameHeight < 280.dp
    val clockSize = (frameHeight.value * 0.15f).coerceIn(15f, 44f).sp

    Column(
        modifier = modifier.padding(top = if (compact) 10.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = date,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
            }
        }
        Text(
            text = time,
            color = Color.White,
            fontSize = clockSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
    }
}

@Composable
private fun HomeScreenHud(
    frameWidth: Dp,
    frameHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val tile = (min(frameWidth.value, frameHeight.value) * 0.105f).coerceIn(12f, 30f).dp
    val gap = tile * 0.30f
    val edge = tile * 0.55f
    // A short (landscape) frame has no room for an icon grid, so only the dock
    // is left as a hint of the home screen.
    val compact = frameHeight < 280.dp

    Column(
        modifier = modifier.padding(
            horizontal = edge,
            // Clear the 30dp preview corners so the dock is never clipped.
            vertical = if (compact) 16.dp else edge,
        ),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (!compact) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        repeat(4) { AppTile(tile, Color.White.copy(alpha = 0.16f)) }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(0.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tile * 0.62f))
                .background(Color.Black.copy(alpha = 0.26f))
                .padding(tile * 0.24f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(4) { AppTile(tile, Color.White.copy(alpha = 0.24f)) }
        }
    }
}

@Composable
private fun AppTile(size: Dp, color: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(color),
    )
}

@Composable
private fun HintPill() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(PanelColor)
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
 * One raised card: target tabs, the aspect/grid options, and the primary action,
 * so the picker reads as a single tool instead of a stack of chip rows.
 */
@Composable
private fun WallpaperControlPanel(
    target: WallpaperTarget,
    orientation: WallpaperOrientation,
    gridVisible: Boolean,
    enabled: Boolean,
    saving: Boolean,
    saveFailed: Boolean,
    onTargetChange: (WallpaperTarget) -> Unit,
    onOrientationChange: (WallpaperOrientation) -> Unit,
    onToggleGrid: () -> Unit,
    onSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(PanelShape)
            .background(PanelColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                // The label shows the current state, so it always reads as active.
                active = true,
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
                icon = Icons.Outlined.GridGoldenratio,
                label = null,
                active = gridVisible,
                enabled = enabled,
                onClick = onToggleGrid,
                contentDescription = stringResource(R.string.wallpaper_toggle_grid),
                modifier = Modifier.width(40.dp),
            )
        }

        if (saveFailed) {
            Text(
                text = stringResource(R.string.wallpaper_set_failed),
                color = DeleteRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TrackShape)
            .background(PanelTrack)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WallpaperTargets.forEach { option ->
            val selected = option == target
            val background by animateColorAsState(
                targetValue = if (selected) PanelRaised else Color.Transparent,
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "tabBackground",
            )
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
                    .height(34.dp)
                    .clip(OptionShape)
                    .background(background)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { onChange(option) },
                        indication = null,
                        interactionSource = null,
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = option.tabIcon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(option.tabLabelRes),
                    color = content,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PanelOption(
    icon: ImageVector,
    label: String?,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> TextMuted.copy(alpha = 0.45f)
            active -> TextPrimary
            else -> TextMuted
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "optionContent",
    )
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(OptionShape)
            .background(PanelRaised)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                indication = null,
                interactionSource = null,
            )
            .padding(horizontal = if (label == null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = content,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
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
        targetValue = if (enabled) TextPrimary else PanelRaised,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "setContainer",
    )
    val content by animateColorAsState(
        targetValue = if (enabled) AmoledBlack else TextMuted,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "setContent",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(PrimaryShape)
            .clickable(
                enabled = enabled && !saving,
                role = Role.Button,
                onClick = onClick,
                indication = null,
                interactionSource = null,
            ),
        shape = PrimaryShape,
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
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.wallpaper_set),
                color = content,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
