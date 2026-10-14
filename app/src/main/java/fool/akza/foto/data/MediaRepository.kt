package fool.akza.foto.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import fool.akza.foto.AppLog
import fool.akza.foto.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryContent(
    val folders: List<MediaFolder>,
    val looseItems: List<MediaItem>,
    val allItems: List<MediaItem> = emptyList(),
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

/** Consent sender that permanently deletes [items] (API 30+), or null. */
fun deleteConsentSender(context: Context, items: List<MediaItem>): android.content.IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val uris = items.filter { it.id != 0L }.map { mediaCollectionUri(it.isVideo, it.id) }
    if (uris.isEmpty()) return null
    return runCatching {
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }.getOrNull()
}

object MediaRepository {

    // Bound for recursive SAF tree browsing to keep load times sane.
    // Note: there is intentionally no item-count cap here — truncating the
    // scan would silently drop photos/videos from the albums view.
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

    suspend fun load(
        context: Context,
        excludeUris: Set<Uri> = emptySet(),
        excludePaths: Set<String> = emptySet(),
    ): GalleryContent = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.settings.value

        if (settings.requireExplicit) {
            // Allow-list: directly browse each granted SAF tree (the only way to
            // reach .nomedia content, which MediaStore never indexes), plus
            // MediaStore items under the same relative prefixes (deduped).
            val scans = settings.selectedTreeUris.map { treeUri ->
                try {
                    browseTree(context, treeUri)
                } catch (e: Exception) {
                    // Grant expired / provider failure: fall back to the
                    // MediaStore supplement for this tree.
                    AppLog.w("SAF browse failed for $treeUri", e)
                    SafScan(emptyList(), emptySet())
                }
            }
            val safItems = scans.flatMap { it.items }
            val safPaths = safItems.mapNotNull { it.canonicalPath() }.toHashSet()
            // `.nomedia` markers are only observable through SAF. Respect them
            // for the browsed items and the MediaStore supplement alike, so a
            // stale indexed row can't leak through. Empty when the toggle is off.
            val noMediaDirs =
                if (settings.respectNoMedia) scans.flatMap { it.noMediaDirs }.toSet() else emptySet()

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

            val combined = safItems + mediaItems
            val raw = combined.filterNot { isUnderNoMedia(it.canonicalDirPath(), noMediaDirs) }
            if (raw.size != combined.size) {
                AppLog.w("load: hid ${combined.size - raw.size} item(s) under .nomedia")
            }
            val items = raw.filterNot { it.isExcluded(excludeUris, excludePaths) }
            if (items.size != raw.size) {
                AppLog.w("load: excluded ${raw.size - items.size} suppressed uri(s)")
            }
            buildFolders(
                items,
                settings.sortOrder,
                context.getString(R.string.folder_unknown),
                context.getString(R.string.folder_storage),
            )
        } else {
            // Deny-list: pure MediaStore path. No `.nomedia` filter is needed —
            // MediaStore never indexes content under a `.nomedia` directory, so
            // the system already hides it here. (Marker detection needs SAF and
            // only affects the allow-list browse above.)
            val selectedPrefixes = settings.selectedTreeUris.mapNotNull { uri ->
                treeUriToRelativePath(context, uri)
            }

            val raw = queryMedia(context)
                .filter { item -> passesFilters(item, settings, selectedPrefixes) }
            val items = raw.filterNot { it.isExcluded(excludeUris, excludePaths) }
            if (items.size != raw.size) {
                AppLog.w("load: excluded ${raw.size - items.size} suppressed uri(s)")
            }
            buildFolders(
                items,
                settings.sortOrder,
                context.getString(R.string.folder_unknown),
                context.getString(R.string.folder_storage),
            )
        }
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

    /** Media items and `.nomedia`-marked directories found under a granted tree. */
    private data class SafScan(
        val items: List<MediaItem>,
        /** Relative dir paths (e.g. "DCIM/Secret") that contain a `.nomedia`. */
        val noMediaDirs: Set<String>,
    )

    /**
     * Recursively browses a granted SAF tree and returns the image/video items
     * inside it plus the relative paths of its `.nomedia`-marked directories.
     *
     * MediaStore never indexes `.nomedia` markers, so SAF is the only place they
     * are observable; ExternalStorageProvider lists them when enumerating a
     * granted tree. The caller decides whether to honour them
     * (see [isUnderNoMedia]).
     */
    private fun browseTree(context: Context, treeUri: String): SafScan {
        val tree = Uri.parse(treeUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootDocId.substringAfter(":").substringAfterLast("/")
            .ifBlank { context.getString(R.string.folder_storage) }

        val items = mutableListOf<MediaItem>()
        val noMediaDirs = mutableSetOf<String>()
        // Iterative DFS. Each entry: (parentDocId, parentName, depth).
        val stack = ArrayDeque<Triple<String, String, Int>>()
        stack.add(Triple(rootDocId, rootName, 0))

        while (stack.isNotEmpty()) {
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
                val childNames = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (name != null) childNames.add(name)

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
                // A `.nomedia` child marks the directory whose children we just
                // listed (parentId embeds its path, e.g. "primary:DCIM/Secret").
                noMediaDirFor(parentId.substringAfter(":").trim('/'), childNames)
                    ?.let { noMediaDirs.add(it) }
            }
        }
        return SafScan(items, noMediaDirs)
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
    internal fun treeUriToRelativePath(context: Context, treeUri: String): String? {
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

    suspend fun loadBucket(
        context: Context,
        folder: MediaFolder,
        excludeUris: Set<Uri> = emptySet(),
        excludePaths: Set<String> = emptySet(),
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val sort = SettingsRepository.settings.value.sortOrder
            val safTree = folder.safTreeUri
            val safDoc = folder.safDocId
            val items = if (safTree != null && safDoc != null) {
                // SAF folder (allow-list): browse the exact directory and merge any
                // MediaStore items under the same path (deduped).
                try {
                    val saf = browseDirectory(context, safTree, safDoc)
                    val media = queryMediaByPath(context, folder.bucketId)
                    val safPaths = saf.mapNotNull { it.canonicalPath() }.toHashSet()
                    saf + media.filterNot { it.canonicalPath() in safPaths }
                } catch (e: Exception) {
                    queryMediaByPath(context, folder.bucketId)
                }
            } else if (folder.mediaStoreBucketId != null) {
                // Deny-list MediaStore folder.
                queryMedia(context, folder.mediaStoreBucketId)
            } else {
                // Allow-list folder whose SAF browse failed (grant expired): fall back
                // to MediaStore items under the directory path.
                queryMediaByPath(context, folder.bucketId)
            }
            items.filterNot { it.isExcluded(excludeUris, excludePaths) }.sortedWith(itemComparator(sort))
        }

    /**
     * All items in every visible folder plus all loose items, in one pass.
     * Used to AoT-warm the thumbnail cache so opening any album is instant.
     */
    suspend fun loadAllItems(
        context: Context,
        content: GalleryContent,
        excludeUris: Set<Uri> = emptySet(),
        excludePaths: Set<String> = emptySet(),
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val folderItems = content.folders.flatMap {
                loadBucket(context, it, excludeUris, excludePaths)
            }
            (folderItems + content.looseItems).filterNot {
                it.isExcluded(excludeUris, excludePaths)
            }
        }

    /**
     * Resolves a single content Uri handed over by another app (VIEW/SEND) into a
     * [MediaItem] so the viewer can open it. Falls back to a minimal item built
     * from the MIME type when no MediaStore row / document metadata is available.
     */
    suspend fun loadItem(context: Context, uri: Uri): MediaItem? =
        withContext(Dispatchers.IO) {
            try {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val isVideo = mime?.startsWith("video/") == true
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val name = uri.lastPathSegment?.substringAfterLast('/')
                    MediaItem(
                        id = 0L,
                        uri = uri,
                        bucketId = "",
                        bucketName = "",
                        relativePath = null,
                        dateAdded = 0L,
                        isVideo = isVideo,
                        displayName = name,
                        mimeType = mime,
                    )
                } else {
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                    )
                    val fromStore = context.contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(0)
                            val name = cursor.getString(1)
                            val added = cursor.getLong(2)
                            val modified = cursor.getLong(3)
                            val size = cursor.getLong(4)
                            val m = cursor.getString(5) ?: mime
                            val rel = cursor.getString(6)
                            val type = cursor.getInt(7)
                            MediaItem(
                                id = id,
                                uri = uri,
                                bucketId = rel?.trimEnd('/')?.substringAfterLast('/') ?: "",
                                bucketName = rel?.trimEnd('/')?.substringAfterLast('/') ?: "",
                                relativePath = rel,
                                dateAdded = added,
                                isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                                displayName = name,
                                mimeType = m,
                                lastModified = modified,
                                sizeBytes = size,
                            )
                        } else {
                            null
                        }
                    }
                    fromStore ?: MediaItem(
                        id = 0L,
                        uri = uri,
                        bucketId = "",
                        bucketName = "",
                        relativePath = null,
                        dateAdded = 0L,
                        isVideo = isVideo,
                        displayName = uri.lastPathSegment,
                        mimeType = mime,
                    )
                }
            } catch (_: Exception) {
                null
            }
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
                // Verify, don't trust: some providers report success while only
                // dropping the index row, leaving the bytes to be re-scanned.
                if (deleteOriginal(context, item) && originalGone(context, item)) {
                    DeleteResult.Deleted
                } else {
                    DeleteResult.Failed
                }
            } catch (e: android.app.RecoverableSecurityException) {
                // API 29: the provider hands back the exact consent intent to launch.
                DeleteResult.Consent(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                // API 30+: consent is obtained via MediaStore.createDeleteRequest.
                DeleteResult.Failed
            } catch (e: Exception) {
                AppLog.w("delete failed for ${item.uri}", e)
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
                } catch (e: Exception) {
                    AppLog.w("copy failed for ${item.uri}", e)
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
                } catch (e: Exception) {
                    AppLog.w("move failed for ${item.uri}", e)
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
        deleteOriginal(context, item)
    } catch (e: Exception) {
        AppLog.w("couldn't remove move source ${item.uri}", e)
        false
    }

    /**
     * Deletes [item]'s original: through the document provider for SAF documents
     * (needs write grant), else through MediaStore.
     *
     * MediaStore deletes go through the type-specific collection (Images/Video),
     * never the generic `Files` URI: deleting from `Files` can drop only the
     * index row and leave the bytes on disk, after which the media scanner
     * re-adds them and the file reappears in the gallery.
     */
    private fun deleteOriginal(context: Context, item: MediaItem): Boolean =
        if (DocumentsContract.isDocumentUri(context, item.uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, item.uri)
        } else {
            val uri = if (item.id != 0L) mediaCollectionUri(item.isVideo, item.id) else item.uri
            context.contentResolver.delete(uri, null, null) > 0
        }

    /**
     * Post-delete self-check: true when [item]'s original is really gone from the
     * provider. Guards against providers that report success without removing the
     * row, which would otherwise resurface the file after a media scan.
     */
    private fun originalGone(context: Context, item: MediaItem): Boolean =
        if (DocumentsContract.isDocumentUri(context, item.uri)) {
            !documentExists(context, item.uri)
        } else if (item.id != 0L) {
            !mediaRowExists(context, item.id, item.isVideo)
        } else {
            true
        }

    private fun documentExists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) {
        false
    }

    private fun mediaRowExists(context: Context, id: Long, isVideo: Boolean): Boolean = try {
        context.contentResolver.query(
            mediaCollectionUri(isVideo, id),
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
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

        // If a document with the same name already exists, append a numeric
        // suffix so copies/moves never silently overwrite a file.
        val dst = createUniqueDocument(baseName) { candidate ->
            DocumentsContract.createDocument(
                context.contentResolver,
                destDir,
                mime,
                candidate,
            )
        } ?: return false

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
        // Items another app moved to the system trash stay hidden.
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

/**
 * True when this item was deleted in this session. Matched by URI, or by
 * canonical path so a lingering MediaStore twin of a SAF-deleted file (same
 * physical file, different URI) is hidden too.
 */
private fun MediaItem.isExcluded(uris: Set<Uri>, paths: Set<String>): Boolean =
    uri in uris || (canonicalPath()?.let { it in paths } == true)
