package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/**
 * PNG textual chunks are the only descriptive metadata most PNGs carry, and the
 * chunk walker feeds the info sheet and the search index, so its decoding rules
 * (Latin-1 vs UTF-8, compressed vs plain, corrupt input) are worth pinning down.
 */
class PngTextTest {

    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    @Test
    fun `plain text chunks are decoded as latin-1`() {
        val png = png(
            chunk("tEXt", "Comment".toByteArray() + 0 + "Caf\u00e9 terrace".toByteArray(Charsets.ISO_8859_1)),
        )
        val chunks = PngText.read(png.inputStream())
        assertEquals(1, chunks.size)
        assertEquals("Comment", chunks[0].keyword)
        assertEquals("Caf\u00e9 terrace", chunks[0].text)
        assertEquals(PngTextKind.TEXT, chunks[0].kind)
    }

    @Test
    fun `compressed text chunks are inflated`() {
        val payload = "Software".toByteArray() + 0 + byteArrayOf(0) + deflate("Foto 1.2")
        val chunks = PngText.read(png(chunk("zTXt", payload)).inputStream())
        assertEquals(1, chunks.size)
        assertEquals("Software", chunks[0].keyword)
        assertEquals("Foto 1.2", chunks[0].text)
        assertEquals(PngTextKind.COMPRESSED_TEXT, chunks[0].kind)
    }

    @Test
    fun `international text chunks decode utf-8 and skip language tags`() {
        val payload = "Title".toByteArray() +
            0 + byteArrayOf(0, 0) +
            "en".toByteArray() + 0 +
            "Titel".toByteArray() + 0 +
            "Berlin \u2014 Nacht".toByteArray(Charsets.UTF_8)
        val chunks = PngText.read(png(chunk("iTXt", payload)).inputStream())
        assertEquals(1, chunks.size)
        assertEquals("Title", chunks[0].keyword)
        assertEquals("Berlin \u2014 Nacht", chunks[0].text)
        assertEquals(PngTextKind.INTERNATIONAL_TEXT, chunks[0].kind)
    }

    @Test
    fun `chunks with an unknown compression method are skipped`() {
        val payload = "Comment".toByteArray() + 0 + byteArrayOf(1) + "raw".toByteArray()
        assertTrue(PngText.read(png(chunk("zTXt", payload)).inputStream()).isEmpty())
    }

    @Test
    fun `empty values and empty keywords are skipped`() {
        val png = png(
            chunk("tEXt", "Empty".toByteArray() + 0),
            chunk("tEXt", byteArrayOf(0) + "orphan".toByteArray()),
            chunk("tEXt", "Author".toByteArray() + 0 + "Akza".toByteArray()),
        )
        val chunks = PngText.read(png.inputStream())
        assertEquals(1, chunks.size)
        assertEquals("Author", chunks[0].keyword)
    }

    @Test
    fun `non-png streams yield nothing`() {
        assertTrue(PngText.read(ByteArray(64) { 0x42 }.inputStream()).isEmpty())
        assertTrue(PngText.read(ByteArrayOutputStream().toByteArray().inputStream()).isEmpty())
    }

    @Test
    fun `a truncated file stops without throwing`() {
        val complete = png(chunk("tEXt", "Comment".toByteArray() + 0 + "hello".toByteArray()))
        // Signature (8) + length (4) + type (4) + three bytes of the payload: the
        // chunk can never be completed, so nothing is reported.
        val truncated = complete.copyOf(19)
        assertTrue(PngText.read(truncated.inputStream()).isEmpty())
        // A file cut short after a complete chunk keeps what it did read.
        val afterFirstChunk = complete.copyOf(complete.size - 2)
        assertEquals(1, PngText.read(afterFirstChunk.inputStream()).size)
    }

    @Test
    fun `a multi-megabyte image chunk before the text does not stop the walk`() {
        // Screenshot PNGs put their pixels in one huge IDAT, and text chunks come
        // after it; the walker must skip the payload, not give up on it.
        val idat = chunk("IDAT", ByteArray(1_500_000) { 0x5A })
        val png = png(idat, chunk("tEXt", "Comment".toByteArray() + 0 + "still here".toByteArray()))
        val chunks = PngText.read(png.inputStream())
        assertEquals(1, chunks.size)
        assertEquals("still here", chunks[0].text)
    }

    @Test
    fun `walking many empty chunks still reaches the last text chunk`() {
        val noise = Array(200) { chunk("tIME", ByteArray(7)) }
        val png = png(*noise, chunk("tEXt", "Comment".toByteArray() + 0 + "late".toByteArray()))
        val chunks = PngText.read(png.inputStream())
        assertEquals(1, chunks.size)
        assertEquals("late", chunks[0].text)
    }

    @Test
    fun `a pathologically long chunk list stops the walk`() {
        val noise = Array(400) { chunk("tIME", ByteArray(7)) }
        val png = png(*noise, chunk("tEXt", "Comment".toByteArray() + 0 + "late".toByteArray()))
        assertTrue(PngText.read(png.inputStream()).isEmpty())
    }

    @Test
    fun `repeated keywords are all kept`() {
        val png = png(
            chunk("tEXt", "Comment".toByteArray() + 0 + "one".toByteArray()),
            chunk("tEXt", "Comment".toByteArray() + 0 + "two".toByteArray()),
        )
        val chunks = PngText.read(png.inputStream())
        assertEquals(listOf("one", "two"), chunks.map { it.text })
    }

    // ---- helpers ----------------------------------------------------------

    private fun png(vararg chunks: ByteArray): ByteArray =
        signature + chunks.fold(ByteArray(0)) { acc, next -> acc + next } + chunk("IEND", ByteArray(0))

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val length = ByteArray(4) {
            ((data.size shr (24 - it * 8)) and 0xFF).toByte()
        }
        // The reader deliberately ignores CRCs, so zeroes are enough here.
        return length + type.toByteArray(Charsets.US_ASCII) + data + ByteArray(4)
    }

    private fun deflate(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
        return out.toByteArray()
    }
}
