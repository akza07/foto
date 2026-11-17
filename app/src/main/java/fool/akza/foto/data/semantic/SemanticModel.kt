package fool.akza.foto.data.semantic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ModelType
import fool.akza.foto.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The on-device EmbeddingGemma 2 model file.
 *
 * The model is never bundled in the APK (388 MB): the user imports it once from
 * device storage. The file lives in app-private storage so no media permission
 * is needed to read it back, and the file on disk is the single source of truth
 * for "is semantic search available?".
 */
object SemanticModel {

    /** Exact name LiteRT-LM expects; the import keeps it so diagnostics stay clear. */
    const val MODEL_FILENAME = "embeddinggemma-2-text-vision-440m.litertlm"

    enum class State { Missing, Importing, Ready, Invalid }

    data class Status(
        val state: State = State.Missing,
        val sizeBytes: Long = 0L,
        /** 0..1 while [State.Importing]. */
        val progress: Float = 0f,
        val message: String? = null,
    ) {
        val isReady: Boolean get() = state == State.Ready
        val exists: Boolean get() = state == State.Ready || state == State.Invalid
    }

    private const val CopyChunk = 1 shl 20

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun file(context: Context): File = File(context.filesDir, MODEL_FILENAME)

    /** Re-reads what is on disk. Cheap: no model parsing, just a stat. */
    fun refresh(context: Context) {
        // An import owns the status while it runs; do not stomp its progress.
        if (_status.value.state == State.Importing) return
        val file = file(context)
        _status.value = if (file.isFile && file.length() > 0L) {
            Status(state = State.Ready, sizeBytes = file.length())
        } else {
            Status(state = State.Missing)
        }
    }

    /**
     * Copies the picked `.litertlm` into app storage, validating it before it
     * replaces the current model. A failed import leaves the previous model (if
     * any) untouched, so the user never ends up with a half-written file.
     */
    suspend fun import(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val target = file(context)
        val part = File(target.parentFile, "$MODEL_FILENAME.part")
        try {
            runCatchingCancellable {
                part.delete()
            val total = querySize(context, uri)
            var copied = 0L
            _status.value = Status(state = State.Importing, sizeBytes = total, progress = 0f)
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(CopyChunk)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            _status.value = Status(
                                state = State.Importing,
                                sizeBytes = total,
                                progress = (copied.toFloat() / total).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            } ?: throw IOException("Could not open the selected file")

            if (part.length() <= 0L) throw IOException("The selected file is empty")

            // A different model means a different embedding space: old vectors
            // would be meaningless (or the wrong dimension). Re-importing the
            // same byte size is treated as the same model and keeps the index.
            val previousSize = if (target.isFile) target.length() else -1L
            val sameModel = previousSize == part.length()

            // Reject anything that is not an EmbeddingGemma LiteRT-LM model
            // before it can be used or shown as ready.
            val info = ModelInfo.from(part.absolutePath)
            try {
                if (info.modelType != ModelType.EMBEDDING) {
                    throw IOException("Not an embedding model (${info.modelType})")
                }
            } finally {
                runCatching { info.close() }
            }

            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace the existing model")
            }
            if (!part.renameTo(target)) {
                throw IOException("Could not finish installing the model")
            }
            // The running engine holds the old file; it must be re-created.
            GemmaEmbedder.close()
            if (!sameModel) {
                PhotoVectorStore.wipe(context)
                PhotoVectorStore.clear()
                SemanticIndexer.invalidate()
            }
            _status.value = Status(state = State.Ready, sizeBytes = target.length())
            }.onFailure { error ->
                AppLog.w("semantic: model import failed", error)
                part.delete()
                val existing = target.takeIf { it.isFile && it.length() > 0L }
                _status.value = if (existing != null) {
                    Status(state = State.Ready, sizeBytes = existing.length())
                } else {
                    Status(
                        state = State.Invalid,
                        message = error.message ?: "Unreadable model file",
                    )
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Leaving Settings cancels the copy; drop the partial file and
            // restore the real state so the row is not stuck at "Copying…".
            part.delete()
            val existing = target.takeIf { it.isFile && it.length() > 0L }
            _status.value = if (existing != null) {
                Status(state = State.Ready, sizeBytes = existing.length())
            } else {
                Status(state = State.Missing)
            }
            throw cancellation
        }
    }

    suspend fun delete(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = file(context)
        val existed = file.exists()
        val removed = runCatching { !existed || file.delete() }.getOrDefault(false)
        if (removed) {
            // Vectors from the deleted model are meaningless; drop them first.
            runCatchingCancellable { PhotoVectorStore.wipe(context) }
            PhotoVectorStore.clear()
            GemmaEmbedder.close()
            SemanticIndexer.invalidate()
            _status.value = Status(state = State.Missing)
        }
        removed
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
        }.getOrDefault(0L)
    }
}
