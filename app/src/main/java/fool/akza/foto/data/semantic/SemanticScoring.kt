package fool.akza.foto.data.semantic

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The small amount of vector math and ranking logic the semantic pipeline relies
 * on. Kept pure and separate so thresholds can be tested without an Android
 * runtime, a model file, or AppSearch.
 */
internal object SemanticScoring {

    /**
     * EmbeddingGemma's text/image scores sit in a compressed band: a true match
     * is roughly 0.72–0.80 and an unrelated photo often still scores ~0.60.
     * A fixed cutoff therefore either admits half the library or cuts a good
     * set short, so a result set must first stand out from its own distribution.
     */
    const val Z_THRESHOLD = 1.75f

    /** Reject even a standout top score when the whole pass is this weak. */
    const val MIN_TOP_SCORE = 0.60f

    /** Below this many candidates the standard deviation is dominated by the
     *  matches themselves (a 10-photo library with 3 sunsets looks "flat"), so
     *  a clear gap over the mean is also accepted. */
    const val SMALL_SAMPLE = 20
    const val SMALL_SAMPLE_GAP = 0.03f

    data class ScoreStats(
        val count: Int,
        val top: Float,
        val mean: Float,
        val stdDev: Float,
        val threshold: Float,
        val confident: Boolean,
    )

    /**
     * Distribution of the candidate scores returned by AppSearch.
     *
     * With fewer than three samples the standard deviation is not meaningful, so
     * only the absolute floor guards those passes.
     */
    fun stats(scores: List<Float>): ScoreStats {
        if (scores.isEmpty()) {
            return ScoreStats(0, 0f, 0f, 0f, MIN_TOP_SCORE, false)
        }
        val top = scores.max()
        val mean = scores.average().toFloat()
        var variance = 0f
        for (score in scores) {
            val delta = score - mean
            variance += delta * delta
        }
        val stdDev = sqrt(variance / scores.size)
        val zGate = if (scores.size >= 3) {
            max(mean + Z_THRESHOLD * stdDev, MIN_TOP_SCORE)
        } else {
            MIN_TOP_SCORE
        }
        // On a small result set, accept either gate: the z-test can reject a
        // real match when a large share of the candidates are matches.
        val gapGate = max(MIN_TOP_SCORE, mean + SMALL_SAMPLE_GAP)
        val smallSample = scores.size < SMALL_SAMPLE
        val threshold = if (smallSample) minOf(zGate, gapGate) else zGate
        return ScoreStats(
            count = scores.size,
            top = top,
            mean = mean,
            stdDev = stdDev,
            threshold = threshold,
            confident = top >= threshold,
        )
    }

    /**
     * Returns results only when the best score clears the confidence gate, then
     * keeps those within [margin] of the top score, capped at [max].
     */
    fun <T> nearestBest(
        items: List<T>,
        margin: Float,
        max: Int,
        score: (T) -> Float,
    ): List<T> {
        if (items.isEmpty() || max <= 0) return emptyList()
        return nearestBest(items, margin, max, stats(items.map(score)), score)
    }

    fun <T> nearestBest(
        items: List<T>,
        margin: Float,
        max: Int,
        stats: ScoreStats,
        score: (T) -> Float,
    ): List<T> {
        if (items.isEmpty() || max <= 0 || !stats.confident) return emptyList()
        return items.filter { score(it) >= stats.top - margin }.take(max)
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
