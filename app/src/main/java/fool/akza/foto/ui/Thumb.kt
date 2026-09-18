package fool.akza.foto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.isAnimatedImage
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.ui.theme.Surface as SurfaceColor

/**
 * Loads a thumbnail via Coil, showing an animated shimmer skeleton until the
 * image is ready so tiles never appear as random gaps. Routes through
 * (`ThumbCache`) so scroll is backed by Coil's persistent disk + memory cache.
 */
@Composable
internal fun Thumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation by ThumbCache.generation.collectAsState()
    val key = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
        ThumbCache.keyFor(item)
    }
    val animated = item.isAnimatedImage()
    // Load from our persisted thumbnail cache first (instant), else via Coil.
    // Animated items always go through Coil so the frames actually move; the
    // persisted first frame would otherwise freeze them.
    if (!animated) {
        val cached = remember(key, generation) { ThumbCache.loadThumb(key) }
        if (cached != null) {
            Image(
                bitmap = cached.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
            return
        }
    }
    val model = remember(item.uri, animated) {
        ThumbCache.buildRequest(
            ctx = context,
            item = item,
            key = key,
            animated = animated,
        )
    }
    val painter = rememberAsyncImagePainter(model = model)
    val state by painter.state.collectAsState()
    when (state) {
        is AsyncImagePainter.State.Success -> Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        is AsyncImagePainter.State.Error -> EmptyPreview(modifier = modifier)
        else -> SkeletonShimmer(modifier = modifier)
    }
}

/**
 * An animated shimmer block used as a loading placeholder. [base] sets the
 * surface it sweeps over, so image tiles ([SurfaceColor]) and empty video
 * posters ([EmptySurface]) share one implementation.
 */
@Composable
internal fun SkeletonShimmer(modifier: Modifier = Modifier, base: Color = SurfaceColor) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
        ),
        label = "shimmerX",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            base.copy(alpha = 0.6f),
            base,
            base.copy(alpha = 0.6f),
        ),
        start = Offset(x * 1000f, 0f),
        end = Offset(x * 1000f + 400f, 0f),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(shimmerBrush),
    )
}
