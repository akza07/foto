package fool.akza.foto.data.semantic

import android.content.Context
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content search facade: turns a text query into ranked library URIs.
 *
 * Text and images share EmbeddingGemma's embedding space, which is what makes
 * "receipt", "sunset over water" or "my dog on a sofa" work without any labels.
 * Image↔image similarity is used by the Albums grouping pass through the same
 * stored vectors.
 */
object SemanticSearch {

    /** Provider-side floor. Text↔image scores vary with how "photographic" the
     *  library is, so the floor stays low and the relative [TextScoreMargin]
     *  does the pruning; this only avoids asking AppSearch for the whole index. */
    private const val TextQueryFloor = 0.15f
    private const val TextScoreMargin = 0.05f
    private const val MaxTextResults = 60

    suspend fun byText(        context: Context,
        query: String,
        limit: Int = 80,
    ): List<VectorHit> = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        SemanticModel.refresh(context)
        if (!SemanticModel.status.value.isReady) return@withContext emptyList()

        val ready = GemmaEmbedder.ensureReady(context).isSuccess
        if (!ready) return@withContext emptyList()
        val vector = GemmaEmbedder.embedQuery(trimmed) ?: return@withContext emptyList()
        runCatchingCancellable {
            val hits = PhotoVectorStore.search(
                context = context,
                queryVector = vector,
                minSimilarity = TextQueryFloor,
                limit = 120,
            )
            SemanticScoring.nearestBest(hits, TextScoreMargin, minOf(MaxTextResults, limit)) {
                it.score
            }
        }.getOrElse {
            AppLog.w("semantic: text search failed", it)
            emptyList()
        }.also { hits ->
            // Tuning hook: a real library is the only place these thresholds
            // can be judged, and the scores are otherwise invisible. Enable
            // with `adb shell setprop log.tag.FotoSemantic DEBUG`.
            if (android.util.Log.isLoggable("FotoSemantic", android.util.Log.DEBUG)) {
                android.util.Log.i(
                    "FotoSemantic",
                    "query=\"$trimmed\" " + hits.take(8).joinToString { hit ->
                        "${hit.uri.substringAfterLast('/')}=${"%.3f".format(hit.score)}"
                    },
                )
            }
        }
    }
}
