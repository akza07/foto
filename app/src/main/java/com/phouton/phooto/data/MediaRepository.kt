package com.phouton.phooto.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryContent(
    val folders: List<MediaFolder>,
    val looseItems: List<MediaItem>,
)

data class MediaInfo(
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
)

/**
 * MediaStore's `createDeleteRequest`/`createTrashRequest` only accept URIs from the
 * item-specific collections (Images/Video/Audio), not the generic `Files` collection.
 * Returns the proper collection URI for a media id.
 */
fun mediaCollectionUri(isVideo: Boolean, id: Long): Uri {
    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    return ContentUris.withAppendedId(collection, id)
}

object MediaRepository {

    suspend fun load(context: Context): GalleryContent = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.settings.value
        val selectedPrefixes = settings.selectedTreeUris.mapNotNull { uri ->
            treeUriToRelativePath(context, uri)
        }
        // Resolve all .nomedia directories once (single query), not per item.
        val noMediaDirs: Set<String> = if (settings.respectNoMedia) {
            queryNoMediaDirs(context)
        } else {
            emptySet()
        }

        val items = queryMedia(context)
            .filter { item -> passesFilters(item, settings, selectedPrefixes, noMediaDirs) }
        val loose = mutableListOf<MediaItem>()
        val grouped = LinkedHashMap<String, MutableList<MediaItem>>()

        for (item in items) {
            if (item.relativePath.isNullOrBlank()) {
                loose.add(item)
            } else {
                grouped.getOrPut(item.bucketId) { mutableListOf() }.add(item)
            }
        }

        val folders = grouped.values
            .map { bucket ->
                val sorted = bucket.sortedByDescending { it.dateAdded }
                MediaFolder(
                    bucketId = bucket[0].bucketId,
                    name = bucket[0].bucketName,
                    count = bucket.size,
                    previews = sorted.take(3).map { it.uri },
                    dateAdded = sorted.first().dateAdded,
                )
            }
            .sortedByDescending { it.dateAdded }

        GalleryContent(folders = folders, looseItems = loose)
    }

    /**
     * Applies the Content Discovery settings:
     * - .nomedia: exclude media whose directory (or ancestor) has a `.nomedia` file.
     * - explicit permission: if on, only media under a selected directory tree is kept
     *   (allow-list; nothing shown when no directory is selected). If off, media under
     *   selected trees is excluded (deny-list).
     */
    private fun passesFilters(
        item: MediaItem,
        settings: AppSettings,
        selectedPrefixes: List<String>,
        noMediaDirs: Set<String>,
    ): Boolean {
        val path = item.relativePath

        if (settings.respectNoMedia && path != null && noMediaDirs.any { path.startsWith(it) }) {
            return false
        }

        if (settings.requireExplicit) {
            // Allow-list: keep only media under a selected directory.
            if (selectedPrefixes.isEmpty()) return false
            val p = path ?: return false
            return selectedPrefixes.any { p.startsWith(it) }
        } else {
            // Deny-list: keep media NOT under any selected directory.
            val p = path ?: return true
            return selectedPrefixes.none { p.startsWith(it) }
        }
    }

    /** Returns the set of relative directory paths (e.g. "DCIM/Camera/") containing a `.nomedia` file. */
    private fun queryNoMediaDirs(context: Context): Set<String> {
        val projection = arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val dirs = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            arrayOf(".nomedia"),
            null,
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val dir = cursor.getString(col)
                if (!dir.isNullOrBlank()) dirs.add(dir)
            }
        }
        return dirs
    }

    /**
     * Resolves a persisted SAF tree URI to a MediaStore relative path (e.g. "DCIM/").
     * Returns null if it can't be resolved.
     */
    private fun treeUriToRelativePath(context: Context, treeUri: String): String? {
        return try {
            val uri = Uri.parse(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // docId looks like "primary:DCIM" or "primary:DCIM/Camera".
            val storageId = docId.substringBefore(":")
            val subPath = docId.substringAfter(":", "")
            val raw = subPath.trim('/')
            val relative = if (raw.isEmpty()) "" else raw + "/"
            relative
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadBucket(context: Context, bucketId: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMedia(context, bucketId).sortedByDescending { it.dateAdded }
        }

    /** Decodes the full-resolution bitmap for [uri]. */
    suspend fun loadFullBitmap(context: Context, uri: Uri): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** Reads metadata for the info sheet. */
    suspend fun loadInfo(context: Context, item: MediaItem): MediaInfo? =
        withContext(Dispatchers.IO) {
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                )
                context.contentResolver.query(
                    item.uri,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0) ?: item.uri.lastPathSegment ?: "Unknown"
                        val size = cursor.getLong(1)
                        val dateAdded = cursor.getLong(2)
                        MediaInfo(name, size, 0, 0, dateAdded)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

    /** Removes the media item from the gallery. */
    suspend fun delete(context: Context, item: MediaItem): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.delete(
                    item.uri,
                    null,
                    null,
                ) > 0
            } catch (e: Exception) {
                false
            }
        }

    private fun queryMedia(context: Context, bucketId: String? = null): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val selection = if (bucketId != null) {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
                "${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
        } else {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        }
        val selectionArgs = mutableListOf<String>(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        ).apply {
            if (bucketId != null) add(bucketId)
        }.toTypedArray()
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mediaType = cursor.getInt(typeCol)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        bucketId = cursor.getString(bucketIdCol) ?: "",
                        bucketName = cursor.getString(bucketNameCol) ?: "Unknown",
                        relativePath = cursor.getString(pathCol),
                        dateAdded = cursor.getLong(dateCol),
                        isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    ),
                )
            }
        }
        return items
    }
}
