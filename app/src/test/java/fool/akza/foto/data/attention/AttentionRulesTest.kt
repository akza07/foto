package fool.akza.foto.data.attention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the viewing-attention rules: what counts as a watch,
 * and how the three lenses order the same underlying numbers.
 */
class AttentionRulesTest {

    @Test
    fun `an image becomes a watch at ten seconds of active time`() {
        assertFalse(isAttentionWatch(isVideo = false, activeMs = 9_999L, playedMs = 0L))
        assertTrue(isAttentionWatch(isVideo = false, activeMs = 10_000L, playedMs = 0L))
    }

    @Test
    fun `a video becomes a watch at three seconds of playback regardless of viewer time`() {
        assertFalse(isAttentionWatch(isVideo = true, activeMs = 60_000L, playedMs = 2_999L))
        assertTrue(isAttentionWatch(isVideo = true, activeMs = 1_000L, playedMs = 3_000L))
    }

    @Test
    fun `the segment limits leave room for a watch but bound idle time`() {
        assertTrue(AttentionMinSessionMs < AttentionImageWatchMs)
        // A quiet look must be able to reach the watch threshold before the idle
        // window cuts it off, otherwise "watches" can never increment without
        // gestures.
        assertTrue(AttentionIdleTimeoutMs > AttentionImageWatchMs)
        assertTrue(AttentionMaxSessionMs > AttentionIdleTimeoutMs)
    }

    @Test
    fun `time stops accruing after the idle window unless a video is playing`() {
        assertTrue(isAttending(nowElapsedRealtimeMs = AttentionIdleTimeoutMs, lastTouchAtElapsedRealtimeMs = 0L, videoPlaying = false))
        assertFalse(isAttending(nowElapsedRealtimeMs = AttentionIdleTimeoutMs + 1, lastTouchAtElapsedRealtimeMs = 0L, videoPlaying = false))
        // A playing video counts as attention even without touches.
        assertTrue(isAttending(nowElapsedRealtimeMs = 60_000L, lastTouchAtElapsedRealtimeMs = 0L, videoPlaying = true))
    }

    @Test
    fun `most watched ranks by watch count with time as the tie-break`() {
        val rows = listOf(
            Row(id = "few-long", totalMs = 90_000L, visits = 1, watches = 1, longestMs = 90_000L, lastViewedAt = 1L),
            Row(id = "many", totalMs = 40_000L, visits = 5, watches = 4, longestMs = 10_000L, lastViewedAt = 2L),
            Row(id = "same-count-more-time", totalMs = 60_000L, visits = 4, watches = 4, longestMs = 15_000L, lastViewedAt = 3L),
        )

        val ranked = rankAttention(rows, AttentionLens.MOST_WATCHED).map { it.id }

        assertEquals(listOf("same-count-more-time", "many", "few-long"), ranked)
    }

    @Test
    fun `longest look ranks by the single longest visit`() {
        val rows = listOf(
            Row(id = "a", totalMs = 100_000L, visits = 10, watches = 9, longestMs = 12_000L, lastViewedAt = 1L),
            Row(id = "b", totalMs = 20_000L, visits = 1, watches = 1, longestMs = 20_000L, lastViewedAt = 2L),
        )

        val ranked = rankAttention(rows, AttentionLens.LONGEST_LOOK).map { it.id }

        assertEquals(listOf("b", "a"), ranked)
    }

    @Test
    fun `revisited ranks by visit count with recency as the tie-break`() {
        val rows = listOf(
            Row(id = "old", totalMs = 50_000L, visits = 4, watches = 2, longestMs = 20_000L, lastViewedAt = 10L),
            Row(id = "new", totalMs = 50_000L, visits = 4, watches = 2, longestMs = 20_000L, lastViewedAt = 20L),
            Row(id = "rare", totalMs = 90_000L, visits = 1, watches = 1, longestMs = 90_000L, lastViewedAt = 30L),
        )

        val ranked = rankAttention(rows, AttentionLens.REVISITED).map { it.id }

        assertEquals(listOf("new", "old", "rare"), ranked)
    }

    private data class Row(
        val id: String,
        override val totalMs: Long,
        override val visits: Int,
        override val watches: Int,
        override val longestMs: Long,
        override val lastViewedAt: Long,
    ) : AttentionRankable
}
