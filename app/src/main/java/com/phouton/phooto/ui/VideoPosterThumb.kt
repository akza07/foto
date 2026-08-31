package com.phouton.phooto.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.data.thumbnails.VideoFrameCache
import com.phouton.phooto.data.thumbnails.VideoPosterExtractor
import com.phouton.phooto.ui.theme.EmptySurface
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast static poster for video tiles.
 *
 * Coil's `VideoFrameDecoder` seeks into the video with MediaMetadataRetriever,
 * which is slow (1-3s per tile). Instead:
 *  1. In-memory LRU of recently shown posters — synchronous, no placeholder flash
 *     when re-entering the viewer or paging between videos.
 *  2. Our private poster cache — instant after first fetch.
 *  3. MediaStore's pre-generated thumbnail via [android.content.ContentResolver.loadThumbnail]
 *     — fast, system-cached (API 29+, our minSdk). The result is persisted to
 *     [VideoFrameCache] so it's instant next time.
 *  4. Coil frame extraction as a last-resort fallback (works for SAF URIs with
 *     no MediaStore thumbnail).
 *
 * [contentScale] lets callers match the destination's geometry: grid/album
 * tiles keep the default [ContentScale.Crop] (fill the tile), while the player
 * page passes [ContentScale.Fit] so the poster is letterboxed exactly like the
 * video surface — otherwise the first rendered frame visibly jumps.
 */
object VideoPosterMemory {
    // Big enough to hold every visible grid tile's poster (a full screen of
    // tiles plus viewer neighbors), so the tapped tile's poster is almost
    // always available synchronously when the viewer opens — that sync hit is
    // what prevents the black placeholder flash during the zoom transition.
    private const val MAX_ENTRIES = 48
    // Hard cap on retained pixels (largest posters ~8 MB each).
    private const val MAX_BYTES = 96L * 1024 * 1024

    // Access-order LRU: get() refreshes recency; put() evicts from the head.
    private val cache = LinkedHashMap<Uri, Bitmap>(64, 0.75f, true)

    fun get(uri: Uri): Bitmap? = synchronized(cache) { cache[uri] }

    fun put(uri: Uri, bitmap: Bitmap) {
        synchronized(cache) {
            cache[uri] = bitmap
            var bytes = cache.values.sumOf { it.byteCount.toLong() }
            val iter = cache.entries.iterator()
            // Evict least-recently-used first; never evict down to zero (the
            // entry we just put is the tail and may alone exceed the budget).
            while (iter.hasNext() && cache.size > 1 &&
                (cache.size > MAX_ENTRIES || bytes > MAX_BYTES)
            ) {
                val removed = iter.next()
                iter.remove()
                bytes -= removed.value.byteCount
            }
        }
    }
}

/**
 * Resolve a poster through the frame-0 chain: memory → disk cache → fresh
 * extraction → MediaStore. Frame 0 is what ExoPlayer renders as a paused
 * preview, so every consumer (grid tile, viewer veil, swipe pre-warm) agrees
 * on the same pixels and the poster→first-frame crossfade is invisible.
 */
private suspend fun resolvePoster(context: Context, item: MediaItem): Bitmap? =
    withContext(Dispatchers.IO) {
        VideoPosterMemory.get(item.uri)
            ?: VideoFrameCache.load(context, item.uri)
            // One-time ~50-150 ms seek for unwarmed videos; persisted below.
            ?: VideoPosterExtractor.extract(
                context,
                item.uri,
                VideoPosterExtractor.POSTER_LONG_EDGE,
            )
            ?: runCatching {
                // Last resort only: representative frame (arbitrary position,
                // 512px) — wrong frame and soft, but better than nothing when
                // the video can't be seeked (exotic codecs / revoked access).
                context.contentResolver.loadThumbnail(item.uri, Size(512, 512), null)
            }.getOrNull()
    }

/**
 * Warms [VideoPosterMemory] for a video without composing anything, so an
 * adjacent page's poster is available synchronously the moment the user starts
 * swiping toward it (no dark placeholder mid-swipe). Cheap when already cached.
 */
suspend fun preloadVideoPoster(context: Context, item: MediaItem) {
    if (VideoPosterMemory.get(item.uri) != null) return
    val loaded = resolvePoster(context, item) ?: return
    VideoPosterMemory.put(item.uri, loaded)
    // Persist like the composable does, so the next open is instant too.
    withContext(Dispatchers.IO) {
        if (!VideoFrameCache.has(context, item.uri)) {
            VideoFrameCache.save(context, item.uri, loaded)
        }
    }
}

@Composable
fun VideoPosterThumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    // Seed synchronously from the memory cache so an already-seen poster paints
    // on the first frame (no placeholder flash during the tile->viewer zoom).
    var poster by remember(item.uri) { mutableStateOf(VideoPosterMemory.get(item.uri)) }
    // True when the poster did NOT come from the sync memory cache: the first
    // paint is then the placeholder, so fade the poster in (hard pop otherwise).
    var posterNeedsFadeIn by remember(item.uri) { mutableStateOf(poster == null) }
    var needCoilFallback by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val loaded = resolvePoster(context, item)
        if (loaded != null) {
            VideoPosterMemory.put(item.uri, loaded)
            // Persist the poster so next open (even after restart) is instant.
            if (!VideoFrameCache.has(context, item.uri)) {
                VideoFrameCache.save(context, item.uri, loaded)
            }
            poster = loaded
        } else {
            needCoilFallback = true
        }
    }

    if (needCoilFallback) {
        // 3) Last resort: Coil frame extraction (slow but works for SAF).
        val key = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
            ThumbCache.keyFor(item)
        }
        val model = remember(item.uri) {
            ThumbCache.buildRequest(context, item, key, animated = false)
        }
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        ) {
            val state by painter.state.collectAsState()
            if (state is AsyncImagePainter.State.Success) {
                SubcomposeAsyncImageContent()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EmptySurface),
                )
            }
        }
        return
    }

    val b = poster
    if (b != null) {
        // Fade in when the bitmap arrived after composition (no sync cache hit);
        // draws at full alpha when seeded synchronously from memory.
        val fadeIn = remember(b) { Animatable(if (posterNeedsFadeIn) 0f else 1f) }
        LaunchedEffect(b) {
            if (posterNeedsFadeIn) fadeIn.animateTo(1f, tween(120))
        }
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.graphicsLayer { alpha = fadeIn.value },
        )
    } else {
        // Decode in-flight: subtle shimmer, consistent with image tiles —
        // only visible for cold, unwarmed videos (the AoT pass prevents this).
        Box(
            modifier = modifier
                .fillMaxSize()
                .videoPosterShimmer(),
        )
    }
}

/** Gentle moving-highlight placeholder for posters being extracted. */
private fun Modifier.videoPosterShimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "posterShimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
        ),
        label = "posterShimmerX",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            EmptySurface.copy(alpha = 0.7f),
            EmptySurface,
            EmptySurface.copy(alpha = 0.7f),
        ),
        start = Offset(x * 1000f, 0f),
        end = Offset(x * 1000f + 400f, 0f),
    )
    background(brush)
}
