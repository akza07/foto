package fool.akza.foto.data.semantic

import android.content.Context
import androidx.appsearch.app.AppSearchBatchResult
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.EmbeddingVector
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.concurrent.futures.await
import fool.akza.foto.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** One photo's 256-D embedding plus the identity needed to validate the cache. */
data class PhotoVector(
    val id: String,
    val uri: String,
    val stamp: String,
    val addedAt: Long,
    val values: FloatArray,
)

data class VectorHit(val id: String, val uri: String, val addedAt: Long, val score: Float)

/**
 * AppSearch is the on-device vector database: embeddings are persisted in
 * LocalStorage and `semanticSearch(...)` does the cosine math natively.
 *
 * The schema is declared by hand instead of with `@Document` + KSP: the project
 * builds Kotlin through AGP's built-in support, and an annotation processor
 * would add a toolchain dependency for one three-property document. This keeps
 * the ABI and build setup identical to the rest of the app.
 */
object PhotoVectorStore {

    private const val DB_NAME = "foto_semantic_v2"
    private const val NAMESPACE = "photos"
    private const val SCHEMA = "FotoPhotoVector"
    private const val PROP_URI = "uri"
    private const val PROP_STAMP = "stamp"
    private const val PROP_ADDED = "addedAt"
    private const val PROP_VECTOR = "embedding"

    /** Changes when the model family/dimension changes, so old vectors never
     *  match a query from a different embedding space. The 768-D switch uses a
     *  fresh database because AppSearch fixes the dimension per embedding
     *  property; a mixed-dimension index would reject writes. */
    const val MODEL_SIGNATURE = "embeddinggemma-2-tv-440m-768d-vt140-s512"

    private val mutex = Mutex()
    private var session: AppSearchSession? = null

    /** In-memory mirror of the stored vectors; the grouping pass needs all of
     *  them at once and re-reading 1000 documents from AppSearch each scan is
     *  pure overhead. Invalidate on every write. */
    @Volatile
    private var cachedVectors: Map<String, FloatArray>? = null

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever the index changes. Similarity/grouping keys on this. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    suspend fun initialize(context: Context): AppSearchSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            session?.let { return@withContext it }

            val searchContext = LocalStorage.SearchContext.Builder(context, DB_NAME).build()
            val created = LocalStorage.createSearchSessionAsync(searchContext).await()
            val schema = AppSearchSchema.Builder(SCHEMA)
                .addProperty(
                    AppSearchSchema.StringPropertyConfig.Builder(PROP_URI)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                        .build(),
                )
                .addProperty(
                    AppSearchSchema.StringPropertyConfig.Builder(PROP_STAMP)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                        .build(),
                )
                .addProperty(
                    AppSearchSchema.LongPropertyConfig.Builder(PROP_ADDED)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                        .setIndexingType(AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_NONE)
                        .build(),
                )
                .addProperty(
                    AppSearchSchema.EmbeddingPropertyConfig.Builder(PROP_VECTOR)
                        .setIndexingType(
                            AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY,
                        )
                        .setQuantizationType(
                            AppSearchSchema.EmbeddingPropertyConfig.QUANTIZATION_TYPE_NONE,
                        )
                        .build(),
                )
                .build()

            created.setSchemaAsync(
                SetSchemaRequest.Builder().addSchemas(schema).build(),
            ).await()

            session = created
            created
        }
    }

    /** Stores/updates a batch. Returns how many documents were accepted. */
    suspend fun putAll(context: Context, vectors: List<PhotoVector>): Int =
        withContext(Dispatchers.IO) {
            if (vectors.isEmpty()) return@withContext 0
            val active = initialize(context)
            val docs = vectors.map { vector ->
                val builder =
                    GenericDocument.Builder<GenericDocument.Builder<*>>(
                        NAMESPACE,
                        vector.id,
                        SCHEMA,
                    )
                builder
                    .setPropertyString(PROP_URI, vector.uri)
                    .setPropertyString(PROP_STAMP, vector.stamp)
                    .setPropertyLong(PROP_ADDED, vector.addedAt)
                    .setPropertyEmbedding(
                        PROP_VECTOR,
                        EmbeddingVector(vector.values, MODEL_SIGNATURE),
                    )
                builder.build()
            }
            val result: AppSearchBatchResult<String, Void> = active.putAsync(
                PutDocumentsRequest.Builder().addGenericDocuments(docs).build(),
            ).await()
            if (!result.isSuccess) {
                AppLog.w("semantic: ${result.failures.size} vectors failed to index")
            }
            cachedVectors = null
            _generation.value += 1
            // The exact count comes from StorageInfo, which is slow enough that
            // it belongs at the end of a pass rather than after every batch.
            result.successes.size
        }

    /** id → stamp for every indexed photo, so changed/replaced files re-embed. */
    suspend fun storedStamps(context: Context): Map<String, String> =
        withContext(Dispatchers.IO) {
            allDocuments(context, PROJECTION_STAMPS).mapNotNull { doc ->
                val uri = doc.getPropertyString(PROP_URI) ?: return@mapNotNull null
                val stamp = doc.getPropertyString(PROP_STAMP) ?: return@mapNotNull null
                uri to stamp
            }.toMap()
        }

    suspend fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val active = initialize(context)
            ids.chunked(200).forEach { chunk ->
                active.removeAsync(
                    RemoveByDocumentIdRequest.Builder(NAMESPACE)
                        .addIds(chunk)
                        .build(),
                ).await()
            }
            cachedVectors = null
            _generation.value += 1
        }
    }

    /** Cosine search over the persisted vectors. The score is recomputed from
     *  the stored embedding so callers get an exact, comparable number. */
    suspend fun search(
        context: Context,
        queryVector: FloatArray,
        minSimilarity: Float = 0.3f,
        limit: Int = 80,
    ): List<VectorHit> = withContext(Dispatchers.IO) {
        val active = initialize(context)
        val spec = SearchSpec.Builder()
            .addFilterNamespaces(NAMESPACE)
            .addFilterSchemas(SCHEMA)
            .setDefaultEmbeddingSearchMetricType(SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE)
            .addEmbeddingParameters(EmbeddingVector(queryVector, MODEL_SIGNATURE))
            .setListFilterQueryLanguageEnabled(true)
            .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
            .setResultCountPerPage(limit)
            .build()
        val query =
            "semanticSearch(getEmbeddingParameter(0), ${minSimilarity.coerceIn(0f, 1f)}, 1.0, \"COSINE\")"

        val page = active.search(query, spec).use { it.nextPageAsync.await() }
        page.mapNotNull { result ->
            val doc = result.genericDocument
            val embedding = doc.getPropertyEmbedding(PROP_VECTOR) ?: return@mapNotNull null
            val uri = doc.getPropertyString(PROP_URI) ?: return@mapNotNull null
            val added = doc.getPropertyLong(PROP_ADDED) ?: 0L
            VectorHit(
                id = doc.id,
                uri = uri,
                addedAt = added,
                score = SemanticScoring.cosine(queryVector, embedding.values)
                    .coerceIn(-1f, 1f),
            )
        }.sortedByDescending { it.score }
    }

    suspend fun allVectors(context: Context): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        cachedVectors?.let { return@withContext it }
        val map = HashMap<String, FloatArray>()
        allDocuments(context).forEach { doc ->
            val uri = doc.getPropertyString(PROP_URI) ?: return@forEach
            val embedding = doc.getPropertyEmbedding(PROP_VECTOR) ?: return@forEach
            map[uri] = embedding.values
        }
        map.also { cachedVectors = it }
    }

    suspend fun refreshCount(context: Context) {
        withContext(Dispatchers.IO) { _indexedCount.value = storedCount(context) }
    }

    /** Removes every vector but keeps the session usable. */
    suspend fun wipe(context: Context) = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val active = initialize(context)
            active.removeAsync(
                "",
                SearchSpec.Builder()
                    .addFilterNamespaces(NAMESPACE)
                    .addFilterSchemas(SCHEMA)
                    .build(),
            ).await()
        }.onFailure { AppLog.w("semantic: vector wipe failed", it) }
        cachedVectors = null
        _indexedCount.value = 0
        _generation.value += 1
    }

    /** Drops every vector and the on-disk database (used when the model is removed). */
    fun clear() {
        cachedVectors = null
        _indexedCount.value = 0
        _generation.value += 1
        session?.let { runCatchingCancellable { it.close() } }
        session = null
    }

    private suspend fun storedCount(context: Context): Int = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val active = session ?: initialize(context)
            active.getStorageInfoAsync().await().aliveDocumentsCount
        }.getOrDefault(0)
    }

    private suspend fun allDocuments(
        context: Context,
        projection: List<String>? = null,
    ): List<GenericDocument> =
        withContext(Dispatchers.IO) {
            val active = initialize(context)
            val spec = SearchSpec.Builder()
                .addFilterNamespaces(NAMESPACE)
                .addFilterSchemas(SCHEMA)
                .apply {
                    if (projection != null) addProjection(SCHEMA, projection)
                }
                .setResultCountPerPage(MAX_DOCUMENTS)
                .build()
            val out = mutableListOf<GenericDocument>()
            active.search("", spec).use { results ->
                while (true) {
                    val page = results.nextPageAsync.await()
                    if (page.isEmpty()) break
                    page.forEach { out += documentOf(it) }
                }
            }
            out
        }

    private fun documentOf(result: SearchResult): GenericDocument = result.genericDocument

    private const val MAX_DOCUMENTS = 10_000

    private val PROJECTION_STAMPS = listOf(PROP_URI, PROP_STAMP)
}
