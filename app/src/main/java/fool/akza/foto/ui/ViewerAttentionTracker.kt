package fool.akza.foto.ui

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.SettingsRepository
import fool.akza.foto.data.attention.AttentionAccumulator
import fool.akza.foto.data.attention.AttentionRepository
import fool.akza.foto.data.attention.AttentionSession
import fool.akza.foto.data.attention.AttentionTotals
import fool.akza.foto.data.attention.attentionKey
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Measures how long the full-screen viewer spends on each item and writes the
 * visits to [AttentionRepository].
 *
 * The timing rules live in [AttentionAccumulator] (pure, unit-tested): only the
 * settled page counts; time stops after 6s without a touch; playing video is
 * attention by itself; a visit is capped at 120s, needs >=1s to be stored, and
 * is replaced in place on every checkpoint so the visit count stays exact.
 * This class only supplies the Android wiring (item identity, screen state,
 * settings, IO) and decides when to flush.
 */
internal class ViewerAttentionTracker(
    context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    private val accumulator = AttentionAccumulator(
        clock = clock,
        enabled = { SettingsRepository.settings.value.attentionTracking },
        screenOn = { power?.isInteractive == true },
    )

    private var item: MediaItem? = null
    private var visitId: String? = null
    private var startedAt = 0L

    /** Starts a fresh visit for [item] (null just closes the previous one). */
    fun begin(item: MediaItem?) {
        flush()
        this.item = item
        visitId = item?.let { UUID.randomUUID().toString() }
        startedAt = System.currentTimeMillis()
        if (item == null) accumulator.close() else accumulator.begin()
    }

    fun setForeground(value: Boolean) {
        accumulator.setForeground(value)
        if (!value) flush()
    }

    fun setPlaying(value: Boolean) {
        accumulator.setPlaying(value)
    }

    fun setLeaving(value: Boolean) {
        accumulator.setLeaving(value)
        if (value) flush()
    }

    /** Any pointer contact keeps the user "present" for the idle window. */
    fun onTouch() {
        accumulator.onTouch()
    }

    fun sample() {
        if (item == null) return
        accumulator.sample()?.let(::persist)
    }

    /** Flushes the visit and forgets it (viewer disposed). */
    fun finish() {
        flush()
        accumulator.close()
        item = null
        visitId = null
    }

    private fun flush() {
        accumulator.checkpoint()?.let(::persist)
    }

    private fun persist(totals: AttentionTotals) {
        val current = item ?: return
        val id = visitId ?: return
        val session = AttentionSession(
            visitId = id,
            mediaKey = current.attentionKey(),
            item = current,
            startedAt = startedAt,
            activeMs = totals.activeMs,
            playedMs = totals.playedMs,
        )
        // The write outlives the viewer's composition; committing on dispose
        // must not be cancelled with it.
        WriteScope.launch { AttentionRepository.record(appContext, session) }
    }

    private companion object {
        val WriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
