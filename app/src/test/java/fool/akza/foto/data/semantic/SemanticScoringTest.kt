package fool.akza.foto.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticScoringTest {

    /** A realistic candidate distribution: the bulk of the library sits in the
     *  0.58–0.64 noise band, with optional true matches above it. */
    private fun distribution(vararg matches: Float): List<Float> = buildList {
        repeat(30) { add(0.58f + it * 0.002f) }
        addAll(matches.toList())
    }

    @Test
    fun `cosine of a vector with itself is one`() {
        val v = floatArrayOf(0.1f, -0.4f, 0.8f, 2.5f)
        assertEquals(1f, SemanticScoring.cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine is scale invariant`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        assertEquals(1f, SemanticScoring.cosine(a, b), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(
            0f,
            SemanticScoring.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6f,
        )
    }

    @Test
    fun `cosine handles empty and zero vectors without dividing by zero`() {
        assertEquals(0f, SemanticScoring.cosine(FloatArray(0), FloatArray(0)), 0f)
        assertEquals(0f, SemanticScoring.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test
    fun `z score gate accepts a clear match`() {
        val scores = distribution(0.80f, 0.77f, 0.70f)
        val stats = SemanticScoring.stats(scores)
        assertTrue(stats.confident)
        assertTrue(stats.top > stats.threshold)
    }

    @Test
    fun `z score gate rejects a flat distribution with no true match`() {
        // Noisy band only: top 0.64 against a 0.60–0.64 spread is not a
        // meaningful standout, which is the "blueshirt" false-positive case.
        val scores = List(40) { 0.60f + (it % 5) * 0.01f }
        val stats = SemanticScoring.stats(scores)
        assertFalse(stats.confident)
        assertTrue(SemanticScoring.nearestBest(scores, 0.05f, 60) { it }.isEmpty())
    }

    @Test
    fun `minimum confidence floor rejects a weak single hit`() {
        val stats = SemanticScoring.stats(listOf(0.50f))
        assertFalse(stats.confident)
        assertTrue(SemanticScoring.nearestBest(listOf(0.50f), 0.05f, 60) { it }.isEmpty())
    }

    @Test
    fun `single strong hit is kept`() {
        val kept = SemanticScoring.nearestBest(listOf(0.70f), 0.05f, 60) { it }
        assertEquals(listOf(0.70f), kept)
    }

    @Test
    fun `nearestBest keeps only results within the margin of the top score`() {
        // One clear match above the noise band; the 0.66 candidate is neither
        // close to the top nor a true match.
        val scores = distribution(0.82f, 0.78f, 0.66f)
        val kept = SemanticScoring.nearestBest(scores, margin = 0.05f, max = 60) { it }
        assertEquals(listOf(0.82f, 0.78f), kept)
    }

    @Test
    fun `nearestBest applies the cap`() {
        val scores = List(10) { 0.8f }
        val kept = SemanticScoring.nearestBest(scores, margin = 0.05f, max = 3) { it }
        assertEquals(3, kept.size)
    }

    @Test
    fun `nearestBest returns nothing for an empty set`() {
        assertTrue(SemanticScoring.nearestBest(emptyList<Float>(), 0.05f, 60) { it }.isEmpty())
    }

    @Test
    fun `stats reports mean standard deviation and threshold`() {
        val stats = SemanticScoring.stats(listOf(0.6f, 0.7f, 0.8f))
        assertEquals(3, stats.count)
        assertEquals(0.8f, stats.top, 1e-5f)
        assertEquals(0.7f, stats.mean, 1e-5f)
        assertEquals(0.08165f, stats.stdDev, 1e-4f)
        // Small sample: the gap gate (mean + 0.03) is the looser of the two.
        assertEquals(0.73f, stats.threshold, 1e-4f)
        assertTrue(stats.confident)
    }

    @Test
    fun `small library with several true matches still passes`() {
        // The emulator's 10-photo library: three sunsets at 0.696 and the rest
        // in the 0.60–0.63 band. The z-test alone rejects this; the gap gate
        // keeps the obvious result.
        val scores = listOf(
            0.696f, 0.695f, 0.694f,
            0.60f, 0.61f, 0.62f, 0.63f, 0.60f, 0.61f, 0.62f,
        )
        val stats = SemanticScoring.stats(scores)
        assertTrue(stats.confident)
        val kept = SemanticScoring.nearestBest(scores, 0.05f, 60) { it }
        assertEquals(listOf(0.696f, 0.695f, 0.694f), kept)
    }

    @Test
    fun `small flat library is still rejected`() {
        val scores = List(10) { 0.60f + (it % 5) * 0.01f }
        assertFalse(SemanticScoring.stats(scores).confident)
        assertTrue(SemanticScoring.nearestBest(scores, 0.05f, 60) { it }.isEmpty())
    }

    @Test
    fun `document query at the top of the band passes`() {
        // "receipt" on the small test library: top 0.622, mean 0.581.
        val scores = listOf(
            0.622f, 0.61f, 0.60f, 0.59f, 0.58f, 0.57f, 0.58f, 0.57f, 0.56f, 0.57f,
        )
        assertTrue(SemanticScoring.stats(scores).confident)
    }

    @Test
    fun `noise without a real match is rejected`() {
        // "blueshirt" with no blue shirt: top 0.614, mean 0.592, std 0.019
        // (z ≈ 1.16) and a 0.022 gap, so neither gate accepts it.
        val scores = listOf(
            0.614f, 0.610f, 0.606f, 0.602f, 0.596f, 0.590f, 0.586f, 0.580f, 0.574f, 0.562f,
        )
        assertFalse(SemanticScoring.stats(scores).confident)
    }
}
