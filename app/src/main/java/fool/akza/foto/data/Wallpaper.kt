package fool.akza.foto.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
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
 * source is owned by the caller and never recycled here; only the intermediates
 * this function builds are.
 *
 * The applied bitmap is scaled down to the display's long edge: the wallpaper
 * service PNG-compresses whatever it is handed on the calling thread, so a
 * full-resolution crop both wastes memory and keeps the picker busy for
 * seconds (and can exhaust the heap on smaller devices).
 */
internal object WallpaperSetter {

    suspend fun apply(
        context: Context,
        source: Bitmap,
        region: CropRegion,
        target: WallpaperTarget,
    ): WallpaperResult = withContext(Dispatchers.IO) {
        try {
            applyCrop(context, source, region, target)
        } catch (t: Throwable) {
            // A failed wallpaper has to stay an inline error. OutOfMemoryError is
            // an Error, so catching only Exception here would still take the app
            // down when the crop or the service's encoder runs out of memory.
            AppLog.w("wallpaper: couldn't set $target", t)
            WallpaperResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun applyCrop(
        context: Context,
        source: Bitmap,
        region: CropRegion,
        target: WallpaperTarget,
    ): WallpaperResult {
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val width = region.width.coerceIn(1, source.width - left)
        val height = region.height.coerceIn(1, source.height - top)

        val display = context.resources.displayMetrics
        val longEdge = max(display.widthPixels, display.heightPixels)
        val scale = if (longEdge > 0) {
            min(1f, longEdge / max(width, height).toFloat())
        } else {
            1f
        }

        var scaledSource: Bitmap? = null
        var payload: Bitmap? = null
        try {
            val cropped = if (scale < 1f) {
                // Scale the whole source first and crop out of the small copy:
                // cropping at full resolution would allocate the very bitmap this
                // step is meant to avoid.
                val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
                val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
                if (scaled === source) {
                    scaled
                } else {
                    scaledSource = scaled
                    val l = (left * scale).roundToInt().coerceIn(0, scaledWidth - 1)
                    val t = (top * scale).roundToInt().coerceIn(0, scaledHeight - 1)
                    val r = ((left + width) * scale).roundToInt().coerceIn(l + 1, scaledWidth)
                    val b = ((top + height) * scale).roundToInt().coerceIn(t + 1, scaledHeight)
                    if (l == 0 && t == 0 && r == scaledWidth && b == scaledHeight) {
                        scaled
                    } else {
                        Bitmap.createBitmap(scaled, l, t, r - l, b - t)
                    }
                }
            } else if (left == 0 && top == 0 && width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createBitmap(source, left, top, width, height)
            }

            val which = when (target) {
                WallpaperTarget.Home -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.Lock -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.Both -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            // The bitmap is already exactly the region the user chose, so the
            // whole image is the visible area. Passing the full rect (rather than
            // null) makes launchers that scale wallpapers for parallax show the
            // chosen composition instead of zooming into an arbitrary slice.
            payload = cropped
            val visible = Rect(0, 0, cropped.width, cropped.height)
            // setBitmap compresses the bitmap and waits for the service before it
            // returns, so it is safe (and cheapest) to recycle right after.
            WallpaperManager.getInstance(context).setBitmap(cropped, visible, true, which)
            return WallpaperResult.Applied
        } finally {
            val bitmap = payload
            if (bitmap != null && bitmap !== source && bitmap !== scaledSource) {
                bitmap.recycle()
            }
            val scaled = scaledSource
            if (scaled != null && scaled !== source && scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }
}
