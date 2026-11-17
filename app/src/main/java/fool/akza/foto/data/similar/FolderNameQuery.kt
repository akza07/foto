package fool.akza.foto.data.similar

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import fool.akza.foto.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folder names already present in a destination. Used only to pick the next
 * free `Grouped(n)` and to reject a name the user typed that already exists.
 *
 * MediaStore exposes the directory as a prefix of RELATIVE_PATH, so one range
 * query finds every child folder without walking the tree. SAF children are
 * listed through the document provider. Both are best-effort: a provider that
 * refuses a query just contributes no names.
 */
internal object FolderNameQuery {

    suspend fun existingNames(
        context: Context,
        destination: String?,
        items: List<MediaItem>,
    ): Set<String> = withContext(Dispatchers.IO) {
        val names = linkedSetOf<String>()

        val prefix = destination?.trim('/')?.takeIf { it.isNotEmpty() }?.plus("/").orEmpty()
        runCatching {
            val selection = if (prefix.isEmpty()) {
                null
            } else {
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            }
            val args = if (prefix.isEmpty()) null else arrayOf("$prefix%")
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH),
                selection,
                args,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val relative = cursor.getString(0) ?: continue
                    // Root-level destinations use the first RELATIVE_PATH
                    // segment; nested ones drop the prefix first.
                    val rest = if (prefix.isEmpty()) relative else relative.removePrefix(prefix)
                    val child = rest.trim('/').substringBefore('/')
                    if (child.isNotBlank()) names += child
                }
            }
        }

        val saf = items.firstOrNull { it.safTreeUri != null }
        if (saf?.safTreeUri != null) {
            runCatching {
                val tree = Uri.parse(saf.safTreeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, saf.bucketId)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getDocumentId(parent),
                )
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            cursor.getString(0)?.let { names += it }
                        }
                    }
                }
            }
        }

        names
    }
}
