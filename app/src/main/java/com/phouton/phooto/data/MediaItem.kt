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
)

data class MediaFolder(
    val bucketId: String,
    val name: String,
    val count: Int,
    val previews: List<Uri>,
    val dateAdded: Long,
)
