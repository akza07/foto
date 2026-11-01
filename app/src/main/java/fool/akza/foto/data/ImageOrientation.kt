package fool.akza.foto.data

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.AppLog

/**
 * Applies an EXIF orientation to [source] and returns an upright bitmap.
 *
 * Metadata-free exports (crop, share) cannot keep the orientation tag, so the
 * rotation has to live in the pixels instead. Returns [source] unchanged when
 * the orientation is normal; the caller owns the returned bitmap.
 */
internal fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return source
    }
    return try {
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        rotated
    } catch (e: OutOfMemoryError) {
        AppLog.w("couldn't apply EXIF orientation", e)
        source
    } catch (e: Exception) {
        AppLog.w("couldn't apply EXIF orientation", e)
        source
    }
}
