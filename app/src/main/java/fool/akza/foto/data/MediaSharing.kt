package fool.akza.foto.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.AppLog
import fool.akza.foto.data.thumbnails.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Outcome of preparing a share. */
internal sealed interface ShareResult {
    /** [send] is the implicit intent; wrap it in a chooser on the main thread. */
    data class Ready(val send: Intent) : ShareResult

    /** At least one photo could not be made metadata-free, so nothing is shared. */
    data object Failed : ShareResult
}

/**
 * Builds the ACTION_SEND intent for the gallery's Share actions.
 *
 * With `stripMetadata` enabled, every image is read once and rewritten without
 * EXIF/XMP/text metadata into the app cache, then handed out through a
 * [FileProvider]. The recipient never receives a URI to the original file, so
 * clearing the cache really does remove the shared copy.
 *
 * Orientation is the one tag that cannot simply disappear: a portrait photo
 * whose rotation lives only in EXIF would arrive sideways. Such photos are
 * decoded, rotated into the pixels and re-encoded. PNG and (API 30+) WebP are
 * re-encoded losslessly; JPEG uses quality 95. Huge photos are sampled down to
 * a bounded size if a full-resolution decode would not fit in the heap, so a
 * share never fails just because the camera produced 50 megapixels. Photo
 * formats with no lossless rewriter here (HEIF/AVIF, and anything unrecognised)
 * always take that re-encode path.
 *
 * Videos are shared exactly as before: their container metadata is a different
 * problem, and the setting is scoped to photos.
 */
internal object MediaSharing {

    /** Refuse to buffer absurd inputs instead of risking the process. */
    private const val MaxImageBytes = 64 * 1024 * 1024

    /**
     * Long edge used when a full-resolution decode would not fit in the heap.
     * Matches the crop editor's cap: still far above any share target's own
     * limit, while keeping a 50 MP source to a few tens of megabytes.
     */
    private const val MaxReencodeDimension = 3072

    /** How long a prepared copy may stay in the cache. */
    private const val CacheMaxAgeMs = 24L * 60 * 60 * 1000

    private const val CacheDirName = "share"

    private data class ShareTarget(
        val uri: Uri,
        val mime: String,
        val isVideo: Boolean,
    )

    private data class PreparedImage(
        val bytes: ByteArray,
        val mime: String,
        val extension: String,
    )

    suspend fun prepare(
        context: Context,
        items: List<MediaItem>,
        stripMetadata: Boolean,
    ): ShareResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext ShareResult.Failed
        if (stripMetadata && items.any { !it.isVideo }) pruneShareCache(context)

        try {
            val targets = ArrayList<ShareTarget>(items.size)
            for (item in items) {
                val target = if (!stripMetadata || item.isVideo) {
                    ShareTarget(
                        uri = item.uri,
                        mime = item.mimeType?.takeIf { it.isNotBlank() }
                            ?: if (item.isVideo) "video/*" else "image/*",
                        isVideo = item.isVideo,
                    )
                } else {
                    prepareImage(context, item) ?: return@withContext ShareResult.Failed
                }
                targets += target
            }
            ShareResult.Ready(buildIntent(targets))
        } catch (e: OutOfMemoryError) {
            AppLog.w("share: out of memory while preparing", e)
            ShareResult.Failed
        } catch (e: Exception) {
            AppLog.w("share: couldn't prepare items", e)
            ShareResult.Failed
        }
    }

    /** Reads, strips and caches one photo, or returns null when it can't. */
    private fun prepareImage(context: Context, item: MediaItem): ShareTarget? {
        val bytes = readImageBytes(context, item.uri) ?: return null
        val format = ImageMetadataStripper.detect(bytes, item.mimeType)
        val orientation = readOrientation(bytes)
        val animated = ImageMetadataStripper.isAnimated(bytes, format)
        val mustReencode = format == ImageFormat.HEIF ||
            format == ImageFormat.OTHER ||
            (!animated && needsOrientationBake(orientation))

        val prepared = if (mustReencode) {
            reencodeUpright(bytes, format, orientation)
        } else {
            val stripped = ImageMetadataStripper.strip(bytes, format)
            when {
                stripped != null ->
                    PreparedImage(stripped, format.mime, format.extension)
                // Never silently flatten an animation into one JPEG frame: if
                // the container walker couldn't rewrite it, fail the share.
                animated -> null
                else -> reencodeUpright(bytes, format, orientation)
            }
        } ?: return null

        val uri = writeShareFile(context, item, prepared) ?: return null
        return ShareTarget(uri, prepared.mime, isVideo = false)
    }

    private fun readImageBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MaxImageBytes) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } catch (e: OutOfMemoryError) {
        AppLog.w("share: image too large to prepare $uri", e)
        null
    } catch (e: Exception) {
        AppLog.w("share: couldn't read $uri", e)
        null
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (e: Exception) {
        AppLog.w("share: couldn't read orientation", e)
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun needsOrientationBake(orientation: Int): Boolean =
        orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED

    /**
     * Decodes [bytes], bakes [orientation] into the pixels and re-encodes. Used
     * for rotated photos and for formats with no lossless metadata walker.
     */
    private fun reencodeUpright(
        bytes: ByteArray,
        format: ImageFormat,
        orientation: Int,
    ): PreparedImage? {
        val decoded = decodeBounded(bytes) ?: return null

        val upright = applyExifOrientation(decoded, orientation)
        return try {
            encodeBitmap(upright, format)
        } finally {
            if (!upright.isRecycled) upright.recycle()
        }
    }

    /**
     * Decodes at full resolution when that fits in the heap, otherwise samples
     * down to [MaxReencodeDimension]. Without this a 48 MP HEIC or a rotated
     * 50 MP JPEG would throw OOM and make the photo impossible to share.
     */
    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val estimatedBytes = width.toLong() * height.toLong() * 4L
        val budget = (Runtime.getRuntime().maxMemory() / 4L)
            .coerceIn(16L * 1024 * 1024, 128L * 1024 * 1024)
        if (estimatedBytes <= budget) {
            decodeAt(bytes, 1)?.let { return it }
        }

        // Full-size decode failed (or was too big to try); step down by powers
        // of two until the bitmap fits. 256 is far past what any phone needs.
        var sample = maxOf(2, sampleSize(width, height, MaxReencodeDimension))
        while (sample <= 256) {
            decodeAt(bytes, sample)?.let { return it }
            sample *= 2
        }
        return null
    }

    private fun decodeAt(bytes: ByteArray, sample: Int): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: OutOfMemoryError) {
        AppLog.w("share: image too large to decode at sample $sample", e)
        null
    } catch (e: Exception) {
        AppLog.w("share: couldn't decode image", e)
        null
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun encodeBitmap(bitmap: Bitmap, format: ImageFormat): PreparedImage? {
        val out = ByteArrayOutputStream()
        val ok = try {
            when (format) {
                ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
                }
                // JPEG, HEIF, AVIF and everything unrecognised become a broadly
                // playable JPEG. 95 keeps the visual quality high without the
                // file-size blow-up of 100.
                else -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } catch (e: OutOfMemoryError) {
            AppLog.w("share: couldn't encode image", e)
            false
        } catch (e: Exception) {
            AppLog.w("share: couldn't encode image", e)
            false
        }
        if (!ok) return null

        val (mime, extension) = when (format) {
            ImageFormat.PNG -> "image/png" to "png"
            ImageFormat.WEBP -> "image/webp" to "webp"
            else -> "image/jpeg" to "jpg"
        }
        return PreparedImage(out.toByteArray(), mime, extension)
    }

    private fun writeShareFile(
        context: Context,
        item: MediaItem,
        prepared: PreparedImage,
    ): Uri? {
        val dir = shareCacheDir(context) ?: return null
        // The cache file name deliberately carries no source name: the URI is
        // visible to the receiving app and should not leak more than the photo.
        val key = "${item.uri}|${item.lastModified}|${item.sizeBytes}|${prepared.extension}"
        val file = File(dir, "foto_${sha1Hex(key)}.${prepared.extension}")
        return try {
            file.writeBytes(prepared.bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        } catch (e: OutOfMemoryError) {
            AppLog.w("share: out of memory caching ${item.uri}", e)
            null
        } catch (e: Exception) {
            AppLog.w("share: couldn't cache ${item.uri}", e)
            null
        }
    }

    private fun shareCacheDir(context: Context): File? {
        val dir = File(context.cacheDir, CacheDirName)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** Drops last session's copies so the cache cannot grow without bound. */
    private fun pruneShareCache(context: Context) {
        val dir = shareCacheDir(context) ?: return
        val cutoff = System.currentTimeMillis() - CacheMaxAgeMs
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun buildIntent(targets: List<ShareTarget>): Intent {
        val uris = targets.map { it.uri }
        val type = shareType(targets)
        val intent = if (targets.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.type = type
        // Some receivers read the grant from ClipData instead of EXTRA_STREAM;
        // providing both keeps single- and multi-share working across apps.
        val clip = ClipData.newRawUri("Foto", uris.first())
        for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Broadest MIME type that covers the batch; receivers filter per URI. */
    private fun shareType(targets: List<ShareTarget>): String {
        if (targets.size == 1) return targets.first().mime
        val anyVideo = targets.any { it.isVideo }
        val anyImage = targets.any { !it.isVideo }
        return when {
            anyVideo && anyImage -> "*/*"
            anyVideo -> "video/*"
            targets.map { it.mime }.distinct().size == 1 -> targets.first().mime
            else -> "image/*"
        }
    }
}
