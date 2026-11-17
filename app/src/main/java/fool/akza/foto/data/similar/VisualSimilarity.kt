package fool.akza.foto.data.similar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.canonicalDirKey
import fool.akza.foto.data.canonicalDirPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private data class FpEntry(val item: MediaItem, val fp: VisualFingerprint)

private class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = IntArray(n)
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var node = x
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        if (rank[ra] < rank[rb]) parent[ra] = rb
        else if (rank[ra] > rank[rb]) parent[rb] = ra
        else {
            parent[rb] = ra
            rank[ra]++
        }
    }
}

internal suspend fun analyzeVisualSimilarity(
    context: Context,
    items: List<MediaItem>,
    existingNames: Map<String, Set<String>> = emptyMap(),
    embeddings: Map<String, FloatArray> = emptyMap(),
    maxItems: Int = 500,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): SimilarReport = withContext(Dispatchers.Default) {
    val candidates = items
        .filter { !it.isVideo }
        .sortedWith(compareByDescending<MediaItem> { it.lastModified }.thenByDescending { it.dateAdded })
        .take(maxItems)

    val total = candidates.size
    val fingerprints = arrayOfNulls<VisualFingerprint>(total)
    val nextIndex = AtomicInteger(0)
    val doneCount = AtomicInteger(0)

    onProgress(0, total)

    withContext(Dispatchers.IO) {
        coroutineScope {
            repeat(4) {
                async {
                    while (true) {
                        val i = nextIndex.getAndIncrement()
                        if (i >= total) break
                        fingerprints[i] = decodeFingerprint(context, candidates[i])
                        val d = doneCount.incrementAndGet()
                        onProgress(d, total)
                    }
                }
            }
        }
    }

    val entries = candidates.mapIndexedNotNull { idx, item ->
        fingerprints[idx]?.let { FpEntry(item, it) }
    }
    val n = entries.size

    if (n == 0) {
        return@withContext SimilarReport(emptyList(), emptyList(), emptyList(), null, 0L)
    }

    // Duplicates
    val dupUF = UnionFind(n)
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            if (isDuplicatePair(entries[i].fp, entries[j].fp)) {
                dupUF.union(i, j)
            }
        }
    }
    val dupGroupsByRoot = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) dupGroupsByRoot.getOrPut(dupUF.find(i)) { mutableListOf() }.add(i)

    val dupEntrySet = mutableSetOf<Int>()
    val duplicateGroups = mutableListOf<DuplicateGroup>()
    dupGroupsByRoot.values.filter { it.size >= 2 }.forEach { idxs ->
        // Union-find is transitive, duplicates are not: require every pair in
        // the component to pass the copy test, or a chain A~B~C could delete C
        // even though A and C are different shots.
        val allPairsDuplicate = idxs.indices.all { x ->
            (x + 1 until idxs.size).all { y ->
                isDuplicatePair(entries[idxs[x]].fp, entries[idxs[y]].fp)
            }
        }
        if (!allPairsDuplicate) return@forEach
        val groupItems = idxs.map { entries[it].item }
        val keeper = groupItems.maxWithOrNull(
            compareBy<MediaItem> { it.sizeBytes }
                .thenBy { it.lastModified }
                .thenBy { it.dateAdded },
        )!!
        val bytesFreed = groupItems.filterNot { it.uri == keeper.uri }.sumOf { it.sizeBytes }
        val folder = commonFolder(groupItems)
        duplicateGroups.add(
            DuplicateGroup(
                id = "dup_${duplicateGroups.size}",
                items = groupItems,
                keeper = keeper,
                bytesFreed = bytesFreed,
                folder = folder,
            ),
        )
        idxs.forEach { dupEntrySet.add(it) }
    }
    duplicateGroups.sortWith(
        compareByDescending<DuplicateGroup> { it.bytesFreed }
            .thenByDescending { it.items.size },
    )

    // Similar groups
    val simCandidates = entries.indices.filter { it !in dupEntrySet }.toList()
    val posOf = IntArray(n) { -1 }
    simCandidates.forEachIndexed { pos, ei -> posOf[ei] = pos }
    val m = simCandidates.size
    val simUF = UnionFind(m.coerceAtLeast(1))
    if (m > 1) {
        for (a in 0 until m) {
            for (b in a + 1 until m) {
                val ei = simCandidates[a]
                val ej = simCandidates[b]
                val itemA = entries[ei].item
                val itemB = entries[ej].item
                val pathA = itemA.canonicalDirKey()
                val pathB = itemB.canonicalDirKey()
                if (pathA != pathB) continue
                val fpA = entries[ei].fp
                val fpB = entries[ej].fp
                // Groups are a content-semantic feature: without embeddings the
                // pair is simply not grouped. There is no colour/dHash fallback,
                // because that produced misleading folders.
                val semantic = semanticSimilarity(itemA, itemB, embeddings) ?: continue
                val ad = aspectDelta(fpA.aspect, fpB.aspect)
                val dateDiff = abs(itemA.dateAdded - itemB.dateAdded)
                if (dateDiff <= 6L * 3600L && ad <= 0.12f && semantic >= SemanticGroupFloor) {
                    simUF.union(a, b)
                }
            }
        }
    }
    val simGroupsByRoot = LinkedHashMap<Int, MutableList<Int>>()
    for (pos in 0 until m) simGroupsByRoot.getOrPut(simUF.find(pos)) { mutableListOf() }.add(pos)

    val similarEntrySet = mutableSetOf<Int>()
    val similarGroups = mutableListOf<SimilarGroup>()
    simGroupsByRoot.values.filter { it.size >= 3 }.forEach { positions ->
        val groupEntryIndices = positions.map { simCandidates[it] }
        val groupItems = groupEntryIndices.map { entries[it].item }
        val folder = groupItems.first().canonicalDirPath()?.trim('/')
        var sum = 0f
        var count = 0
        for (x in groupEntryIndices.indices) {
            for (y in x + 1 until groupEntryIndices.size) {
                val iA = entries[groupEntryIndices[x]].item
                val iB = entries[groupEntryIndices[y]].item
                val score = semanticSimilarity(iA, iB, embeddings) ?: continue
                sum += score
                count++
            }
        }
        val avg = if (count > 0) sum / count else 0f
        val nameSet = folder?.let { existingNames[it] }.orEmpty()
        val suggested = nextGroupedName(nameSet)
        similarGroups.add(
            SimilarGroup(
                id = "sim_${similarGroups.size}",
                items = groupItems,
                folder = folder,
                averageSimilarity = avg,
                suggestedName = suggested,
            ),
        )
        groupEntryIndices.forEach { similarEntrySet.add(it) }
    }
    similarGroups.sortWith(
        compareByDescending<SimilarGroup> { it.items.size }
            .thenByDescending { it.averageSimilarity },
    )

    // Possible groups
    val possibleCandidateEntries = entries.indices.filter { it !in dupEntrySet && it !in similarEntrySet }.toList()
    val ppm = possibleCandidateEntries.size
    val pUF = UnionFind(ppm.coerceAtLeast(1))
    if (ppm > 1) {
        for (a in 0 until ppm) {
            for (b in a + 1 until ppm) {
                val ei = possibleCandidateEntries[a]
                val ej = possibleCandidateEntries[b]
                val itemA = entries[ei].item
                val itemB = entries[ej].item
                val pathA = itemA.canonicalDirKey()
                val pathB = itemB.canonicalDirKey()
                if (pathA != pathB) continue
                val semantic = semanticSimilarity(itemA, itemB, embeddings) ?: continue
                if (semantic >= SemanticPairFloor) pUF.union(a, b)
            }
        }
    }
    val possibleCompMap = LinkedHashMap<Int, MutableList<Int>>()
    for (pos in 0 until ppm) possibleCompMap.getOrPut(pUF.find(pos)) { mutableListOf() }.add(pos)

    val possibleGroups = mutableListOf<PossibleGroup>()
    possibleCompMap.values.forEach { positions ->
        if (positions.size != 2) return@forEach
        val gei = positions.map { possibleCandidateEntries[it] }
        val groupItems = gei.map { entries[it].item }
        val folder = groupItems.first().canonicalDirPath()?.trim('/')
        val avg = semanticSimilarity(groupItems[0], groupItems[1], embeddings) ?: 0f
        possibleGroups.add(
            PossibleGroup(
                id = "pos_${possibleGroups.size}",
                items = groupItems,
                folder = folder,
                averageSimilarity = avg,
            ),
        )
    }
    possibleGroups.sortByDescending { it.averageSimilarity }
    val possibleTop = possibleGroups.take(3)

    val preview = duplicateGroups.firstOrNull()?.keeper
        ?: similarGroups.firstOrNull()?.items?.firstOrNull()
        ?: possibleTop.firstOrNull()?.items?.firstOrNull()
    val bytesReclaimable = duplicateGroups.sumOf { it.bytesFreed }

    SimilarReport(
        duplicates = duplicateGroups,
        similarGroups = similarGroups,
        possibleGroups = possibleTop,
        preview = preview,
        bytesReclaimable = bytesReclaimable,
    )
}

private fun decodeFingerprint(context: Context, item: MediaItem): VisualFingerprint? {
    val uri = item.uri
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (_: Exception) {
        return null
    }
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null

    val sample = sampleSize(w, h)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    } ?: return null

    return try {
        computeFingerprint(bitmap, w, h)
    } finally {
        bitmap.recycle()
    }
}

private fun sampleSize(w: Int, h: Int): Int {
    var sample = 1
    val longest = max(w, h)
    while (longest / (sample * 2) >= 64) sample *= 2
    return sample
}

private fun computeFingerprint(bitmap: Bitmap, origW: Int, origH: Int): VisualFingerprint {
    // dHash: 9x8 grayscale
    val dSmall = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    val dHash = try {
        val pixels = IntArray(9 * 8)
        dSmall.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = pixels[y * 9 + x]
                val right = pixels[y * 9 + x + 1]
                val gl = 0.299f * ((left shr 16) and 0xFF) +
                    0.587f * ((left shr 8) and 0xFF) +
                    0.114f * (left and 0xFF)
                val gr = 0.299f * ((right shr 16) and 0xFF) +
                    0.587f * ((right shr 8) and 0xFF) +
                    0.114f * (right and 0xFF)
                hash = (hash shl 1) or (if (gl < gr) 1L else 0L)
            }
        }
        hash
    } finally {
        if (dSmall !== bitmap) dSmall.recycle()
    }

    // Vector: 16x16
    // A finer luma map is kept separately for the duplicate-vs-pose decision.
    val lSmall = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
    val luma = try {
        val pixels = IntArray(32 * 32)
        lSmall.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val out = FloatArray(32 * 32)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = (
                0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
                ) / 255f
        }
        out
    } finally {
        if (lSmall !== bitmap) lSmall.recycle()
    }

    val vSmall = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
    val vector = try {
        val pixels = IntArray(16 * 16)
        vSmall.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        val floats = FloatArray(64)
        var idx = 0
        // 4x4 block averages of R, G, B
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (dy in 0 until 4) {
                    for (dx in 0 until 4) {
                        val p = pixels[(by * 4 + dy) * 16 + (bx * 4 + dx)]
                        r += ((p shr 16) and 0xFF).toFloat()
                        g += ((p shr 8) and 0xFF).toFloat()
                        b += (p and 0xFF).toFloat()
                    }
                }
                floats[idx++] = r / 16f
                floats[idx++] = g / 16f
                floats[idx++] = b / 16f
            }
        }
        // 4x4 block averages of luma
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                var l = 0f
                for (dy in 0 until 4) {
                    for (dx in 0 until 4) {
                        val p = pixels[(by * 4 + dy) * 16 + (bx * 4 + dx)]
                        l += 0.299f * ((p shr 16) and 0xFF) +
                            0.587f * ((p shr 8) and 0xFF) +
                            0.114f * (p and 0xFF)
                    }
                }
                floats[idx++] = l / 16f
            }
        }
        var norm = 0f
        for (v in floats) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in floats.indices) floats[i] /= norm
        } else {
            floats.fill(0f)
        }
        floats
    } finally {
        if (vSmall !== bitmap) vSmall.recycle()
    }

    return VisualFingerprint(
        dHash = dHash,
        vector = vector,
        luma = luma,
        aspect = origW.toFloat() / origH.toFloat(),
        width = origW,
        height = origH,
    )
}

private fun isDuplicatePair(a: VisualFingerprint, b: VisualFingerprint): Boolean {
    val h = java.lang.Long.bitCount(a.dHash xor b.dHash)
    val c = cosine(a.vector, b.vector)
    val ad = aspectDelta(a.aspect, b.aspect)
    val diff = meanAbsDiff(a.luma, b.luma)
    // A pixel-level check separates a re-save/copy from a burst frame: a
    // shifted subject keeps the colour vector close, but changes the 32x32
    // luma enough to be a "similar shot", not a copy.
    return ad <= 0.08f && diff <= 0.008f &&
        ((h <= 8 && c >= 0.95f) || (c >= 0.995f && h <= 16))
}

private fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
    val len = minOf(a.size, b.size)
    if (len == 0) return 1f
    var sum = 0f
    for (i in 0 until len) sum += abs(a[i] - b[i])
    return sum / len
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    val len = minOf(a.size, b.size)
    for (i in 0 until len) dot += a[i] * b[i]
    return dot
}

private fun aspectDelta(a: Float, b: Float): Float {
    val mx = max(a, b)
    return if (mx == 0f) 0f else abs(a - b) / mx
}

// Grouping thresholds. Semantic scores are cosine in the shared
// text/vision space: a different frame of the same scene lands high, unrelated
// photos of the same subject land lower. These are deliberately conservative
// (better to miss a group than to offer a wrong folder) and stay tunable while
// the on-device model is calibrated against a real library. Photos without
// embeddings are not grouped at all — there is no perceptual fallback.
private const val SemanticGroupFloor = 0.88f
private const val SemanticPairFloor = 0.92f

private fun semanticSimilarity(
    a: MediaItem,
    b: MediaItem,
    embeddings: Map<String, FloatArray>,
): Float? {
    val va = embeddings[a.uri.toString()] ?: return null
    val vb = embeddings[b.uri.toString()] ?: return null
    var dot = 0f
    var na = 0f
    var nb = 0f
    val len = minOf(va.size, vb.size)
    if (len == 0) return null
    for (i in 0 until len) {
        dot += va[i] * vb[i]
        na += va[i] * va[i]
        nb += vb[i] * vb[i]
    }
    if (na <= 0f || nb <= 0f) return null
    return dot / (sqrt(na) * sqrt(nb))
}

