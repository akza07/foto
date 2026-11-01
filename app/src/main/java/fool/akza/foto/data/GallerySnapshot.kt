package fool.akza.foto.data

import android.content.Context
import android.net.Uri
import fool.akza.foto.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Disk copy of the last album grid, so a returning launch can paint the library
 * before MediaStore answers.
 *
 * The launch spinner used to cover the whole MediaStore query plus its grouping
 * — around a second for 10k photos on a fast device, several on a cold, slow
 * phone — even though the previews were already cached. Nothing on the albums
 * screen needs to wait for that query when the same grid was on screen a minute
 * ago, so the folder list (with its cover items) and any loose files are written
 * here after every successful load and read back at the next launch; the real
 * query still runs and replaces it.
 *
 * Only the albums grid is snapshotted, not the full item list: storing 10k items
 * made reading *slower* than querying (measured 1.1s), while folders+covers are
 * a few hundred lines and parse in single-digit milliseconds. The content is
 * marked [GalleryContent.provisional] so the UI can render it directly instead of
 * deriving albums from an item list that is still loading.
 *
 * Format: a version line, then one tab-separated line per folder and per loose
 * file. Deliberately not JSON — parsing this is a `split` per line.
 */
object GallerySnapshot {

    private const val FILE_NAME = "gallery_snapshot.tsv"
    private const val VERSION = "1"

    private const val FOLDER = "F"
    private const val ITEM = "I"
    private const val RECENT = "R"
    private const val SEP = '\t'

    /**
     * How many items of the Photos timeline are stored for the first paint. The
     * timeline is the default tab, so it has to show something immediately; a few
     * screens' worth is enough, and the rest appends below once the scan lands
     * (which never reflows what is already on screen).
     */
    private const val MaxRecentItems = 150

    /** The previous albums grid, or null on first run / after a format change. */
    suspend fun read(context: Context): GalleryContent? = withContext(Dispatchers.IO) {
        val file = file(context)
        if (!file.exists()) return@withContext null
        try {
            val folders = ArrayList<MediaFolder>()
            val loose = ArrayList<MediaItem>()
            val recent = ArrayList<MediaItem>()
            file.bufferedReader().use { reader ->
                if (reader.readLine() != VERSION) return@withContext null
                while (true) {
                    val line = reader.readLine() ?: break
                    when (line.substringBefore(SEP)) {
                        FOLDER -> decodeFolder(line)?.toFolder()?.let(folders::add)
                        ITEM -> decodeItem(line)?.toItem()?.let(loose::add)
                        RECENT -> decodeItem(line)?.toItem()?.let(recent::add)
                    }
                }
            }
            if (folders.isEmpty() && loose.isEmpty() && recent.isEmpty()) return@withContext null
            GalleryContent(
                folders = folders,
                looseItems = loose,
                // Enough of the Photos timeline to fill the first screen; it is
                // extended in place when the real scan returns.
                allItems = (recent + loose).distinctBy { it.uri },
                provisional = true,
            )
        } catch (e: Exception) {
            AppLog.w("snapshot: read failed", e)
            null
        }
    }

    /** Writes the albums grid; best effort, never throws into the caller. */
    suspend fun write(context: Context, content: GalleryContent) = withContext(Dispatchers.IO) {
        if (content.folders.isEmpty() && content.looseItems.isEmpty()) return@withContext
        try {
            val file = file(context)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.bufferedWriter().use { out ->
                out.write(VERSION)
                out.write("\n")
                content.folders.forEach { folder ->
                    out.write(encodeFolder(folder.toRecord()))
                    out.write("\n")
                }
                content.looseItems.forEach { item ->
                    out.write(encodeItem(item.toRecord()))
                    out.write("\n")
                }
                // The first screen of the timeline, in the current sort order.
                content.allItems.take(MaxRecentItems).forEach { item ->
                    out.write("$RECENT$SEP" + encodeItem(item.toRecord()).removePrefix("$ITEM$SEP"))
                    out.write("\n")
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            AppLog.w("snapshot: write failed", e)
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    // ---- serialization ----------------------------------------------------
    //
    // Encoded and decoded through plain string records, so the format is unit
    // testable without a Context or an android.net.Uri.

    /** One photo/video as stored in the snapshot. */
    internal data class ItemRecord(
        val id: Long,
        val uri: String,
        val bucketId: String,
        val bucketName: String,
        val relativePath: String?,
        val dateAdded: Long,
        val isVideo: Boolean,
        val displayName: String?,
        val safTreeUri: String?,
        val mimeType: String?,
        val lastModified: Long,
        val sizeBytes: Long,
    )

    /** One album tile as stored in the snapshot. */
    internal data class FolderRecord(
        val bucketId: String,
        val name: String,
        val count: Int,
        val dateAdded: Long,
        val safTreeUri: String?,
        val safDocId: String?,
        val mediaStoreBucketId: String?,
        val covers: List<ItemRecord>,
    )

    internal fun MediaItem.toRecord(): ItemRecord = ItemRecord(
        id = id,
        uri = uri.toString(),
        bucketId = bucketId,
        bucketName = bucketName,
        relativePath = relativePath,
        dateAdded = dateAdded,
        isVideo = isVideo,
        displayName = displayName,
        safTreeUri = safTreeUri,
        mimeType = mimeType,
        lastModified = lastModified,
        sizeBytes = sizeBytes,
    )

    internal fun MediaFolder.toRecord(): FolderRecord = FolderRecord(
        bucketId = bucketId,
        name = name,
        count = count,
        dateAdded = dateAdded,
        safTreeUri = safTreeUri,
        safDocId = safDocId,
        mediaStoreBucketId = mediaStoreBucketId,
        covers = previews.map { it.toRecord() },
    )

    private fun ItemRecord.toItem(): MediaItem? {
        val parsed = uri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return MediaItem(
            id = id,
            uri = parsed,
            bucketId = bucketId,
            bucketName = bucketName,
            relativePath = relativePath,
            dateAdded = dateAdded,
            isVideo = isVideo,
            displayName = displayName,
            safTreeUri = safTreeUri,
            mimeType = mimeType,
            lastModified = lastModified,
            sizeBytes = sizeBytes,
        )
    }

    private fun FolderRecord.toFolder(): MediaFolder = MediaFolder(
        bucketId = bucketId,
        name = name,
        count = count,
        previews = covers.mapNotNull { it.toItem() },
        dateAdded = dateAdded,
        safTreeUri = safTreeUri,
        safDocId = safDocId,
        mediaStoreBucketId = mediaStoreBucketId,
    )

    internal fun encodeFolder(folder: FolderRecord): String = buildString {
        append(FOLDER).append(SEP)
        append(folder.bucketId.escape()).append(SEP)
        append(folder.name.escape()).append(SEP)
        append(folder.count).append(SEP)
        append(folder.dateAdded).append(SEP)
        append(folder.safTreeUri.orEmpty().escape()).append(SEP)
        append(folder.safDocId.orEmpty().escape()).append(SEP)
        append(folder.mediaStoreBucketId.orEmpty().escape()).append(SEP)
        folder.covers.forEach { cover ->
            // The body contains tabs, so it is escaped as one field before being
            // joined; the comma separator itself stays raw.
            append(encodeItemBody(cover).escape())
            append(',')
        }
    }

    internal fun encodeItem(item: ItemRecord): String = "$ITEM$SEP" + encodeItemBody(item)

    private fun encodeItemBody(item: ItemRecord): String = buildString {
        append(item.id).append(SEP)
        append(item.uri.escape()).append(SEP)
        append(item.bucketId.escape()).append(SEP)
        append(item.bucketName.escape()).append(SEP)
        append(item.relativePath.orEmpty().escape()).append(SEP)
        append(item.dateAdded).append(SEP)
        append(if (item.isVideo) "1" else "0").append(SEP)
        append(item.displayName.orEmpty().escape()).append(SEP)
        append(item.safTreeUri.orEmpty().escape()).append(SEP)
        append(item.mimeType.orEmpty().escape()).append(SEP)
        append(item.lastModified).append(SEP)
        append(item.sizeBytes)
    }

    internal fun decodeFolder(line: String): FolderRecord? {
        val parts = line.split(SEP)
        if (parts.size < 9) return null
        val covers = if (parts[8].isBlank()) {
            emptyList()
        } else {
            parts[8].split(',').mapNotNull { decodeItemBody(it.unescape()) }
        }
        return FolderRecord(
            bucketId = parts[1].unescape(),
            name = parts[2].unescape(),
            count = parts[3].toIntOrNull() ?: return null,
            dateAdded = parts[4].toLongOrNull() ?: 0L,
            safTreeUri = parts[5].unescape().ifBlank { null },
            safDocId = parts[6].unescape().ifBlank { null },
            mediaStoreBucketId = parts[7].unescape().ifBlank { null },
            covers = covers,
        )
    }

    internal fun decodeItem(line: String): ItemRecord? {
        // Lines carry a one-letter tag ("I" for loose files, "R" for the timeline
        // preview); strip whichever it is before decoding the fields.
        val body = if (line.length > 1 && line[1] == SEP) line.substring(2) else line
        return decodeItemBody(body)
    }

    private fun decodeItemBody(body: String): ItemRecord? {
        val parts = body.split(SEP)
        if (parts.size < 12) return null
        if (parts[1].isBlank()) return null
        return ItemRecord(
            id = parts[0].toLongOrNull() ?: 0L,
            uri = parts[1].unescape(),
            bucketId = parts[2].unescape(),
            bucketName = parts[3].unescape(),
            relativePath = parts[4].unescape().ifBlank { null },
            dateAdded = parts[5].toLongOrNull() ?: 0L,
            isVideo = parts[6] == "1",
            displayName = parts[7].unescape().ifBlank { null },
            safTreeUri = parts[8].unescape().ifBlank { null },
            mimeType = parts[9].unescape().ifBlank { null },
            lastModified = parts[10].toLongOrNull() ?: 0L,
            sizeBytes = parts[11].toLongOrNull() ?: 0L,
        )
    }

    /** Tabs, commas and newlines would break the format; escape them (and `\`). */
    internal fun String.escape(): String = replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace(",", "\\c")

    internal fun String.unescape(): String = buildString(length) {
        var i = 0
        while (i < this@unescape.length) {
            val c = this@unescape[i]
            if (c == '\\' && i + 1 < this@unescape.length) {
                when (this@unescape[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'c' -> append(',')
                    '\\' -> append('\\')
                    else -> append(this@unescape[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
