package com.phouton.phooto.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.phouton.phooto.AppLog
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One item in Phooto's own trash: a private copy of a deleted file plus enough
 * metadata to restore it to where it came from.
 */
data class TrashEntry(
    val id: String,
    val name: String,
    val mimeType: String?,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val deletedAt: Long,
    val originalUri: String,
    val relativePath: String?,
    val safTreeUri: String?,
    val safParentDocId: String?,
    val dateAdded: Long,
    val payload: File,
)

/**
 * App-private trash under `filesDir/trash/<id>/`. Deleted files are copied here
 * (bytes + `meta.json`) before their original is removed, so deletes are fully
 * isolated from Android's system trash and can be restored or purged by Phooto
 * alone. Writes are ordinary file I/O; the payload keeps its original extension
 * so thumbnail decoders can infer the format.
 */
object TrashStore {
    private const val DIR_NAME = "trash"
    private const val META_NAME = "meta.json"

    private fun baseDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun list(context: Context): List<TrashEntry> {
        val dirs = baseDir(context).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readEntry(it) }.sortedByDescending { it.deletedAt }
    }

    fun count(context: Context): Int =
        baseDir(context).listFiles()?.count { it.isDirectory } ?: 0

    fun sizeBytes(context: Context): Long = try {
        baseDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /** Copies [item]'s bytes into the trash. Returns the entry, or null on failure. */
    fun add(context: Context, item: MediaItem): TrashEntry? {
        val dir = File(baseDir(context), UUID.randomUUID().toString())
        return try {
            dir.mkdirs()
            val ext = item.displayName
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
                ?: "bin"
            val payload = File(dir, "payload.$ext")
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                payload.outputStream().use { output -> input.copyTo(output) }
            }
            if (!payload.isFile || payload.length() == 0L) {
                dir.deleteRecursively()
                return null
            }
            val meta = JSONObject().apply {
                put("name", item.displayName ?: "file")
                put("mime", item.mimeType ?: JSONObject.NULL)
                put("isVideo", item.isVideo)
                put("size", payload.length())
                put("deletedAt", System.currentTimeMillis())
                put("uri", item.uri.toString())
                put("relativePath", item.relativePath ?: JSONObject.NULL)
                put("safTreeUri", item.safTreeUri ?: JSONObject.NULL)
                // For SAF items, bucketId is the parent document id.
                put("safParent", if (item.safTreeUri != null) item.bucketId else JSONObject.NULL)
                put("dateAdded", item.dateAdded)
                put("payload", payload.name)
            }
            File(dir, META_NAME).writeText(meta.toString())
            readEntry(dir)
        } catch (e: Exception) {
            AppLog.w("trash add failed for ${item.uri}", e)
            runCatching { dir.deleteRecursively() }
            null
        }
    }

    /** Permanently removes a trash entry. */
    fun remove(context: Context, entry: TrashEntry): Boolean =
        runCatching { File(baseDir(context), entry.id).deleteRecursively() }.getOrDefault(false)

    /** Permanently removes every trash entry. */
    fun clear(context: Context) {
        runCatching {
            baseDir(context).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Copies [entry]'s bytes back to a new MediaStore item (or SAF document) and
     * drops the trash entry on success. Returns true when restored.
     */
    suspend fun restore(context: Context, entry: TrashEntry): Boolean {
        val target = try {
            if (entry.safTreeUri != null && entry.safParentDocId != null) {
                restoreSaf(context, entry)
            } else {
                restoreMediaStore(context, entry)
            }
        } catch (e: Exception) {
            AppLog.w("trash restore target failed for ${entry.name}", e)
            null
        } ?: return false

        val written = try {
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                entry.payload.inputStream().use { input -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            AppLog.w("trash restore write failed for ${entry.name}", e)
            false
        }
        if (!written) {
            runCatching { context.contentResolver.delete(target, null, null) }
            return false
        }
        // MediaStore inserts are pending until cleared.
        if (entry.safTreeUri == null) {
            runCatching {
                context.contentResolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
        remove(context, entry)
        return true
    }

    private fun restoreMediaStore(context: Context, entry: TrashEntry): Uri? {
        val collection = if (entry.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val base = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            entry.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Prefer the original folder; fall back if the path isn't writable.
        val rel = entry.relativePath?.trim('/')?.takeIf { it.isNotBlank() }
        if (rel != null) {
            val withPath = ContentValues(base).apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$rel/")
            }
            runCatching { context.contentResolver.insert(collection, withPath) }
                .getOrNull()
                ?.let { return it }
        }
        return context.contentResolver.insert(collection, base)
    }

    private fun restoreSaf(context: Context, entry: TrashEntry): Uri? {
        val tree = Uri.parse(entry.safTreeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, entry.safParentDocId)
        val mime = entry.mimeType ?: "application/octet-stream"
        DocumentsContract.createDocument(context.contentResolver, parent, mime, entry.name)
            ?.let { return it }
        // Name collision: append a numeric suffix like the copy/move path.
        val dot = entry.name.lastIndexOf('.')
        val stem = if (dot > 0) entry.name.substring(0, dot) else entry.name
        val ext = if (dot > 0) entry.name.substring(dot) else ""
        for (attempt in 1 until 100) {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mime,
                "$stem ($attempt)$ext",
            )?.let { return it }
        }
        return null
    }

    private fun readEntry(dir: File): TrashEntry? {
        return try {
            val metaFile = File(dir, META_NAME)
            if (!metaFile.isFile) return null
            val json = JSONObject(metaFile.readText())
            val payload = File(dir, json.optString("payload", "payload.bin"))
            if (!payload.isFile) return null
            TrashEntry(
                id = dir.name,
                name = json.optString("name", payload.name),
                mimeType = json.optStringOrNull("mime"),
                isVideo = json.optBoolean("isVideo", false),
                sizeBytes = json.optLong("size", payload.length()),
                deletedAt = json.optLong("deletedAt", 0L),
                originalUri = json.optString("uri", ""),
                relativePath = json.optStringOrNull("relativePath"),
                safTreeUri = json.optStringOrNull("safTreeUri"),
                safParentDocId = json.optStringOrNull("safParent"),
                dateAdded = json.optLong("dateAdded", 0L),
                payload = payload,
            )
        } catch (e: Exception) {
            AppLog.w("trash entry unreadable: ${dir.name}", e)
            null
        }
    }
}

/** [JSONObject.optString] that maps JSON null / missing to a Kotlin null. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
