package fool.akza.foto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
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

private val BarShape = RoundedCornerShape(50)

/**
 * Floating standalone pill showing background work: thumbnail caching first,
 * then metadata indexing (which runs after it and feeds search). Content-sized
 * and rounded so it reads as an overlay — the caller is responsible for
 * positioning it (e.g. `align(Alignment.TopCenter)` + safe-area padding below
 * the app bar). Disappears automatically once the work is done.
 */
@Composable
fun ThumbnailSyncBar(modifier: Modifier = Modifier) {
    val thumbs by ThumbCache.progress.collectAsState()
    val metadata by MetadataIndexer.progress.collectAsState()
    // Previews gate what the grid can draw, so they own the pill while they run;
    // metadata indexing takes over the same spot afterwards.
    val previews = thumbs.isIndexing
    val progress = if (previews) thumbs.done to thumbs.total else metadata.done to metadata.total
    val label = if (previews) R.string.caching_previews else R.string.indexing_metadata
    AnimatedVisibility(
        visible = previews || metadata.isIndexing,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .shadow(12.dp, BarShape)
                .clip(BarShape),
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
                if (progress.first < progress.second) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round,
                        color = TextPrimary,
                        trackColor = Border,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(15.dp),
                    )
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
                    text = stringResource(
                        R.string.caching_progress_count,
                        progress.first,
                        progress.second,
                    ),
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
