package fool.akza.foto.data.thumbnails

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for changes and asks the caller to reload the gallery.
 *
 * The actual reconciliation (computing which items need thumbnails, enqueuing
 * missing ones) lives in [GalleryScreen] where the full item list is already
 * available; this class just signals "something changed, please reconcile".
 *
 * `notifyForDescendants = true` means any insert/update/delete on
 * MediaStore.Files or its children triggers us. A 1500 ms debounce coalesces
 * burst events (e.g. a camera burst).
 */
class SyncCoordinator(
    private val onChange: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var debounceJob: Job? = null

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleReload()
            }
        }
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            /* notifyForDescendants = */ true,
            obs,
        )
        observer = obs
    }

    fun stop(context: Context) {
        if (!running.compareAndSet(true, false)) return
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun scheduleReload() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            onChange()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L
    }
}
