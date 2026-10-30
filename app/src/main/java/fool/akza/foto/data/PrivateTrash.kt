package fool.akza.foto.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * App-private trash for SAF documents.
 *
 * Android's MediaStore trash only covers rows that MediaStore indexes. Files
 * discovered through a granted SAF tree (for example `.nomedia` content or the
 * "Only show selected folders" mode) have no platform trash, so deleting them
 * would otherwise be irreversible. [PrivateTrash] moves their bytes into
 * `filesDir/trash` first and records where they came from, letting the Trash
 * screen restore them to the original SAF directory or delete them permanently.
 *
 * Files stay in app storage, so uninstalling Foto removes them along with the
 * rest of its private data.
 */
object PrivateTrash {
    private const val DIR_NAME = "trash"
    private const val DATA_SUFFIX = ".data"
    private const val META_SUFFIX = ".json"

    private data class Meta(
        val id: String,
        val originalUri: String,
        val treeUri: String,
        val parentId: String,
        val name: String,
        val mimeType: String,
        val isVideo: Boolean,
        val sizeBytes: Long,
        val dateModified: Long,
        val trashedAt: Long,
    )

    /**
     * Moves [item]'s bytes into private storage and then deletes the original
     * SAF document. Returns false without deleting the original when the copy
     * or metadata write fails.
     */
    suspend fun trash(context: Context, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val treeUri = item.safTreeUri ?: return@withContext false
        val parentId = item.bucketId.takeIf { it.isNotBlank() } ?: return@withContext false
        if (!DocumentsContract.isDocumentUri(context, item.uri)) return@withContext false

        val id = UUID.randomUUID().toString()
        val dataFile = dataFile(context, id)
        val metaFile = metaFile(context, id)
        try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                dataFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            val meta = Meta(
                id = id,
                originalUri = item.uri.toString(),
                treeUri = treeUri,
                parentId = parentId,
                name = item.displayName?.takeIf { it.isNotBlank() } ?: "file",
                mimeType = item.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                isVideo = item.isVideo,
                sizeBytes = dataFile.length(),
                dateModified = item.lastModified,
                trashedAt = System.currentTimeMillis() / 1000L,
            )
            metaFile.writeText(meta.toJson().toString())

            if (MediaRepository.delete(context, item) != DeleteResult.Deleted) {
                dataFile.delete()
                metaFile.delete()
                return@withContext false
            }
            true
        } catch (e: Exception) {
            AppLog.w("private trash failed for ${item.uri}", e)
            dataFile.delete()
            metaFile.delete()
            false
        }
    }

    /** Entries currently held in the private trash, newest first. */
    suspend fun list(context: Context): List<TrashEntry> = withContext(Dispatchers.IO) {
        listMetas(context).mapNotNull { meta ->
            val dataFile = dataFile(context, meta.id)
            if (!dataFile.exists()) {
                metaFile(context, meta.id).delete()
                return@mapNotNull null
            }
            TrashEntry(
                uri = Uri.parse("foto-trash://${meta.id}"),
                name = meta.name,
                mimeType = meta.mimeType,
                isVideo = meta.isVideo,
                sizeBytes = dataFile.length(),
                dateModified = meta.trashedAt,
                source = TrashSource.Private,
                privateId = meta.id,
            )
        }
    }

    /** Restores a private entry to its original SAF directory. */
    suspend fun restore(context: Context, entry: TrashEntry): Boolean = withContext(Dispatchers.IO) {
        val id = entry.privateId ?: return@withContext false
        val meta = readMeta(context, id) ?: return@withContext false
        restoreMeta(context, meta)
    }

    /** Restores the private entry whose original URI is [item]'s URI. */
    suspend fun restore(context: Context, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val meta = listMetas(context).firstOrNull { it.originalUri == item.uri.toString() }
            ?: return@withContext false
        restoreMeta(context, meta)
    }

    /** Permanently removes one private entry. */
    suspend fun delete(context: Context, entry: TrashEntry): Boolean = withContext(Dispatchers.IO) {
        val id = entry.privateId ?: return@withContext false
        val dataRemoved = dataFile(context, id).delete() || !dataFile(context, id).exists()
        val metaRemoved = metaFile(context, id).delete() || !metaFile(context, id).exists()
        dataRemoved && metaRemoved
    }

    private fun restoreMeta(context: Context, meta: Meta): Boolean {
        val dataFile = dataFile(context, meta.id)
        if (!dataFile.exists()) {
            metaFile(context, meta.id).delete()
            return false
        }

        val tree = runCatching { Uri.parse(meta.treeUri) }.getOrNull() ?: return false
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, meta.parentId)
        }.getOrNull()
        val root = runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        }.getOrNull()

        val destination = createDocument(context, parent, meta) ?: createDocument(context, root, meta)
            ?: return false

        return try {
            dataFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("no output stream")
            } ?: throw IllegalStateException("no private data")
            dataFile.delete()
            metaFile(context, meta.id).delete()
            true
        } catch (e: Exception) {
            AppLog.w("private trash restore failed for ${meta.originalUri}", e)
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            false
        }
    }

    private fun createDocument(context: Context, parent: Uri?, meta: Meta): Uri? {
        if (parent == null) return null
        return createUniqueDocument(meta.name) { candidate ->
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    meta.mimeType,
                    candidate,
                )
            }.getOrNull()
        }
    }

    private fun listMetas(context: Context): List<Meta> {
        val files = trashDir(context).listFiles { file -> file.name.endsWith(META_SUFFIX) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            val id = file.name.removeSuffix(META_SUFFIX)
            readMeta(context, id)
        }
    }

    private fun readMeta(context: Context, id: String): Meta? = try {
        val json = JSONObject(metaFile(context, id).readText())
        Meta(
            id = id,
            originalUri = json.getString("originalUri"),
            treeUri = json.getString("treeUri"),
            parentId = json.getString("parentId"),
            name = json.optString("name", "file"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            isVideo = json.optBoolean("isVideo", false),
            sizeBytes = json.optLong("sizeBytes", 0L),
            dateModified = json.optLong("dateModified", 0L),
            trashedAt = json.optLong("trashedAt", 0L),
        )
    } catch (e: Exception) {
        AppLog.w("couldn't read private trash metadata $id", e)
        null
    }

    private fun Meta.toJson(): JSONObject = JSONObject().apply {
        put("originalUri", originalUri)
        put("treeUri", treeUri)
        put("parentId", parentId)
        put("name", name)
        put("mimeType", mimeType)
        put("isVideo", isVideo)
        put("sizeBytes", sizeBytes)
        put("dateModified", dateModified)
        put("trashedAt", trashedAt)
    }

    private fun trashDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun dataFile(context: Context, id: String): File =
        File(trashDir(context), id + DATA_SUFFIX)

    private fun metaFile(context: Context, id: String): File =
        File(trashDir(context), id + META_SUFFIX)
}
