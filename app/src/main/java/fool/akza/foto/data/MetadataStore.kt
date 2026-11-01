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
 * Local, searchable index of the metadata each file carries.
 *
 * Reading EXIF is far too slow to repeat on every keystroke, so each file's
 * metadata is reduced to a set of words once (see [MetadataIndexer]) and stored
 * here. Search is then token based and forgiving: any word order, partial words
 * and small typos match (see `FuzzyMatch.kt`), which is what people expect from
 * a search box rather than "the whole query must appear as one substring".
 *
 * Three tables:
 * - `metadata_entry` — one row per file: what it is and the stamp that says
 *   whether the words are current.
 * - `metadata_word` — the words that file contributes. Primary key order
 *   `(word, uri)` makes "which files have a word starting with `can`" a range
 *   scan.
 * - `metadata_lexicon` — the distinct words, so a typo fallback can score a few
 *   thousand short strings instead of every row in the index.
 *
 * Nothing leaves the device: this is the same metadata the info sheet shows.
 */
object MetadataStore {

    private const val DB_NAME = "metadata.db"
    private const val DB_VERSION = 2
    private const val ENTRY = "metadata_entry"
    private const val WORD = "metadata_word"
    private const val LEXICON = "metadata_lexicon"

    /** Words kept per file; the tail of a very chatty EXIF block is not worth it. */
    private const val MaxWordsPerFile = 96

    /** One token longer than this is a blob, not a word. */
    private const val MaxTokenLength = 64

    /** Query tokens considered; a 40-word "query" is someone leaning on a key. */
    private const val MaxQueryTokens = 6

    /** Words a single query token may expand to (prefix or typo matches). */
    private const val MaxWordsPerToken = 400

    /** Lexicon entries scored for a typo fallback. */
    private const val MaxLexiconScan = 50_000

    /** URIs a single word lookup may return before we stop collecting. */
    private const val MaxHits = 5_000

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

    /** Indexed stamps keyed by URI. */
    suspend fun stamps(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            helper(context).readableDatabase.query(
                ENTRY,
                arrayOf("uri", "stamp"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        }.onFailure {
            AppLog.w("metadata: stamp read failed", it)
        }.getOrDefault(emptyMap())
    }

    /**
     * One file's finished words, waiting to be written. The indexer reads a wave
     * of files in parallel and hands the results over as a list, so the writes
     * land in one transaction instead of one commit (and one fsync) per file.
     */
    data class Entry(
        val item: MediaItem,
        val words: List<String>,
        val stamp: String,
    )

    /**
     * Replaces the words of every [entries] item in a single transaction.
     *
     * SQLite has one writer anyway, so batching is both faster and simpler than
     * letting the parallel readers commit on their own: the caller keeps writing
     * on one thread while the reads fan out.
     */
    suspend fun upsertAll(context: Context, entries: List<Entry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        runCatching {
            val db = helper(context).writableDatabase
            val indexedAt = System.currentTimeMillis()
            db.beginTransaction()
            try {
                val clearWords = db.compileStatement("DELETE FROM $WORD WHERE uri = ?")
                val insertWord = db.compileStatement(
                    "INSERT OR IGNORE INTO $WORD(word, uri) VALUES(?, ?)",
                )
                val insertLexicon = db.compileStatement(
                    "INSERT OR IGNORE INTO $LEXICON(word) VALUES(?)",
                )
                // A wave shares most of its words (same camera, same album), so the
                // lexicon is filled once per distinct word rather than once per file.
                val waveWords = LinkedHashSet<String>()
                entries.forEach { entry ->
                    val uri = entry.item.uri.toString()
                    db.insertWithOnConflict(
                        ENTRY,
                        null,
                        ContentValues().apply {
                            put("uri", uri)
                            put("display_name", entry.item.displayName)
                            put("bucket_name", entry.item.bucketName)
                            put("mime_type", entry.item.mimeType)
                            put("stamp", entry.stamp)
                            put("indexed_at", indexedAt)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    clearWords.bindString(1, uri)
                    clearWords.executeUpdateDelete()
                    entry.words.forEach { word ->
                        insertWord.bindString(1, word)
                        insertWord.bindString(2, uri)
                        insertWord.executeInsert()
                        waveWords.add(word)
                    }
                }
                waveWords.forEach { word ->
                    insertLexicon.bindString(1, word)
                    insertLexicon.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.onFailure {
            AppLog.w("metadata: write failed for ${entries.size} entries", it)
        }
    }

    /**
     * URIs matching [rawQuery]. Every query token must match, but each may match
     * a different word — order doesn't matter, prefixes count, and small typos
     * are forgiven.
     */
    suspend fun search(context: Context, rawQuery: String): Set<String> =
        withContext(Dispatchers.IO) {
            val tokens = tokenizeForSearch(rawQuery).take(MaxQueryTokens)
            if (tokens.isEmpty()) return@withContext emptySet()
            runCatching {
                val db = helper(context).readableDatabase
                var matches: Set<String>? = null
                for (token in tokens) {
                    val words = wordsWithPrefix(db, token).ifEmpty { wordsWithinEdits(db, token) }
                    val uris = urisForWords(db, words)
                    if (uris.isEmpty()) return@runCatching emptySet()
                    matches = matches?.intersect(uris) ?: uris
                    if (matches.isEmpty()) return@runCatching emptySet()
                }
                matches.orEmpty().take(MaxHits).toSet()
            }.onFailure {
                AppLog.w("metadata: search failed", it)
            }.getOrDefault(emptySet())
        }

    /** Distinct indexed words that start with [token]. */
    private fun wordsWithPrefix(db: SQLiteDatabase, token: String): List<String> =
        db.rawQuery(
            "SELECT word FROM $LEXICON WHERE word >= ? AND word < ? LIMIT $MaxWordsPerToken",
            arrayOf(token, token + '\uFFFF'),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    /**
     * Typo fallback: score the lexicon's words of a plausible length. This only
     * runs when the prefix scan found nothing, which is exactly the "I mistyped
     * it" case.
     */
    private fun wordsWithinEdits(db: SQLiteDatabase, token: String): List<String> {
        val budget = maxEditsFor(token)
        if (budget == 0) return emptyList()
        // Literals, not bound arguments: rawQuery binds everything as TEXT, and
        // SQLite compares a TEXT '7' as greater than any INTEGER, which would
        // make `length(word) BETWEEN '5' AND '7'` always false.
        val minLength = token.length - budget
        val maxLength = token.length + budget
        return db.rawQuery(
            "SELECT word FROM $LEXICON WHERE length(word) BETWEEN $minLength AND $maxLength " +
                "LIMIT $MaxLexiconScan",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val word = cursor.getString(0)
                    if (matchToken(token, word) == TokenMatch.FUZZY) {
                        add(word)
                        if (size >= MaxWordsPerToken) break
                    }
                }
            }
        }
    }

    private fun urisForWords(db: SQLiteDatabase, words: List<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in words.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT DISTINCT uri FROM $WORD WHERE word IN ($placeholders) LIMIT $MaxHits",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) out += cursor.getString(0)
                if (out.size >= MaxHits) break
            }
        }
        return out
    }

    /**
     * Throws the whole index away and asks for a new pass. Used when something
     * that changes what a file yields (the photo-location grant) flips, because
     * the files themselves did not change.
     */
    fun invalidate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val db = helper(app).writableDatabase
                db.beginTransaction()
                try {
                    db.delete(ENTRY, null, null)
                    db.delete(WORD, null, null)
                    db.delete(LEXICON, null, null)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }.onSuccess { _version.update { it + 1 } }
                .onFailure { AppLog.w("metadata: invalidate failed", it) }
        }
    }

    /** How many files the index currently knows about. */
    suspend fun count(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            helper(context).readableDatabase
                .rawQuery("SELECT COUNT(*) FROM $ENTRY", null)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }.getOrDefault(0)
    }

    /** Bytes the index occupies on disk, WAL included, so Settings can show it. */
    fun diskBytes(context: Context): Long {
        val db = context.applicationContext.getDatabasePath(DB_NAME)
        return listOf(db, java.io.File("${db.path}-wal"), java.io.File("${db.path}-shm"))
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Empties the index and gives the space back to the filesystem.
     *
     * Unlike [invalidate] this does not ask for a rebuild: it is the user asking
     * for the stored words to be gone, so the index stays empty until the library
     * loads again (app start, pull-to-refresh), which rebuilds it from the files.
     */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val db = helper(context).writableDatabase
            db.beginTransaction()
            try {
                db.delete(ENTRY, null, null)
                db.delete(WORD, null, null)
                db.delete(LEXICON, null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // The word table is the bulk of the file: without compacting, the tens
            // of megabytes an index of a big library grew to would stay allocated.
            db.execSQL("VACUUM")
            // Under WAL the compacted pages are still in the log; folding them into
            // the database is what actually returns the bytes to the filesystem.
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        }.onFailure {
            AppLog.w("metadata: clear failed", it)
        }
    }

    /** Drops rows for files that no longer exist in the library. */
    suspend fun prune(context: Context, keep: Collection<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val db = helper(context).writableDatabase
            val indexed = db.query(ENTRY, arrayOf("uri"), null, null, null, null, null)
                .use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            (indexed - keep.toSet()).chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(ENTRY, "uri IN ($placeholders)", chunk.toTypedArray())
                db.delete(WORD, "uri IN ($placeholders)", chunk.toTypedArray())
            }
            // Words orphaned by the deletes above must not keep matching.
            db.execSQL(
                "DELETE FROM $LEXICON WHERE NOT EXISTS " +
                    "(SELECT 1 FROM $WORD WHERE $WORD.word = $LEXICON.word)",
            )
        }.onFailure {
            AppLog.w("metadata: prune failed", it)
        }
    }

    /**
     * The words that make [item] findable: its name, album and path, its MIME
     * type, and every metadata label and value the info sheet shows.
     */
    internal fun buildSearchWords(
        label: (Int) -> String,
        item: MediaItem,
        sections: List<MetadataSection>,
    ): List<String> {
        val words = LinkedHashSet(buildItemWords(item))
        val extra = LinkedHashSet<String>()
        metadataWords(label, sections).forEach { extra += it }
        words += extra.take(MaxWordsPerFile - words.size).filter { it.length <= MaxTokenLength }
        return words.take(MaxWordsPerFile)
    }

    /** The name/album/path/type a file is findable by even if it can't be read. */
    internal fun buildItemWords(item: MediaItem): List<String> {
        val words = LinkedHashSet<String>()
        listOfNotNull(item.displayName, item.bucketName, item.relativePath, item.mimeType)
            .forEach { text ->
                tokenizeForSearch(text)
                    .filter { it.length <= MaxTokenLength }
                    .forEach { words += it }
            }
        return words.take(MaxWordsPerFile)
    }

    /** The words a metadata block contributes, in display order. */
    internal fun metadataWords(
        label: (Int) -> String,
        sections: List<MetadataSection>,
    ): List<String> {
        val words = LinkedHashSet<String>()
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
                tokenizeForSearch(fieldLabel).forEach { words += it }
                tokenizeForSearch(value).forEach { words += it }
            }
        }
        return words.toList()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        init {
            // The index is written in the background while the search screen may be
            // reading it, so run in WAL: readers then see the last committed
            // snapshot instead of waiting for the writer to finish, and commits are
            // an append rather than a journal rewrite.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Every row here is derived from a file we can read again, so a
            // crash or power loss may cost the last few seconds of indexing but
            // must never cost a corrupt index. That is exactly what NORMAL under
            // WAL guarantees, and it drops the fsync per commit that made a pass
            // wait on storage.
            db.execSQL("PRAGMA synchronous = NORMAL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $ENTRY (
                    uri TEXT PRIMARY KEY NOT NULL,
                    display_name TEXT,
                    bucket_name TEXT,
                    mime_type TEXT,
                    stamp TEXT NOT NULL,
                    indexed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $WORD (
                    word TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    PRIMARY KEY (word, uri)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_metadata_word_uri ON $WORD(uri)")
            db.execSQL(
                """
                CREATE TABLE $LEXICON (
                    word TEXT PRIMARY KEY NOT NULL
                ) WITHOUT ROWID
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The index is derived from the files themselves, so dropping and
            // rebuilding it on a schema change is correct and cheap.
            db.execSQL("DROP TABLE IF EXISTS $ENTRY")
            db.execSQL("DROP TABLE IF EXISTS $WORD")
            db.execSQL("DROP TABLE IF EXISTS $LEXICON")
            onCreate(db)
        }
    }
}
