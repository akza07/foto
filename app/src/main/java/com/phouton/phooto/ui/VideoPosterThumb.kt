package com.phouton.phooto.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.thumbnails.ThumbCache
import com.phouton.phooto.data.thumbnails.VideoFrameCache
import com.phouton.phooto.ui.theme.EmptySurface
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fast static poster for video tiles.
 *
 * Coil's `VideoFrameDecoder` seeks into the video with MediaMetadataRetriever,
 * which is slow (1-3s per tile). Instead:
 *  1. Our private poster cache — instant after first fetch.
 *  2. MediaStore's pre-generated thumbnail via [android.content.ContentResolver.loadThumbnail]
 *     — fast, system-cached (API 29+, our minSdk). The result is persisted to
 *     [VideoFrameCache] so it's instant next time.
 *  3. Coil frame extraction as a last-resort fallback (works for SAF URIs with
 *     no MediaStore thumbnail).
 */
@Composable
fun VideoPosterThumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var poster by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var needCoilFallback by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val loaded = withContext(Dispatchers.IO) {
            // 1) Private poster cache — instant after first fetch.
            VideoFrameCache.load(context, item.uri)
                // 2) MediaStore system thumbnail — fast even on first open.
                ?: runCatching {
                    context.contentResolver.loadThumbnail(
                        item.uri,
                        Size(512, 512),
                        null,
                    )
                }.getOrNull()
        }
        if (loaded != null) {
            // Persist the poster so next open (even after restart) is instant.
            if (!VideoFrameCache.has(context, item.uri)) {
                VideoFrameCache.save(context, item.uri, loaded)
            }
            poster = loaded
        } else {
            needCoilFallback = true
        }
    }

    if (needCoilFallback) {
        // 3) Last resort: Coil frame extraction (slow but works for SAF).
        val key = remember(item.uri, item.lastModified, item.sizeBytes, item.mimeType) {
            ThumbCache.keyFor(item)
        }
        val model = remember(item.uri) {
            ThumbCache.buildRequest(context, item, key, animated = false)
        }
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        ) {
            val state by painter.state.collectAsState()
            if (state is AsyncImagePainter.State.Success) {
                SubcomposeAsyncImageContent()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EmptySurface),
                )
            }
        }
        return
    }

    val b = poster
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        // Decode in-flight: subtle placeholder, never a black/blank tile.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(EmptySurface),
        )
    }
}
