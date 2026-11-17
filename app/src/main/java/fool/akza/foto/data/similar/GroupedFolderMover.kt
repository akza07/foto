package fool.akza.foto.data.similar

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.canonicalDirPath
import fool.akza.foto.data.mediaCollectionUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves a similar group into a real `Grouped(n)` folder.
 *
 * SAF documents are moved through DocumentsContract (the folder is created
 * beside them). MediaStore rows are moved by rewriting RELATIVE_PATH; the
 * provider creates the directory. A provider that needs per-item consent makes
 * that item fail rather than duplicating it — the UI reports the partial result.
 */
internal object GroupedFolderMover {

    suspend fun move(
        context: Context,
        items: List<MediaItem>,
        destination: String?,
        folderName: String,
    ): GroupMoveResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext GroupMoveResult(0, 0, null)
        val name = folderName.trim()
        if (name.isEmpty()) return@withContext GroupMoveResult(0, items.size, null)

        val saf = items.filter { it.safTreeUri != null }
        val media = items.filter { it.safTreeUri == null }
        if (saf.isNotEmpty() && media.isNotEmpty()) {
            // One physical folder can't span a document tree and MediaStore.
            return@withContext GroupMoveResult(0, items.size, null)
        }

        if (saf.isNotEmpty()) {
            moveSaf(context, saf, destination, name)
        } else {
            moveMediaStore(context, media, destination, name)
        }
    }

    private fun moveSaf(
        context: Context,
        items: List<MediaItem>,
        destination: String?,
        name: String,
    ): GroupMoveResult {
        val tree = items.firstOrNull()?.safTreeUri?.let(Uri::parse) ?: return GroupMoveResult(0, items.size, null)
        val sameFolder = items.mapNotNull { it.canonicalDirPath()?.trim('/') }.distinct()
        if (sameFolder.size != 1 || destination?.trim('/') != sameFolder.first()) {
            // Parent-folder moves need the parent document id; only same-folder
            // is supported without walking the tree.
            return GroupMoveResult(0, items.size, null)
        }
        val parentId = items.first().bucketId
        val parent = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        }.getOrNull() ?: return GroupMoveResult(0, items.size, null)

        val newDir = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull() ?: return GroupMoveResult(0, items.size, null)

        var moved = 0
        var failed = 0
        for (item in items) {
            val ok = runCatching {
                DocumentsContract.moveDocument(context.contentResolver, item.uri, parent, newDir) != null
            }.getOrDefault(false)
            if (ok) {
                moved++
                continue
            }
            if (copyThenDelete(context, item, newDir)) moved++ else failed++
        }
        val folder = sameFolder.firstOrNull()?.let { "$it/$name" }
        return GroupMoveResult(moved, failed, folder)
    }

    private fun copyThenDelete(context: Context, item: MediaItem, newDir: Uri): Boolean = try {
        val name = item.displayName ?: item.uri.lastPathSegment ?: "photo"
        val mime = item.mimeType ?: "application/octet-stream"
        val target = DocumentsContract.createDocument(
            context.contentResolver,
            newDir,
            mime,
            name,
        )
        if (target == null) {
            false
        } else {
            val copied = context.contentResolver.openInputStream(item.uri)?.use { input ->
                context.contentResolver.openOutputStream(target, "w")?.use { output ->
                    input.copyTo(output)
                    true
                } ?: false
            } ?: false
            if (copied) {
                DocumentsContract.deleteDocument(context.contentResolver, item.uri)
            } else {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                false
            }
        }
    } catch (t: Throwable) {
        AppLog.w("similar: SAF grouping failed for ${item.uri}", t)
        false
    }

    private fun moveMediaStore(
        context: Context,
        items: List<MediaItem>,
        destination: String?,
        name: String,
    ): GroupMoveResult {
        val parent = destination?.trim('/')?.ifBlank { null }
            ?: items.firstOrNull()?.canonicalDirPath()?.trim('/')
        val newPath = (if (parent.isNullOrBlank()) name else "$parent/$name") + "/"
        var moved = 0
        var failed = 0
        for (item in items) {
            val ok = runCatching {
                val target = if (item.id != 0L) {
                    mediaCollectionUri(item.isVideo, item.id)
                } else {
                    item.uri
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                }
                context.contentResolver.update(target, values, null, null) > 0
            }.getOrDefault(false)
            if (ok) moved++ else failed++
        }
        val folder = newPath.trimEnd('/')
        return GroupMoveResult(moved, failed, folder)
    }
}
