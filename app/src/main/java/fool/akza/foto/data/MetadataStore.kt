package fool.akza.foto.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fool.akza.foto.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local, searchable mirror of the metadata each file carries.
 *
 * Reading EXIF is far too slow to repeat on every keystroke, so the flattened
 * text of one item is written here once (see [MetadataIndexer]) and search runs
 * a single indexed-free `LIKE` over it. Nothing leaves the device: this is the
 * same metadata the info sheet shows, stored so it can be queried by name,
 * camera, date, place or PNG keyword.
 *
 * Rows are keyed by URI and stamped with the same `lastModified:size` identity
 * the thumbnail cache uses, so an edited or replaced file is re-indexed while
 * untouched files cost one stamp read.
 */
object MetadataStore {

    private const val DB_NAME = "metadata.db"
    private const val DB_VERSION = 1
    private const val TABLE = "metadata_entry"

    /** Upper bound on one row's searchable text; comments can be enormous. */
    private const val MaxSearchTextLength = 8 * 1024

    /** Upper bound on search hits, so a one-letter query can't return everything. */
    private const val MaxSearchHits = 2000

    @Volatile private var helper: Helper? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _version = MutableStateFlow(0)

    /** Bumped when the index is discarded, so surfacing code re-runs its pass. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun helper(context: Context): Helper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
    }

    /** Indexed stamps (`lastModified:size`) keyed by URI. */
    suspend fun stamps(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            helper(context).readableDatabase.query(
                TABLE,
                arrayOf("uri", "stamp"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getString(1))
                    }
                }
            }
        }.onFailure {
            AppLog.w("metadata: stamp read failed", it)
        }.getOrDefault(emptyMap())
    }

    /** Writes (or replaces) the searchable text of [item]. */
    suspend fun upsert(context: Context, item: MediaItem, text: String, stamp: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put("uri", item.uri.toString())
                    put("display_name", item.displayName)
                    put("bucket_name", item.bucketName)
                    put("mime_type", item.mimeType)
                    put("stamp", stamp)
                    put("search_text", normalizeForSearch(text).take(MaxSearchTextLength))
                    put("indexed_at", System.currentTimeMillis())
                }
                helper(context).writableDatabase.insertWithOnConflict(
                    TABLE,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }.onFailure {
                AppLog.w("metadata: write failed for ${item.uri}", it)
            }
        }

    /** URIs whose indexed metadata contains [query]. */
    suspend fun search(context: Context, query: String): Set<String> =
        withContext(Dispatchers.IO) {
            val needle = normalizeForSearch(query)
            if (needle.isEmpty()) return@withContext emptySet()
            runCatching {
                helper(context).readableDatabase.query(
                    TABLE,
                    arrayOf("uri"),
                    "search_text LIKE ? ESCAPE '\\'",
                    arrayOf(likePattern(needle)),
                    null,
                    null,
                    null,
                    MaxSearchHits.toString(),
                ).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            }.onFailure {
                AppLog.w("metadata: search failed", it)
            }.getOrDefault(emptySet())
        }

    /**
     * Throws the whole index away and asks for a new pass. Used when something
     * that changes what a file yields (the photo-location grant) flips, because
     * the files themselves did not change.
     */
    fun invalidate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { helper(app).writableDatabase.delete(TABLE, null, null) }
                .onSuccess { _version.update { it + 1 } }
                .onFailure { AppLog.w("metadata: invalidate failed", it) }
        }
    }

    /** Drops rows for files that no longer exist in the library. */
    suspend fun prune(context: Context, keep: Collection<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val db = helper(context).writableDatabase
            val indexed = db.query(TABLE, arrayOf("uri"), null, null, null, null, null)
                .use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            val stale = indexed - keep.toSet()
            stale.chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(TABLE, "uri IN ($placeholders)", chunk.toTypedArray())
            }
        }.onFailure {
            AppLog.w("metadata: prune failed", it)
        }
    }

    /**
     * Flattens one item's metadata into the text that search matches against.
     * Labels are resolved through [label], so the stored text uses the same
     * wording the info sheet shows ("Camera model", "Exposure time", …).
     */
    internal fun buildSearchText(
        label: (Int) -> String,
        item: MediaItem,
        info: MediaInfo,
    ): String = buildString {
        item.displayName?.let { appendLine(it) }
        info.name.takeIf { it != item.displayName }?.let { appendLine(it) }
        item.bucketName.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        item.relativePath?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        (info.mimeType ?: item.mimeType)?.let { appendLine(it) }
        appendLine(flattenSections(label, info.metadata))
    }.trim()

    /** `label: value` lines for every metadata field, in display order. */
    internal fun flattenSections(
        label: (Int) -> String,
        sections: List<MetadataSection>,
    ): String = buildString {
        sections.forEach { section ->
            section.fields.forEach { field ->
                val fieldLabel = when (val l = field.label) {
                    is MetadataLabel.Res -> label(l.id)
                    is MetadataLabel.Tag -> l.name
                }
                val value = when (val v = field.value) {
                    is MetadataValue.Text -> v.text
                    is MetadataValue.Res -> label(v.id)
                }
                appendLine("$fieldLabel: $value")
            }
        }
    }.trim()

    /**
     * Lowercases and turns punctuation into single spaces, so a query matches the
     * way a person types it: "ISO 400" finds `ISO: 400`, "37 46" finds
     * `37° 46' 29.6" N`, and "canon eos" finds `Camera model: Canon EOS R6`.
     */
    internal fun normalizeForSearch(text: String): String =
        text.lowercase().replace(SearchNoise, " ").trim()

    /** Everything that isn't a letter or digit is a separator for search. */
    private val SearchNoise = Regex("[^\\p{L}\\p{N}]+")

    /** Escapes `%` and `_` so a literal query can't turn into a wildcard. */
    internal fun likePattern(query: String): String {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    uri TEXT PRIMARY KEY NOT NULL,
                    display_name TEXT,
                    bucket_name TEXT,
                    mime_type TEXT,
                    stamp TEXT NOT NULL,
                    search_text TEXT NOT NULL,
                    indexed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The index is derived from the files themselves, so dropping and
            // rebuilding it on a schema change is correct and cheap.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
