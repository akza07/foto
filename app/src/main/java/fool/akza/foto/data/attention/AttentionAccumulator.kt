package fool.akza.foto.data.attention

/** Snapshot of the two clocks a visit accumulates. */
internal data class AttentionTotals(
    val activeMs: Long,
    val playedMs: Long,
)

/**
 * Android-free timing state machine behind [fool.akza.foto.ui.ViewerAttentionTracker].
 *
 * Split out so the parts that are easy to get wrong — the idle debounce, the
 * per-visit cap, foreground/screen gating and "nothing changed, don't rewrite"
 * — can be driven with a fake clock in unit tests.
 */
internal class AttentionAccumulator(
    private val clock: () -> Long,
    private val enabled: () -> Boolean,
    private val screenOn: () -> Boolean,
) {
    private var open = false
    private var activeMs = 0L
    private var playedMs = 0L
    private var lastSampleAt = 0L
    private var lastTouchAt = 0L
    private var lastWriteAt = 0L
    private var lastCommittedActive = -1L
    private var lastCommittedPlayed = -1L
    private var foreground = false
    private var playing = false
    private var leaving = false

    val isOpen: Boolean get() = open

    /** Starts a new, empty visit. Any previous one must be checkpointed first. */
    fun begin() {
        open = true
        activeMs = 0L
        playedMs = 0L
        playing = false
        leaving = false
        lastCommittedActive = -1L
        lastCommittedPlayed = -1L
        val now = clock()
        lastSampleAt = now
        lastTouchAt = now
        lastWriteAt = now
    }

    fun close() {
        open = false
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            val now = clock()
            lastSampleAt = now
            lastTouchAt = now
        }
    }

    fun setPlaying(value: Boolean) {
        playing = value
    }

    fun setLeaving(value: Boolean) {
        leaving = value
    }

    /** Any pointer contact keeps the user "present" for the idle window. */
    fun onTouch() {
        lastTouchAt = clock()
    }

    /**
     * Advances the clocks by however much real time has passed. Returns the
     * totals when a periodic checkpoint is due (so a long visit survives process
     * death), otherwise null.
     */
    fun sample(): AttentionTotals? {
        if (!open) return null
        val now = clock()
        val dt = (now - lastSampleAt).coerceIn(0L, MAX_SAMPLE_GAP_MS)
        lastSampleAt = now
        if (!leaving && foreground && enabled() && screenOn()) {
            if (isAttending(now, lastTouchAt, playing) && activeMs < AttentionMaxSessionMs) {
                activeMs = (activeMs + dt).coerceAtMost(AttentionMaxSessionMs)
            }
            if (playing && playedMs < AttentionMaxSessionMs) {
                playedMs = (playedMs + dt).coerceAtMost(AttentionMaxSessionMs)
            }
        }
        return if (now - lastWriteAt >= CHECKPOINT_MS) checkpoint() else null
    }

    /**
     * Returns the totals to persist when they changed since the last write, or
     * null when the visit is too short, tracking is off, or nothing changed (an
     * idle/capped visit must not rewrite an identical row forever).
     */
    fun checkpoint(): AttentionTotals? {
        if (!open) return null
        if (activeMs < AttentionMinSessionMs || !enabled()) return null
        lastWriteAt = clock()
        if (activeMs == lastCommittedActive && playedMs == lastCommittedPlayed) return null
        lastCommittedActive = activeMs
        lastCommittedPlayed = playedMs
        return AttentionTotals(activeMs = activeMs, playedMs = playedMs)
    }

    private companion object {
        const val CHECKPOINT_MS = 15_000L

        /** A stalled main thread must not turn into a fake multi-second sample. */
        const val MAX_SAMPLE_GAP_MS = 750L
    }
}
