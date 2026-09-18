package fool.akza.foto.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.previewStamp
import fool.akza.foto.data.thumbnails.ThumbCache
import fool.akza.foto.data.thumbnails.VideoFrameCache
import fool.akza.foto.data.thumbnails.VideoPosterExtractor
import fool.akza.foto.ui.theme.EmptySurface
import coil3.compose.AsyncImagePainter
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

    /** Poster identity: the source URI plus a stamp of the file's bytes. */
    private data class Key(val uri: Uri, val stamp: String)

    // Access-order LRU: get() refreshes recency; put() evicts from the head.
    private val cache = LinkedHashMap<Key, Bitmap>(64, 0.75f, true)

    fun get(item: MediaItem): Bitmap? =
        synchronized(cache) { cache[Key(item.uri, item.previewStamp())] }

    fun put(item: MediaItem, bitmap: Bitmap) {
        synchronized(cache) {
            cache[Key(item.uri, item.previewStamp())] = bitmap
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

    /** Drops every cached poster for [uri] (its bytes changed or were deleted). */
    fun remove(uri: Uri) {
        synchronized(cache) {
            val iter = cache.keys.iterator()
            while (iter.hasNext()) {
                if (iter.next().uri == uri) iter.remove()
            }
        }
    }

    /**
     * Drops the entire poster cache. Used after a video delete: clearing the
     * whole (small, bounded) cache guarantees no deleted video's poster can
     * survive in the process, since the UI re-seeds from [VideoFrameCache] on
     * the next composition anyway.
     */
    fun clear() {
        synchronized(cache) { cache.clear() }
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
        VideoPosterMemory.get(item)
            // Only trust the disk poster when it was written from the same
            // bytes; otherwise re-extract so an edited/replaced video updates.
            ?: if (VideoFrameCache.hasFreshPoster(context, item.uri, item.previewStamp())) {
                VideoFrameCache.load(context, item.uri)
            } else {
                null
            }
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
    if (VideoPosterMemory.get(item) != null) return
    val loaded = resolvePoster(context, item) ?: return
    VideoPosterMemory.put(item, loaded)
    // Persist like the composable does, so the next open is instant too.
    withContext(Dispatchers.IO) {
        if (!VideoFrameCache.hasFreshPoster(context, item.uri, item.previewStamp())) {
            VideoFrameCache.save(context, item.uri, loaded, item.previewStamp())
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
    // The generation re-seeds after an eviction, so a file re-created at the
    // same URI doesn't keep the deleted video's poster.
    val generation by ThumbCache.generation.collectAsState()
    val stamp = item.previewStamp()
    var poster by remember(item.uri, stamp, generation) {
        mutableStateOf(VideoPosterMemory.get(item))
    }
    // True when the poster did NOT come from the sync memory cache: the first
    // paint is then the placeholder, so fade the poster in (hard pop otherwise).
    var posterNeedsFadeIn by remember(item.uri, stamp, generation) { mutableStateOf(poster == null) }
    var needCoilFallback by remember(item.uri, stamp, generation) { mutableStateOf(false) }

    LaunchedEffect(item.uri, stamp, generation) {
        val loaded = resolvePoster(context, item)
        if (loaded != null) {
            VideoPosterMemory.put(item, loaded)
            // Persist the poster so next open (even after restart) is instant.
            if (!VideoFrameCache.hasFreshPoster(context, item.uri, stamp)) {
                VideoFrameCache.save(context, item.uri, loaded, stamp)
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
        val model = remember(item.uri, stamp) {
            ThumbCache.buildRequest(context, item, key, animated = false)
        }
        val painter = coil3.compose.rememberAsyncImagePainter(model = model)
        val state by painter.state.collectAsState()
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = modifier,
            )
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(EmptySurface),
            )
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
        SkeletonShimmer(modifier = modifier, base = EmptySurface)
    }
}
