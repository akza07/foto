package fool.akza.foto.data.attention

import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.canonicalPath

/** Minimum viewer time for a visit to be written at all (sub-second swipes don't count). */
internal const val AttentionMinSessionMs = 1_000L

/**
 * How long the viewer keeps counting after the last touch. Any interaction
 * (tap, zoom, pan, action sheet) resets this; once it lapses the user is
 * treated as away and time stops accruing until the next touch.
 *
 * Deliberately longer than [AttentionImageWatchMs]: with a shorter window a
 * quiet look (no touches) could never reach the watch threshold, which made
 * "watches" look permanently frozen. The per-visit cap still bounds an
 * abandoned photo.
 */
internal const val AttentionIdleTimeoutMs = 15_000L

/** Hard cap on one visit, so a photo left open on a desk can't accumulate forever. */
internal const val AttentionMaxSessionMs = 120_000L

/** An image visit with at least this much active time counts as a "watch". */
internal const val AttentionImageWatchMs = 10_000L

/** A video counts as watched once this much playback time has been seen. */
internal const val AttentionVideoWatchMs = 3_000L

/**
 * One viewer visit to one item. Written to SQLite keyed by [visitId], so a
 * periodic checkpoint can replace the row with the running totals instead of
 * creating a second visit.
 */
data class AttentionSession(
    val visitId: String,
    val mediaKey: String,
    val item: MediaItem,
    val startedAt: Long,
    val activeMs: Long,
    val playedMs: Long,
) {
    val qualified: Boolean
        get() = isAttentionWatch(item.isVideo, activeMs, playedMs)
}

/**
 * Stable identity for one media item across the session log. SAF document URIs
 * embed the path (and therefore change on rename/move), so the canonical path
 * inside the tree is preferred when available; everything else falls back to
 * the URI.
 */
fun MediaItem.attentionKey(): String = canonicalPath() ?: uri.toString()

/** Whether a visit of [activeMs] viewer time / [playedMs] playback counts as a watch. */
internal fun isAttentionWatch(isVideo: Boolean, activeMs: Long, playedMs: Long): Boolean =
    if (isVideo) playedMs >= AttentionVideoWatchMs else activeMs >= AttentionImageWatchMs

/**
 * Whether time should keep accruing: a playing video is attention by itself,
 * otherwise the last touch must be within the idle window. Pure so the
 * idle/debounce boundary can be unit-tested.
 */
internal fun isAttending(
    nowElapsedRealtimeMs: Long,
    lastTouchAtElapsedRealtimeMs: Long,
    videoPlaying: Boolean,
): Boolean = videoPlaying ||
    nowElapsedRealtimeMs - lastTouchAtElapsedRealtimeMs <= AttentionIdleTimeoutMs

/**
 * The numbers a ranking sorts on. Split from [AttentionEntry] so the ordering
 * rules can be unit-tested without constructing Android-backed media items.
 */
internal interface AttentionRankable {
    val totalMs: Long
    val visits: Int
    val watches: Int
    val longestMs: Long
    val lastViewedAt: Long
}

/** Aggregated attention for one item over the selected period. */
data class AttentionEntry(
    val mediaKey: String,
    val item: MediaItem,
    override val totalMs: Long,
    val playedMs: Long,
    override val visits: Int,
    override val watches: Int,
    override val longestMs: Long,
    override val lastViewedAt: Long,
) : AttentionRankable

/** Data behind the smart tile on the Albums grid. */
data class AttentionTile(
    /** Most-watched item, used as the tile preview. */
    val preview: MediaItem?,
    /** Distinct items with at least one recorded visit. */
    val items: Int,
    /** Total recorded visits. */
    val sessions: Int,
)

/** Counts used by Settings (clear-history summary). */
data class AttentionSummary(
    val sessions: Int,
    val items: Int,
)

enum class AttentionPeriod(val sinceDays: Int?) {
    WEEK(7),
    MONTH(30),
    ALL(null),
}

enum class AttentionLens {
    MOST_WATCHED,
    LONGEST_LOOK,
    REVISITED,
}

/**
 * Orders aggregated rows for the selected lens. Every lens is monotonic on the
 * number it is named after, with total time and recency as stable tie-breakers;
 * the other numbers stay visible in each row so no ranking hides context.
 */
internal fun <T : AttentionRankable> rankAttention(entries: List<T>, lens: AttentionLens): List<T> =
    when (lens) {
        AttentionLens.MOST_WATCHED -> entries.sortedWith(
            compareByDescending<T> { it.watches }
                .thenByDescending { it.totalMs }
                .thenByDescending { it.lastViewedAt },
        )
        AttentionLens.LONGEST_LOOK -> entries.sortedWith(
            compareByDescending<T> { it.longestMs }
                .thenByDescending { it.totalMs },
        )
        AttentionLens.REVISITED -> entries.sortedWith(
            compareByDescending<T> { it.visits }
                .thenByDescending { it.lastViewedAt },
        )
    }
