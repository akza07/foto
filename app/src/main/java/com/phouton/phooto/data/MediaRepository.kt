package com.phouton.phooto.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
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
    val dateModified: Long = 0L,
    val mimeType: String? = null,
)

/** Outcome of a [MediaRepository.delete] attempt. */
sealed interface DeleteResult {
    /** The item was deleted. */
    data object Deleted : DeleteResult
    /** The system requires explicit user consent: launch [intentSender]. */
    data class Consent(val intentSender: android.content.IntentSender) : DeleteResult
    /** Nothing was deleted and there is no consent path (e.g. SAF read-only). */
    data object Failed : DeleteResult
}

/** Outcome of a [MediaRepository.moveTo] batch. */
data class MoveResult(
    /** Copied and the source was removed. */
    val moved: Int,
    /** Copied but the source could not be removed (left as a duplicate). */
    val copiedOnly: Int,
) {
    val total: Int get() = moved + copiedOnly
}

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

/**
 * Consent sender that moves [items] to the system trash (API 30+). Trashed items
 * can be restored by the user (or in-app via [restoreConsentSender] on API 31+).
 * Returns null on older API levels or if the request can't be built.
 */
fun trashConsentSender(context: Context, items: List<MediaItem>): android.content.IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        MediaStore.createTrashRequest(
            context.contentResolver,
            items.filter { it.id != 0L }.map { mediaCollectionUri(it.isVideo, it.id) },
            true,
        ).intentSender
    }.getOrNull()
}

/** Consent sender that restores [items] from the trash (API 30+), or null. */
fun restoreConsentSender(context: Context, items: List<MediaItem>): android.content.IntentSender? {
    val uris = items.filter { it.id != 0L }.map { mediaCollectionUri(it.isVideo, it.id) }
    if (uris.isEmpty()) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        // createTrashRequest with isTrash = false is the restore operation.
        MediaStore.createTrashRequest(context.contentResolver, uris, false).intentSender
    }.getOrNull()
}

/**
 * Canonical relative directory path (no storage prefix, no trailing slash), e.g.
 * "DCIM/Camera". SAF items derive it from their parent doc id ("primary:DCIM/Camera"),
 * MediaStore items from RELATIVE_PATH ("DCIM/Camera/").
 */
private fun MediaItem.canonicalDirPath(): String? = if (safTreeUri != null) {
    bucketId.substringAfter(":").trim('/')
} else {
    relativePath?.trim('/')
}

/** Case-insensitive grouping key derived from [canonicalDirPath]. */
private fun MediaItem.canonicalDirKey(): String? = canonicalDirPath()?.lowercase()

/**
 * Canonical relative file path used to dedup SAF-browsed items against MediaStore items
 * (e.g. "DCIM/Camera/img.jpg"). SAF doc ids embed the path; MediaStore items derive it
 * from RELATIVE_PATH + DISPLAY_NAME. Lower-cased for case-insensitive comparison.
 */
private fun MediaItem.canonicalPath(): String? {
    val raw = if (safTreeUri != null) {
        uri.lastPathSegment?.substringAfter(":")
    } else {
        val rel = relativePath ?: return null
        val name = displayName ?: return null
        rel + name
    } ?: return null
    return raw.lowercase()
}

object MediaRepository {

    // Bounds for recursive SAF tree browsing to keep load times sane.
    private const val MaxSafItems = 2000
    private const val MaxSafDepth = 12

    private val MediaProjection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.BUCKET_ID,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.SIZE,
    )

    suspend fun load(context: Context): GalleryContent = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.settings.value

        if (settings.requireExplicit) {
            // Allow-list: directly browse each granted SAF tree (reveals .nomedia
            // content, which MediaStore never indexes), supplemented by MediaStore
            // items under the same relative prefixes (deduped).
            val safItems = settings.selectedTreeUris
                .flatMap { treeUri ->
                    try {
                        browseTree(context, treeUri)
                    } catch (e: Exception) {
                        // Grant expired / provider failure: fall back to the
                        // MediaStore supplement for this tree.
                        emptyList()
                    }
                }
            val safPaths = safItems.mapNotNull { it.canonicalPath() }.toHashSet()

            val selectedPrefixes = settings.selectedTreeUris.mapNotNull { treeUri ->
                treeUriToRelativePath(context, treeUri)
            }
            val mediaItems = if (selectedPrefixes.isEmpty()) {
                emptyList()
            } else {
                queryMedia(context)
                    .filter { item ->
                        val p = item.relativePath ?: return@filter false
                        selectedPrefixes.any { p.startsWith(it) }
                    }
                    .filterNot { item -> item.canonicalPath() in safPaths }
            }

            val items = safItems + mediaItems
            buildFolders(items)
        } else {
            // Deny-list: pure MediaStore path + existing filters (unchanged).
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
            buildFolders(items)
        }
    }

    /**
     * Groups items into folders by canonical directory path so that SAF-browsed and
     * MediaStore-sourced items for the same physical directory land in one album.
     */
    private fun buildFolders(items: List<MediaItem>): GalleryContent {
        val loose = mutableListOf<MediaItem>()
        val grouped = LinkedHashMap<String, MutableList<MediaItem>>()
        val dirNames = HashMap<String, String>()      // key -> display dir path
        val safTrees = HashMap<String, String?>()      // key -> safTreeUri
        val safDocIds = HashMap<String, String?>()     // key -> saf parent doc id
        val mediaStoreBucketIds = HashMap<String, String?>() // key -> MediaStore bucket id

        for (item in items) {
            if (item.canonicalDirPath().isNullOrBlank()) {
                loose.add(item)
                continue
            }
            val key = item.canonicalDirKey() ?: continue
            val dirPath = item.canonicalDirPath()
            grouped.getOrPut(key) { mutableListOf() }.add(item)
            if (dirPath != null) dirNames.putIfAbsent(key, dirPath)
            if (item.safTreeUri != null) {
                safTrees.putIfAbsent(key, item.safTreeUri)
                if (safDocIds[key] == null) safDocIds[key] = item.bucketId
            }
            if (item.mediaStoreBucketId() != null) {
                mediaStoreBucketIds.putIfAbsent(key, item.mediaStoreBucketId())
            }
        }

        val folders = grouped.entries
            .map { (key, bucket) ->
                val sorted = bucket.sortedByDescending { it.dateAdded }
                val dir = dirNames[key] ?: "Unknown"
                MediaFolder(
                    bucketId = dir,
                    name = dir.substringAfterLast("/").ifBlank { "Storage" },
                    count = bucket.size,
                    previews = sorted.take(3).map { it.uri },
                    dateAdded = sorted.first().dateAdded,
                    safTreeUri = safTrees[key],
                    safDocId = safDocIds[key],
                    mediaStoreBucketId = mediaStoreBucketIds[key],
                )
            }
            .sortedByDescending { it.dateAdded }

        return GalleryContent(folders = folders, looseItems = loose)
    }

    private fun MediaItem.mediaStoreBucketId(): String? =
        if (safTreeUri == null && bucketId.isNotBlank()) bucketId else null

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

    // ---------------------------------------------------------------------------
    // SAF tree browsing
    //
    // MediaStore never indexes files inside .nomedia directories, so the only way
    // to show them is to enumerate the granted tree directly through the Storage
    // Access Framework. ExternalStorageProvider does NOT hide .nomedia directories
    // when listing children of a granted tree (only Android/data, Android/obb and
    // Android/sandbox are hidden).
    // ---------------------------------------------------------------------------

    /** Recursively browses a granted SAF tree and returns image/video items inside it. */
    private fun browseTree(context: Context, treeUri: String): List<MediaItem> {
        val tree = Uri.parse(treeUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootDocId.substringAfter(":").substringAfterLast("/")
            .ifBlank { "Storage" }

        val items = mutableListOf<MediaItem>()
        // Iterative DFS. Each entry: (parentDocId, parentName, depth).
        val stack = ArrayDeque<Triple<String, String, Int>>()
        stack.add(Triple(rootDocId, rootName, 0))

        while (stack.isNotEmpty()) {
            if (items.size >= MaxSafItems) break
            val (parentId, parentName, depth) = stack.removeLast()
            if (depth > MaxSafDepth) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, parentId,
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (items.size >= MaxSafItems) break
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDir) {
                        if (docId != null) {
                            stack.add(Triple(docId, name ?: docId, depth + 1))
                        }
                    } else if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                        if (docId == null) continue
                        val mod = if (modCol >= 0) cursor.getLong(modCol) / 1000L else 0L
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        items.add(
                            MediaItem(
                                id = 0L,
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                                bucketId = parentId,
                                bucketName = parentName,
                                relativePath = null,
                                dateAdded = mod,
                                isVideo = mime.startsWith("video/"),
                                displayName = name,
                                safTreeUri = treeUri,
                                mimeType = mime,
                                lastModified = mod,
                                sizeBytes = size,
                            ),
                        )
                    }
                }
            }
        }
        return items
    }

    /** Browses a single directory inside a granted SAF tree (no recursion). */
    private fun browseDirectory(context: Context, treeUri: String, docId: String): List<MediaItem> {
        val tree = Uri.parse(treeUri)
        val parentName = docId.substringAfterLast("/")
        val items = mutableListOf<MediaItem>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val docIdChild = cursor.getString(idCol)
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir) continue
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    if (docIdChild == null) continue
                    val mod = if (modCol >= 0) cursor.getLong(modCol) / 1000L else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    items.add(
                        MediaItem(
                            id = 0L,
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docIdChild),
                            bucketId = docId,
                            bucketName = parentName,
                            relativePath = null,
                            dateAdded = mod,
                            isVideo = mime.startsWith("video/"),
                            displayName = name,
                            safTreeUri = treeUri,
                            mimeType = mime,
                            lastModified = mod,
                            sizeBytes = size,
                        ),
                    )
                }
            }
        }
        return items
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
            val subPath = docId.substringAfter(":", "")
            val raw = subPath.trim('/')
            val relative = if (raw.isEmpty()) "" else raw + "/"
            relative
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadBucket(context: Context, folder: MediaFolder): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val safTree = folder.safTreeUri
            val safDoc = folder.safDocId
            if (safTree != null && safDoc != null) {
                // SAF folder (allow-list): browse the exact directory and merge any
                // MediaStore items under the same path (deduped).
                try {
                    val saf = browseDirectory(context, safTree, safDoc)
                    val media = queryMediaByPath(context, folder.bucketId)
                    val safPaths = saf.mapNotNull { it.canonicalPath() }.toHashSet()
                    (saf + media.filterNot { it.canonicalPath() in safPaths })
                        .sortedByDescending { it.dateAdded }
                } catch (e: Exception) {
                    queryMediaByPath(context, folder.bucketId).sortedByDescending { it.dateAdded }
                }
            } else if (folder.mediaStoreBucketId != null) {
                // Deny-list MediaStore folder.
                queryMedia(context, folder.mediaStoreBucketId)
                    .sortedByDescending { it.dateAdded }
            } else {
                // Allow-list folder whose SAF browse failed (grant expired): fall back
                // to MediaStore items under the directory path.
                queryMediaByPath(context, folder.bucketId).sortedByDescending { it.dateAdded }
            }
        }

    /**
     * All items in every visible folder plus all loose items, in one pass.
     * Used to AoT-warm the thumbnail cache so opening any album is instant.
     */
    suspend fun loadAllItems(context: Context, content: GalleryContent): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val folderItems = content.folders.flatMap { loadBucket(context, it) }
            folderItems + content.looseItems
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
                if (DocumentsContract.isDocumentUri(context, item.uri)) {
                    // SAF item: read document columns directly.
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    context.contentResolver.query(
                        item.uri,
                        projection,
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(0)
                                ?: item.uri.lastPathSegment ?: "Unknown"
                            val size = cursor.getLong(1)
                            val lastModified = cursor.getLong(2)
                            val mime = cursor.getString(3)
                            val (w, h) = readDimensions(context, item.uri, mime)
                            val seconds = if (lastModified > 0) lastModified / 1000L else 0L
                            MediaInfo(name, size, w, h, seconds, seconds, mime)
                        } else {
                            null
                        }
                    }
                } else {
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.WIDTH,
                        MediaStore.MediaColumns.HEIGHT,
                        MediaStore.MediaColumns.MIME_TYPE,
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
                            val dateModified = cursor.getLong(3)
                            var w = cursor.getInt(4)
                            var h = cursor.getInt(5)
                            val mime = cursor.getString(6)
                            if (w <= 0 || h <= 0) {
                                val dims = readDimensions(context, item.uri, mime)
                                w = dims.first
                                h = dims.second
                            }
                            MediaInfo(name, size, w, h, dateAdded, dateModified, mime)
                        } else {
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Best-effort pixel dimensions: MediaMetadataRetriever (rotation-aware) for
     * video, bounds-only decode for images. Returns (0, 0) if unreadable.
     */
    private fun readDimensions(context: Context, uri: Uri, mime: String?): Pair<Int, Int> = try {
        if (mime?.startsWith("video/") == true) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) h to w else w to h
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            opts.outWidth to opts.outHeight
        }
    } catch (_: Exception) {
        0 to 0
    }

    /** Removes the media item from the gallery. */
    suspend fun delete(context: Context, item: MediaItem): DeleteResult =
        withContext(Dispatchers.IO) {
            try {
                if (DocumentsContract.isDocumentUri(context, item.uri)) {
                    // SAF item: delete through the document provider (needs write grant).
                    if (DocumentsContract.deleteDocument(context.contentResolver, item.uri)) {
                        DeleteResult.Deleted
                    } else {
                        DeleteResult.Failed
                    }
                } else {
                    if (context.contentResolver.delete(item.uri, null, null) > 0) {
                        DeleteResult.Deleted
                    } else {
                        DeleteResult.Failed
                    }
                }
            } catch (e: android.app.RecoverableSecurityException) {
                // API 29: the provider hands back the exact consent intent to launch.
                DeleteResult.Consent(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                // API 30+: consent is obtained via MediaStore.createDeleteRequest.
                DeleteResult.Failed
            } catch (e: Exception) {
                DeleteResult.Failed
            }
        }

    /**
     * Copies [items] into the directory tree [destTreeUri] (picked by the user
     * via OpenDocumentTree). Returns the number of items copied successfully.
     *
     * Works for both MediaStore and SAF items: each is streamed into a new
     * document created under the destination tree.
     */
    suspend fun copyTo(context: Context, items: List<MediaItem>, destTreeUri: Uri): Int =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext 0
            val destDir = rootDocumentOf(context, destTreeUri) ?: return@withContext 0
            var copied = 0
            for (item in items) {
                try {
                    if (createAndCopy(context, item, destDir)) copied++
                } catch (_: Exception) {
                }
            }
            copied
        }

    /**
     * Moves [items] into the directory tree [destTreeUri]: copies each item then
     * deletes the source document (for SAF items). MediaStore sources that can't
     * be deleted directly are left for the caller to request system consent for.
     *
     * Returns [MoveResult]: [MoveResult.moved] were fully moved (copied + source
     * deleted); [MoveResult.copiedOnly] were copied but the original could not be
     * removed, so the caller can tell the user rather than silently duplicating.
     */
    suspend fun moveTo(context: Context, items: List<MediaItem>, destTreeUri: Uri): MoveResult =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext MoveResult(0, 0)
            val destDir = rootDocumentOf(context, destTreeUri) ?: return@withContext MoveResult(0, 0)
            var moved = 0
            var copiedOnly = 0
            for (item in items) {
                try {
                    if (createAndCopy(context, item, destDir)) {
                        if (deleteSource(context, item)) moved++ else copiedOnly++
                    }
                } catch (_: Exception) {
                }
            }
            MoveResult(moved, copiedOnly)
        }

    /**
     * Deletes the source of a move. SAF items delete through the document
     * provider; MediaStore items try a direct contentResolver delete (works for
     * app-owned files; others need the system consent flow).
     */
    private fun deleteSource(context: Context, item: MediaItem): Boolean = try {
        if (DocumentsContract.isDocumentUri(context, item.uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, item.uri)
        } else {
            context.contentResolver.delete(item.uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }

    /** Resolves a picked tree URI to its root document URI, or null on failure. */
    private fun rootDocumentOf(context: Context, treeUri: Uri): Uri? = try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    } catch (_: Exception) {
        null
    }

    /** Creates a document for [item] under [destDir] and streams the bytes in. */
    private fun createAndCopy(context: Context, item: MediaItem, destDir: Uri): Boolean {
        val baseName = item.displayName
            ?: item.uri.lastPathSegment
            ?: "file_${System.currentTimeMillis()}"
        val mime = item.mimeType ?: "application/octet-stream"
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""

        // If a document with the same name already exists, append a numeric
        // suffix so copies/moves never silently overwrite a file.
        var dst = DocumentsContract.createDocument(
            context.contentResolver,
            destDir,
            mime,
            baseName,
        )
        var attempt = 1
        while (dst == null && attempt < 100) {
            dst = DocumentsContract.createDocument(
                context.contentResolver,
                destDir,
                mime,
                "$stem ($attempt)$ext",
            )
            attempt++
        }
        dst ?: return false

        context.contentResolver.openInputStream(item.uri)?.use { input ->
            context.contentResolver.openOutputStream(dst, "w")?.use { output ->
                input.copyTo(output)
                return true
            }
        }
        return false
    }

    /** Queries MediaStore image/video rows, optionally restricted to a bucket. */
    private fun queryMedia(context: Context, bucketId: String? = null): List<MediaItem> =
        queryMediaInternal(
            context,
            selection = if (bucketId != null) {
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
                    "${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
            } else {
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            },
            selectionArgs = mutableListOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ).apply {
                if (bucketId != null) add(bucketId)
            }.toTypedArray(),
        )

    /** Queries MediaStore image/video rows that live exactly in [dirPath]. */
    private fun queryMediaByPath(context: Context, dirPath: String): List<MediaItem> =
        queryMediaInternal(
            context,
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
            selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                dirPath.trimEnd('/') + "/",
            ),
        )

    private fun queryMediaInternal(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
    ): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        // Trashed items (API 30+) must not reappear in the gallery after a
        // "move to trash"; they are still MediaStore rows until purged.
        val effectiveSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "($selection) AND (${MediaStore.MediaColumns.IS_TRASHED} = 0)"
        } else {
            selection
        }

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            MediaProjection,
            effectiveSelection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

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
                        displayName = cursor.getString(nameCol),
                        mimeType = cursor.getString(mimeCol),
                        // DATE_MODIFIED is epoch seconds, matching DATE_ADDED.
                        lastModified = cursor.getLong(modifiedCol),
                        sizeBytes = cursor.getLong(sizeCol),
                    ),
                )
            }
        }
        return items
    }
}
