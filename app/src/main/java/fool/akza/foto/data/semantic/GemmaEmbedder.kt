package fool.akza.foto.data.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import fool.akza.foto.AppLog
import fool.akza.foto.data.SemanticAccelerator
import fool.akza.foto.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 (text + vision, 440M) through Google's LiteRT-LM runtime.
 *
 * The engine is process-wide and created lazily: loading a 388 MB model is the
 * single most expensive step in the feature, so it only happens once an actual
 * embedding needs computing. Inference is serialized because a LiteRT-LM engine
 * is not safe to call from several threads at once.
 *
 * Backends are tried from fastest to most portable. Vendor GPU drivers differ
 * wildly (Mali, Adreno, Xclipse), so a failure is expected on some devices and
 * must downgrade instead of breaking search.
 */
object GemmaEmbedder {

    /** Full 768-D embedding. The 256-D Matryoshka slice saved index space but
     *  dropped the attribute dimensions that separate "blue shirt" from a
     *  generally blue photo. */
    const val DIM = 768

    /** Bump when the image/vision settings below change: stored vectors then
     *  carry a different stamp, and a new dimension also gets its own AppSearch
     *  database, so old vectors never mix with a new embedding space. */
    const val EmbeddingConfigId = "embeddinggemma-2-tv-440m-768d-vt140-s512"

    private const val TAG = "GemmaEmbedder"

    /** The vision encoder patchifies at 16px. 768px produced ~1100 patches and
     *  ~2 GB of GPU buffers on a Dimensity 8000, which made the phone swap.
     *  512px keeps patches near 540, so the larger 140-token budget still stays
     *  far below the original memory spike while preserving fine detail. */
    private const val ImageMaxDimension = 512

    /** 140 soft tokens preserve small objects and clothing attributes; the 512px
     *  bound above is what keeps the vision patch count (and GPU memory) down. */
    private const val VisionTokensPerImage = 140

    /** EmbeddingGemma 2 retrieval prompt. (EmbeddingGemma 1 used
     *  `task: search query | text:`; that format is wrong for this model.) */
    private const val QueryPrefix = "task: search result | query:"

    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null
    private var activeBackend: String? = null
    private var activePreference: SemanticAccelerator? = null

    private val _error = MutableStateFlow<String?>(null)
    /** Last backend failure, for the Content search settings row. */
    val error: StateFlow<String?> = _error.asStateFlow()

    // The vision encoder's GPU buffers are large (a Dimensity 8000 held ~2 GB).
    // They are only needed while embedding, so the engine is released after a
    // quiet period instead of keeping the phone short on memory all day.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    @Volatile
    private var lastUse = 0L
    private val idleCloseMs = 90_000L

    private val _backend = MutableStateFlow<String?>(null)
    /** Human-readable backend label once the engine is live ("GPU", "CPU", …). */
    val backend: StateFlow<String?> = _backend.asStateFlow()

    private val options = EmbeddingOptions(
        normalize = true,
        insertSpecialTokens = true,
        outputSize = DIM,
        visionTokensPerImage = VisionTokensPerImage,
    )

    suspend fun ensureReady(context: Context): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val preference = SettingsRepository.settings.value.semanticAccelerator
            if (engine != null && activePreference == preference) {
                touch()
                scheduleIdleClose()
                return@withContext Result.success(activeBackend ?: preference.name)
            }
            // A different accelerator was chosen: drop the old engine first.
            if (engine != null) closeEngineLocked()

            val modelFile = SemanticModel.file(context)
            if (!modelFile.isFile || modelFile.length() <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException("The on-device model is not installed"),
                )
            }

            // Strict: the selected backend is the only one tried. A GPU failure
            // disables content search rather than silently running on the CPU.
            val candidate = candidateFor(preference)
            try {
                val config = EmbeddingEngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = candidate.text,
                    visionBackend = candidate.vision,
                    visionTokensPerImage = VisionTokensPerImage,
                    // Queries and prompts are short; capping the text
                    // signature keeps LiteRT from allocating buffers for the
                    // 8192-token variant, which is a large part of the
                    // engine's GPU footprint.
                    maxInputLength = 256,
                )
                val created = EmbeddingEngine(config)
                created.initialize()
                // Initialisation can succeed and the first forward pass still
                // fail (missing shader, no memory); force one tiny text
                // embedding before declaring the backend usable.
                val probe = created.computeEmbedding(
                    listOf(InputData.Text("$QueryPrefix warmup")),
                    options,
                )
                if (probe.embedding.isEmpty()) error("empty warmup embedding")

                engine = created
                activeBackend = candidate.label
                activePreference = preference
                _backend.value = candidate.label
                _error.value = null
                AppLog.d("semantic: engine ready on ${candidate.label}")
                Result.success(candidate.label)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "${candidate.label} unavailable: ${t.message}")
                AppLog.w("semantic: ${candidate.label} init failed", t)
                _error.value = t.message ?: t.javaClass.simpleName
                activePreference = null
                runCatching { engine?.close() }
                engine = null
                _backend.value = null
                Result.failure(t)
            }
        }
    }

    /** Embeds one photo. [uri] is decoded to a bounded JPEG first so the engine
     *  accepts every gallery format (HEIC/AVIF included) and the JNI copy stays small. */
    suspend fun embedImage(context: Context, uri: Uri): FloatArray? {
        val bytes = withContext(Dispatchers.IO) { imageBytes(context, uri) } ?: return null
        return embed(listOf(InputData.Image(bytes)))
    }

    suspend fun embedQuery(text: String): FloatArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return embed(listOf(InputData.Text("$QueryPrefix $trimmed")))
    }

    private suspend fun embed(input: List<InputData>): FloatArray? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val active = engine ?: return@withContext null
            touch()
            // Background indexing must not outrank the UI. The caller thread's
            // niceness is inherited by much of the CPU-side work (decode,
            // preprocess, XNNPACK setup) even when the heavy lift is on the GPU.
            val previousPriority = runCatching {
                Process.getThreadPriority(Process.myTid())
            }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val vector = active.computeEmbedding(input, options).embedding
                normalize(vector)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.w("semantic: embedding failed", t)
                null
            } finally {
                Process.setThreadPriority(previousPriority)
                scheduleIdleClose()
            }
        }
    }

    /** Closes the engine. Serialized with inference so a model replace/delete
     *  cannot free native state under a running forward pass. */
    suspend fun close() = withContext(Dispatchers.Default) {
        idleJob?.cancel()
        mutex.withLock { closeEngineLocked() }
    }

    private fun closeEngineLocked() {
        runCatching { engine?.close() }
        engine = null
        activeBackend = null
        activePreference = null
        _backend.value = null
    }

    private fun touch() {
        lastUse = SystemClock.elapsedRealtime()
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleCloseMs)
            closeIfIdle()
        }
    }

    private suspend fun closeIfIdle() {
        mutex.withLock {
            if (engine == null) return
            if (SystemClock.elapsedRealtime() - lastUse < idleCloseMs) return
            closeEngineLocked()
            AppLog.d("semantic: engine released after idle")
        }
    }

    private fun candidateFor(preference: SemanticAccelerator): Candidate =
        when (preference) {
            SemanticAccelerator.CPU -> Candidate("CPU", Backend.CPU(), Backend.CPU())
            SemanticAccelerator.GPU -> Candidate("GPU", Backend.GPU(), Backend.GPU())
        }

    private data class Candidate(val label: String, val text: Backend, val vision: Backend)

    /** Decodes, orientation-corrects, bounds and re-encodes an image for the
     *  vision encoder. ImageDecoder handles HEIF/AVIF and EXIF rotation. */
    private fun imageBytes(context: Context, uri: Uri): ByteArray? {
        val source = runCatching {
            ImageDecoder.createSource(context.contentResolver, uri)
        }.getOrNull() ?: return null

        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > ImageMaxDimension) {
                    decoder.setTargetSampleSize(max(1, longest / ImageMaxDimension))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null

        return try {
            val longest = max(bitmap.width, bitmap.height)
            val scaled = if (longest > ImageMaxDimension) {
                val scale = ImageMaxDimension.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * scale).toInt()),
                    max(1, (bitmap.height * scale).toInt()),
                    true,
                )
            } else {
                bitmap
            }
            ByteArrayOutputStream(64 * 1024).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }.also {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Defensive L2 normalization; the runtime already normalizes with
     *  [EmbeddingOptions.normalize], but an older model file may not. */
    private fun normalize(vector: FloatArray): FloatArray {
        val size = min(DIM, vector.size)
        val out = FloatArray(size)
        var norm = 0f
        for (i in 0 until size) {
            out[i] = vector[i]
            norm += out[i] * out[i]
        }
        if (norm > 0f) {
            val inv = 1f / sqrt(norm)
            for (i in out.indices) out[i] *= inv
        }
        return out
    }
}
