package fool.akza.foto.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the delete bar's smoothing: it must always move toward
 * the real count without overshooting, keep one continuous pace across a burst
 * of per-file updates, and land exactly on 100%.
 */
class DeleteProgressChaseTest {

    private val frame = 1f / 60f

    @Test
    fun `moves toward the target without overshooting`() {
        val up = chaseProgress(current = 0f, target = 0.5f, dtSeconds = frame)
        assertTrue(up > 0f && up < 0.5f)

        val down = chaseProgress(current = 0.9f, target = 0.4f, dtSeconds = frame)
        assertTrue(down > 0.4f && down < 0.9f)
    }

    @Test
    fun `no time means no movement`() {
        assertEquals(0.3f, chaseProgress(0.3f, 1f, 0f), 0f)
    }

    @Test
    fun `converges and snaps to the target`() {
        var shown = 0f
        repeat(180) { shown = chaseProgress(shown, 0.8f, frame) }
        assertEquals(0.8f, shown, 0f)
    }

    @Test
    fun `snaps once it is close enough`() {
        val justInside = ProgressSnapEpsilon * 0.5f
        assertEquals(1f, chaseProgress(1f - justInside, 1f, frame), 0f)
    }

    @Test
    fun `finishing is faster than the normal chase`() {
        // The final count gives the bar a shorter time constant, so a completed
        // batch lands on 100% before the modal fades out instead of being cut
        // off mid-sweep.
        val finishing = chaseProgress(0f, 1f, 0.2f)
        val normal = chaseProgress(0f, 0.9f, 0.2f)
        assertTrue(finishing > 0.95f)
        assertTrue(normal < 0.8f)
    }

    @Test
    fun `a burst of per-file updates stays monotonic and never overshoots`() {
        var shown = 0f
        var target = 0f
        repeat(200) { step ->
            // Simulate uneven completion: some frames no progress, some a step.
            if (step % 3 == 0) target = (step + 1) / 200f
            val next = chaseProgress(shown, target, frame)
            assertTrue("must not move backwards", next >= shown - 1e-6f)
            assertTrue("must not overshoot", next <= target + 1e-6f)
            assertTrue("must stay in range", next in 0f..1f)
            shown = next
        }
        // After the batch is done and a few more frames, it is at 100%.
        repeat(30) { shown = chaseProgress(shown, 1f, frame) }
        assertEquals(1f, shown, 0f)
        assertTrue(abs(shown - 1f) < 1e-6f)
    }
}
