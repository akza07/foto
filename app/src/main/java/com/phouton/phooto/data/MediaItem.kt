package com.phouton.phooto.data

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
    /** MIME type from MediaStore or DocumentsContract. Used to decide whether a
     * tile should render animated (image/gif, image/webp, image/heif) and as a
     * tiebreaker in the thumbnail cache key. */
    val mimeType: String? = null,
    /** Last-modified epoch seconds (MediaStore DATE_MODIFIED or DocumentsContract
     * COLUMN_LAST_MODIFIED). Mixed into the thumbnail cache key so edits bust
     * the cached entry. */
    val lastModified: Long = 0L,
    /** File size in bytes (MediaStore SIZE or DocumentsContract COLUMN_SIZE).
     * Mixed into the thumbnail cache key for additional entropy. */
    val sizeBytes: Long = 0L,
) {
    /**
     * True when the source is a multi-frame image Coil can animate inline
     * (GIF, animated WebP, animated HEIF/HEIC). Cheap to compute from
     * [mimeType]; we don't decode the file to verify.
     */
    val isAnimated: Boolean
        get() = mimeType in ANIMATED_MIME_TYPES
}

private val ANIMATED_MIME_TYPES = setOf(
    "image/gif",
    "image/webp",
    "image/heif",
    "image/heic",
    "image/avif",
)

data class MediaFolder(
    val bucketId: String,
    val name: String,
    val count: Int,
    val previews: List<Uri>,
    val dateAdded: Long,
    /** SAF tree URI when this folder came from directly browsing a granted tree. */
    val safTreeUri: String? = null,
    /** Parent document id within [safTreeUri] when SAF-originated. */
    val safDocId: String? = null,
    /** MediaStore bucket id for MediaStore-sourced items in this folder (deny-list). */
    val mediaStoreBucketId: String? = null,
)
