package fool.akza.foto.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/** Which PNG textual chunk a [PngTextChunk] came from. */
enum class PngTextKind {
    /** `tEXt`: keyword + uncompressed Latin-1 text. */
    TEXT,

    /** `zTXt`: keyword + zlib-compressed Latin-1 text. */
    COMPRESSED_TEXT,

    /** `iTXt`: keyword + UTF-8 text, optionally compressed. */
    INTERNATIONAL_TEXT,
}

/** One keyword/value pair stored in a PNG's textual chunks. */
data class PngTextChunk(val keyword: String, val text: String, val kind: PngTextKind)

/**
 * Reader for PNG textual chunks (`tEXt`, `zTXt`, `iTXt`).
 *
 * ExifInterface only surfaces EXIF and XMP, so a PNG's keywords — often the only
 * descriptive metadata such files carry — would otherwise be invisible. This is
 * a deliberately small chunk walker: it never decodes pixels, caps how much it
 * will read from one file, and returns an empty list for anything that is not a
 * valid PNG.
 */
internal object PngText {

    private val Signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Upper bound on a single textual chunk we are willing to inflate or decode. */
    private const val MaxChunkBytes = 1 shl 20

    /** Upper bound on one chunk's decoded text, so a zip bomb can't stall us. */
    private const val MaxTextBytes = 1 shl 16

    /** Upper bound on the number of chunks we walk in one file. */
    private const val MaxChunksWalked = 256

    /** Upper bound on the number of textual chunks returned for one file. */
    private const val MaxTextChunks = 64

    /** Upper bound on the text bytes read from one file, whatever its headers say. */
    private const val MaxFileBytes = 4 shl 20

    fun read(stream: InputStream): List<PngTextChunk> {
        if (!readFully(stream, Signature.size).contentEquals(Signature)) return emptyList()
        val chunks = mutableListOf<PngTextChunk>()
        var consumed = Signature.size
        var walked = 0
        while (walked < MaxChunksWalked && chunks.size < MaxTextChunks && consumed < MaxFileBytes) {
            val lengthBytes = readFully(stream, 4)
            if (lengthBytes.size < 4) break
            val length = readInt(lengthBytes)
            if (length < 0) break
            val typeBytes = readFully(stream, 4)
            if (typeBytes.size < 4) break
            val type = String(typeBytes, Charsets.US_ASCII)
            if (type == "IEND") break
            walked++

            // Pixels (IDAT) and every other unknown chunk are skipped without
            // decoding: a 12MP screenshot is one multi-megabyte IDAT, and the
            // textual chunks we want usually come *after* it.
            val textual = type == "tEXt" || type == "zTXt" || type == "iTXt"
            if (!textual || length > MaxChunkBytes) {
                if (!skipFully(stream, length.toLong() + 4L)) break
                continue
            }
            val data = readFully(stream, length)
            if (data.size < length) break
            // Every chunk is followed by a CRC we don't need to verify: a bad
            // CRC only means a corrupt file, and the value itself is still text.
            readFully(stream, 4)
            consumed += 12 + length
            when (type) {
                "tEXt" -> parseText(data)?.let { chunks += it }
                "zTXt" -> parseCompressedText(data)?.let { chunks += it }
                "iTXt" -> parseInternationalText(data)?.let { chunks += it }
            }
        }
        return chunks
    }

    /** `keyword\0Latin-1 text`. */
    private fun parseText(data: ByteArray): PngTextChunk? {
        val separator = data.indexOf(0.toByte())
        if (separator <= 0 || separator + 1 >= data.size) return null
        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
        val text = String(data, separator + 1, data.size - separator - 1, Charsets.ISO_8859_1)
        return cleanChunk(keyword, text, PngTextKind.TEXT)
    }

    /** `keyword\0 method(1) zlib(text)`. */
    private fun parseCompressedText(data: ByteArray): PngTextChunk? {
        val separator = data.indexOf(0.toByte())
        if (separator <= 0 || separator + 2 >= data.size) return null
        // Byte after the keyword is the compression method; only deflate (0) exists.
        if (data[separator + 1] != 0.toByte()) return null
        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
        val text = inflate(data, separator + 2, data.size - separator - 2)
            ?.toString(Charsets.ISO_8859_1)
            ?: return null
        return cleanChunk(keyword, text, PngTextKind.COMPRESSED_TEXT)
    }

    /** `keyword\0 flag(1) method(1) language\0 translated\0 UTF-8 text`. */
    private fun parseInternationalText(data: ByteArray): PngTextChunk? {
        val keywordEnd = data.indexOf(0.toByte())
        if (keywordEnd <= 0 || keywordEnd + 3 >= data.size) return null
        val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
        val compressed = data[keywordEnd + 1] != 0.toByte()
        // Skip the compression method byte, the language tag and the translated
        // keyword; only the payload matters for the info sheet.
        val languageEnd = data.indexOf(0.toByte(), keywordEnd + 3)
        if (languageEnd < 0) return null
        val translatedEnd = data.indexOf(0.toByte(), languageEnd + 1)
        if (translatedEnd < 0 || translatedEnd + 1 >= data.size) return null
        val payloadOffset = translatedEnd + 1
        val payloadLength = data.size - payloadOffset
        val text = if (compressed) {
            inflate(data, payloadOffset, payloadLength)?.toString(Charsets.UTF_8)
        } else {
            String(data, payloadOffset, payloadLength, Charsets.UTF_8)
        } ?: return null
        return cleanChunk(keyword, text, PngTextKind.INTERNATIONAL_TEXT)
    }

    private fun cleanChunk(keyword: String, text: String, kind: PngTextKind): PngTextChunk? {
        val cleanKeyword = keyword.trim().take(120)
        val cleanText = text.trim().take(MaxTextBytes)
        if (cleanKeyword.isEmpty() || cleanText.isEmpty()) return null
        return PngTextChunk(cleanKeyword, cleanText, kind)
    }

    /**
     * Inflates at most [MaxTextBytes]. Returns null when the stream is not a
     * plain deflate stream we can read.
     */
    private fun inflate(data: ByteArray, offset: Int, length: Int): ByteArray? = try {
        if (length <= 0) {
            null
        } else {
            val source = ByteArrayInputStream(data, offset, length)
            InflaterInputStream(source).use { inflater ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < MaxTextBytes) {
                    val read = inflater.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, minOf(read, MaxTextBytes - total))
                    total += read
                }
                out.toByteArray()
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Skips [count] bytes without allocating them; false when the stream ends first. */
    private fun skipFully(stream: InputStream, count: Long): Boolean {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    private fun readFully(stream: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(buffer, offset, count - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == count) buffer else buffer.copyOf(offset)
    }

    private fun readInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun ByteArray.indexOf(value: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    /** Appends the textual chunks of [stream] to [draft], if it is a PNG. */
    fun addSection(stream: InputStream, draft: SectionDraft) {
        read(stream).forEach { chunk ->
            val suffix = when (chunk.kind) {
                PngTextKind.TEXT -> ""
                PngTextKind.COMPRESSED_TEXT -> " (zTXt)"
                PngTextKind.INTERNATIONAL_TEXT -> " (iTXt)"
            }
            draft.entry(chunk.keyword + suffix, chunk.text)
        }
    }
}
