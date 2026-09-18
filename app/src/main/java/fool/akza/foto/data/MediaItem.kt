package fool.akza.foto.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val bucketId: String,
    val bucketName: String,
    val relativePath: String?,
    val dateAdded: Long,
    val isVideo: Boolean,
    /** File name (MediaStore DISPLAY_NAME). Used for SAF/MediaStore dedup. */
    val displayName: String? = null,
    /** Set when this item was discovered by directly browsing a granted SAF tree
     * (as opposed to MediaStore). Non-null only for SAF-originated items. */
    val safTreeUri: String? = null,
    /** MIME type from MediaStore or DocumentsContract. Used as a tiebreaker
     *  in the thumbnail cache key. */
    val mimeType: String? = null,
    /** Last-modified epoch seconds (MediaStore DATE_MODIFIED or DocumentsContract
     * COLUMN_LAST_MODIFIED). Mixed into the thumbnail cache key so edits bust
     * the cached entry. */
    val lastModified: Long = 0L,
    /** File size in bytes (MediaStore SIZE or DocumentsContract COLUMN_SIZE).
     * Mixed into the thumbnail cache key for additional entropy. */
    val sizeBytes: Long = 0L,
)

/**
 * True when [mime] denotes an image format that can carry animation. Coil's
 * animated decoder handles GIF, animated WebP, animated HEIF and (API 31+)
 * animated AVIF; static variants of the same formats simply render frame one.
 */
internal fun isAnimatedMime(mime: String?): Boolean {
    val m = mime?.lowercase() ?: return false
    return m == "image/gif" ||
        m == "image/webp" ||
        m == "image/heif" ||
        m == "image/heif-sequence" ||
        m == "image/avif"
}

fun MediaItem.isAnimatedImage(): Boolean = isAnimatedMime(mimeType)

/**
 * Identity stamp for derived previews (thumbnails, video posters). Changes when
 * the underlying bytes do, so caches keyed by it are busted on edit/replace.
 * URI-only keys cannot see this — SAF document URIs are path-stable, so a file
 * recreated at the same path would otherwise keep its old preview.
 */
internal fun MediaItem.previewStamp(): String = "$lastModified:$sizeBytes"

data class MediaFolder(
    val bucketId: String,
    val name: String,
    val count: Int,
    /**
     * Up to 3 newest items shown as the stacked cover. Carries full identity
     * (not just the URI) so a cover cache keyed on it is busted when a file is
     * edited, replaced, or deleted — SAF document URIs are path-stable.
     */
    val previews: List<MediaItem>,
    val dateAdded: Long,
    /** SAF tree URI when this folder came from directly browsing a granted tree. */
    val safTreeUri: String? = null,
    /** Parent document id within [safTreeUri] when SAF-originated. */
    val safDocId: String? = null,
    /** MediaStore bucket id for MediaStore-sourced items in this folder (deny-list). */
    val mediaStoreBucketId: String? = null,
)
