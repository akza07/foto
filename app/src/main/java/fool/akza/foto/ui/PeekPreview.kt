package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import fool.akza.foto.data.MediaItem
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary

/**
 * Long-press peek/pop gesture.
 *
 * The tile is not clickable in the normal Compose sense: this detector has to
 * own the gesture so a press can turn into either a tap (on release) or a peek
 * (after the long-press timeout). Once peeking starts, all remaining pointer
 * events are consumed so the parent scroll/grid cannot move and the release
 * only ends the peek. A tap still calls [onClick].
 */
@Composable
internal fun Modifier.peekGesture(
    onPeekStart: () -> Unit,
    onPeekEnd: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(onPeekStart, onPeekEnd, onClick) {
        detectTapGestures(
            // onPress runs on every down; waiting for release gives the
            // peek/pop lifecycle without stealing short taps. onTap still fires
            // for a normal tap, and not after a long press.
            onPress = {
                tryAwaitRelease()
                onPeekEnd()
            },
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPeekStart()
            },
            onTap = {
                onClick()
            },
        )
    }
}

/**
 * Large centered preview shown while a tile is held. It only reads state; the
 * gesture that opened it continues underneath, so releasing the finger dismisses
 * the overlay and leaves the screen exactly as it was.
 */
@Composable
internal fun PhotoPeekOverlay(
    item: MediaItem?,
    modifier: Modifier = Modifier,
) {
    // Keep the last item during the exit animation.
    var lastItem by remember { mutableStateOf(item) }
    LaunchedEffect(item) {
        if (item != null) lastItem = item
    }

    AnimatedVisibility(
        visible = item != null,
        enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.92f),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.96f),
        modifier = modifier.zIndex(20f),
    ) {
        lastItem?.let { preview ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ThemeSurface)
                        .border(1.dp, Border, RoundedCornerShape(18.dp)),
                ) {
                    Thumb(
                        item = preview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = preview.displayName ?: "Photo",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Release to close",
                            color = TextMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
