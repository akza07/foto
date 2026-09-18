package fool.akza.foto.data.thumbnails

/**
 * Whether a video is actively playing, so the background poster warm pass can
 * back off.
 *
 * [VideoPosterExtractor] seeks with `MediaMetadataRetriever`, which uses the
 * same hardware decoders as playback on some devices. Extraction is not urgent
 * (the tile keeps its shimmer until the poster lands), so the warm pass skips
 * while this is set and picks the item up on a later pass.
 */
object PlaybackThrottle {
    @Volatile
    var isPlaying: Boolean = false
}
