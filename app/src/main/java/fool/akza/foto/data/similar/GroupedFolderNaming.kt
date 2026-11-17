package fool.akza.foto.data.similar

import fool.akza.foto.data.MediaItem
import fool.akza.foto.data.canonicalDirPath

private val GroupedNamePattern = Regex("^Grouped\\((\\d+)\\)$", RegexOption.IGNORE_CASE)

/** The numeric suffix of a `Grouped(n)` folder name, or null for any other name. */
internal fun groupedIndex(name: String): Int? =
    GroupedNamePattern.matchEntire(name.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

/**
 * Next free `Grouped(n)` in a directory. The user asked for a default name that
 * increments rather than colliding, so take the highest existing suffix + 1.
 * Numbering is per directory: `Grouped(1)` may exist in both Camera and WhatsApp.
 */
fun nextGroupedName(existing: Collection<String>): String {
    val highest = existing.mapNotNull(::groupedIndex).maxOrNull() ?: 0
    return "Grouped(${highest + 1})"
}

/** Folder names must be a single path segment on every provider we support. */
fun folderNameError(name: String, existing: Collection<String>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a folder name"
        trimmed == "." || trimmed == ".." -> "Use a different folder name"
        trimmed.any { it in "/\\:*?\"<>|" } -> "A folder name can't contain / \\ : * ? \" < > |"
        trimmed.endsWith(" ") || trimmed.endsWith(".") ->
            "A folder name can't end with a space or period"
        existing.any { it.equals(trimmed, ignoreCase = true) } ->
            "A folder with this name already exists"
        else -> null
    }
}

/** The folder a group sits in, or the deepest folder every item shares. */
internal fun commonFolder(items: List<MediaItem>): String? =
    commonFolderOf(items.map { it.canonicalDirPath()?.trim('/') })

/** Pure form of [commonFolder], testable without Android MediaItem instances. */
internal fun commonFolderOf(paths: List<String?>): String? {
    val cleaned = paths
        .mapNotNull { it?.trim('/')?.takeIf(String::isNotEmpty) }
        .distinct()
    if (cleaned.isEmpty()) return null
    if (cleaned.size == 1) return cleaned.first()
    val split = cleaned.map { it.split('/') }
    val common = mutableListOf<String>()
    for (index in 0 until split.minOf { it.size }) {
        val part = split.first()[index]
        if (split.all { it.getOrNull(index) == part }) common += part else break
    }
    return common.joinToString("/").ifBlank { null }
}

/** Parent of a relative folder, or null when the folder is already at the root. */
internal fun parentFolder(folder: String?): String? {
    val trimmed = folder?.trim('/') ?: return null
    return trimmed.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { null }
}
