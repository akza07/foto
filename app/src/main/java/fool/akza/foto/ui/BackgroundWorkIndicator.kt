package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.data.MetadataIndexer
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.delay

private val BarShape = RoundedCornerShape(50)

/** Size of the collapsed chip, and the distance it keeps from the end edge. */
private val ChipSize = 40.dp
private val ChipEndInset = 12.dp

/** Distance below the status bar shared by every row. */
private val IndicatorTopInset = 64.dp

/** Gap between two passes running at the same time. */
private val StackGap = 8.dp

/** How long a row stays expanded before it steps aside to a chip. */
private const val CollapseDelayMs = 2_500L

/** Duration of the pill → chip morph (and its reverse). */
private const val MorphDurationMs = 320

/** Duration of the fade/slide in when a pass starts. */
private const val AppearDurationMs = 260

/** Duration of the fade out when the work is done. */
private const val DisappearDurationMs = 140

/** How long a finished pass stays up as a check before it fades away. */
private const val CompletedHoldMs = 1_600L

/** Grace before "finished" is shown, so a hand-off between passes is not one. */
private const val CompletedGraceMs = 300L

/**
 * Background work indicators for the albums screen: one row per pass, preview
 * caching and metadata indexing (which feeds search).
 *
 * Both can run for a while, and a permanent banner in the middle of the screen
 * turns into noise. Each row therefore behaves the same way: the full pill shows
 * up when its pass starts — so the user sees *why* a search or a cover may still
 * be filling in — then steps aside to a small spinner chip against the end edge,
 * holds a check when the pass finishes, and fades. Tapping a chip brings the
 * label and count back; tapping the label tucks it away again.
 *
 * The rows stack: previews on top, indexing below it with [StackGap] between, and
 * both disappear on their own timer as they finish, so the stack shrinks from the
 * top instead of everything rearranging at once.
 *
 * The collapse is a single morph rather than a swap: one value drives the
 * surface's width, height, corner radius and horizontal travel, so the pill
 * visibly shrinks and slides into the chip while its label fades out and the
 * glyph fades in. The pill's content is measured unbounded, so the shrinking
 * surface crops it instead of re-wrapping it mid-flight.
 *
 * The caller passes a full-size modifier; the indicator places its own anchors.
 */
@Composable
fun BackgroundWorkIndicator(modifier: Modifier = Modifier) {
    val thumbs by ThumbCache.progress.collectAsState()
    val metadata by MetadataIndexer.progress.collectAsState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = IndicatorTopInset),
    ) {
        val rowWidth = maxWidth
        Column(Modifier.fillMaxWidth()) {
            // Order follows the work: previews gate what the grid can draw, so
            // they own the first slot; indexing joins underneath them.
            PassRow(
                label = R.string.caching_previews,
                done = thumbs.done,
                total = thumbs.total,
                active = thumbs.isIndexing,
                availableWidth = rowWidth,
            )
            PassRow(
                label = R.string.indexing_metadata,
                done = metadata.done,
                total = metadata.total,
                active = metadata.isIndexing,
                availableWidth = rowWidth,
            )
        }
    }
}

/**
 * One pass: a pill that morphs into a chip, holds a check when it finishes, and
 * takes itself out of the stack after that. Its own timer, so two passes running
 * at once collapse and finish independently.
 */
@Composable
private fun PassRow(
    label: Int,
    done: Int,
    total: Int,
    active: Boolean,
    availableWidth: Dp,
) {
    // Expanded at the start of a pass, then collapsed to the side. Re-expanding
    // by hand keeps it open: that is an explicit request to see progress.
    var expanded by remember { mutableStateOf(true) }
    var userOpened by remember { mutableStateOf(false) }
    // A pass that ends gets a short check instead of vanishing: "done" and
    // "gave up halfway" must not look the same.
    var finished by remember { mutableStateOf(false) }
    var everRan by remember { mutableStateOf(false) }
    // 0 = full pill, 1 = collapsed chip. One value for every animated property
    // keeps position, size and content in step, so nothing can jump.
    val morph = remember { Animatable(0f) }
    // Natural (unbounded) size of the pill row: the surface shrinks below it, so
    // this has to stay the full-pill size for the whole transition.
    var pillSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(active) {
        if (active) {
            everRan = true
            finished = false
            expanded = true
            userOpened = false
            morph.snapTo(0f)
            delay(CollapseDelayMs)
            if (!userOpened) expanded = false
        } else if (everRan) {
            // Starting a pass cancels this, so the grace period only elapses when
            // nothing took over.
            delay(CompletedGraceMs)
            finished = true
            delay(CompletedHoldMs)
            finished = false
        }
    }
    LaunchedEffect(expanded) {
        val target = if (expanded) 0f else 1f
        if (morph.value != target) {
            morph.animateTo(
                targetValue = target,
                animationSpec = tween(MorphDurationMs, easing = FastOutSlowInEasing),
            )
        }
    }

    val visible = active || finished
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) AppearDurationMs else DisappearDurationMs),
        label = "pass-appear",
    )
    val density = LocalDensity.current
    val progress = morph.value
    val pillWidth = with(density) { pillSize.width.toDp() }
    val pillHeight = with(density) { pillSize.height.toDp() }
    val width = lerp(pillWidth, ChipSize, progress)
    val height = lerp(pillHeight, ChipSize, progress)
    // The pill is centred, the chip hugs the end edge; interpolating the left
    // edge moves the surface from one anchor to the other.
    val start = lerp((availableWidth - pillWidth) / 2, availableWidth - ChipEndInset - ChipSize, progress)
    // The label clears out before the surface reaches chip size, and the glyph
    // only arrives in the second half, so text never competes with the glyph.
    val pillAlpha = (1f - progress / 0.6f).coerceIn(0f, 1f)
    val glyphAlpha = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val glyphScale = 0.7f + 0.3f * glyphAlpha
    // While collapsed only the glyph is left, so the label's semantics would be a
    // second, stale readout: clear them and let the chip description speak.
    val rowSemantics = if (expanded) Modifier else Modifier.clearAndSetSemantics { }
    val chipDescription = if (expanded) {
        null
    } else {
        stringResource(R.string.work_chip_description, stringResource(label), done, total)
    }

    // The stack owns the vertical space: a row that is not running shrinks to
    // nothing, so the rows below it move up instead of leaving a hole.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(AppearDurationMs)) +
            expandVertically(tween(AppearDurationMs), expandFrom = Alignment.Top),
        exit = fadeOut(tween(DisappearDurationMs)) +
            shrinkVertically(tween(DisappearDurationMs), shrinkTowards = Alignment.Top),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = StackGap),
        ) {
            Surface(
                modifier = Modifier
                    .offset(x = start)
                    .width(width)
                    .height(height)
                    .graphicsLayer { translationY = -(1f - appear) * 16.dp.toPx() }
                    .shadow(12.dp, BarShape)
                    .clip(BarShape)
                    .clickable {
                        userOpened = true
                        expanded = !expanded
                    }
                    .semantics {
                        chipDescription?.let { contentDescription = it }
                    },
                shape = BarShape,
                color = ThemeSurface,
                border = BorderStroke(1.dp, Border),
                shadowElevation = 0.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Measured unbounded: the row keeps its full-pill width while
                    // the surface shrinks around it, so the label is cropped, not
                    // rewrapped.
                    Row(
                        modifier = Modifier
                            .then(rowSemantics)
                            .fillMaxSize()
                            .wrapContentSize(Alignment.Center, unbounded = true)
                            .onSizeChanged { pillSize = it }
                            .alpha(pillAlpha)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        if (finished) {
                            CheckGlyph()
                        } else if (done < total) {
                            ProgressGlyph()
                        } else {
                            SyncGlyph()
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(label),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.caching_progress_count, done, total),
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.Center, unbounded = true)
                            .alpha(glyphAlpha)
                            .graphicsLayer {
                                scaleX = glyphScale
                                scaleY = glyphScale
                            },
                    ) {
                        when {
                            finished -> CheckGlyph(size = 18)
                            done < total -> ProgressGlyph(size = 18)
                            else -> SyncGlyph(size = 18)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckGlyph(size: Int = 15) {
    Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = null,
        tint = TextPrimary,
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun ProgressGlyph(size: Int = 15) {
    CircularProgressIndicator(
        modifier = Modifier.size(size.dp),
        strokeWidth = 2.dp,
        strokeCap = StrokeCap.Round,
        color = TextPrimary,
        trackColor = Border,
    )
}

@Composable
private fun SyncGlyph(size: Int = 15) {
    Icon(
        imageVector = Icons.Outlined.Sync,
        contentDescription = null,
        tint = TextPrimary,
        modifier = Modifier.size(size.dp),
    )
}
