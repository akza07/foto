package com.phouton.phooto.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.phouton.phooto.R
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.DeleteRed
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Actions offered by the viewer's radial menu. */
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

private val ButtonSize = 52.dp
private val SectorSize = 46.dp
private val WheelRadius = 86.dp
private val HubSize = 44.dp
private val EdgeMargin = 10.dp

/**
 * Floating "actions" button for the image viewer. Tap opens a radial menu; drag
 * (when unpinned) repositions it; while the menu is open, slide to a sector and
 * release to run it, or tap the centre hub to pin/unpin.
 *
 * The button is its own small touch target so the image's own gestures are
 * untouched; only when the menu is open does a full-screen layer take over.
 */
@Composable
internal fun ViewerActionOverlay(
    containerSize: IntSize,
    alpha: Float,
    buttonX: Float,
    buttonY: Float,
    pinned: Boolean,
    wheelVisible: Boolean,
    onWheelVisibleChange: (Boolean) -> Unit,
    onPositionChange: (Float, Float) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (containerSize == IntSize.Zero) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()

    val buttonPx = with(density) { ButtonSize.toPx() }
    val sectorPx = with(density) { SectorSize.toPx() }
    val wheelRadiusPx = with(density) { WheelRadius.toPx() }
    val hubPx = with(density) { HubSize.toPx() }
    val edgePx = with(density) { EdgeMargin.toPx() }
    val wheelOuterPx = wheelRadiusPx + sectorPx / 2f
    val wheelBoxPx = wheelOuterPx * 2f
    val hubHitPx = hubPx / 2f + with(density) { 8.dp.toPx() }

    val clampButton: (Offset) -> Offset = { c ->
        Offset(
            c.x.coerceIn(buttonPx / 2f + edgePx, w - buttonPx / 2f - edgePx),
            c.y.coerceIn(buttonPx / 2f + edgePx, h - buttonPx / 2f - edgePx),
        )
    }
    val buttonCenter = clampButton(Offset(buttonX * w, buttonY * h))
    // Nudge the wheel inward so every sector stays on-screen and reachable.
    val wheelCenter = Offset(
        buttonCenter.x.coerceIn(wheelBoxPx / 2f + edgePx, w - wheelBoxPx / 2f - edgePx),
        buttonCenter.y.coerceIn(wheelBoxPx / 2f + edgePx, h - wheelBoxPx / 2f - edgePx),
    )

    var hovered by remember { mutableStateOf<ViewerAction?>(null) }
    var previewCenter by remember { mutableStateOf<Offset?>(null) }

    if (!wheelVisible) {
        // Drag preview while repositioning.
        previewCenter?.let { pc ->
            Box(
                modifier = modifier
                    .offset {
                        IntOffset((pc.x - buttonPx / 2f).roundToInt(), (pc.y - buttonPx / 2f).roundToInt())
                    }
                    .size(ButtonSize)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .border(1.dp, TextPrimary.copy(alpha = 0.7f), CircleShape)
                    .background(TextPrimary.copy(alpha = 0.15f)),
            )
        }

        // The button: a small node, so touches elsewhere reach the image.
        Box(
            modifier = modifier
                .offset {
                    IntOffset(
                        (buttonCenter.x - buttonPx / 2f).roundToInt(),
                        (buttonCenter.y - buttonPx / 2f).roundToInt(),
                    )
                }
                .size(ButtonSize)
                .graphicsLayer { this.alpha = if (previewCenter != null) alpha * 0.5f else alpha }
                .pointerInput(buttonX, buttonY, pinned, containerSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            if (!change.pressed) break
                            if (!moved &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                moved = true
                            }
                            if (moved) {
                                change.consume()
                                if (!pinned) {
                                    val delta = last - down.position
                                    previewCenter = clampButton(buttonCenter + delta)
                                }
                            }
                        }
                        previewCenter = null
                        if (moved && !pinned) {
                            val delta = last - down.position
                            val newCenter = clampButton(buttonCenter + delta)
                            onPositionChange(
                                (newCenter.x / w).coerceIn(0f, 1f),
                                (newCenter.y / h).coerceIn(0f, 1f),
                            )
                        } else if (!moved) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onWheelVisibleChange(true)
                        }
                    }
                }
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.viewer_actions),
                tint = TextPrimary,
                modifier = Modifier.size(24.dp),
            )
            if (!pinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TextMuted),
                )
            }
        }
    } else {
        // Menu open: a full-screen layer that owns the slide-to-select gesture.
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(buttonX, buttonY, pinned, containerSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var current = down.position
                        fun updateHover(p: Offset) {
                            val d = p - wheelCenter
                            val dist = d.getDistance()
                            hovered = if (dist < hubHitPx || dist > wheelOuterPx) {
                                null
                            } else {
                                val deg = atan2(d.y.toDouble(), d.x.toDouble()) * 180.0 / Math.PI
                                val norm = ((deg.toFloat() + 90f) % 360f + 360f) % 360f
                                ViewerAction.entries[(norm / 90f).roundToInt() % ViewerAction.entries.size]
                            }
                        }
                        updateHover(current)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            current = change.position
                            if (!change.pressed) break
                            val before = hovered
                            updateHover(current)
                            if (before != hovered) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            change.consume()
                        }
                        val dist = (current - wheelCenter).getDistance()
                        val action = hovered
                        onWheelVisibleChange(false)
                        hovered = null
                        when {
                            dist < hubHitPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPinnedChange(!pinned)
                            }
                            action != null -> onAction(action)
                        }
                    }
                },
        ) {
            // Menu disc.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (wheelCenter.x - wheelOuterPx).roundToInt(),
                            (wheelCenter.y - wheelOuterPx).roundToInt(),
                        )
                    }
                    .size(with(density) { (wheelOuterPx * 2f).toDp() })
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            )

            ViewerAction.entries.forEachIndexed { index, action ->
                val angle = Math.toRadians((-90f + index * 90f).toDouble())
                val cx = wheelCenter.x + (cos(angle) * wheelRadiusPx).toFloat()
                val cy = wheelCenter.y + (sin(angle) * wheelRadiusPx).toFloat()
                val selected = hovered == action
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((cx - sectorPx / 2f).roundToInt(), (cy - sectorPx / 2f).roundToInt())
                        }
                        .size(SectorSize)
                        .clip(CircleShape)
                        .background(if (selected) TextPrimary else Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = stringResource(action.labelRes()),
                        tint = when {
                            action == ViewerAction.Delete -> DeleteRed
                            selected -> AmoledBlack
                            else -> TextPrimary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Centre hub: pin / unpin.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((wheelCenter.x - hubPx / 2f).roundToInt(), (wheelCenter.y - hubPx / 2f).roundToInt())
                    }
                    .size(HubSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (pinned) R.string.viewer_unpin else R.string.viewer_pin,
                    ),
                    tint = if (pinned) TextPrimary else TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
