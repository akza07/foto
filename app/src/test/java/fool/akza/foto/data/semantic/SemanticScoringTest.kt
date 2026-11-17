package fool.akza.foto.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticScoringTest {

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
    fun `nearestBest keeps only results within the margin of the top score`() {
        // Real text→image scores: three sunset shots (~0.77) and unrelated
        // photos in the 0.66–0.70 band.
        val scores = listOf(0.775f, 0.772f, 0.770f, 0.695f, 0.683f, 0.670f)
        val kept = SemanticScoring.nearestBest(scores, margin = 0.05f, max = 60) { it }
        assertEquals(listOf(0.775f, 0.772f, 0.770f), kept)
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
}
