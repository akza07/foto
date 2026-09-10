package com.phouton.phooto.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.data.thumbnails.PlaybackThrottle
import kotlin.math.abs

/**
 * Owns the viewer's single ExoPlayer and a Media3 [DefaultPreloadManager].
 *
 * The viewer used to build (and release) one ExoPlayer per settled video page,
 * so every swipe paid full player startup and the poster veil existed to hide a
 * cold decoder. This controller keeps one player alive for the whole viewer
 * session and preloads the videos near the settled page, so the first frame is
 * usually buffered before the swipe finishes.
 *
 * Threading: every method must be called on the main thread (the preload
 * manager enforces this). Compose effects and `Player.Listener` callbacks both
 * run there.
 *
 * Lifecycle: call [release] exactly once when the viewer closes. The preload
 * manager is released before the player, which is the order Media3 requires.
 */
@OptIn(UnstableApi::class)
class VideoPlaybackController(
    context: Context,
    loopVideos: Boolean = false,
) {

    private val appContext = context.applicationContext
    private val targetPreloadStatus = TieredPreloadStatusControl()

    val player: ExoPlayer
    private val preloadManager: DefaultPreloadManager?

    /** uri string -> the exact Media3 item handed to the preload manager. */
    private val tracked = LinkedHashMap<String, Media3MediaItem>()

    /** uri string -> reusable Media3 item (stable instance for map lookups). */
    private val media3Items = HashMap<String, Media3MediaItem>()

    private var mediaItems: List<MediaItem> = emptyList()
    private var currentIndex: Int = C.INDEX_UNSET
    private var released = false

    /**
     * mediaId of the item whose first frame the player has rendered, or null
     * while the surface still needs covering. The video page fades its poster
     * veil when this matches its own item.
     */
    var renderedMediaId by mutableStateOf<String?>(null)
        private set

    init {
        // Prefer the shared player + preload manager. If the (unstable) preload
        // manager can't be built on this device, fall back to a plain player so
        // playback still works; it just loses neighbor preloading.
        val shared = runCatching { buildShared() }.getOrNull()
        player = shared?.player ?: exoPlayerBuilder().build()
        preloadManager = shared?.preloadManager
        player.repeatMode = if (loopVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedMediaId = player.currentMediaItem?.mediaId
            }

            override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
                renderedMediaId = null
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Let the background poster warm pass back off: it seeks with
                // MediaMetadataRetriever, which can contend with the decoder.
                // The pass retries later.
                PlaybackThrottle.isPlaying = isPlaying
            }
        })
    }

    private class SharedPlayer(val player: ExoPlayer, val preloadManager: DefaultPreloadManager)

    /**
     * Builds the player and the preload manager from one builder so they share
     * the load control, renderers, bandwidth meter and looper, as Media3
     * requires. Releases the manager if the player build fails.
     */
    private fun buildShared(): SharedPlayer {
        val builder = DefaultPreloadManager.Builder(appContext, targetPreloadStatus)
        val manager = builder.build()
        return try {
            SharedPlayer(builder.buildExoPlayer(exoPlayerBuilder()), manager)
        } catch (t: Throwable) {
            runCatching { manager.release() }
            throw t
        }
    }

    private fun exoPlayerBuilder(): ExoPlayer.Builder =
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)

    /**
     * Replaces the viewer's item list. Indices shift when an item is deleted,
     * so the preload window is recomputed around [currentIndex]. Tracking is
     * keyed by uri, so the item the player is currently using is never removed
     * by a re-index.
     */
    fun updateItems(items: List<MediaItem>) {
        if (released) return
        mediaItems = items
        val liveUris = items.mapTo(HashSet()) { it.uri.toString() }
        media3Items.keys.retainAll(liveUris)
        syncWindow()
    }

    /** Called when the pager settles on [index]. */
    fun onSettled(index: Int) {
        if (released) return
        currentIndex = index
        // Add the desired window (including the settled item) before handing a
        // source to the player, so a preloaded source is available if warm.
        syncWindow()

        val item = mediaItems.getOrNull(index)
        if (item != null && item.isVideo) {
            val mediaItem = media3ItemFor(item)
            val preloaded = preloadManager?.getMediaSource(mediaItem)
            if (preloaded != null) {
                player.setMediaSource(preloaded)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.prepare()
            player.playWhenReady = false
        } else {
            // Settled on an image: drop the video decoders.
            player.pause()
            player.stop()
        }
    }

    /**
     * Applies the "loop videos" preference. Safe to call at any time; toggling
     * it while a video is playing takes effect at its next end.
     */
    fun setLoop(isLooping: Boolean) {
        if (released) return
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Releases the preload manager first, then the player. */
    fun release() {
        if (released) return
        released = true
        PlaybackThrottle.isPlaying = false
        runCatching { preloadManager?.release() }
        runCatching { player.release() }
        tracked.clear()
        media3Items.clear()
    }

    /**
     * Adds videos within [PRELOAD_WINDOW] of the settled index and removes the
     * rest. Re-adding on every settle is cheap; membership is by uri.
     */
    private fun syncWindow() {
        if (released || currentIndex == C.INDEX_UNSET) return
        val manager = preloadManager ?: return
        val center = currentIndex
        val from = (center - PRELOAD_WINDOW).coerceAtLeast(0)
        val to = (center + PRELOAD_WINDOW).coerceAtMost(mediaItems.lastIndex)
        val desired = LinkedHashMap<String, Media3MediaItem>()
        if (from <= to) {
            for (i in from..to) {
                val item = mediaItems[i]
                if (item.isVideo) {
                    desired[item.uri.toString()] = media3ItemFor(item)
                }
            }
        }

        val iterator = tracked.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!desired.containsKey(entry.key)) {
                manager.remove(entry.value)
                iterator.remove()
            }
        }

        for ((uri, mediaItem) in desired) {
            if (!tracked.containsKey(uri)) {
                val ranking = mediaItems.indexOfFirst { it.uri.toString() == uri }
                if (ranking >= 0) {
                    // A URI whose type can't be inferred would throw in
                    // createMediaSource; skip preloading it rather than crash.
                    // The player still plays it via setMediaItem on settle.
                    if (runCatching { manager.add(mediaItem, ranking) }.isSuccess) {
                        tracked[uri] = mediaItem
                    }
                }
            }
        }

        targetPreloadStatus.currentIndex = center
        manager.setCurrentPlayingIndex(center)
        manager.invalidate()
    }

    private fun media3ItemFor(item: MediaItem): Media3MediaItem {
        val key = item.uri.toString()
        return media3Items.getOrPut(key) {
            Media3MediaItem.Builder()
                .setUri(item.uri)
                .setMediaId(key)
                .build()
        }
    }

    /**
     * Preload budget by distance from the settled item. Adjacent videos get a
     * few seconds buffered; farther ones are only prepared so the window stays
     * bounded.
     */
    private class TieredPreloadStatusControl :
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

        var currentIndex: Int = C.INDEX_UNSET

        override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
            if (currentIndex == C.INDEX_UNSET) {
                return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
            return when (abs(rankingData - currentIndex)) {
                0 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
                1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1_500L)
                2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                in 3..PRELOAD_WINDOW ->
                    DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }
}

/** Videos kept tracked on each side of the settled index. */
private const val PRELOAD_WINDOW = 4
