package fool.akza.foto.data.similar

import android.content.Context
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.semantic.PhotoVectorStore
import fool.akza.foto.data.semantic.SemanticIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the visual-similarity pass for the Albums surface.
 *
 * The scan is intentionally lazy: it runs only when the Albums tab (or the
 * Similar screen) is visible, and it is keyed by the library identity so a
 * refresh after a delete does not re-decode every photo. The result drives both
 * the smart tile and the recommendation inbox.
 */
object SimilarityRepository {

    private val _report = MutableStateFlow<SimilarReport?>(null)
    val report: StateFlow<SimilarReport?> = _report.asStateFlow()

    private val _progress = MutableStateFlow(SimilarProgress())
    val progress: StateFlow<SimilarProgress> = _progress.asStateFlow()

    private val _analyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _analyzing.asStateFlow()

    private val mutex = Mutex()
    private var lastKey: String? = null
    private var lastNames: String? = null
    private var lastVectorGeneration: Int = -1

    /** Recomputes when the library (or visible folder set) changed. */
    suspend fun refresh(
        context: Context,
        items: List<MediaItem>,
        existingNames: Map<String, Set<String>> = emptyMap(),
    ) {
        val key = libraryKey(items)
        val namesKey = existingNames.entries
            .sortedBy { it.key }
            .joinToString("|") { (folder, names) -> "$folder:${names.sorted().joinToString(",")}" }
        // New embeddings change the grouping decision, so the report must be
        // rebuilt as the content pass fills in.
        val vectorGeneration = PhotoVectorStore.generation.value
        if (key == lastKey &&
            namesKey == lastNames &&
            vectorGeneration == lastVectorGeneration &&
            _report.value != null
        ) {
            return
        }

        mutex.withLock {
            if (key == lastKey &&
                namesKey == lastNames &&
                vectorGeneration == lastVectorGeneration &&
                _report.value != null
            ) {
                return
            }
            _analyzing.value = true
            _progress.value = SimilarProgress(done = 0, total = items.size)
            try {
                // Embeddings refine scene/pose grouping; photos that are not
                // indexed yet keep the perceptual fallback, so Albums still
                // works while the first pass is warming up.
                val embeddings = SemanticIndexer.vectors(context)
                val result = withContext(Dispatchers.IO) {
                    analyzeVisualSimilarity(
                        context = context,
                        items = items,
                        existingNames = existingNames,
                        embeddings = embeddings,
                        onProgress = { done, total ->
                            _progress.value = SimilarProgress(done = done, total = total)
                        },
                    )
                }
                _report.value = result
                lastKey = key
                lastNames = namesKey
                lastVectorGeneration = vectorGeneration
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.w("similar: scan failed", t)
                _report.value = SimilarReport(
                    duplicates = emptyList(),
                    similarGroups = emptyList(),
                    possibleGroups = emptyList(),
                    preview = null,
                    bytesReclaimable = 0,
                )
                lastKey = key
                lastNames = namesKey
                lastVectorGeneration = vectorGeneration
            } finally {
                _progress.value = SimilarProgress()
                _analyzing.value = false
            }
        }
    }

    /** Forces the next visible Albums pass to rescan. */
    fun invalidate() {
        lastKey = null
        lastNames = null
        lastVectorGeneration = -1
        _report.value = null
    }

    fun clear() {
        _report.value = null
        _progress.value = SimilarProgress()
        _analyzing.value = false
        invalidate()
    }

    private fun libraryKey(items: List<MediaItem>): String {
        // Order-insensitive: a sort-order change must not force a full re-decode.
        var hash = 17L
        for (item in items.sortedBy { it.uri.toString() }) {
            hash = hash * 31 + item.uri.hashCode()
            hash = hash * 31 + item.lastModified
            hash = hash * 31 + item.sizeBytes
            hash = hash * 31 + item.dateAdded
        }
        return "${items.size}:$hash"
    }
}
