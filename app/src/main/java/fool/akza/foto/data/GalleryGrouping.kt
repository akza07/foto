package fool.akza.foto.data

// Pure grouping/filtering logic extracted from MediaRepository so it can be
// unit-tested on the JVM without Android framework classes.

/**
 * Canonical relative directory path (no storage prefix, no trailing slash), e.g.
 * "DCIM/Camera". SAF items derive it from their parent doc id ("primary:DCIM/Camera"),
 * MediaStore items from RELATIVE_PATH ("DCIM/Camera/").
 */
internal fun canonicalDirPath(
    safTreeUri: String?,
    bucketId: String,
    relativePath: String?,
): String? = if (safTreeUri != null) {
    bucketId.substringAfter(":").trim('/')
} else {
    relativePath?.trim('/')
}

internal fun MediaItem.canonicalDirPath(): String? =
    canonicalDirPath(safTreeUri, bucketId, relativePath)

/** Case-insensitive grouping key derived from [canonicalDirPath]. */
internal fun MediaItem.canonicalDirKey(): String? = canonicalDirPath()?.lowercase()

/**
 * Canonical relative file path used to dedup SAF-browsed items against MediaStore
 * items (e.g. "DCIM/Camera/img.jpg"). SAF doc ids embed the path; MediaStore items
 * derive it from RELATIVE_PATH + DISPLAY_NAME. Lower-cased for case-insensitive
 * comparison.
 */
internal fun canonicalMediaPath(
    safTreeUri: String?,
    relativePath: String?,
    displayName: String?,
    lastPathSegment: String?,
): String? {
    val raw = if (safTreeUri != null) {
        lastPathSegment?.substringAfter(":")
    } else {
        val rel = relativePath ?: return null
        val name = displayName ?: return null
        rel + name
    } ?: return null
    return raw.lowercase()
}

internal fun MediaItem.canonicalPath(): String? =
    canonicalMediaPath(safTreeUri, relativePath, displayName, uri.lastPathSegment)

/** MediaStore bucket id for MediaStore-sourced items, null for SAF items. */
internal fun MediaItem.mediaStoreBucketId(): String? =
    if (safTreeUri == null && bucketId.isNotBlank()) bucketId else null

/**
 * Groups items into folders by canonical directory path so that SAF-browsed and
 * MediaStore-sourced items for the same physical directory land in one album.
 */
internal fun buildFolders(
    items: List<MediaItem>,
    sort: MediaSort,
    unknownDirName: String = "Unknown",
    storageDirName: String = "Storage",
): GalleryContent {
    val loose = mutableListOf<MediaItem>()
    val grouped = LinkedHashMap<String, MutableList<MediaItem>>()
    val dirNames = HashMap<String, String>()      // key -> display dir path
    val safTrees = HashMap<String, String?>()      // key -> safTreeUri
    val safDocIds = HashMap<String, String?>()     // key -> saf parent doc id
    val mediaStoreBucketIds = HashMap<String, String?>() // key -> MediaStore bucket id

    for (item in items) {
        if (item.canonicalDirPath().isNullOrBlank()) {
            loose.add(item)
            continue
        }
        val key = item.canonicalDirKey() ?: continue
        val dirPath = item.canonicalDirPath()
        grouped.getOrPut(key) { mutableListOf() }.add(item)
        if (dirPath != null) dirNames.putIfAbsent(key, dirPath)
        if (item.safTreeUri != null) {
            safTrees.putIfAbsent(key, item.safTreeUri)
            if (safDocIds[key] == null) safDocIds[key] = item.bucketId
        }
        if (item.mediaStoreBucketId() != null) {
            mediaStoreBucketIds.putIfAbsent(key, item.mediaStoreBucketId())
        }
    }

    val folders = grouped.entries
        .map { (key, bucket) ->
            val sorted = bucket.sortedWith(itemComparator(sort))
            val dir = dirNames[key] ?: unknownDirName
            MediaFolder(
                bucketId = dir,
                name = dir.substringAfterLast("/").ifBlank { storageDirName },
                count = bucket.size,
                previews = sorted.take(3),
                dateAdded = sorted.first().dateAdded,
                safTreeUri = safTrees[key],
                safDocId = safDocIds[key],
                mediaStoreBucketId = mediaStoreBucketIds[key],
            )
        }
        .sortedWith(folderComparator(sort))

    return GalleryContent(folders = folders, looseItems = loose.sortedWith(itemComparator(sort)))
}

/**
 * Applies the Content Discovery settings. Framework-free predicate so it can be
 * unit-tested; [passesFilters] is the [MediaItem] wrapper.
 */
internal fun shouldIncludeMedia(
    path: String?,
    settings: AppSettings,
    selectedPrefixes: List<String>,
): Boolean {
    if (settings.requireExplicit) {
        // Allow-list: keep only media under a selected directory.
        if (selectedPrefixes.isEmpty()) return false
        val p = path ?: return false
        return selectedPrefixes.any { p.startsWith(it) }
    }
    // Deny-list: keep media NOT under any selected directory. No `.nomedia`
    // test is needed here — MediaStore never indexes content under a `.nomedia`
    // directory, so the platform already hides it in this view.
    val p = path ?: return true
    return selectedPrefixes.none { p.startsWith(it) }
}

/**
 * Applies the Content Discovery settings:
 * - explicit permission: if on, only media under a selected directory tree is kept
 *   (allow-list; nothing shown when no directory is selected). If off, media under
 *   selected trees is excluded (deny-list).
 */
internal fun passesFilters(
    item: MediaItem,
    settings: AppSettings,
    selectedPrefixes: List<String>,
): Boolean = shouldIncludeMedia(item.relativePath, settings, selectedPrefixes)

/**
 * The relative directory path that a `.nomedia` child marks, or null when the
 * directory has no marker. [parentDir] is the directory's own relative path
 * ("" for the root of a granted tree).
 *
 * `.nomedia` markers are invisible to MediaStore; this is applied to SAF tree
 * browsing, which is the only place the marker is observable.
 */
internal fun noMediaDirFor(parentDir: String, childNames: List<String>): String? =
    if (childNames.any { it == ".nomedia" }) parentDir else null

/**
 * True when [dir] is a `.nomedia`-marked directory or a descendant of one. An
 * empty marker ("") is the root of a granted tree, so it hides everything under
 * it. Prefix matching is path-segment aware, so "DCIM/Secret" does not match
 * "DCIM/Secret2".
 */
internal fun isUnderNoMedia(dir: String?, markers: Set<String>): Boolean {
    if (dir == null) return false
    return markers.any { m -> m.isEmpty() || dir == m || dir.startsWith("$m/") }
}

/** Ordering for media within a folder. */
internal fun itemComparator(sort: MediaSort): Comparator<MediaItem> = when (sort) {
    MediaSort.DATE_NEWEST -> Comparator { a, b -> b.dateAdded.compareTo(a.dateAdded) }
    MediaSort.DATE_OLDEST -> Comparator { a, b -> a.dateAdded.compareTo(b.dateAdded) }
    MediaSort.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
    MediaSort.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
}

/** Ordering for the folder/album list. */
internal fun folderComparator(sort: MediaSort): Comparator<MediaFolder> = when (sort) {
    MediaSort.DATE_NEWEST -> Comparator { a, b -> b.dateAdded.compareTo(a.dateAdded) }
    MediaSort.DATE_OLDEST -> Comparator { a, b -> a.dateAdded.compareTo(b.dateAdded) }
    MediaSort.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    MediaSort.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
}
