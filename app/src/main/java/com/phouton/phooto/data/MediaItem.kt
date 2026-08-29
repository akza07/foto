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
