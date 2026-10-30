package fool.akza.foto.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import fool.akza.foto.AppLog

/** One media item currently sitting in Android's system trash. */
data class SystemTrashEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateModified: Long,
)

/**
 * Thin wrapper over Android's MediaStore trash (API 30+).
 *
 * Deleting through the system trash is O(1) per item: the provider flips the
 * row's trashed state instead of the app copying every byte into private
 * storage. The file also shows up in the platform's own trash UI, so nothing
 * is hidden from the user.
 *
 * Every mutating call returns an [IntentSender]; the provider performs the
 * change only after the user approves it (no dialog is shown for items this
 * app owns). Callers launch it with an `ActivityResultContracts.StartIntentSenderForResult`.
 */
object SystemTrash {

    /** True when this device has Android's MediaStore trash (API 30+). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Moves [uris] into the system trash (restorable by the user). */
    fun trashRequest(context: Context, uris: Collection<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val list = uris.distinct()
        if (list.isEmpty()) return null
        return runCatching {
            MediaStore.createTrashRequest(context.contentResolver, list, true).intentSender
        }.onFailure {
            AppLog.w("system trash request failed for ${list.size} item(s)", it)
        }.getOrNull()
    }

    /** Takes [uris] back out of the system trash (restore). */
    fun restoreRequest(context: Context, uris: Collection<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val list = uris.distinct()
        if (list.isEmpty()) return null
        return runCatching {
            MediaStore.createTrashRequest(context.contentResolver, list, false).intentSender
        }.onFailure {
            AppLog.w("system trash restore failed for ${list.size} item(s)", it)
        }.getOrNull()
    }

    /** Permanently deletes [uris], bypassing the trash. */
    fun deleteRequest(context: Context, uris: Collection<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val list = uris.distinct()
        if (list.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, list).intentSender
        }.onFailure {
            AppLog.w("system delete request failed for ${list.size} item(s)", it)
        }.getOrNull()
    }

    /** Everything currently in the system trash, newest first. */
    fun list(context: Context): List<SystemTrashEntry> {
        if (!supported) return emptyList()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // Trashed rows are excluded from ordinary queries; MATCH_TRASHED=ONLY
        // is the supported way to ask for just those.
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val entries = mutableListOf<SystemTrashEntry>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                args,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val mediaType = cursor.getInt(typeCol)
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val isImage = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                    if (!isVideo && !isImage) continue
                    val id = cursor.getLong(idCol)
                    entries += SystemTrashEntry(
                        uri = mediaCollectionUri(isVideo, id),
                        name = cursor.getString(nameCol) ?: "file",
                        mimeType = cursor.getString(mimeCol),
                        isVideo = isVideo,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(modifiedCol),
                    )
                }
            }
        }.onFailure { AppLog.w("system trash list failed", it) }
        return entries.sortedByDescending { it.dateModified }
    }
}
