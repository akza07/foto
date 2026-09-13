package com.phouton.phooto.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phouton.phooto.R
import com.phouton.phooto.ui.theme.DeleteRed
import com.phouton.phooto.ui.theme.TextMuted
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

private val SectorSize = 58.dp
private val WheelRadius = 98.dp
private val CancelRadius = 30.dp
private val EdgeMargin = 8.dp

// High-contrast palette that reads over any photo: a dimming scrim behind a
// near-opaque disc, solid sector fills, and a light rim on every circle.
private val WheelSurface = Color(0xFF09090B)
private val SectorFill = Color(0xFF27272A)
private val SectorFillSelected = Color(0xFFFAFAFA)
private val WheelRim = Color.White.copy(alpha = 0.22f)

private fun wheelOuterPx(density: Density): Float =
    with(density) { (WheelRadius + SectorSize / 2).toPx() }

/** Clamps a long-press point so the whole wheel stays on-screen. */
internal fun clampRadialCenter(press: Offset, containerSize: IntSize, density: Density): Offset {
    val outer = wheelOuterPx(density)
    val edge = with(density) { EdgeMargin.toPx() }
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
    val cancel = with(density) { CancelRadius.toPx() }
    if (dist < cancel || dist > outer) return null
    val deg = atan2(d.y.toDouble(), d.x.toDouble()) * 180.0 / Math.PI
    val norm = ((deg.toFloat() + 90f) % 360f + 360f) % 360f
    return ViewerAction.entries[(norm / 90f).roundToInt() % ViewerAction.entries.size]
}

/**
 * Stateless radial menu drawn at [center]. The gesture that drives it lives in
 * the viewer (long-press to summon, slide to hover, release to run); this only
 * renders the scrim, disc and sectors.
 */
@Composable
internal fun ViewerActionWheel(
    center: Offset,
    hovered: ViewerAction?,
    containerSize: IntSize,
    density: Density,
    modifier: Modifier = Modifier,
) {
    if (containerSize == IntSize.Zero) return
    val outer = wheelOuterPx(density)
    val radiusPx = with(density) { WheelRadius.toPx() }
    val sectorPx = with(density) { SectorSize.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim: dims the photo so the wheel stays legible on any background.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
        )

        // Disc.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((center.x - outer).roundToInt(), (center.y - outer).roundToInt())
                }
                .size(with(density) { (outer * 2f).toDp() })
                .clip(CircleShape)
                .background(WheelSurface.copy(alpha = 0.96f))
                .border(1.dp, WheelRim, CircleShape),
        )

        ViewerAction.entries.forEachIndexed { index, action ->
            val angle = Math.toRadians((-90f + index * 90f).toDouble())
            val cx = center.x + (cos(angle) * radiusPx).toFloat()
            val cy = center.y + (sin(angle) * radiusPx).toFloat()
            val selected = hovered == action
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((cx - sectorPx / 2f).roundToInt(), (cy - sectorPx / 2f).roundToInt())
                    }
                    .size(SectorSize)
                    .scale(if (selected) 1.14f else 1f)
                    .clip(CircleShape)
                    .background(if (selected) SectorFillSelected else SectorFill)
                    .border(1.dp, WheelRim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = stringResource(action.labelRes()),
                    tint = when {
                        selected -> Color(0xFF09090B)
                        action == ViewerAction.Delete -> DeleteRed
                        else -> TextPrimary
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Centre marker / cancel zone.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (center.x - with(density) { CancelRadius.toPx() }).roundToInt(),
                        (center.y - with(density) { CancelRadius.toPx() }).roundToInt(),
                    )
                }
                .size(CancelRadius * 2)
                .clip(CircleShape)
                .border(1.dp, WheelRim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(TextMuted.copy(alpha = 0.6f)),
            )
        }
    }
}
