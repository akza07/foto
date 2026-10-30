package fool.akza.foto.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** A crop rectangle in normalized (0..1) upright image coordinates. */
internal data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Where the wallpaper is applied. */
internal enum class WallpaperTarget { Home, Lock, Both }

/** Outcome of [WallpaperSetter.apply]. */
internal sealed interface WallpaperResult {
    /** The requested target(s) now show the new wallpaper. */
    data object Applied : WallpaperResult
    /** The system refused the request; [reason] is for logs. */
    data class Failed(val reason: String? = null) : WallpaperResult
}

/**
 * Normalized width / normalized height of a crop whose on-screen frame has
 * [frameAspect] (output width / height) on an image with [imageAspect].
 *
 * A crop is a fixed-aspect window over the image: its pixel aspect is
 * `(width * imageWidth) / (height * imageHeight)`, so keeping that equal to the
 * frame aspect fixes the normalized width/height ratio.
 */
private fun normalizedRatio(frameAspect: Float, imageAspect: Float): Float =
    frameAspect / imageAspect

/**
 * Centered, maximum-size crop for [frameAspect] on an image with [imageAspect].
 * The result fills one image dimension exactly and stays inside the image.
 */
internal fun fitWallpaperCrop(frameAspect: Float, imageAspect: Float): NormalizedCrop {
    val ratio = normalizedRatio(frameAspect, imageAspect)
    val width = min(1f, ratio)
    val height = width / ratio
    val left = (1f - width) / 2f
    val top = (1f - height) / 2f
    return NormalizedCrop(left, top, left + width, top + height)
}

/**
 * Applies one pinch/pan step to [crop], keeping its fixed aspect.
 *
 * [focusX]/[focusY] are the normalized image point under the gesture centroid,
 * and [panX]/[panY] the drag in normalized image units. [zoom] follows the
 * gesture convention (>1 = fingers spread = zoom in), so it shrinks the crop
 * window around the focus point; the result is clamped inside the image and
 * never shrinks below [minWidth].
 */
internal fun transformWallpaperCrop(
    crop: NormalizedCrop,
    frameAspect: Float,
    imageAspect: Float,
    focusX: Float,
    focusY: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    minWidth: Float = 0.08f,
): NormalizedCrop {
    val ratio = normalizedRatio(frameAspect, imageAspect)
    val maxWidth = min(1f, ratio)
    val safeZoom = zoom.coerceAtLeast(0.0001f)
    val width = (crop.width / safeZoom).coerceIn(min(minWidth, maxWidth), maxWidth)
    val height = width / ratio

    // Where the focus point sits inside the old crop (0..1). If the crop has no
    // area yet (first frame), anchor on its center.
    val relX = if (crop.width > 0f) ((focusX - crop.left) / crop.width).coerceIn(0f, 1f) else 0.5f
    val relY = if (crop.height > 0f) ((focusY - crop.top) / crop.height).coerceIn(0f, 1f) else 0.5f

    val left = (focusX - relX * width + panX)
        .coerceIn(0f, (1f - width).coerceAtLeast(0f))
    val top = (focusY - relY * height + panY)
        .coerceIn(0f, (1f - height).coerceAtLeast(0f))
    return NormalizedCrop(left, top, left + width, top + height)
}

/** Maps a normalized crop onto [imageWidth] × [imageHeight] pixel bounds. */
internal fun NormalizedCrop.toCropRegion(imageWidth: Int, imageHeight: Int): CropRegion {
    val x = (left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
    val y = (top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
    val right = (right * imageWidth).roundToInt().coerceIn(x + 1, imageWidth)
    val bottom = (bottom * imageHeight).roundToInt().coerceIn(y + 1, imageHeight)
    return CropRegion(left = x, top = y, width = right - x, height = bottom - y)
}

/**
 * Crops [source] to [region] and hands the result to the system wallpaper
 * service for [target].
 *
 * Unlike the crop editor, nothing is written to storage: the bitmap is passed
 * straight to [WallpaperManager], so the user's library is untouched. The
 * source is owned by the caller and never recycled here; only the intermediate
 * crop is.
 */
internal object WallpaperSetter {

    suspend fun apply(
        context: Context,
        source: Bitmap,
        region: CropRegion,
        target: WallpaperTarget,
    ): WallpaperResult = withContext(Dispatchers.IO) {
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val width = region.width.coerceIn(1, source.width - left)
        val height = region.height.coerceIn(1, source.height - top)

        val cropped = if (left == 0 && top == 0 && width == source.width && height == source.height) {
            source
        } else {
            try {
                Bitmap.createBitmap(source, left, top, width, height)
            } catch (e: Exception) {
                AppLog.w("wallpaper: couldn't crop source", e)
                return@withContext WallpaperResult.Failed("crop failed")
            }
        }

        try {
            val which = when (target) {
                WallpaperTarget.Home -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.Lock -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.Both -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            // The bitmap is already exactly the region the user chose, so the
            // whole image is the visible area. Passing the full rect (rather than
            // null) makes launchers that scale wallpapers for parallax show the
            // chosen composition instead of zooming into an arbitrary slice.
            val visible = Rect(0, 0, cropped.width, cropped.height)
            WallpaperManager.getInstance(context).setBitmap(cropped, visible, true, which)
            WallpaperResult.Applied
        } catch (e: Exception) {
            // SecurityException when SET_WALLPAPER is missing/denied, IOException
            // when the wallpaper service refuses the image (some OEM lock screens).
            AppLog.w("wallpaper: couldn't set $target", e)
            WallpaperResult.Failed(e.message)
        } finally {
            if (cropped !== source) cropped.recycle()
        }
    }
}
