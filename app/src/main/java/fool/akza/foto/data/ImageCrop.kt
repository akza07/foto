package fool.akza.foto.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A crop rectangle in source-bitmap pixel coordinates. */
data class CropRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Outcome of [ImageCropper.saveCroppedCopy]. */
sealed interface CropSaveResult {
    /** The cropped copy was written and can now be shown/queried. */
    data class Saved(val item: MediaItem) : CropSaveResult
    /** Nothing was written; the caller should show an error. */
    data class Failed(val reason: String? = null) : CropSaveResult
}

/**
 * Editable-bitmap loading and non-destructive crop export.
 *
 * Crop output is always written as a new file: the original is never modified,
 * so a bad crop can't destroy the user's photo and no MediaStore write consent
 * is needed. MediaStore items are saved beside the original when the folder is
 * a normal image location; SAF items are written through the same tree grant.
 */
object ImageCropper {

    /**
     * Upper bound for the working bitmap. Cropping happens at up to this size,
     * which keeps a 108 MP source from needing a >400 MB ARGB bitmap while still
     * producing a high-quality result on phone screens.
     */
    const val MAX_EDIT_DIMENSION = 3072

    /**
     * Decodes [uri] at a manageable size and applies its EXIF orientation, so
     * the editor and the resulting crop share upright coordinates.
     */
    suspend fun loadEditableBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_EDIT_DIMENSION,
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext null

            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                runCatching {
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            applyExifOrientation(decoded, orientation)
        } catch (e: Exception) {
            AppLog.w("crop: couldn't decode $uri", e)
            null
        }
    }

    /**
     * Writes [source] cropped to [region] as a new image and returns the created
     * [MediaItem]. The returned item is ready to display immediately, before the
     * gallery's async reload has rediscovered it.
     */
    suspend fun saveCroppedCopy(
        context: Context,
        original: MediaItem,
        source: Bitmap,
        region: CropRegion,
    ): CropSaveResult = withContext(Dispatchers.IO) {
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val width = region.width.coerceIn(1, source.width - left)
        val height = region.height.coerceIn(1, source.height - top)

        val cropped = if (left == 0 && top == 0 && width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, left, top, width, height)
        }

        val output = outputFormat(original.mimeType)
        val bytes = try {
            ByteArrayOutputStream().use { stream ->
                if (!cropped.compress(output.format, 92, stream)) {
                    return@withContext CropSaveResult.Failed("compress failed")
                }
                stream.toByteArray()
            }
        } catch (e: Exception) {
            AppLog.w("crop: couldn't encode result", e)
            return@withContext CropSaveResult.Failed("encode failed")
        } finally {
            if (cropped !== source) cropped.recycle()
        }

        val name = croppedName(original.displayName, output.extension)
        if (original.safTreeUri != null) {
            val safUri = saveViaSaf(context, original, bytes, output.mime, name)
            if (safUri != null) {
                val now = System.currentTimeMillis() / 1000L
                return@withContext CropSaveResult.Saved(
                    original.copy(
                        id = 0L,
                        uri = safUri,
                        relativePath = null,
                        dateAdded = now,
                        displayName = name,
                        mimeType = output.mime,
                        lastModified = now,
                        sizeBytes = bytes.size.toLong(),
                    ),
                )
            }
        }

        val target = saveViaMediaStore(context, original, bytes, output.mime, name)
            ?: return@withContext CropSaveResult.Failed("write failed")
        val now = System.currentTimeMillis() / 1000L
        val folder = target.relativePath.trimEnd('/').substringAfterLast('/')
        val item = MediaItem(
            id = runCatching { ContentUris.parseId(target.uri) }.getOrDefault(0L),
            uri = target.uri,
            bucketId = target.relativePath.trimEnd('/'),
            bucketName = folder.ifBlank { "Foto" },
            relativePath = target.relativePath,
            dateAdded = now,
            isVideo = false,
            displayName = name,
            mimeType = output.mime,
            lastModified = now,
            sizeBytes = bytes.size.toLong(),
        )
        CropSaveResult.Saved(item)
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
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
        } catch (e: Exception) {
            AppLog.w("crop: couldn't apply EXIF orientation", e)
            source
        }
    }

    private data class OutputFormat(
        val mime: String,
        val extension: String,
        val format: Bitmap.CompressFormat,
    )

    /**
     * Keeps PNG/WebP sources in their own format; everything else (JPEG, HEIC,
     * AVIF, GIF, …) becomes a JPEG copy so the result is broadly playable.
     */
    private fun outputFormat(sourceMime: String?): OutputFormat {
        val mime = sourceMime?.lowercase().orEmpty()
        return when {
            mime == "image/png" -> OutputFormat("image/png", "png", Bitmap.CompressFormat.PNG)
            mime == "image/webp" -> OutputFormat(
                "image/webp",
                "webp",
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                },
            )
            else -> OutputFormat("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG)
        }
    }

    private fun croppedName(originalName: String?, extension: String): String {
        val base = originalName?.takeIf { it.isNotBlank() }
            ?: "Foto_${System.currentTimeMillis()}"
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        return "${stem}_cropped.$extension"
    }

    private data class SavedTarget(val uri: Uri, val relativePath: String)

    /**
     * Creates the result beside the original when it lives in a normal public
     * image directory (DCIM/Pictures/Download/Documents); otherwise it goes to
     * Pictures/Foto. [MediaStore] rejects some relative paths, so each attempt
     * is self-contained and the copy falls back rather than failing outright.
     */
    private fun saveViaMediaStore(
        context: Context,
        original: MediaItem,
        bytes: ByteArray,
        mime: String,
        name: String,
    ): SavedTarget? {
        val preferred = original.relativePath?.trim()?.trim('/')?.plus("/")
        val paths = listOfNotNull(
            preferred?.takeIf { it.isSafeImageRelativePath() },
            "Pictures/Foto/",
        ).distinct()

        for (relativePath in paths) {
            val uri = insertAndWrite(context, relativePath, name, mime, bytes)
            if (uri != null) return SavedTarget(uri, relativePath)
        }
        return null
    }

    private fun insertAndWrite(
        context: Context,
        relativePath: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            AppLog.w("crop: MediaStore insert failed for $relativePath", e)
            null
        } ?: return null

        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            AppLog.w("crop: MediaStore write failed for $uri", e)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    /**
     * Writes the crop into the same SAF directory as the original. Falls back to
     * MediaStore when the document was handed to us without a tree grant, or
     * when the provider refuses the create/write.
     */
    private fun saveViaSaf(
        context: Context,
        original: MediaItem,
        bytes: ByteArray,
        mime: String,
        name: String,
    ): Uri? {
        val tree = original.safTreeUri?.let(Uri::parse) ?: return null
        val parentId = try {
            val documentId = DocumentsContract.getDocumentId(original.uri)
            documentId.substringBeforeLast('/', missingDelimiterValue = "")
                .ifBlank { DocumentsContract.getTreeDocumentId(tree) }
        } catch (e: Exception) {
            AppLog.w("crop: SAF parent lookup failed for ${original.uri}", e)
            return null
        }

        val parent = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        } catch (e: Exception) {
            AppLog.w("crop: SAF parent URI failed for $parentId", e)
            return null
        }

        val destination = createUniqueDocument(name) { candidate ->
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, parent, mime, candidate)
            }.getOrNull()
        } ?: return null

        return try {
            context.contentResolver.openOutputStream(destination, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("no output stream")
            destination
        } catch (e: Exception) {
            AppLog.w("crop: SAF write failed for $destination", e)
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, destination)
            }
            null
        }
    }
}

/** Public directories MediaStore accepts for image inserts. */
private fun String.isSafeImageRelativePath(): Boolean {
    val top = substringBefore('/').lowercase()
    return top in setOf("dcim", "pictures", "download", "documents")
}
