package com.phouton.phooto.data.thumbnails

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.video.videoFramePercent
import com.phouton.phooto.data.MediaItem
import java.security.MessageDigest
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** Long-edge target (px) for grid tiles. Drives Coil's `.size(...)` hint. */
    const val THUMB_LONG_EDGE_PX = 320

    /** Where in the video to grab the still thumbnail. 10% avoids black intros. */
    const val VIDEO_FRAME_START_PERCENT = 0.10

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

    private val keyByUri: MutableMap<Uri, String> = java.util.concurrent.ConcurrentHashMap<Uri, String>()

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
        if (initialized) return
        val app = context.applicationContext
        appContext = app

        SingletonImageLoader.setSafe { ctx ->
            val cacheDir = app.filesDir.resolve(DISK_CACHE_DIR).apply { mkdirs() }
            val disk = DiskCache.Builder()
                .directory(cacheDir.toOkioPath())
                .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                .build()
            diskCache = disk

            ImageLoader.Builder(ctx)
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
     * Derives a stable cache key from the item's identity. Two calls with the
     * same item always return the same string; an edit (mtime/size change)
     * returns a different one, busting the cache.
     */
    fun keyFor(item: MediaItem): String {
        val cached = keyByUri[item.uri]
        if (cached != null) return cached
        val raw = buildString {
            append(item.uri.toString())
            append('|').append(item.lastModified)
            append('|').append(item.sizeBytes)
            append('|').append(item.mimeType.orEmpty())
        }
        val key = sha1Hex(raw)
        keyByUri[item.uri] = key
        return key
    }

    /**
     * If [item] doesn't already have a disk entry, kick off a Coil request
     * sized to [THUMB_LONG_EDGE_PX] so the bytes land in the persistent cache.
     * Returns true if a request was enqueued, false if it was already cached
     * (or we're shut down).
     */
    fun enqueueIfMissing(item: MediaItem, loader: ImageLoader? = null): Boolean {
        if (shutdown || !initialized) return false
        val ctx = appContext ?: return false
        val key = keyFor(item)
        val disk = diskCache ?: return false
        if (disk.openSnapshot(key) != null) {
            bumpDone()
            return false
        }
        bumpTotal()

        scope.launch {
            try {
                val request = buildRequest(ctx, item, key, animated = false, targetSize = THUMB_LONG_EDGE_PX)
                (loader ?: SingletonImageLoader.get(ctx)).execute(request)
            } catch (_: Exception) {
                // Best-effort: cache miss is fine, the next render will retry.
            } finally {
                bumpDone()
            }
        }
        return true
    }

    /**
     * URI-only variant for warming folder-cover previews (which we only have a
     * `List<Uri>` for, not the full `MediaItem`). The key is derived from the
     * URI alone; if the same item later appears as a `MediaItem` with a
     * different key (because we learned its mtime/size), the disk entry will
     * be replaced on the next enqueue.
     */
    fun enqueueIfMissingUri(uri: Uri, loader: ImageLoader? = null): Boolean {
        if (shutdown || !initialized) return false
        val ctx = appContext ?: return false
        val key = keyByUri[uri] ?: sha1Hex(uri.toString()).also { keyByUri[uri] = it }
        val disk = diskCache ?: return false
        if (disk.openSnapshot(key) != null) {
            bumpDone()
            return false
        }
        bumpTotal()

        scope.launch {
            try {
                val request = ImageRequest.Builder(ctx)
                    .data(uri)
                    .diskCacheKey(key)
                    .memoryCacheKey(key)
                    .size(Size(THUMB_LONG_EDGE_PX, THUMB_LONG_EDGE_PX))
                    .build()
                (loader ?: SingletonImageLoader.get(ctx)).execute(request)
            } catch (_: Exception) {
                // Best-effort.
            } finally {
                bumpDone()
            }
        }
        return true
    }

    /**
     * Builds an [ImageRequest] for the gallery tile. With [animated] = false the
     * request is sized to [targetSize] so the decode + memory footprint stay
     * small. With [animated] = true no size hint is applied so Coil decodes the
     * source at its intrinsic size (required for animated drawables).
     */
    fun buildRequest(
        ctx: Context,
        item: MediaItem,
        key: String,
        animated: Boolean,
        targetSize: Int = THUMB_LONG_EDGE_PX,
    ): ImageRequest {
        val b = ImageRequest.Builder(ctx)
            .data(item.uri)
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .listener(
                onError = { _, _ -> /* handled elsewhere; never crash the tile */ },
            )
        if (!animated) {
            b.size(Size(targetSize, targetSize))
        }
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

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) hex.append('0')
            hex.append(v.toString(16))
        }
        return hex.toString()
    }
}
