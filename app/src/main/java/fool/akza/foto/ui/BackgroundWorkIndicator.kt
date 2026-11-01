package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/** How long the full pill stays up before it steps aside to a chip. */
private const val CollapseDelayMs = 2_500L

/**
 * Background work indicator for the albums screen, shared by preview caching and
 * metadata indexing (which runs after it and feeds search).
 *
 * Both can run for a while, and a permanent banner in the middle of the screen
 * turns into noise. The full pill therefore shows up when work starts — so the
 * user sees *why* a search or a cover may still be filling in — and then steps
 * aside to a small spinner chip against the end edge, where it stays until the
 * work is done. Tapping the chip brings the label and count back; tapping the
 * label tucks it away again.
 *
 * The caller passes a full-size modifier; the indicator places its own anchors.
 */
@Composable
fun BackgroundWorkIndicator(modifier: Modifier = Modifier) {
    val thumbs by ThumbCache.progress.collectAsState()
    val metadata by MetadataIndexer.progress.collectAsState()
    // Previews gate what the grid can draw, so they own the indicator while they
    // run; metadata indexing takes over the same spot afterwards.
    val previews = thumbs.isIndexing
    val active = previews || metadata.isIndexing
    val done = if (previews) thumbs.done else metadata.done
    val total = if (previews) thumbs.total else metadata.total
    val label = if (previews) R.string.caching_previews else R.string.indexing_metadata

    // Expanded at the start of a pass, then collapsed to the side. Re-expanding
    // by hand keeps it open: that is an explicit request to see progress.
    var expanded by remember { mutableStateOf(true) }
    var userOpened by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        expanded = true
        userOpened = false
        if (active) {
            delay(CollapseDelayMs)
            if (!userOpened) expanded = false
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) {
            AnimatedVisibility(
                visible = active && expanded,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = fadeOut(animationSpec = tween(140)) +
                    scaleOut(targetScale = 0.9f, animationSpec = tween(140)),
            ) {
                Surface(
                    modifier = Modifier
                        .shadow(12.dp, BarShape)
                        .clip(BarShape)
                        .clickable {
                            userOpened = true
                            expanded = false
                        },
                    shape = BarShape,
                    color = ThemeSurface,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        if (done < total) ProgressGlyph() else SyncGlyph()
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
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 12.dp),
        ) {
            AnimatedVisibility(
                visible = active && !expanded,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn() +
                    scaleIn(initialScale = 0.9f),
                exit = slideOutHorizontally(targetOffsetX = { it }) +
                    fadeOut(animationSpec = tween(140)),
            ) {
                val description = stringResource(
                    R.string.indexing_metadata_chip,
                    done.coerceAtMost(total),
                    total,
                )
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            userOpened = true
                            expanded = true
                        }
                        .semantics { contentDescription = description },
                    shape = CircleShape,
                    color = ThemeSurface,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done < total) ProgressGlyph(size = 18) else SyncGlyph(size = 18)
                    }
                }
            }
        }
    }
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
