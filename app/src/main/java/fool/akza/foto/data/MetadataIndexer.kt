package fool.akza.foto.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import fool.akza.foto.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Keeps [MetadataStore] in step with the library.
 *
 * Indexing opens every file's header, so it runs in the background in small
 * batches with a pause between them, and only for files whose index stamp
 * changed since the last pass. One [sync] call always works through the whole
 * backlog: a partial pass would leave search silently incomplete until the
 * library happened to change again.
 *
 * A finished pass also drops rows for files that no longer exist, which is safe
 * because at that point every item in the library is known.
 */
object MetadataIndexer {

    /** Files read between pauses; the pause lets thumbnails and scrolling breathe. */
    private const val BatchSize = 25

    private const val PauseMs = 60L

    private val mutex = Mutex()

    /** Indexes whatever is stale in [items]. Returns the number of files read. */
    suspend fun sync(context: Context, items: List<MediaItem>): Int {
        // One pass at a time: a reload burst must not queue up parallel scans.
        if (!mutex.tryLock()) return 0
        return try {
            withContext(Dispatchers.IO) { index(context, items) }
        } finally {
            mutex.unlock()
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
                locationGranted = locationGranted,
            )
        }
        val pending = items.filter { keys[it.uri.toString()] != known[it.uri.toString()] }

        var indexed = 0
        pending.chunked(BatchSize).forEach { batch ->
            batch.forEach { item ->
                // Cancellation must unwind, not be logged as a per-file failure.
                currentCoroutineContext().ensureActive()
                try {
                    val info = MediaRepository.loadInfo(context, item)
                    if (info != null) {
                        MetadataStore.upsert(
                            context,
                            item,
                            MetadataStore.buildSearchText(context::getString, item, info),
                            keys.getValue(item.uri.toString()),
                        )
                        indexed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("metadata: index failed for ${item.uri}", e)
                }
            }
            delay(PauseMs)
        }

        // Everything in the library is current now, so anything else in the
        // table is a deleted or renamed-away file.
        MetadataStore.prune(context, keys.keys)
        return indexed
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
    ): String = buildString {
        append(if (locationGranted) '1' else '0')
        append(':').append(displayName.orEmpty())
        append(':').append(bucketName)
        append(':').append(stamp)
    }

    private fun hasPhotoLocationAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
