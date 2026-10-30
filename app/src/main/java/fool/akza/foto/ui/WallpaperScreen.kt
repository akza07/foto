package fool.akza.foto.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val WallpaperButtonShape = RoundedCornerShape(16.dp)

/** The orientation of the wallpaper frame, independent of the device's rotation. */
private enum class WallpaperOrientation(val labelRes: Int) {
    Portrait(R.string.wallpaper_orientation_portrait),
    Landscape(R.string.wallpaper_orientation_landscape),
}

@StringRes
private fun WallpaperTarget.labelRes(): Int = when (this) {
    WallpaperTarget.Home -> R.string.wallpaper_target_home
    WallpaperTarget.Lock -> R.string.wallpaper_target_lock
    WallpaperTarget.Both -> R.string.wallpaper_target_both
}

/**
 * Full-screen wallpaper picker for one image.
 *
 * The crop is a fixed-aspect window over the upright source bitmap: the frame
 * matches the screen in the chosen [WallpaperOrientation], and the user pans
 * and pinches to choose which part of the photo fills it. [WallpaperTarget]
 * selects the home screen, the lock screen or both. Nothing is written to
 * storage — the crop goes straight to the system wallpaper service.
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
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> WallpaperCanvas(
                        bitmap = bitmap,
                        crop = crop,
                        frameAspect = frameAspect,
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

            WallpaperControls(
                orientation = orientation,
                target = target,
                enabled = bitmap != null && !saving,
                saving = saving,
                saveFailed = saveFailed,
                onOrientationChange = {
                    orientation = it
                    saveFailed = false
                },
                onTargetChange = {
                    target = it
                    saveFailed = false
                },
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

@Composable
private fun WallpaperTopBar(
    enabled: Boolean,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.wallpaper_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReset, enabled = enabled && canReset) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.wallpaper_reset),
                tint = if (enabled && canReset) TextPrimary else TextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun WallpaperCanvas(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    frameAspect: Float,
    onCropChange: (NormalizedCrop) -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val display = remember(viewSize, bitmap) {
        fitDisplayRect(viewSize, bitmap.width, bitmap.height)
    }
    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val latestDisplay = rememberUpdatedState(display)
    val latestCrop = rememberUpdatedState(crop)
    val latestFrameAspect = rememberUpdatedState(frameAspect)
    val latestOnCropChange = rememberUpdatedState(onCropChange)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.wallpaper_title),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val d = latestDisplay.value
                        if (d.width <= 0f || d.height <= 0f) {
                            return@detectTransformGestures
                        }
                        latestOnCropChange.value(
                            transformWallpaperCrop(
                                crop = latestCrop.value,
                                frameAspect = latestFrameAspect.value,
                                imageAspect = imageAspect,
                                focusX = (centroid.x - d.left) / d.width,
                                focusY = (centroid.y - d.top) / d.height,
                                panX = pan.x / d.width,
                                panY = pan.y / d.height,
                                zoom = zoom,
                            ),
                        )
                    }
                },
        ) {
            if (display.width <= 0f || display.height <= 0f) return@Canvas
            drawCropOverlay(
                display = display,
                crop = crop.toView(display),
                showEdges = false,
            )
        }
    }
}

@Composable
private fun WallpaperControls(
    orientation: WallpaperOrientation,
    target: WallpaperTarget,
    enabled: Boolean,
    saving: Boolean,
    saveFailed: Boolean,
    onOrientationChange: (WallpaperOrientation) -> Unit,
    onTargetChange: (WallpaperTarget) -> Unit,
    onSet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            WallpaperOrientation.entries.forEach { option ->
                SelectChip(
                    label = stringResource(option.labelRes),
                    selected = option == orientation,
                    enabled = enabled,
                    onClick = { onOrientationChange(option) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            WallpaperTarget.entries.forEach { option ->
                SelectChip(
                    label = stringResource(option.labelRes()),
                    selected = option == target,
                    enabled = enabled,
                    onClick = { onTargetChange(option) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.wallpaper_hint),
            color = TextMuted,
            fontSize = 12.sp,
        )
        if (saveFailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.wallpaper_set_failed),
                color = DeleteRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(WallpaperButtonShape)
                .clickable(
                    enabled = enabled && !saving,
                    role = Role.Button,
                    onClick = onSet,
                    indication = null,
                    interactionSource = null,
                ),
            shape = WallpaperButtonShape,
            color = if (enabled) TextPrimary else ThemeSurface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.wallpaper_set),
                    color = if (enabled) AmoledBlack else TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Maps a normalized crop onto the on-screen image rect. */
private fun NormalizedCrop.toView(display: Rect): Rect = Rect(
    left = display.left + left * display.width,
    top = display.top + top * display.height,
    right = display.left + right * display.width,
    bottom = display.top + bottom * display.height,
)
