package fool.akza.foto.data.semantic

import android.content.Context
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Content search facade: turns a text query into ranked library URIs.
 *
 * Text and images share EmbeddingGemma's embedding space, which is what makes
 * "receipt", "sunset over water" or "my dog on a sofa" work without any labels.
 * Image↔image similarity is used by the Albums grouping pass through the same
 * stored vectors.
 */
object SemanticSearch {

    /** Provider-side floor. Scores vary with how "photographic" the library is,
     *  so the floor stays low and the confidence gate does the pruning; this
     *  only avoids asking AppSearch for the whole index. */
    private const val TextQueryFloor = 0.15f
    private const val TextScoreMargin = 0.05f
    private const val MaxTextResults = 60

    private val MultiSpace = Regex("\\s+")
    private val CamelBoundary = Regex("([a-z0-9])([A-Z])")

    /** Colour words are the common concatenation in photo queries
     *  ("blueshirt", "redcar"); splitting them is enough to make those usable
     *  without a dictionary. */
    private val ColorPrefixes = listOf(
        "black", "brown", "green", "orange", "purple", "yellow",
        "blue", "gray", "grey", "pink", "red", "white",
    )

    suspend fun byText(
        context: Context,
        query: String,
        limit: Int = 80,
    ): List<VectorHit> = withContext(Dispatchers.Default) {
        val raw = query.trim()
        if (raw.isEmpty()) return@withContext emptyList()
        val normalized = normalizeQuery(raw)
        SemanticModel.refresh(context)
        if (!SemanticModel.status.value.isReady) return@withContext emptyList()

        val ready = GemmaEmbedder.ensureReady(context).isSuccess
        if (!ready) return@withContext emptyList()
        val vector = GemmaEmbedder.embedQuery(normalized) ?: return@withContext emptyList()

        val hits = runCatchingCancellable {
            PhotoVectorStore.search(
                context = context,
                queryVector = vector,
                minSimilarity = TextQueryFloor,
                limit = 120,
            )
        }.getOrElse {
            AppLog.w("semantic: text search failed", it)
            return@withContext emptyList()
        }

        val stats = SemanticScoring.stats(hits.map { it.score })
        val selected = SemanticScoring.nearestBest(
            items = hits,
            margin = TextScoreMargin,
            max = minOf(MaxTextResults, limit),
            stats = stats,
        ) { it.score }

        // Tuning hook: a real library is the only place these thresholds can be
        // judged. Enable with `adb shell setprop log.tag.FotoSemantic DEBUG`.
        if (android.util.Log.isLoggable("FotoSemantic", android.util.Log.DEBUG)) {
            android.util.Log.i(
                "FotoSemantic",
                "query=\"$raw\" normalized=\"$normalized\" candidates=${stats.count} " +
                    "top=${fmt(stats.top)} mean=${fmt(stats.mean)} " +
                    "std=${fmt(stats.stdDev)} threshold=${fmt(stats.threshold)} " +
                    "confident=${stats.confident} kept=${selected.size}",
            )
            if (selected.isNotEmpty()) {
                android.util.Log.i(
                    "FotoSemantic",
                    selected.take(8).joinToString { hit ->
                        "${hit.uri.substringAfterLast('/')}=${fmt(hit.score)}"
                    },
                )
            }
        }
        selected
    }

    /**
     * Normalizes a raw query before it is embedded:
     *
     * - camelCase boundaries become spaces (`BlueShirt` → `Blue Shirt`),
     * - simple colour+object concatenations are split (`blueshirt` → `blue shirt`),
     * - whitespace is trimmed and collapsed,
     * - short 1–2 word queries become a visual phrase (`a photo of blue shirt`);
     *   3+ word queries are already descriptive and are passed through.
     *
     * The EmbeddingGemma task prefix is added later by [GemmaEmbedder].
     */
    internal fun normalizeQuery(raw: String): String {
        val collapsed = raw.trim().replace(MultiSpace, " ")
        if (collapsed.isEmpty()) return ""
        val spaced = collapsed.replace(CamelBoundary, "$1 $2")
        val words = spaced.split(' ')
            .filter { it.isNotEmpty() }
            .flatMap(::splitCompound)
        val phrase = words.joinToString(" ")
        return if (words.size in 1..2) "a photo of $phrase" else phrase
    }

    /** Splits a leading colour from a longer concatenated word; leaves normal
     *  words and colour words themselves untouched. */
    private fun splitCompound(word: String): List<String> {
        if (word.length < 7) return listOf(word)
        val lower = word.lowercase(Locale.ROOT)
        val color = ColorPrefixes.firstOrNull {
            lower.startsWith(it) && lower.length - it.length >= 4
        } ?: return listOf(word)
        return listOf(word.substring(0, color.length), word.substring(color.length))
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value)
}
