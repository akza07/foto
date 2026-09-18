package fool.akza.foto.data.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import fool.akza.foto.AppLog
import kotlin.math.roundToInt

/**
 * Extracts a video's FRAME 0 as a poster bitmap, scaled at extraction time.
 *
 * Why frame 0: ExoPlayer renders frame 0 when prepared (paused preview). The
 * viewer's poster veil must be pixel-identical to that frame, so the
 * poster→first-frame crossfade is invisible. This deliberately differs from
 * the old MediaStore `loadThumbnail` posters (representative frame, arbitrary
 * position) which made the crossfade a visible content jump.
 *
 * Why extraction here: `MediaMetadataRetriever.getScaledFrameAtTimestamp`
 * scales while decoding (no full-res bitmap), takes ~50-150 ms per video, and
 * is meant to run ONCE per video inside the background warm pass
 * ([ThumbCache.enqueueOne] → [VideoFrameCache.save]); every later read is a
 * plain JPEG decode from disk.
 */
object VideoPosterExtractor {

    /**
     * Returns the video's frame 0 scaled so its long edge is [longEdge] px,
     * or null if the video can't be read (unsupported/corrupt/SAF revoked).
     */
    fun extract(context: Context, uri: Uri, longEdge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val (w, h) = videoDimensions(retriever) ?: return fallbackFrame(retriever)
            if (w <= 0 || h <= 0) return fallbackFrame(retriever)

            // Preserve aspect; scale so the LONG edge == longEdge.
            val scale = longEdge.toFloat() / maxOf(w, h).toFloat()
            val targetW = (w * scale).roundToInt().coerceAtLeast(1)
            val targetH = (h * scale).roundToInt().coerceAtLeast(1)

            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetW,
                targetH,
            ) ?: fallbackFrame(retriever)
        } catch (e: Exception) {
            AppLog.w("poster extraction failed for $uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Rotation-aware video dimensions, or null if unreadable. */
    private fun videoDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int>? {
        val w = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
        )?.toIntOrNull() ?: return null
        val h = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
        )?.toIntOrNull() ?: return null
        val rotation = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
        )?.toIntOrNull() ?: 0
        // Portrait videos store landscape dimensions + 90/270 rotation.
        return if (rotation == 90 || rotation == 270) Pair(h, w) else Pair(w, h)
    }

    /** Last resort inside the retriever: unscaled first frame. */
    private fun fallbackFrame(retriever: MediaMetadataRetriever): Bitmap? = try {
        retriever.getFrameAtTime(0L)
    } catch (_: Exception) {
        null
    }

    /** Poster long edge used by the warm pass and the UI fallback path. */
    const val POSTER_LONG_EDGE = 720
}
