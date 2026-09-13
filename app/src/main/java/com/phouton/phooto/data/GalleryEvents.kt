package com.phouton.phooto.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide one-shot events. Currently just "the media library changed, reload
 * it", emitted by [com.phouton.phooto.MainActivity]'s MediaStore observer and by
 * Settings after clearing the cache, and collected by the composed gallery.
 *
 * A shared flow keeps the producer and consumer decoupled (no registration
 * handshake) and drops emissions while nothing is collecting, which is fine for
 * a reload hint.
 */
object GalleryEvents {
    private val _reload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by the gallery while it is composed. */
    val reload: SharedFlow<Unit> = _reload.asSharedFlow()

    fun requestReload() {
        _reload.tryEmit(Unit)
    }
}
