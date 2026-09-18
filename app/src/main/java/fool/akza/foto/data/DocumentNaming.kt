package fool.akza.foto.data

/**
 * Creates a document named [name], appending a numeric " (n)" suffix before the
 * extension when that name is already taken, so copies/moves/restores never
 * silently overwrite an existing file.
 *
 * [create] should attempt to create the given name and return null when it is
 * refused (collision, bad grant, …). Generic over the created handle so the
 * naming loop can be unit-tested without Android's `Uri`.
 */
internal fun <T : Any> createUniqueDocument(name: String, create: (String) -> T?): T? {
    create(name)?.let { return it }
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (attempt in 1 until 100) {
        create("$stem ($attempt)$ext")?.let { return it }
    }
    return null
}
