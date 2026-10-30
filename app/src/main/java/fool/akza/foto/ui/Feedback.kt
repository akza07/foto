package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as SurfaceColor
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Shadcn-style toast: a small rounded pill at the bottom center that auto-dismisses. */
@Composable
internal fun Toast(message: String, key: Int, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        visible = true
        delay(2000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(50),
                color = TextPrimary,
                shadowElevation = 8.dp,
            ) {
                Text(
                    text = message,
                    color = AmoledBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Undo bar shown after items are moved to the system trash. Sits where the toast
 * would and offers a single "Undo" action (restore) before auto-dismissing.
 */
@Composable
internal fun UndoBar(key: Int, onUndo: () -> Unit, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        visible = true
        delay(6000)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(50),
                color = TextPrimary,
                shadowElevation = 8.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.undo_moved_to_trash),
                        color = AmoledBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_undo),
                        color = AmoledBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClick = onUndo)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/** Modal progress card used while a bulk delete copies/trashes items. */
@Composable
internal fun DeleteProgressOverlay(
    visible: Boolean,
    done: Int,
    total: Int,
) {
    // Keep the last real values while the exit animation plays, and animate the
    // bar between per-item updates so 5 files read as one continuous motion.
    var shownDone by remember { mutableStateOf(done) }
    var shownTotal by remember { mutableStateOf(total) }
    LaunchedEffect(done, total) {
        if (total > 0) {
            shownDone = done
            shownTotal = total
        }
    }
    val target = if (shownTotal <= 0) 0f else {
        (shownDone.toFloat() / shownTotal).coerceIn(0f, 1f)
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "deleteProgress",
    )
    val animatedDone = (animated * shownTotal).roundToInt().coerceIn(0, shownTotal)
    val interaction = remember { MutableInteractionSource() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack.copy(alpha = 0.68f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(
                    initialScale = 0.94f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(180)),
                exit = scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(140)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(R.string.deleting_items),
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(50)),
                            color = TextPrimary,
                            trackColor = Border,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(
                                R.string.delete_progress_count,
                                animatedDone,
                                shownTotal,
                            ),
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Simple centered spinner + label overlay while a long operation runs. */
@Composable
internal fun BusyOverlay(visible: Boolean, message: String) {
    val interaction = remember { MutableInteractionSource() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledBlack.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(
                    initialScale = 0.94f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(180)),
                exit = scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(140)),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp),
                            strokeWidth = 2.dp,
                            color = TextPrimary,
                        )
                        Text(
                            text = message,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
