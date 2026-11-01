package fool.akza.foto.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import fool.akza.foto.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Keeps [MetadataStore] in step with the library.
 *
 * Indexing opens every file's header, and that is a stall on storage rather than
 * work this process does, so a pass runs in waves: [BatchSize] files are read
 * concurrently (up to [Parallelism] at a time), the wave is then written in a
 * single transaction, and the pass pauses before the next wave so thumbnails and
 * scrolling keep breathing.
 *
 * Only files whose index stamp changed since the last pass are read. One [sync]
 * call always works through the whole backlog: a partial pass would leave search
 * silently incomplete until the library happened to change again.
 *
 * A finished pass also drops rows for files that no longer exist, which is safe
 * because at that point every item in the library is known.
 */
object MetadataIndexer {

    /**
     * Files per wave: read concurrently, then written in one transaction. The
     * pause after a wave is what keeps the background pass out of the way.
     */
    private const val BatchSize = 25

    /**
     * Header reads in flight per wave. Reading a file header is mostly waiting on
     * storage, so a few in parallel hide that latency; past four the flash and the
     * CPU-side EXIF parsing are the bottleneck, and more concurrency would only
     * compete with thumbnail decoding for the same queue.
     */
    private val Parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private const val PauseMs = 60L

    /**
     * Files still to read in the current pass. Indexing is invisible work that
     * takes a while on a big library, so both the album screen (as a pill next to
     * the thumbnail sync one) and the search screen (as a notice above the
     * results) surface it instead of leaving people to wonder why a query about
     * their camera finds nothing yet.
     */
    data class Progress(val total: Int = 0, val done: Int = 0) {
        val isIndexing: Boolean get() = total > 0 && done < total

        /** Files not read yet. */
        val remaining: Int get() = (total - done).coerceAtLeast(0)
    }

    private val _progress = MutableStateFlow(Progress())

    /** Live progress of the pass, or `0/0` when idle. */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _generation = MutableStateFlow(0)

    /** Bumped as the index grows, so open search results can refresh themselves. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val mutex = Mutex()

    /** Indexes whatever is stale in [items]. Returns the number of files read. */
    suspend fun sync(context: Context, items: List<MediaItem>): Int {
        // One pass at a time, but queue rather than drop: a pass can be requested
        // while another is still unwinding (an invalidation restarts the effect),
        // and a dropped pass would leave the index empty or half-built. A queued
        // pass is cheap — it reads the stamps, finds nothing pending, and prunes.
        return mutex.withLock {
            withContext(Dispatchers.IO) { index(context, items) }
        }
    }

    private suspend fun index(context: Context, items: List<MediaItem>): Int {
        val locationGranted = hasPhotoLocationAccess(context)
        val known = MetadataStore.stamps(context)
        val keys = items.associate {
            it.uri.toString() to stampFor(
                stamp = it.previewStamp(),
                displayName = it.displayName,
                bucketName = it.bucketName,
                relativePath = it.relativePath,
                locationGranted = locationGranted,
            )
        }
        val pending = items.filter { keys[it.uri.toString()] != known[it.uri.toString()] }

        val startedAt = System.currentTimeMillis()
        var indexed = 0
        var done = 0
        val batches = pending.chunked(BatchSize)
        val gate = Semaphore(Parallelism)
        if (pending.isNotEmpty()) _progress.value = Progress(total = pending.size, done = 0)
        batches.forEachIndexed { batchIndex, batch ->
            // Read the wave in parallel, write it in one transaction. A read that
            // fails records nothing, so the file stays pending and is retried on
            // the next pass instead of being silently dropped from search.
            val read = coroutineScope {
                batch.map { item ->
                    val stamp = keys.getValue(item.uri.toString())
                    async(Dispatchers.IO) {
                        gate.withPermit { readEntry(context, item, stamp) }
                    }
                }.awaitAll()
            }
            MetadataStore.upsertAll(context, read.mapNotNull { it.entry })
            indexed += read.count { it.readable }
            done += batch.size
            // Publish per wave rather than per file: the pill only needs to move.
            _progress.value = Progress(total = pending.size, done = done)
            _generation.update { it + 1 }
            if (batchIndex < batches.lastIndex) delay(PauseMs)
        }

        // Everything in the library is current now, so anything else in the table
        // is a deleted or renamed-away file. An empty list is *not* proof of an
        // empty library — settings (require-explicit, .nomedia) and transient
        // provider misses can filter everything out — so never prune on it.
        if (items.isNotEmpty()) MetadataStore.prune(context, keys.keys)
        _progress.value = Progress()
        if (pending.isNotEmpty()) {
            AppLog.d(
                "metadata: indexed $indexed/${pending.size} files in " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        }
        return indexed
    }

    /** One wave item: the entry to write, plus whether the file itself was readable. */
    private class ReadResult(val entry: MetadataStore.Entry?, val readable: Boolean)

    /** Reads one file's metadata and reduces it to words, off the main thread. */
    private suspend fun readEntry(
        context: Context,
        item: MediaItem,
        stamp: String,
    ): ReadResult {
        // Cancellation must unwind, not be logged as a per-file failure.
        currentCoroutineContext().ensureActive()
        return try {
            val info = MediaRepository.loadInfo(context, item)
            // Unreadable (locked, denied SAF grant, corrupt) files still record
            // their name so they stay findable and are not re-opened on every pass;
            // a real change updates the stamp.
            val words = if (info != null) {
                MetadataStore.buildSearchWords(context::getString, item, info)
            } else {
                MetadataStore.buildItemWords(item)
            }
            ReadResult(MetadataStore.Entry(item, words, stamp), info != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("metadata: index failed for ${item.uri}", e)
            ReadResult(null, false)
        }
    }

    /**
     * Identity of an index row. It carries the photo-location grant because GPS
     * is redacted without it: flipping the permission must re-read the file, or
     * search would keep the GPS-less text forever. The display name and album
     * are part of it so a rename re-indexes too.
     */
    internal fun stampFor(
        stamp: String,
        displayName: String?,
        bucketName: String,
        locationGranted: Boolean,
        relativePath: String? = null,
    ): String = buildString {
        append(if (locationGranted) '1' else '0')
        // Length prefixes keep the parts unambiguous ("a:b" + "c" vs "a" + "b:c").
        listOf(displayName.orEmpty(), bucketName, relativePath.orEmpty(), stamp).forEach {
            append(':').append(it.length).append(':').append(it)
        }
    }

    private fun hasPhotoLocationAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
