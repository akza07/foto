package fool.akza.foto.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.CropRegion
import fool.akza.foto.data.CropSaveResult
import fool.akza.foto.data.ImageCropper
import fool.akza.foto.data.MediaItem
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val CropSheetShape = RoundedCornerShape(16.dp)

/** Preset crop ratios. `ratio` is output width / output height. */
private enum class CropAspect(val labelRes: Int, val ratio: Float?) {
    Free(R.string.crop_aspect_free, null),
    Square(R.string.crop_aspect_square, 1f),
    Portrait(R.string.crop_aspect_portrait, 4f / 5f),
    Wide(R.string.crop_aspect_wide, 16f / 9f),
}

private enum class CropDrag {
    Move,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    Left,
    Top,
    Right,
    Bottom,
}

/**
 * Full-screen crop editor for one image.
 *
 * The crop rectangle is kept in normalized image coordinates (0..1), so it is
 * independent of the on-screen display size and survives layout changes. The
 * source bitmap is decoded upright (EXIF applied) and downscaled to
 * [ImageCropper.MAX_EDIT_DIMENSION]; saving writes a new copy and never edits
 * the original.
 */
@Composable
internal fun CropScreen(
    item: MediaItem,
    onDismiss: () -> Unit,
    onSaved: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val bitmapState = remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(item.uri) { mutableStateOf(false) }
    val cropState = remember(item.uri) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val aspectState = remember(item.uri) { mutableStateOf(CropAspect.Free) }
    var saving by remember(item.uri) { mutableStateOf(false) }
    var saveFailed by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val loaded = ImageCropper.loadEditableBitmap(context, item.uri)
        bitmapState.value = loaded
        loadFailed = loaded == null
    }

    BackHandler(enabled = !saving) { onDismiss() }

    val bitmap = bitmapState.value
    val crop = cropState.value
    val aspect = aspectState.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack)
            // Own the pointer stream for the whole screen: without this, taps on
            // empty chrome areas would fall through to the viewer underneath
            // (toggling its chrome or dismissing it while the editor is open).
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
            CropTopBar(
                enabled = !saving,
                canReset = bitmap != null,
                onDismiss = onDismiss,
                onReset = {
                    aspectState.value = CropAspect.Free
                    cropState.value = Rect(0f, 0f, 1f, 1f)
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
                    bitmap != null -> CropCanvas(
                        bitmap = bitmap,
                        crop = crop,
                        aspect = aspect,
                        onCropChange = { cropState.value = it },
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

            CropControls(
                aspect = aspect,
                enabled = bitmap != null && !saving,
                saving = saving,
                saveFailed = saveFailed,
                onAspectChange = {
                    aspectState.value = it
                    bitmapState.value?.let { source ->
                        cropState.value = fitAspect(
                            aspect = it,
                            imageWidth = source.width,
                            imageHeight = source.height,
                        )
                    }
                    saveFailed = false
                },
                onCancel = onDismiss,
                onSave = save@{
                    val source = bitmapState.value ?: return@save
                    if (saving) return@save
                    saving = true
                    saveFailed = false
                    scope.launch {
                        val result = ImageCropper.saveCroppedCopy(
                            context = context,
                            original = item,
                            source = source,
                            region = crop.toPixelRegion(source),
                        )
                        saving = false
                        when (result) {
                            is CropSaveResult.Saved -> onSaved(result.item)
                            is CropSaveResult.Failed -> saveFailed = true
                        }
                    }
                },
            )
        }

        BusyOverlay(
            visible = saving,
            message = stringResource(R.string.crop_saving),
        )
    }
}

@Composable
private fun CropTopBar(
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
            text = stringResource(R.string.crop_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReset, enabled = enabled && canReset) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.crop_reset),
                tint = if (enabled && canReset) TextPrimary else TextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    crop: Rect,
    aspect: CropAspect,
    onCropChange: (Rect) -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val displayRect = remember(viewSize, bitmap) {
        fitDisplayRect(viewSize, bitmap.width, bitmap.height)
    }
    val latestDisplayRect = rememberUpdatedState(displayRect)
    val latestAspect = rememberUpdatedState(aspect)
    val latestCrop = rememberUpdatedState(crop)
    val latestOnCropChange = rememberUpdatedState(onCropChange)
    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.crop_title),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val display = latestDisplayRect.value
                        if (display.width <= 0f || display.height <= 0f) {
                            return@awaitEachGesture
                        }
                        val viewCrop = latestCrop.value.toView(display)
                        val mode = hitTest(
                            position = down.position,
                            crop = viewCrop,
                            allowEdges = latestAspect.value.ratio == null,
                            tolerance = 28.dp.toPx(),
                        ) ?: return@awaitEachGesture

                        val startCrop = latestCrop.value
                        val startAspect = latestAspect.value
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - down.position
                            val updated = dragCrop(
                                start = startCrop,
                                mode = mode,
                                deltaX = delta.x / display.width,
                                deltaY = delta.y / display.height,
                                aspect = startAspect,
                                imageAspect = imageAspect,
                                minSize = 0.05f,
                            )
                            latestOnCropChange.value(updated)
                            change.consume()
                        }
                    }
                },
        ) {
            if (displayRect.width <= 0f || displayRect.height <= 0f) return@Canvas
            drawCropOverlay(
                display = displayRect,
                crop = crop.toView(displayRect),
                showEdges = aspect.ratio == null,
            )
        }
    }
}

@Composable
private fun CropControls(
    aspect: CropAspect,
    enabled: Boolean,
    saving: Boolean,
    saveFailed: Boolean,
    onAspectChange: (CropAspect) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
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
            CropAspect.entries.forEach { option ->
                AspectChip(
                    label = stringResource(option.labelRes),
                    selected = option == aspect,
                    enabled = enabled,
                    onClick = { onAspectChange(option) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.crop_hint),
            color = TextMuted,
            fontSize = 12.sp,
        )
        if (saveFailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.crop_save_failed),
                color = DeleteRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CropSheetShape)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onCancel,
                        indication = null,
                        interactionSource = null,
                    ),
                shape = CropSheetShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, Border),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = if (enabled) TextPrimary else TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CropSheetShape)
                    .clickable(
                        enabled = enabled && !saving,
                        role = Role.Button,
                        onClick = onSave,
                        indication = null,
                        interactionSource = null,
                    ),
                shape = CropSheetShape,
                color = if (enabled) TextPrimary else ThemeSurface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.crop_save_copy),
                        color = if (enabled) AmoledBlack else TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.crop_original_kept),
            color = TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AspectChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                indication = null,
                interactionSource = null,
            ),
        shape = CircleShape,
        color = if (selected) TextPrimary else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) TextPrimary else Border),
    ) {
        Text(
            text = label,
            color = when {
                selected -> AmoledBlack
                enabled -> TextPrimary
                else -> TextMuted
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Fit the image inside the available canvas, centered. */
private fun fitDisplayRect(size: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (size.width <= 0 || size.height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return Rect.Zero
    }
    val scale = min(
        size.width.toFloat() / imageWidth.toFloat(),
        size.height.toFloat() / imageHeight.toFloat(),
    )
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun Rect.toView(display: Rect): Rect = Rect(
    left = display.left + left * display.width,
    top = display.top + top * display.height,
    right = display.left + right * display.width,
    bottom = display.top + bottom * display.height,
)

private fun Rect.toPixelRegion(source: Bitmap): CropRegion {
    val x = (left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val y = (top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val right = (right * source.width).roundToInt().coerceIn(x + 1, source.width)
    val bottom = (bottom * source.height).roundToInt().coerceIn(y + 1, source.height)
    return CropRegion(left = x, top = y, width = right - x, height = bottom - y)
}

/** Centered, maximum-size rectangle with [aspect]'s output ratio. */
private fun fitAspect(aspect: CropAspect, imageWidth: Int, imageHeight: Int): Rect {
    val ratio = aspect.ratio ?: return Rect(0f, 0f, 1f, 1f)
    if (imageWidth <= 0 || imageHeight <= 0) return Rect(0f, 0f, 1f, 1f)
    val normalized = ratio * imageHeight.toFloat() / imageWidth.toFloat()
    return if (normalized >= 1f) {
        val height = 1f / normalized
        Rect(0f, (1f - height) / 2f, 1f, (1f + height) / 2f)
    } else {
        val width = normalized
        Rect((1f - width) / 2f, 0f, (1f + width) / 2f, 1f)
    }
}

private fun hitTest(
    position: Offset,
    crop: Rect,
    allowEdges: Boolean,
    tolerance: Float,
): CropDrag? {
    val corners = listOf(
        CropDrag.TopLeft to Offset(crop.left, crop.top),
        CropDrag.TopRight to Offset(crop.right, crop.top),
        CropDrag.BottomLeft to Offset(crop.left, crop.bottom),
        CropDrag.BottomRight to Offset(crop.right, crop.bottom),
    )
    corners.firstOrNull { (_, point) -> (position - point).getDistance() <= tolerance }
        ?.let { return it.first }

    if (allowEdges) {
        if (abs(position.y - crop.top) <= tolerance && position.x in crop.left..crop.right) {
            return CropDrag.Top
        }
        if (abs(position.y - crop.bottom) <= tolerance && position.x in crop.left..crop.right) {
            return CropDrag.Bottom
        }
        if (abs(position.x - crop.left) <= tolerance && position.y in crop.top..crop.bottom) {
            return CropDrag.Left
        }
        if (abs(position.x - crop.right) <= tolerance && position.y in crop.top..crop.bottom) {
            return CropDrag.Right
        }
    }

    return if (position.x in crop.left..crop.right && position.y in crop.top..crop.bottom) {
        CropDrag.Move
    } else {
        null
    }
}

/**
 * Applies one drag delta to [start]. Freeform drags move a single edge; fixed
 * ratios are maintained from the opposite corner so the crop never distorts.
 */
private fun dragCrop(
    start: Rect,
    mode: CropDrag,
    deltaX: Float,
    deltaY: Float,
    aspect: CropAspect,
    imageAspect: Float,
    minSize: Float,
): Rect {
    if (mode == CropDrag.Move) {
        val width = start.width
        val height = start.height
        val left = (start.left + deltaX).coerceIn(0f, 1f - width)
        val top = (start.top + deltaY).coerceIn(0f, 1f - height)
        return Rect(left, top, left + width, top + height)
    }

    val ratio = aspect.ratio
    if (ratio == null) {
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        when (mode) {
            CropDrag.Left, CropDrag.TopLeft, CropDrag.BottomLeft -> left += deltaX
            CropDrag.Right, CropDrag.TopRight, CropDrag.BottomRight -> right += deltaX
            else -> Unit
        }
        when (mode) {
            CropDrag.Top, CropDrag.TopLeft, CropDrag.TopRight -> top += deltaY
            CropDrag.Bottom, CropDrag.BottomLeft, CropDrag.BottomRight -> bottom += deltaY
            else -> Unit
        }
        left = left.coerceIn(0f, 1f - minSize)
        right = right.coerceIn(minSize, 1f)
        top = top.coerceIn(0f, 1f - minSize)
        bottom = bottom.coerceIn(minSize, 1f)
        if (right - left < minSize) {
            if (mode.isLeftHandle()) left = right - minSize else right = left + minSize
        }
        if (bottom - top < minSize) {
            if (mode.isTopHandle()) top = bottom - minSize else bottom = top + minSize
        }
        return Rect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        )
    }

    // Fixed ratio: anchor the opposite corner and derive both dimensions from it.
    val normalizedRatio = ratio / imageAspect
    val anchorX = if (mode.isRightHandle()) start.left else start.right
    val anchorY = if (mode.isBottomHandle()) start.top else start.bottom
    val signX = if (mode.isRightHandle()) 1f else -1f
    val signY = if (mode.isBottomHandle()) 1f else -1f
    val candidateWidth = start.width + signX * deltaX
    val candidateHeight = start.height + signY * deltaY
    val maxWidth = if (signX > 0f) 1f - anchorX else anchorX
    val maxHeight = if (signY > 0f) 1f - anchorY else anchorY
    val limit = min(maxWidth, maxHeight * normalizedRatio).coerceAtLeast(0.0001f)
    val width = max(candidateWidth, candidateHeight * normalizedRatio)
        .coerceIn(min(minSize, limit), limit)
    val height = width / normalizedRatio
    val left = if (signX > 0f) anchorX else anchorX - width
    val top = if (signY > 0f) anchorY else anchorY - height
    return Rect(left, top, left + width, top + height)
}

private fun CropDrag.isLeftHandle(): Boolean =
    this == CropDrag.Left || this == CropDrag.TopLeft || this == CropDrag.BottomLeft

private fun CropDrag.isRightHandle(): Boolean =
    this == CropDrag.Right || this == CropDrag.TopRight || this == CropDrag.BottomRight

private fun CropDrag.isTopHandle(): Boolean =
    this == CropDrag.Top || this == CropDrag.TopLeft || this == CropDrag.TopRight

private fun CropDrag.isBottomHandle(): Boolean =
    this == CropDrag.Bottom || this == CropDrag.BottomLeft || this == CropDrag.BottomRight

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(
    display: Rect,
    crop: Rect,
    showEdges: Boolean,
) {
    val scrim = AmoledBlack.copy(alpha = 0.58f)
    if (crop.top > display.top) {
        drawRect(
            color = scrim,
            topLeft = Offset(display.left, display.top),
            size = Size(display.width, crop.top - display.top),
        )
    }
    if (crop.bottom < display.bottom) {
        drawRect(
            color = scrim,
            topLeft = Offset(display.left, crop.bottom),
            size = Size(display.width, display.bottom - crop.bottom),
        )
    }
    if (crop.left > display.left) {
        drawRect(
            color = scrim,
            topLeft = Offset(display.left, crop.top),
            size = Size(crop.left - display.left, crop.height),
        )
    }
    if (crop.right < display.right) {
        drawRect(
            color = scrim,
            topLeft = Offset(crop.right, crop.top),
            size = Size(display.right - crop.right, crop.height),
        )
    }

    val borderWidth = 1.5.dp.toPx()
    drawRect(
        color = TextPrimary,
        topLeft = Offset(crop.left, crop.top),
        size = Size(crop.width, crop.height),
        style = Stroke(width = borderWidth),
    )

    val grid = TextPrimary.copy(alpha = 0.35f)
    val gridWidth = 1.dp.toPx()
    for (i in 1..2) {
        val x = crop.left + crop.width * i / 3f
        drawLine(grid, Offset(x, crop.top), Offset(x, crop.bottom), gridWidth)
        val y = crop.top + crop.height * i / 3f
        drawLine(grid, Offset(crop.left, y), Offset(crop.right, y), gridWidth)
    }

    val handleLength = 20.dp.toPx()
    val handleWidth = 3.dp.toPx()
    val handleColor = TextPrimary
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(handleColor, Offset(x, y), Offset(x + dx, y), handleWidth)
        drawLine(handleColor, Offset(x, y), Offset(x, y + dy), handleWidth)
    }
    corner(crop.left, crop.top, handleLength, handleLength)
    corner(crop.right, crop.top, -handleLength, handleLength)
    corner(crop.left, crop.bottom, handleLength, -handleLength)
    corner(crop.right, crop.bottom, -handleLength, -handleLength)

    if (showEdges) {
        val edgeLength = 22.dp.toPx()
        val half = edgeLength / 2f
        val centerX = (crop.left + crop.right) / 2f
        val centerY = (crop.top + crop.bottom) / 2f
        drawLine(
            handleColor,
            Offset(centerX - half, crop.top),
            Offset(centerX + half, crop.top),
            handleWidth,
        )
        drawLine(
            handleColor,
            Offset(centerX - half, crop.bottom),
            Offset(centerX + half, crop.bottom),
            handleWidth,
        )
        drawLine(
            handleColor,
            Offset(crop.left, centerY - half),
            Offset(crop.left, centerY + half),
            handleWidth,
        )
        drawLine(
            handleColor,
            Offset(crop.right, centerY - half),
            Offset(crop.right, centerY + half),
            handleWidth,
        )
    }
}
