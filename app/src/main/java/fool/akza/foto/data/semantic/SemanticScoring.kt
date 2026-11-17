package fool.akza.foto.data.semantic

import kotlin.math.sqrt

/**
 * The small amount of vector math the semantic pipeline relies on.
 *
 * Kept pure and separate so the thresholds can be tested without an Android
 * runtime, a model file, or AppSearch.
 */
internal object SemanticScoring {

    /**
     * Relative cutoff for text→photo results.
     *
     * EmbeddingGemma's text/image scores sit in a compressed band: a strong
     * match is around 0.70–0.80 and an unrelated photo often still scores
     * ~0.60. An absolute cutoff therefore either admits half the library or
     * drops the tail of a good set, so results within [margin] of the best
     * score are kept.
     */
    fun <T> nearestBest(
        items: List<T>,
        margin: Float,
        max: Int,
        score: (T) -> Float,
    ): List<T> {
        if (items.isEmpty() || max <= 0) return emptyList()
        val best = items.maxOf(score)
        return items.filter { score(it) >= best - margin }.take(max)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until size) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }
}
