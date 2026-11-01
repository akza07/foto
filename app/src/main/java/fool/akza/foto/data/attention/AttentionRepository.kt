package fool.akza.foto.data.attention

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Local-only log of viewer attention (time spent per photo/video), backing the
 * Insights screen and its smart tile.
 *
 * One row per viewer visit, replaced in place by periodic checkpoints so a long
 * look is crash-safe without inflating the visit count. Everything stays on
 * device: nothing here is uploaded, and Settings can wipe it in one action.
 */
object AttentionRepository {

    private const val DB_NAME = "attention.db"
    private const val DB_VERSION = 2
    private const val TABLE = "attention_session"

    /** Rows older than this are pruned so the log can't grow forever. */
    private const val HISTORY_LIMIT_MS = 730L * 24 * 60 * 60 * 1000

    private val ITEM_COLUMNS =
        "uri, media_id, display_name, bucket_id, bucket_name, relative_path, " +
            "saf_tree_uri, mime_type, last_modified, size_bytes, date_added, is_video"

    private val AGGREGATE_SQL = """
        SELECT media_key,
               MAX(uri) AS uri,
               MAX(media_id) AS media_id,
               MAX(display_name) AS display_name,
               MAX(bucket_id) AS bucket_id,
               MAX(bucket_name) AS bucket_name,
               MAX(relative_path) AS relative_path,
               MAX(saf_tree_uri) AS saf_tree_uri,
               MAX(mime_type) AS mime_type,
               MAX(last_modified) AS last_modified,
               MAX(size_bytes) AS size_bytes,
               MAX(date_added) AS date_added,
               MAX(is_video) AS is_video,
               SUM(active_ms) AS total_ms,
               SUM(played_ms) AS played_ms,
               COUNT(*) AS visits,
               SUM(qualified) AS watches,
               MAX(active_ms) AS longest_ms,
               MAX(started_at) AS last_started
        FROM $TABLE
        WHERE started_at >= ?
        GROUP BY media_key
        ORDER BY total_ms DESC
    """.trimIndent()

    @Volatile private var helper: Helper? = null
    @Volatile private var pruned = false

    private val _version = MutableStateFlow(0)

    /** Bumped after every write/clear so surfaces can refresh their query. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun helper(context: Context): Helper {
        helper?.let { return it }
        return synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
    }

    /** Inserts or replaces one visit. Called on IO by the tracker. */
    suspend fun record(context: Context, session: AttentionSession) = withContext(Dispatchers.IO) {
        if (session.activeMs < AttentionMinSessionMs) return@withContext
        runCatching {
            val db = helper(context).writableDatabase
            db.insertWithOnConflict(TABLE, null, session.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            if (!pruned) {
                pruned = true
                val cutoff = System.currentTimeMillis() - HISTORY_LIMIT_MS
                db.delete(TABLE, "started_at < ?", arrayOf(cutoff.toString()))
            }
        }.onSuccess {
            _version.update { it + 1 }
        }.onFailure {
            AppLog.w("attention: write failed", it)
        }
    }

    /** Aggregated rows for [period], newest-first by total time (limit 200). */
    suspend fun entries(context: Context, period: AttentionPeriod): List<AttentionEntry> =
        withContext(Dispatchers.IO) {
            val since = period.sinceDays
                ?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
                ?: 0L
            runCatching {
                helper(context).readableDatabase.rawQuery(AGGREGATE_SQL, arrayOf(since.toString()))
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.toEntry())
                        }
                    }
            }.onFailure {
                AppLog.w("attention: query failed", it)
            }.getOrDefault(emptyList())
        }

    /** Data for the Albums smart tile, or null while nothing has been recorded. */
    suspend fun tile(context: Context): AttentionTile? = withContext(Dispatchers.IO) {
        runCatching {
            val db = helper(context).readableDatabase
            val summary = db.readSummary()
            if (summary.sessions == 0) return@runCatching null
            // The tile backdrop is the item with the most accumulated viewing
            // time (the same number "Most watched" ranks on), not one long look.
            val preview = db.rawQuery(
                "SELECT $ITEM_COLUMNS FROM $TABLE " +
                    "GROUP BY media_key ORDER BY SUM(active_ms) DESC LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toItem() else null
            }
            AttentionTile(preview = preview, items = summary.items, sessions = summary.sessions)
        }.onFailure {
            AppLog.w("attention: tile query failed", it)
        }.getOrNull()
    }

    /** Visit/item counts for the Settings clear-history row. */
    suspend fun summary(context: Context): AttentionSummary = withContext(Dispatchers.IO) {
        runCatching {
            helper(context).readableDatabase.readSummary()
        }.onFailure {
            AppLog.w("attention: summary failed", it)
        }.getOrDefault(AttentionSummary(0, 0))
    }

    /** Wipes the whole history. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            helper(context).writableDatabase.delete(TABLE, null, null)
        }.onSuccess {
            _version.update { it + 1 }
        }.onFailure {
            AppLog.w("attention: clear failed", it)
        }
    }

    /** Drops the history of items that no longer exist (deleted for good). */
    suspend fun forget(context: Context, mediaKeys: Collection<String>) =
        withContext(Dispatchers.IO) {
            if (mediaKeys.isEmpty()) return@withContext
            runCatching {
                val db = helper(context).writableDatabase
                mediaKeys.forEach { key ->
                    db.delete(TABLE, "media_key = ?", arrayOf(key))
                }
            }.onSuccess {
                _version.update { it + 1 }
            }.onFailure {
                AppLog.w("attention: forget failed", it)
            }
        }

    private fun SQLiteDatabase.readSummary(): AttentionSummary {
        return rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT media_key) FROM $TABLE",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                AttentionSummary(sessions = cursor.getInt(0), items = cursor.getInt(1))
            } else {
                AttentionSummary(0, 0)
            }
        }
    }

    private fun AttentionSession.toValues(): ContentValues = ContentValues().apply {
        put("visit_id", visitId)
        put("media_key", mediaKey)
        put("uri", item.uri.toString())
        put("media_id", item.id)
        put("display_name", item.displayName)
        put("bucket_id", item.bucketId)
        put("bucket_name", item.bucketName)
        put("relative_path", item.relativePath)
        put("saf_tree_uri", item.safTreeUri)
        put("mime_type", item.mimeType)
        put("last_modified", item.lastModified)
        put("size_bytes", item.sizeBytes)
        put("date_added", item.dateAdded)
        put("is_video", if (item.isVideo) 1 else 0)
        put("started_at", startedAt)
        put("active_ms", activeMs)
        put("played_ms", playedMs)
        put("qualified", if (qualified) 1 else 0)
    }

    private fun Cursor.toItem(): MediaItem = MediaItem(
        id = getLong(getColumnIndexOrThrow("media_id")),
        uri = Uri.parse(getString(getColumnIndexOrThrow("uri"))),
        bucketId = getString(getColumnIndexOrThrow("bucket_id")).orEmpty(),
        bucketName = getString(getColumnIndexOrThrow("bucket_name")).orEmpty(),
        relativePath = getStringOrNull("relative_path"),
        dateAdded = getLong(getColumnIndexOrThrow("date_added")),
        isVideo = getInt(getColumnIndexOrThrow("is_video")) != 0,
        displayName = getStringOrNull("display_name"),
        safTreeUri = getStringOrNull("saf_tree_uri"),
        mimeType = getStringOrNull("mime_type"),
        lastModified = getLong(getColumnIndexOrThrow("last_modified")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
    )

    private fun Cursor.toEntry(): AttentionEntry = AttentionEntry(
        mediaKey = getString(getColumnIndexOrThrow("media_key")),
        item = toItem(),
        totalMs = getLong(getColumnIndexOrThrow("total_ms")),
        playedMs = getLong(getColumnIndexOrThrow("played_ms")),
        visits = getInt(getColumnIndexOrThrow("visits")),
        watches = getInt(getColumnIndexOrThrow("watches")),
        longestMs = getLong(getColumnIndexOrThrow("longest_ms")),
        lastViewedAt = getLong(getColumnIndexOrThrow("last_started")),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    visit_id TEXT PRIMARY KEY NOT NULL,
                    media_key TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    media_id INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT,
                    bucket_id TEXT,
                    bucket_name TEXT,
                    relative_path TEXT,
                    saf_tree_uri TEXT,
                    mime_type TEXT,
                    last_modified INTEGER NOT NULL DEFAULT 0,
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    date_added INTEGER NOT NULL DEFAULT 0,
                    is_video INTEGER NOT NULL DEFAULT 0,
                    started_at INTEGER NOT NULL,
                    active_ms INTEGER NOT NULL,
                    played_ms INTEGER NOT NULL,
                    qualified INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_attention_key ON $TABLE(media_key)")
            db.execSQL("CREATE INDEX idx_attention_started ON $TABLE(started_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The attention log is derived, local-only analytics (not user data),
            // so recreating it on a schema change is the safe, simple migration:
            // the aggregate query can never run against a half-old table.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
