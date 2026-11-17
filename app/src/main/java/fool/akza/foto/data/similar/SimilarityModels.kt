package fool.akza.foto.data.similar

import fool.akza.foto.data.MediaItem

/** A small visual fingerprint used to compare photos without the semantic model. */
internal data class VisualFingerprint(
    /** 64-bit dHash; equal bits mean the same light/dark structure. */
    val dHash: Long,
    /** L2-normalised colour + coarse-luma vector. */
    val vector: FloatArray,
    /** 32x32 grayscale values in 0..1, used to tell copies from pose changes. */
    val luma: FloatArray,
    val aspect: Float,
    val width: Int,
    val height: Int,
)

/** A set of photos that are the same shot (copies, edits, re-sends). */
data class DuplicateGroup(
    val id: String,
    val items: List<MediaItem>,
    val keeper: MediaItem,
    val bytesFreed: Long,
    val folder: String?,
) {
    val removable: List<MediaItem> get() = items.filterNot { it.uri == keeper.uri }
}

/** A set of shots that belong together but should be kept (bursts, poses). */
data class SimilarGroup(
    val id: String,
    val items: List<MediaItem>,
    val folder: String?,
    val averageSimilarity: Float,
    /** Name precomputed against the visible folders; refreshed when the sheet opens. */
    val suggestedName: String,
) {
    val sameFolder: String? get() = folder
    val parentFolder: String? get() = folder?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.ifBlank { null }
}

/** Two or three ungrouped shots that look related enough to review. */
data class PossibleGroup(
    val id: String,
    val items: List<MediaItem>,
    val folder: String?,
    val averageSimilarity: Float,
)

/**
 * Everything the Similar surface needs. [preview] is the item used behind the
 * Albums smart tile; [recommendations] counts every decision shown in the inbox.
 */
data class SimilarReport(
    val duplicates: List<DuplicateGroup>,
    val similarGroups: List<SimilarGroup>,
    val possibleGroups: List<PossibleGroup>,
    val preview: MediaItem?,
    val bytesReclaimable: Long,
) {
    val recommendations: Int
        get() = duplicates.size + similarGroups.size + possibleGroups.size

    val isEmpty: Boolean get() = recommendations == 0
}

/** Progress of the visual pass, surfaced as a quiet line while Albums is open. */
data class SimilarProgress(
    val done: Int = 0,
    val total: Int = 0,
) {
    val isRunning: Boolean get() = total > 0 && done < total
}

/** Result of trying to move a group into a real folder. */
data class GroupMoveResult(
    val moved: Int,
    val failed: Int,
    val folder: String?,
) {
    val succeeded: Boolean get() = moved > 0 && failed == 0
}
