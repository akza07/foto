package com.phouton.phooto.data.thumbnails

/**
 * Tiny bridge so `MainActivity` (which owns the `SyncCoordinator`) can ask the
 * current `GalleryScreen` to reload when MediaStore notifies us. `GalleryScreen`
 * publishes its reload lambda on entry and clears it on dispose; calls between
 * disposes (e.g. activity stopped before gallery mounted) are dropped silently.
 */
object ExternalReloadSink {
    @Volatile
    var current: (() -> Unit)? = null
}
