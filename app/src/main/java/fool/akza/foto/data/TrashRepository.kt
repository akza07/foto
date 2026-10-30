package fool.akza.foto.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a trash entry came from. */
enum class TrashSource { System, Private }

/**
 * One item currently in the app's Trash screen.
 *
 * [TrashSource.System] entries live in Android's MediaStore trash and are
 * restored/deleted through the provider's consent flow. [TrashSource.Private]
 * entries are SAF documents that Android has no trash for; Foto keeps their
 * bytes in app storage until the user restores or deletes them permanently.
 */
data class TrashEntry(
    /** System: the MediaStore item URI. Private: a synthetic unique key. */
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateModified: Long,
    val source: TrashSource = TrashSource.System,
    /** Identifier of the private trash entry; null for system entries. */
    val privateId: String? = null,
)

/** Merged view of Android's system trash and Foto's private SAF trash. */
object TrashRepository {
    suspend fun list(context: Context): List<TrashEntry> = withContext(Dispatchers.IO) {
        (SystemTrash.list(context) + PrivateTrash.list(context))
            .sortedByDescending { it.dateModified }
    }

    /**
     * Builds a viewer-ready item for [entry]. Private entries point at the
     * app-private data file; system entries point at the trashed MediaStore row.
     */
    fun previewItem(context: Context, entry: TrashEntry): MediaItem? = when (entry.source) {
        TrashSource.Private -> PrivateTrash.previewItem(context, entry)
        TrashSource.System -> MediaItem(
            id = entry.uri.lastPathSegment?.toLongOrNull() ?: 0L,
            uri = entry.uri,
            bucketId = "",
            bucketName = "",
            relativePath = null,
            dateAdded = entry.dateModified,
            isVideo = entry.isVideo,
            displayName = entry.name,
            mimeType = entry.mimeType,
            lastModified = entry.dateModified,
            sizeBytes = entry.sizeBytes,
        )
    }
}
