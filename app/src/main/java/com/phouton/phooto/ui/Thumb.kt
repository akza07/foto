package com.phouton.phooto.ui

import android.net.Uri
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.isAnimatedImage
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.ui.theme.Surface as SurfaceColor

/**
 * Loads a thumbnail via Coil, showing an animated shimmer skeleton until the
 * image is ready so tiles never appear as random gaps.
 *
 * The `uri`-only overload is used for folder-cover previews where we don't
 * have a full `MediaItem`. The `item` overload uses the thumbnail cache
 * (`ThumbCache`) so scroll is backed by Coil's persistent disk + memory cache.
 */
@Composable
internal fun Thumb(uri: Uri, modifier: Modifier = Modifier) {
    val key = remember(uri) { ThumbCache.keyForUri(uri) }
    val cached = remember(key) { ThumbCache.loadThumb(key) }
    if (cached != null) {
        Image(
            bitmap = cached.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    val painter = rememberAsyncImagePainter(model = uri)
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
 * Item-aware variant: routes through `ThumbCache` so each tile shares Coil's
 * disk + memory cache. Animated formats (GIF / animated WebP / HEIF / AVIF)
 * skip the static persisted thumbnail and play inline via Coil's animated
 * decoder; static images load the persisted JPEG for an instant first paint.
 */
@Composable
internal fun Thumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val key = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
        ThumbCache.keyFor(item)
    }
    val animated = item.isAnimatedImage()
    // Load from our persisted thumbnail cache first (instant), else via Coil.
    // Animated items always go through Coil so the frames actually move; the
    // persisted first frame would otherwise freeze them.
    if (!animated) {
        val cached = remember(key) { ThumbCache.loadThumb(key) }
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

/** An animated shimmer block used as a loading placeholder. */
@Composable
internal fun SkeletonShimmer(modifier: Modifier = Modifier) {
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
            SurfaceColor.copy(alpha = 0.6f),
            SurfaceColor.copy(alpha = 1f),
            SurfaceColor.copy(alpha = 0.6f),
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
