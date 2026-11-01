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
import kotlinx.coroutines.sync.withLock
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

        var indexed = 0
        val batches = pending.chunked(BatchSize)
        batches.forEachIndexed { batchIndex, batch ->
            batch.forEach { item ->
                // Cancellation must unwind, not be logged as a per-file failure.
                currentCoroutineContext().ensureActive()
                try {
                    val info = MediaRepository.loadInfo(context, item)
                    // Unreadable (locked, denied SAF grant, corrupt) files still
                    // record their name so they stay findable and are not
                    // re-opened on every pass; a real change updates the stamp.
                    val words = if (info != null) {
                        MetadataStore.buildSearchWords(context::getString, item, info)
                    } else {
                        MetadataStore.buildItemWords(item)
                    }
                    MetadataStore.upsert(
                        context,
                        item,
                        words,
                        keys.getValue(item.uri.toString()),
                    )
                    if (info != null) indexed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("metadata: index failed for ${item.uri}", e)
                }
            }
            if (batchIndex < batches.lastIndex) delay(PauseMs)
        }

        // Everything in the library is current now, so anything else in the table
        // is a deleted or renamed-away file. An empty list is *not* proof of an
        // empty library — settings (require-explicit, .nomedia) and transient
        // provider misses can filter everything out — so never prune on it.
        if (items.isNotEmpty()) MetadataStore.prune(context, keys.keys)
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
