package com.phouton.phooto.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phouton.phooto.R
import com.phouton.phooto.ui.theme.DeleteRed
import com.phouton.phooto.ui.theme.TextPrimary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Actions offered by the viewer's long-press radial menu. */
internal enum class ViewerAction { Delete, Info, Share, Crop }

private fun ViewerAction.icon(): ImageVector = when (this) {
    ViewerAction.Delete -> Icons.Outlined.Delete
    ViewerAction.Info -> Icons.Outlined.Info
    ViewerAction.Share -> Icons.Outlined.Share
    ViewerAction.Crop -> Icons.Outlined.Crop
}

@StringRes
private fun ViewerAction.labelRes(): Int = when (this) {
    ViewerAction.Delete -> R.string.action_delete
    ViewerAction.Info -> R.string.info_title
    ViewerAction.Share -> R.string.action_share
    ViewerAction.Crop -> R.string.action_crop
}

private val WheelRadius = 132.dp
private val HubRadius = 38.dp
private val IconSize = 26.dp
private val IconRadiusFraction = 0.62f
private val SectorColumnWidth = 78.dp

private val RimColor = Color.White.copy(alpha = 0.20f)
private val SpokeColor = Color.White.copy(alpha = 0.16f)
private val HoverTint = Color.White.copy(alpha = 0.14f)

private fun wheelOuterPx(density: Density): Float = with(density) { WheelRadius.toPx() }

/** Dimming distance for the background image while the wheel is open. */
internal const val RADIAL_BLUR_DP = 16f

/** Clamps a long-press point so the whole wheel stays on-screen. */
internal fun clampRadialCenter(press: Offset, containerSize: IntSize, density: Density): Offset {
    val outer = wheelOuterPx(density)
    val edge = with(density) { 8.dp.toPx() }
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    if (w < outer * 2f + edge * 2f || h < outer * 2f + edge * 2f) {
        return Offset(w / 2f, h / 2f)
    }
    return Offset(
        press.x.coerceIn(outer + edge, w - outer - edge),
        press.y.coerceIn(outer + edge, h - outer - edge),
    )
}

/** The action under [position] for a wheel centred at [center], or null to cancel. */
internal fun radialActionAt(position: Offset, center: Offset, density: Density): ViewerAction? {
    val d = position - center
    val dist = d.getDistance()
    val outer = wheelOuterPx(density)
    val cancel = with(density) { HubRadius.toPx() }
    if (dist < cancel || dist > outer) return null
    val deg = atan2(d.y.toDouble(), d.x.toDouble()) * 180.0 / Math.PI
    val norm = ((deg.toFloat() + 90f) % 360f + 360f) % 360f
    return ViewerAction.entries[(norm / 90f).roundToInt() % ViewerAction.entries.size]
}

/**
 * Glassy radial menu drawn at [center]. [progress] (0..1, spring-driven) scales and
 * fades the whole wheel so it blooms in and out of the press point.
 */
@Composable
internal fun ViewerActionWheel(
    center: Offset,
    hovered: ViewerAction?,
    containerSize: IntSize,
    density: Density,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (containerSize == IntSize.Zero) return

    val p = progress.coerceIn(0f, 1.2f)
    val outer = with(density) { (WheelRadius * p).toPx() }
    val hub = with(density) { (HubRadius * p).toPx() }
    val iconRadius = outer * IconRadiusFraction
    val iconHalf = with(density) { IconSize.toPx() } / 2f
    val columnHalf = with(density) { SectorColumnWidth.toPx() } / 2f
    val rim = with(density) { 1.dp.toPx() }
    val hoveredIndex = hovered?.let { ViewerAction.entries.indexOf(it) } ?: -1

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Glassy disc: a touch lighter in the middle.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.34f),
                    ),
                    center = center,
                    radius = outer.coerceAtLeast(1f),
                ),
                radius = outer,
                center = center,
            )

            // Highlighted wedge.
            if (hoveredIndex >= 0) {
                val start = -135f + hoveredIndex * 90f
                val wedge = Path().apply {
                    moveTo(center.x, center.y)
                    arcTo(
                        Rect(center.x - outer, center.y - outer, center.x + outer, center.y + outer),
                        start,
                        90f,
                        false,
                    )
                    close()
                }
                drawPath(
                    path = wedge,
                    color = if (hovered == ViewerAction.Delete) {
                        DeleteRed.copy(alpha = 0.85f)
                    } else {
                        HoverTint
                    },
                )
            }

            // Sector dividers.
            for (i in 0 until ViewerAction.entries.size) {
                val a = Math.toRadians((-135f + i * 90f).toDouble())
                drawLine(
                    color = SpokeColor,
                    start = Offset(
                        center.x + (cos(a) * hub).toFloat(),
                        center.y + (sin(a) * hub).toFloat(),
                    ),
                    end = Offset(
                        center.x + (cos(a) * outer).toFloat(),
                        center.y + (sin(a) * outer).toFloat(),
                    ),
                    strokeWidth = rim,
                )
            }

            // Outer rim + centre hub.
            drawCircle(color = RimColor, radius = outer, center = center, style = Stroke(rim))
            drawCircle(
                color = Color.Black.copy(alpha = 0.30f),
                radius = hub,
                center = center,
            )
            drawCircle(color = RimColor, radius = hub, center = center, style = Stroke(rim))
        }

        // Icons + labels.
        ViewerAction.entries.forEachIndexed { index, action ->
            val a = Math.toRadians((-90f + index * 90f).toDouble())
            val ix = center.x + (cos(a) * iconRadius).toFloat()
            val iy = center.y + (sin(a) * iconRadius).toFloat()
            val selected = hovered == action
            Column(
                modifier = Modifier
                    .offset {
                        IntOffset((ix - columnHalf).roundToInt(), (iy - iconHalf).roundToInt())
                    }
                    .width(SectorColumnWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = null,
                    tint = when {
                        selected -> Color.White
                        action == ViewerAction.Delete -> DeleteRed
                        else -> TextPrimary
                    },
                    modifier = Modifier.size(IconSize),
                )
                Text(
                    text = stringResource(action.labelRes()),
                    color = if (selected) Color.White else TextPrimary.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Centre X (cancel).
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            tint = TextPrimary.copy(alpha = 0.9f),
            modifier = Modifier
                .offset {
                    IntOffset((center.x - hub * 0.42f).roundToInt(), (center.y - hub * 0.42f).roundToInt())
                }
                .size(with(density) { (hub * 0.84f).toDp() }),
        )

        // "Release to …" hint.
        hovered?.let { action ->
            val label = stringResource(R.string.release_to, stringResource(action.labelRes()))
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (center.x - with(density) { 120.dp.toPx() }).roundToInt(),
                            (center.y + outer + with(density) { 20.dp.toPx() }).roundToInt(),
                        )
                    }
                    .width(240.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (action == ViewerAction.Delete) {
                        DeleteRed.copy(alpha = 0.9f)
                    } else {
                        Color(0xE609090B)
                    },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}
