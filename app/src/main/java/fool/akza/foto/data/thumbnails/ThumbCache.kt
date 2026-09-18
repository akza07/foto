package fool.akza.foto.data.thumbnails

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.toBitmap
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.video.videoFramePercent
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.previewStamp
import java.io.File
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Owns the singleton Coil [ImageLoader] configured with a persistent on-disk
 * thumbnail cache, plus a tiny in-memory `Map<Uri, Key>` so the UI can resolve
 * a tile to its cache key without re-hashing.
 *
 * Coil's `DiskCache` is the persistence layer — we don't ship a custom SQLite
 * index. The cache lives under `filesDir/image_cache` so Android's automatic
 * `cacheDir` wipes (storage pressure) don't touch it.
 *
 * Animation is delegated to Coil's decoders:
 *  - `coil-gif` registers `AnimatedImageDecoder` (API 28+) / `GifDecoder`
 *    automatically when on the classpath; GIF, animated WebP and animated
 *    HEIF tiles play inline.
 *  - `coil-video` registers `VideoFrameDecoder`; we set
 *    `videoFramePercent(0.10)` so the static thumbnail isn't the usual
 *    pitch-black opening frame.
 */
object ThumbCache {

    private const val DISK_CACHE_DIR = "image_cache"
    private const val DISK_CACHE_MAX_BYTES = 512L * 1024 * 1024 // 512 MB
    // Our own persisted thumbnail JPEGs (Coil won't persist content-URI sources).
    private const val THUMB_CACHE_DIR = "thumb_cache"

    /** Long-edge target (px) for grid tiles. Drives Coil's `.size(...)` hint. */
    const val THUMB_LONG_EDGE_PX = 320

    /** Where in the video to grab the still thumbnail. 10% avoids black intros. */
    const val VIDEO_FRAME_START_PERCENT = 0.10

    /** Target frame rate for animated GIF/WebP/HEIF tiles in the grid. */
    const val GRID_ANIMATION_FPS = 8f

    /** Snapshot of how much of the gallery is already cached on disk. */
    data class Progress(
        val total: Int = 0,
        val done: Int = 0,
    ) {
        val isIndexing: Boolean get() = total > 0 && done < total
        val fraction: Float get() = if (total == 0) 1f else (done.toFloat() / total)
    }

    @Volatile private var initialized = false
    @Volatile private var shutdown = false
    @Volatile private var appContext: Context? = null

    /** Per-URI item key cache: the signature it was derived from plus the key. */
    private data class ItemKey(val signature: String, val key: String)

    private val itemKeyByUri: MutableMap<Uri, ItemKey> =
        java.util.concurrent.ConcurrentHashMap<Uri, ItemKey>()

    /**
     * Bumped whenever an entry is evicted or the cache is cleared. The UI keys
     * its `remember`ed bitmaps on this so a tile whose persisted image was
     * removed (or replaced at the same URI) repaints, instead of showing the old
     * decode until its composition slot happens to be disposed.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var diskCache: DiskCache? = null

    /**
     * Builds the Coil [ImageLoader] singleton with a persistent disk cache and
     * starts the reconciliation scope. Safe to call multiple times — the
     * underlying `SingletonImageLoader.setSafe` is idempotent in Coil 3.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        // Always re-arm: MainActivity calls shutdown() on destroy, and an
        // activity can be recreated in a surviving process. Bailing out on
        // [initialized] alone would leave [shutdown] latched true, silently
        // disabling every enqueue for the rest of the process.
        appContext = app
        shutdown = false
        if (initialized) return

        SingletonImageLoader.setSafe { ctx ->
            val cacheDir = app.filesDir.resolve(DISK_CACHE_DIR).apply { mkdirs() }
            val disk = DiskCache.Builder()
                .directory(cacheDir.toOkioPath())
                .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                .build()
            diskCache = disk

            ImageLoader.Builder(ctx)
                .components {
                    add(coil3.video.VideoFrameDecoder.Factory())
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(ctx, 0.25)
                        .build()
                }
                .diskCache(disk)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }

        initialized = true
        shutdown = false
    }

    fun shutdown() {
        if (!initialized) return
        shutdown = true
        scope.coroutineContext[Job]?.let { job ->
            job.children.forEach { it.cancel() }
        }
    }

    /**
     * Returns the total on-disk size in bytes of the caches (Coil image_cache
     * + our thumb_cache + video poster frames). Walks the actual directories
     * because Coil's DiskLruCache.size() under-reports after a restart.
     */
    fun totalCacheBytes(): Long {
        val app = appContext ?: return 0L
        return try {
            val imageDir = app.filesDir.resolve(DISK_CACHE_DIR)
            val thumbDir = app.filesDir.resolve(THUMB_CACHE_DIR)
            val framesDir = app.filesDir.resolve("frames")
            val dirs = listOfNotNull(
                imageDir.takeIf { it.exists() },
                thumbDir.takeIf { it.exists() },
                framesDir.takeIf { it.exists() },
            )
            dirs.sumOf { dir ->
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Deletes all cached thumbnails on [Dispatchers.IO]. Resets the in-memory
     * key map and progress. The next [enqueueIfMissing] will re-decode from the
     * original URI. Safe to call even if not yet [init].
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            diskCache?.clear()
        } catch (_: Exception) {
        }
        for (dirName in listOf(DISK_CACHE_DIR, THUMB_CACHE_DIR)) {
            try {
                appContext?.filesDir?.resolve(dirName)?.let { dir ->
                    if (dir.exists()) {
                        // Also sweep any leftover journal / tmp files Coil may have left.
                        dir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Clear Coil's memory cache so stale bitmaps don't outlive the disk purge.
        try {
            val ctx = appContext
            if (ctx != null) {
                SingletonImageLoader.get(ctx).memoryCache?.clear()
            }
        } catch (_: Exception) {
        }
        itemKeyByUri.clear()
        synchronized(totalLock) {
            pendingTotal = 0
            pendingDone = 0
            _progress.value = Progress()
        }
        _generation.value += 1
    }

    /**
     * Drops every cached artifact for [uri]: the persisted image thumbnail and
     * its failure marker, both in-memory key-map entries, the Coil memory-cache
     * entries, and the video poster directory.
     *
     * Called when the underlying file is deleted. Without this, a file later
     * created at the same URI (SAF document URIs are path-derived and stable;
     * MediaStore ids can be reused) would render the deleted file's preview.
     */
    fun evict(uri: Uri) {
        val keys = setOfNotNull(itemKeyByUri.remove(uri)?.key)
        keys.forEach { key ->
            try { thumbFile(key).delete() } catch (_: Exception) {}
            try { failureFile(key).delete() } catch (_: Exception) {}
        }
        val ctx = appContext
        if (ctx != null) {
            // Coil's disk cache can still hold an entry for the deleted URI
            // (the video-frame fallback and legacy covers used URI-based keys),
            // and a process restart would not clear it. Drop it here.
            try {
                diskCache?.remove(uri.toString())
                keys.forEach { key -> diskCache?.remove(key) }
            } catch (_: Exception) {
            }
            try {
                val memory = SingletonImageLoader.get(ctx).memoryCache
                keys.forEach { key ->
                    memory?.remove(MemoryCache.Key(key))
                    memory?.remove(MemoryCache.Key("$key:anim"))
                }
                // Full-resolution viewer decodes are keyed off the data URI.
                memory?.remove(MemoryCache.Key(uri.toString()))
            } catch (_: Exception) {
            }
            try { VideoFrameCache.evict(ctx, uri) } catch (_: Exception) {}
        }
        _generation.value += 1
    }

    /** Loads a persisted thumbnail JPEG, or null if missing/corrupt. */
    fun loadThumb(key: String): android.graphics.Bitmap? {
        val f = thumbFile(key)
        if (!f.isFile) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derives a stable cache key from the item's identity. Two calls with the
     * same item always return the same string; an edit (mtime/size change)
     * returns a different one, busting the cache.
     */
    fun keyFor(item: MediaItem): String {
        val signature = buildString {
            append(item.uri.toString())
            append('|').append(item.lastModified)
            append('|').append(item.sizeBytes)
            append('|').append(item.mimeType.orEmpty())
        }
        val cached = itemKeyByUri[item.uri]
        // Only reuse the cached key while the signature still matches: a file
        // re-created or edited at the same URI must get a fresh key, otherwise
        // the old persisted thumbnail is served for the lifetime of the process
        // (SAF document URIs are path-stable, so this happens on re-download /
        // copy / restore at the same path).
        if (cached != null && cached.signature == signature) return cached.key
        val key = sha1Hex(signature)
        itemKeyByUri[item.uri] = ItemKey(signature, key)
        return key
    }

    /**
     * If [item] doesn't already have a persisted thumbnail, kick off a Coil
     * request sized to [THUMB_LONG_EDGE_PX] and save the decoded bitmap as a
     * JPEG under `filesDir/thumb_cache/<key>.jpg`. Returns true if a request
     * was enqueued, false if it was already cached (or we're shut down).
     *
     * Coil does NOT persist content-URI sources to the disk cache, so the
     * decoded thumbnail is stored as a plain file we read back directly.
     * Accounting note: only *real* work is counted — an item already on disk
     * (or whose recent attempt failed; see [isAlreadyCached]) bumps nothing,
     * so a warm cache never shows the pill.
     */
    fun enqueueIfMissing(item: MediaItem, loader: ImageLoader? = null): Boolean {
        if (shutdown || !initialized) return false
        val ctx = appContext ?: return false
        val key = keyFor(item)
        if (isAlreadyCached(ctx, item, key)) {
            return false
        }
        bumpTotal()
        enqueueOne(ctx, item, key, loader)
        return true
    }

    /**
     * Enqueues thumbnails for a batch of items (the AoT warm-up pass) with a
     * per-URI dedupe and rate-limited chunking so a large library doesn't
     * thrash the disk/CPU. Items already on disk are skipped and don't count
     * toward progress — the pill only reflects real work. Runs on the cache's
     * IO scope (disk checks + enqueues off the main thread).
     *
     * Freshness gates:
     *  - Videos: [VideoFrameCache.hasFreshPoster] (pipeline marker, not size —
     *    `getScaledFrameAtTime` never upscales, so sub-target videos are still
     *    fresh). Recently failed videos are skipped too, so an unreadable file
     *    doesn't count as pending work on every launch.
     *  - Images: the persisted thumb file, else a fresh failure marker.
     */
    fun enqueueAllIfMissing(
        items: List<MediaItem>,
        // Video poster extraction (MediaMetadataRetriever seek) is heavier than
        // a Coil image decode, so the AoT pass works in smaller chunks.
        chunkSize: Int = 6,
        delayMs: Long = 50,
    ) {
        if (shutdown || !initialized) return
        val ctx = appContext ?: return

        scope.launch {
            val seen = HashSet<Uri>()
            for (chunk in items.chunked(chunkSize)) {
                for (item in chunk) {
                    if (!seen.add(item.uri)) continue
                    if (isAlreadyCached(ctx, item, keyFor(item))) continue
                    bumpTotal()
                    enqueueOne(ctx, item, keyFor(item), null)
                }
                if (delayMs > 0) delay(delayMs)
            }
        }
    }

    /**
     * Kicks off a Coil decode for one thumbnail and persists the decoded
     * bitmap; pairs a [bumpDone] with the [bumpTotal].
     *
     * Videos take a different path: Coil's 320px frame-at-10% thumb is never
     * read by video tiles, so the work is spent on what the UI actually shows
     * — a frame-0 poster at [VideoPosterExtractor.POSTER_LONG_EDGE] px via
     * [VideoPosterExtractor], persisted with [VideoFrameCache]. This makes the
     * AoT warm pass pre-warm the real poster pipeline: grid tiles and the
     * viewer's poster veil both read the same disk cache afterwards.
     */
    private fun enqueueOne(
        ctx: Context,
        item: MediaItem,
        key: String,
        loader: ImageLoader?,
    ) {
        scope.launch {
            try {
                if (item.isVideo) {
                    extractAndSavePoster(ctx, item)
                } else {
                    val request = buildRequest(ctx, item, key, animated = false, targetSize = THUMB_LONG_EDGE_PX)
                    val result = (loader ?: SingletonImageLoader.get(ctx)).execute(request)
                    val persisted = if (result is coil3.request.SuccessResult) {
                        persistThumbnail(key, result.image)
                    } else {
                        false
                    }
                    if (!persisted) {
                        // No thumbnail was written; without a marker this item
                        // would count as pending work on every launch.
                        markFailed(key)
                    }
                }
            } catch (e: Exception) {
                if (!item.isVideo) markFailed(key)
                // Best-effort: cache miss is fine, the next render will retry.
                AppLog.w("thumbnail decode failed for ${item.uri}", e)
            } finally {
                bumpDone()
            }
        }
    }

    /**
     * Extracts frame 0 as the video's poster (unless a fresh one is already
     * cached) and persists it to [VideoFrameCache]. One-time ~50-150 ms per
     * video; every later read is a JPEG decode from disk. Freshness uses the
     * pipeline marker rather than the poster's pixel size, because the
     * extractor never upscales (a 640px video stores a 640px poster and is
     * still fresh). Failures are marked so the warm pass skips — and doesn't
     * re-count — unreadable videos for a day.
     */
    private suspend fun extractAndSavePoster(ctx: Context, item: MediaItem) {
        val edge = VideoPosterExtractor.POSTER_LONG_EDGE
        val stamp = item.previewStamp()
        if (VideoFrameCache.hasFreshPoster(ctx, item.uri, stamp)) return
        // Skip while a video is playing: the retriever seek can contend with
        // the decoder. Not marked failed, so the next pass retries.
        if (PlaybackThrottle.isPlaying) return
        val poster = VideoPosterExtractor.extract(ctx, item.uri, edge)
        if (poster == null) {
            VideoFrameCache.markFailed(ctx, item.uri, stamp)
            return
        }
        if (!VideoFrameCache.save(ctx, item.uri, poster, stamp)) {
            // Extraction worked but persistence didn't (disk full, I/O error);
            // same accounting consequence as a failed extraction.
            VideoFrameCache.markFailed(ctx, item.uri, stamp)
        }
    }

    /** Path to a persisted thumbnail's JPEG file (app-private, survives restarts). */
    private fun thumbFile(key: String): File {
        val dir = File(appContext?.filesDir ?: return File(""), THUMB_CACHE_DIR).apply { mkdirs() }
        return File(dir, "$key.jpg")
    }

    /**
     * True when [item] needs no work: a usable artifact already exists on
     * disk, or a recent attempt failed and is being suppressed. Videos read
     * the poster marker (not pixel size — the extractor never upscales) plus
     * the video failure marker; images read the thumb file plus the image
     * failure marker. Kept in one place so the warm pass, the loose-item
     * pass, and future callers agree on what "already done" means.
     */
    private fun isAlreadyCached(ctx: Context, item: MediaItem, key: String): Boolean =
        if (item.isVideo) {
            val stamp = item.previewStamp()
            VideoFrameCache.hasFreshPoster(ctx, item.uri, stamp) ||
                VideoFrameCache.isFailureFresh(ctx, item.uri, stamp)
        } else {
            thumbFile(key).isFile || isFailureFresh(key)
        }

    /** Sidecar marking a known-failed image decode; lives with its thumb file. */
    private fun failureFile(key: String): File {
        val dir = File(appContext?.filesDir ?: return File(""), THUMB_CACHE_DIR).apply { mkdirs() }
        return File(dir, "$key.failed")
    }

    private fun isFailureFresh(key: String): Boolean {
        val f = failureFile(key)
        if (!f.isFile) return false
        val writtenAt = f.lastModified()
        return writtenAt > 0L &&
            System.currentTimeMillis() - writtenAt < VideoFrameCache.FAILURE_RETRY_MS
    }

    private fun markFailed(key: String) {
        try {
            failureFile(key).apply {
                writeText("failed\n")
                setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Encodes a decoded thumbnail bitmap to JPEG and writes it atomically
     * (tmp + rename) to the thumb cache dir. Coil doesn't persist content-URI
     * sources to disk, so this is what makes thumbnails survive restarts.
     * Returns true only if a thumbnail is now readable on disk; on success any
     * stale failure marker for [key] is removed.
     */
    private fun persistThumbnail(key: String, image: coil3.Image): Boolean {
        val target = thumbFile(key)
        val tmp = File(target.parentFile, "$key.jpg.tmp")
        try {
            val bitmap = image.toBitmap()
            val ok = tmp.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (ok) {
                try { target.delete() } catch (_: Exception) {}
                if (tmp.renameTo(target)) {
                    try { failureFile(key).delete() } catch (_: Exception) {}
                    return true
                }
            }
            try { tmp.delete() } catch (_: Exception) {}
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Builds an [ImageRequest] for the gallery tile. With [animated] = false the
     * request is sized to [targetSize] so the decode + memory footprint stay
     * small. With [animated] = true the source is decoded at [targetSize] too
     * (so the grid doesn't pay full-res), but via an animated decoder so GIF /
     * WebP / HEIF frames play. Grid tiles animate continuously (no loop cap —
     * a fixed repeatCount can blank an AnimatedImageDrawable after it finishes).
     */
    fun buildRequest(
        ctx: Context,
        item: MediaItem,
        key: String,
        animated: Boolean,
        targetSize: Int = THUMB_LONG_EDGE_PX,
    ): ImageRequest {
        val memKey = if (animated) "$key:anim" else key
        val b = ImageRequest.Builder(ctx)
            .data(item.uri)
            .diskCacheKey(key)
            .memoryCacheKey(memKey)
            .size(Size(targetSize, targetSize))
            .listener(
                onError = { _, _ -> /* handled elsewhere; never crash the tile */ },
            )
        if (item.isVideo) {
            b.videoFramePercent(VIDEO_FRAME_START_PERCENT)
        }
        // NOTE: intentionally no repeatCount() here. AnimatedImageDrawable loops
        // forever by default; that's the desired grid preview behavior.
        return b.build()
    }

    /**
     * Builds an [ImageRequest] for the full-screen viewer at original
     * resolution. Unlike [buildRequest] it uses [Size.ORIGINAL] so pinch-zoom
     * reveals full detail instead of a 320px cached thumbnail. No custom
     * disk/memory keys are set so the thumb cache entry is not overwritten.
     */
    fun buildFullRequest(
        ctx: Context,
        item: MediaItem,
    ): ImageRequest {
        val b = ImageRequest.Builder(ctx)
            .data(item.uri)
            .size(Size.ORIGINAL)
            .listener(
                onError = { _, _ -> /* viewer shows EmptySurface */ },
            )
        if (item.isVideo) {
            b.videoFramePercent(VIDEO_FRAME_START_PERCENT)
        }
        return b.build()
    }

    // ---- progress accounting -------------------------------------------------

    private val totalLock = Any()
    private var pendingTotal = 0
    private var pendingDone = 0

    private fun bumpTotal() {
        synchronized(totalLock) {
            pendingTotal += 1
            emit()
        }
    }

    private fun bumpDone() {
        synchronized(totalLock) {
            pendingDone += 1
            emit()
        }
    }

    private fun emit() {
        _progress.value = Progress(total = pendingTotal, done = pendingDone)
    }
}
