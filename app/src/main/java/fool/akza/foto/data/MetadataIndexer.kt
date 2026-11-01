package fool.akza.foto.data

import android.content.Context
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Keeps [MetadataStore] in step with the library.
 *
 * Indexing opens every file's header, so it runs in the background, in small
 * batches with a pause between them, and only for files whose `lastModified:size`
 * stamp changed since the last pass. A pass that finishes also drops rows for
 * files that were deleted, which is safe because at that point every item in the
 * library is known.
 */
object MetadataIndexer {

    /** Files indexed per pass; the rest are picked up by the next reload. */
    private const val MaxPerPass = 150

    /** Yield to the rest of the app (thumbnails, scrolling) between batches. */
    private const val PauseEvery = 15
    private const val PauseMs = 60L

    private val mutex = Mutex()

    /** Indexes whatever is stale in [items]. Returns the number of files read. */
    suspend fun sync(context: Context, items: List<MediaItem>): Int {
        if (items.isEmpty()) return 0
        // One pass at a time: a reload burst must not queue up parallel scans.
        if (!mutex.tryLock()) return 0
        return try {
            withContext(Dispatchers.IO) { index(context, items) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun index(context: Context, items: List<MediaItem>): Int {
        val known = MetadataStore.stamps(context)
        val pending = items.filter { known[it.uri.toString()] != it.previewStamp() }
        if (pending.isEmpty()) {
            // Everything current is indexed, so anything else in the table is a
            // file that no longer exists.
            MetadataStore.prune(context, items.map { it.uri.toString() })
            return 0
        }

        val batch = pending.take(MaxPerPass)
        var indexed = 0
        batch.forEachIndexed { position, item ->
            try {
                val info = MediaRepository.loadInfo(context, item)
                if (info != null) {
                    MetadataStore.upsert(
                        context,
                        item,
                        MetadataStore.buildSearchText(context::getString, item, info),
                    )
                    indexed++
                }
            } catch (e: Exception) {
                AppLog.w("metadata: index failed for ${item.uri}", e)
            }
            if (position % PauseEvery == PauseEvery - 1) delay(PauseMs)
        }
        if (pending.size <= MaxPerPass) {
            MetadataStore.prune(context, items.map { it.uri.toString() })
        }
        return indexed
    }
}
