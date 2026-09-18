package fool.akza.foto.data

import android.net.Uri

/**
 * Creates a document named [name], appending a numeric " (n)" suffix before the
 * extension when that name is already taken, so copies/moves/restores never
 * silently overwrite an existing file.
 *
 * [create] should attempt `DocumentsContract.createDocument` for the given name
 * and return null when the provider refuses (name collision, bad grant, …).
 */
internal fun createUniqueDocument(name: String, create: (String) -> Uri?): Uri? {
    create(name)?.let { return it }
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (attempt in 1 until 100) {
        create("$stem ($attempt)$ext")?.let { return it }
    }
    return null
}
