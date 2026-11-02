package fool.akza.foto.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import fool.akza.foto.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps [MetadataStore] in step with the library.
 *
 * Indexing opens every file's header, which is a stall on storage plus a CPU-side
 * parse, so a pass is a small pipeline rather than a loop: a handful of readers
 * work through the backlog in parallel, and one writer commits their results in
 * batches (SQLite has a single writer anyway, so the readers never wait on a
 * commit). Nothing sleeps between files — the number of readers is the throttle.
 *
 * That number tunes itself. The reads are a mix of storage waits and EXIF parsing,
 * so the useful concurrency is not a property of the CPU alone: the pass measures
 * its own throughput every [TuneChunk] files, adds a reader while that keeps
 * making it faster, and settles on the fastest setting it found. A phone whose
 * flash collapses under parallel reads ends up with fewer readers; one with fast
 * storage and spare cores ends up with more.
 *
 * Only files whose index stamp changed since the last pass are read. One [sync]
 * call always works through the whole backlog: a partial pass would leave search
 * silently incomplete until the library happened to change again.
 *
 * A finished pass also drops rows for files that no longer exist, which is safe
 * because at that point every item in the library is known.
 */
object MetadataIndexer {

    /** Ceiling for reads in flight: past this the flash and the EXIF parse queue up. */
    private val MaxParallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /** Reads in flight to open with, before the pass has measured anything. */
    private val SeedParallelism = (MaxParallelism - 1).coerceAtLeast(2)

    /** Files read between two throughput measurements of the pass. */
    private const val TuneChunk = 200

    /**
     * Entries coalesced into one write transaction. Large on purpose: under WAL a
     * commit costs one append, so a whole pass should cost a handful of them
     * rather than one commit per file (or per few files).
     */
    private const val WriteBatchSize = 256

    /**
     * How often the pill's count is updated, at most. Time-based rather than a
     * file count: on a 2000-file pass a 25-file step is a jump the user reads as
     * a stall, while on a slow device publishing per file is needless churn.
     */
    private const val ProgressPublishMs = 100L

    /** Files between two search-index refreshes; open results need not follow every step. */
    private const val GenerationEvery = 50

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

    /**
     * Indexes whatever is stale in [items]. Returns the number of files read.
     *
     * [complete] says whether [items] is the whole visible library. A partial list
     * — the launch snapshot's first screen, which is painted before the real scan
     * lands — must not prune, or every row outside that screen would be deleted
     * and then read again (EXIF and all) by the pass that follows, which is an
     * index rebuild on every launch.
     */
    suspend fun sync(context: Context, items: List<MediaItem>, complete: Boolean = true): Int {
        // One pass at a time, but queue rather than drop: a pass can be requested
        // while another is still unwinding (an invalidation restarts the effect),
        // and a dropped pass would leave the index empty or half-built. A queued
        // pass is cheap — it reads the stamps, finds nothing pending, and prunes.
        return mutex.withLock {
            withContext(Dispatchers.IO) { index(context, items, complete) }
        }
    }

    private suspend fun index(context: Context, items: List<MediaItem>, complete: Boolean): Int {
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
        if (pending.isEmpty()) {
            pruneIfSafe(context, items, keys.keys, complete)
            _progress.value = Progress()
            return 0
        }

        _progress.value = Progress(total = pending.size, done = 0)
        val readable = AtomicInteger(0)
        var parallelism = SeedParallelism
        var applied = parallelism

        val writes: WriteStats
        coroutineScope {
            // One writer, many readers: results flow over a channel so a commit
            // never blocks the reads and the reads never race over the write lock.
            val entries = Channel<MetadataStore.Entry>(capacity = WriteBatchSize * 2)
            val writer = async(Dispatchers.IO) { drain(context, entries, pending.size) }
            try {
                var bestRate = 0.0
                var bestParallelism = parallelism
                var probing = true
                var offset = 0
                while (offset < pending.size) {
                    val chunk = pending.subList(offset, minOf(offset + TuneChunk, pending.size))
                    offset += chunk.size
                    val started = System.nanoTime()
                    readChunk(context, chunk, keys, parallelism, entries, readable)
                    val elapsedMs =
                        ((System.nanoTime() - started) / 1_000_000.0).coerceAtLeast(1.0)
                    applied = parallelism
                    // Hill-climb on throughput: add a reader while that makes the
                    // pass faster, and go back to the best setting once it doesn't.
                    val rate = chunk.size * 1_000.0 / elapsedMs
                    if (rate > bestRate * 1.02) {
                        bestRate = rate
                        bestParallelism = parallelism
                        if (probing && parallelism < MaxParallelism) parallelism++
                    } else if (parallelism > bestParallelism) {
                        parallelism = bestParallelism
                        probing = false
                    } else {
                        probing = false
                    }
                }
            } finally {
                entries.close()
            }
            writes = writer.await()
        }

        pruneIfSafe(context, items, keys.keys, complete)
        // The final counts stay published: that is what lets the pill show a
        // completed pass instead of resetting to 0/0 and vanishing.
        AppLog.d(
            "metadata: indexed ${readable.get()}/${pending.size} files in " +
                "${System.currentTimeMillis() - startedAt}ms at $applied readers " +
                "(writes ${writes.writeMs}ms, ${writes.transactions} transactions)",
        )
        return readable.get()
    }

    /**
     * Reads [chunk] with [parallelism] workers. Workers pull from a queue instead
     * of splitting the chunk up front, so a slow video cannot leave the others
     * idle while it finishes.
     */
    private suspend fun readChunk(
        context: Context,
        chunk: List<MediaItem>,
        keys: Map<String, String>,
        parallelism: Int,
        out: SendChannel<MetadataStore.Entry>,
        readable: AtomicInteger,
    ) = coroutineScope {
        val queue = Channel<MediaItem>(capacity = parallelism * 2)
        repeat(parallelism) {
            launch(Dispatchers.IO) {
                for (item in queue) {
                    out.send(readEntry(context, item, keys.getValue(item.uri.toString()), readable))
                }
            }
        }
        for (item in chunk) queue.send(item)
        queue.close()
    }

    /** Reads one file's metadata and reduces it to words, off the main thread. */
    private suspend fun readEntry(
        context: Context,
        item: MediaItem,
        stamp: String,
        readable: AtomicInteger,
    ): MetadataStore.Entry {
        // Cancellation must unwind, not be logged as a per-file failure.
        currentCoroutineContext().ensureActive()
        return try {
            // Only the embedded metadata is indexed: the file facts are already on
            // the item, and the info sheet's dimension reads (a MediaMetadataRetriever
            // for every video) are not worth repeating just to build search words.
            val sections = MetadataReader.read(context, item.uri, item.isVideo, item.mimeType)
            if (sections.isNotEmpty()) readable.incrementAndGet()
            MetadataStore.Entry(
                item = item,
                words = MetadataStore.buildSearchWords(context::getString, item, sections),
                stamp = stamp,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("metadata: index failed for ${item.uri}", e)
            // Still record the name: an unreadable file should stay findable and
            // should not be re-opened on every pass.
            MetadataStore.Entry(item, MetadataStore.buildItemWords(item), stamp)
        }
    }

    /** What a pass's writes actually cost, for the debug summary. */
    private class WriteStats(val writeMs: Long, val transactions: Int)

    /**
     * Writes finished entries in batches and publishes progress as they land, so a
     * whole pass costs a few transactions instead of one commit per file.
     */
    private suspend fun drain(
        context: Context,
        incoming: ReceiveChannel<MetadataStore.Entry>,
        total: Int,
    ): WriteStats {
        val batch = ArrayList<MetadataStore.Entry>(WriteBatchSize)
        var done = 0
        var writeMs = 0L
        var transactions = 0
        var publishedAt = System.currentTimeMillis()
        for (entry in incoming) {
            batch += entry
            done++
            if (batch.size >= WriteBatchSize) {
                writeMs += commit(context, batch)
                transactions++
                batch.clear()
            }
            val now = System.currentTimeMillis()
            if (now - publishedAt >= ProgressPublishMs) {
                publishedAt = now
                _progress.value = Progress(total = total, done = done)
            }
            if (done % GenerationEvery == 0) _generation.update { it + 1 }
        }
        if (batch.isNotEmpty()) {
            writeMs += commit(context, batch)
            transactions++
        }
        // Published after the last commit, because this is what tells the pill the
        // pass completed rather than stopped — announcing it while the final batch
        // was still unwritten made readers (Settings, an open search) see a
        // half-written index at the moment they were told it was done.
        _progress.value = Progress(total = total, done = done)
        _generation.update { it + 1 }
        return WriteStats(writeMs, transactions)
    }

    /** One write transaction, timed so the summary can say where a pass spends it. */
    private suspend fun commit(context: Context, batch: List<MetadataStore.Entry>): Long {
        val started = System.nanoTime()
        MetadataStore.upsertAll(context, batch)
        return (System.nanoTime() - started) / 1_000_000L
    }

    /**
     * Drops rows for files that no longer exist, but only from a list that can be
     * trusted to be the whole library. See [shouldPrune].
     */
    private suspend fun pruneIfSafe(
        context: Context,
        items: List<MediaItem>,
        keep: Set<String>,
        complete: Boolean,
    ) {
        if (shouldPrune(complete, items.size)) MetadataStore.prune(context, keep)
    }

    /**
     * Whether a pass over [itemCount] items may delete the rows it did not see.
     *
     * Two things make a list unsafe to prune from. An empty list is *not* proof of
     * an empty library — settings (require-explicit, .nomedia) and transient
     * provider misses can filter everything out. A partial list is not one either:
     * the launch snapshot paints only its first screen, so pruning from it deleted
     * every row outside that screen and the real scan then re-read the whole
     * library (EXIF and all) on every launch.
     */
    internal fun shouldPrune(complete: Boolean, itemCount: Int): Boolean =
        complete && itemCount > 0

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
