package fool.akza.foto.data.semantic

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import fool.akza.foto.AppLog
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.SemanticIndexing
import fool.akza.foto.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Progress of the content pass, surfaced like the preview/metadata passes. */
data class SemanticProgress(
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val failed: Int = 0,
    val backend: String? = null,
    /** Stopped because the device was short on memory; resumes on next launch. */
    val paused: Boolean = false,
    /** Stopped because the selected accelerator could not start. */
    val backendFailed: Boolean = false,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val isIndexing: Boolean get() = running && total > 0
}

/**
 * Keeps the AppSearch vector index in step with the library.
 *
 * Embedding a photo is 100–800 ms, so the pass is incremental and resumable:
 * every stored vector carries the file's identity stamp and only changed or new
 * files are re-embedded. Work that already finished survives process death,
 * which matters on the first run over a large library.
 */
object SemanticIndexer {

    /** First-run ceiling. Keeps a huge library from pinning the CPU for an hour;
     *  the newest photos are the ones search/grouping needs most. */
    private const val MaxPhotosPerPass = 800

    private val _progress = MutableStateFlow(SemanticProgress())
    val progress: StateFlow<SemanticProgress> = _progress.asStateFlow()

    private val mutex = Mutex()
    private var lastKey: String? = null

    /** Makes the next pass re-read the library instead of trusting the last key. */
    fun invalidate() {
        lastKey = null
    }

    suspend fun sync(
        context: Context,
        items: List<MediaItem>,
        complete: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            SemanticModel.refresh(context)
            if (!SemanticModel.status.value.isReady) {
                _progress.value = SemanticProgress()
                return@withLock
            }

            // The user can pause indexing or restrict it to charging; both are
            // respected before any photo is decoded.
            when (SettingsRepository.settings.value.semanticIndexing) {
                SemanticIndexing.PAUSED -> {
                    _progress.value = SemanticProgress()
                    return@withLock
                }
                SemanticIndexing.CHARGING -> if (!isCharging(context)) {
                    _progress.value = SemanticProgress()
                    return@withLock
                }
                SemanticIndexing.AUTOMATIC -> Unit
            }

            val candidates = items
                .asSequence()
                .filter { !it.isVideo }
                .distinctBy { it.uri.toString() }
                .sortedByDescending { it.lastModified }
                .take(MaxPhotosPerPass)
                .toList()
            if (candidates.isEmpty()) return@withLock

            val key = libraryKey(candidates)
            if (key == lastKey) return@withLock

            val stored = runCatchingCancellable { PhotoVectorStore.storedStamps(context) }
                .getOrElse {
                    AppLog.w("semantic: could not read the vector index", it)
                    emptyMap()
                }

            // Deleted/moved files leave stale vectors behind; drop them so a
            // query never returns a URI that no longer exists. Prune against the
            // whole library, not just this pass's capped candidates.
            if (complete) {
                val libraryUris = items
                    .asSequence()
                    .filter { !it.isVideo }
                    .mapTo(HashSet()) { it.uri.toString() }
                val stale = stored.keys - libraryUris
                if (stale.isNotEmpty()) {
                    runCatchingCancellable { PhotoVectorStore.remove(context, stale) }
                        .onFailure { AppLog.w("semantic: stale vector removal failed", it) }
                }
            }

            val work = candidates.filter { stored[it.uri.toString()] != stamp(it) }

            if (work.isEmpty()) {
                PhotoVectorStore.refreshCount(context)
                _progress.value = SemanticProgress(
                    done = candidates.size,
                    total = candidates.size,
                    backend = GemmaEmbedder.backend.value,
                )
                lastKey = key
                return@withLock
            }

            val engine = GemmaEmbedder.ensureReady(context).getOrElse { error ->
                // Keep lastKey unset: the next pass should retry once the
                // backend becomes available (e.g. after a phone restart).
                AppLog.w("semantic: model unavailable", error)
                _progress.value = SemanticProgress()
                return@withLock
            }

            val total = work.size
            _progress.value = SemanticProgress(
                done = 0,
                total = total,
                running = true,
                backend = engine,
            )

            val pending = ArrayList<PhotoVector>(8)
            var failed = 0
            var done = 0
            var paused = false
            var backendFailed = false

            suspend fun flush() {
                if (pending.isEmpty()) return
                val batch = ArrayList(pending)
                pending.clear()
                runCatchingCancellable { PhotoVectorStore.putAll(context, batch) }
                    .onFailure { AppLog.w("semantic: vector write failed", it) }
            }

            for ((index, item) in work.withIndex()) {
                currentCoroutineContext().ensureActive()
                // Keep the device responsive: on battery, breathe between
                // images; on the charger, run flat out. Power saver gets the
                // gentlest pace.
                if (index > 0) delay(paceBetweenImages(context))
                // Memory pressure or a low battery backs the pass off, then
                // leaves it resumable instead of pushing the phone into swap.
                if (index > 0 && index % 5 == 0 && !awaitResources(context)) {
                    paused = true
                    break
                }
                var vector = GemmaEmbedder.embedImage(context, item.uri)
                if (vector == null) {
                    // The engine can be gone mid-pass (accelerator changed, model
                    // replaced, idle release). Recreate it once; if the required
                    // backend still cannot start, the feature is off — stop the
                    // pass instead of burning every remaining photo on failure.
                    if (GemmaEmbedder.ensureReady(context).isFailure) {
                        backendFailed = true
                        break
                    }
                    vector = GemmaEmbedder.embedImage(context, item.uri)
                }
                if (vector == null) {
                    failed++
                } else {
                    pending += PhotoVector(
                        id = item.uri.toString(),
                        uri = item.uri.toString(),
                        stamp = stamp(item),
                        addedAt = item.dateAdded,
                        values = vector,
                    )
                    if (pending.size >= 8) flush()
                }
                done++
                _progress.value = SemanticProgress(
                    done = done,
                    total = total,
                    running = true,
                    failed = failed,
                    backend = engine,
                )
            }
            flush()

            PhotoVectorStore.refreshCount(context)
            if (!paused && !backendFailed) lastKey = key
            _progress.value = SemanticProgress(
                done = done,
                total = total,
                failed = failed,
                backend = engine,
                paused = paused,
                backendFailed = backendFailed,
            )
        }
    }

    /** Everything currently indexed, as URI → normalized vector. */
    suspend fun vectors(context: Context): Map<String, FloatArray> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable { PhotoVectorStore.allVectors(context) }
                .getOrElse {
                    AppLog.w("semantic: could not read vectors", it)
                    emptyMap()
                }
        }

    private fun stamp(item: MediaItem): String =
        "${GemmaEmbedder.EmbeddingConfigId}:${item.lastModified}:${item.sizeBytes}:${item.dateAdded}"

    private fun libraryKey(items: List<MediaItem>): String {
        var hash = 17L
        for (item in items.sortedBy { it.uri.toString() }) {
            hash = hash * 31 + item.uri.hashCode()
            hash = hash * 31 + stamp(item).hashCode()
        }
        return "${items.size}:$hash"
    }

    /** Waits up to 30s for memory/battery to recover; false means stop the pass. */
    private suspend fun awaitResources(context: Context): Boolean {
        if (!underPressure(context)) return true
        repeat(6) {
            delay(5_000)
            if (!underPressure(context)) return true
        }
        return false
    }

    private fun underPressure(context: Context): Boolean =
        memoryPressure(context) || batteryLow(context)

    private fun memoryPressure(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.lowMemory || info.availMem < 700L * 1024 * 1024
    }

    private fun batteryLow(context: Context): Boolean {
        if (isCharging(context)) return false
        val status = batteryStatus(context) ?: return false
        val level = status.first
        val scale = status.second
        return scale > 0 && level * 100 / scale <= 15
    }

    /** A light breather on battery or while charging, more in power saver.
     *  Charging is not a licence to peg the SoC: sustained GPU work still
     *  heats the phone and makes the UI stutter. */
    private fun paceBetweenImages(context: Context): Long {
        val charging = isCharging(context)
        val powerSave = context.getSystemService(PowerManager::class.java)
            ?.isPowerSaveMode == true
        return when {
            powerSave && !charging -> 400L
            charging -> 60L
            else -> 120L
        }
    }

    private fun isCharging(context: Context): Boolean {
        val plugged = batteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    /** (level, scale) from the sticky battery broadcast. */
    private fun batteryStatus(context: Context): Pair<Int, Int>? {
        val intent = batteryIntent(context) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level to scale
    }

    private fun batteryIntent(context: Context) = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
}
