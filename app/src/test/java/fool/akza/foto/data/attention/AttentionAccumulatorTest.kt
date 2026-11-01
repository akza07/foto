package fool.akza.foto.data.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the attention timing rules with a fake clock: idle debounce, touch
 * revival, foreground/screen gating, the per-visit cap, video playback time and
 * the "don't rewrite an unchanged visit" checkpoint behavior.
 */
class AttentionAccumulatorTest {

    private var now = 0L
    private var enabled = true
    private var screenOn = true

    private fun accumulator() = AttentionAccumulator(
        clock = { now },
        enabled = { enabled },
        screenOn = { screenOn },
    )

    /**
     * Advances in sample-sized steps, then flushes (like a page change would),
     * returning the totals that would be written.
     */
    private fun AttentionAccumulator.advance(ms: Long, step: Long = 250L): AttentionTotals? {
        var last: AttentionTotals? = null
        repeat((ms / step).toInt()) {
            now += step
            sample()?.let { last = it }
        }
        // Prefer a fresh flush so the returned totals cover the whole window;
        // fall back to the last periodic checkpoint when nothing changed since.
        return checkpoint() ?: last
    }

    @Test
    fun `time stops after the idle window without touches`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)

        val totals = acc.advance(20_000L)

        assertEquals(AttentionIdleTimeoutMs, totals?.activeMs)
        assertEquals(0L, totals?.playedMs)
        // Nothing changed after the idle cutoff, so no further writes are due.
        assertNull(acc.advance(2_000L))
    }

    @Test
    fun `a quiet look long enough to count reaches the watch threshold`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)

        val totals = acc.advance(12_000L)

        assertEquals(12_000L, totals?.activeMs)
        assertTrue(isAttentionWatch(isVideo = false, activeMs = totals!!.activeMs, playedMs = 0L))
    }

    @Test
    fun `a touch revives the idle window`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        acc.advance(5_000L)
        acc.onTouch()

        // The touch restarts the idle window, so 5s + 15s of attention accrue.
        val totals = acc.advance(20_000L)

        assertEquals(20_000L, totals?.activeMs)
    }

    @Test
    fun `background time is not counted and does not jump on resume`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        val before = acc.advance(2_000L)
        assertEquals(2_000L, before?.activeMs)

        acc.setForeground(false)
        // Backgrounding flushes, but nothing changed since the last write.
        assertNull(acc.checkpoint())

        now += 30_000L
        acc.setForeground(true)
        assertEquals(3_000L, acc.advance(1_000L)?.activeMs)
    }

    @Test
    fun `screen-off time is not counted`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        screenOn = false

        assertNull(acc.advance(10_000L))
        assertNull(acc.advance(1_000L))
    }

    @Test
    fun `one visit is capped`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        // Playing bypasses the idle window, so this reaches the hard cap.
        acc.setPlaying(true)
        val totals = acc.advance(200_000L, step = 500L)

        assertEquals(AttentionMaxSessionMs, totals?.activeMs)
    }

    @Test
    fun `playing video accrues playback time without touches`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        acc.setPlaying(true)

        val totals = acc.advance(10_000L)

        assertEquals(10_000L, totals?.playedMs)
        assertEquals(10_000L, totals?.activeMs)
    }

    @Test
    fun `disabled tracking accrues and writes nothing`() {
        enabled = false
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)

        assertNull(acc.advance(10_000L))
        assertNull(acc.checkpoint())
    }

    @Test
    fun `an unchanged visit is not rewritten`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        val first = acc.advance(2_000L)
        assertEquals(2_000L, first?.activeMs)
        assertTrue(acc.checkpoint() == null)
    }

    @Test
    fun `too-short visits are never persisted`() {
        val acc = accumulator()
        acc.begin()
        acc.setForeground(true)
        acc.advance(750L)

        assertNull(acc.checkpoint())
    }
}
