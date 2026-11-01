package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The share path promises that no EXIF, GPS, XMP or text chunk leaves the phone.
 * These fixtures pin the per-container rules down without needing a device: the
 * stripper is deliberately pure byte processing, so a regression here would
 * otherwise only show up as a privacy leak.
 */
class ImageMetadataStripperTest {

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    // ---- Detection --------------------------------------------------------

    @Test
    fun `containers are detected from magic bytes`() {
        assertEquals(ImageFormat.JPEG, ImageMetadataStripper.detect(jpeg(), "image/jpeg"))
        assertEquals(ImageFormat.PNG, ImageMetadataStripper.detect(png(pngChunk("IHDR", ByteArray(13))), null))
        assertEquals(
            ImageFormat.WEBP,
            ImageMetadataStripper.detect(webp(riffChunk("VP8 ", byteArrayOf(1))), null),
        )
        assertEquals(
            ImageFormat.GIF,
            ImageMetadataStripper.detect(gif(imageBlock()), "image/gif"),
        )
        assertEquals(
            ImageFormat.HEIF,
            ImageMetadataStripper.detect(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray(), null),
        )
        assertEquals(ImageFormat.OTHER, ImageMetadataStripper.detect(byteArrayOf(1, 2, 3), null))
    }

    @Test
    fun `animated formats are recognised`() {
        assertTrue(
            ImageMetadataStripper.isAnimated(
                png(pngChunk("IHDR", ByteArray(13)), pngChunk("acTL", ByteArray(8))),
                ImageFormat.PNG,
            ),
        )
        assertTrue(
            ImageMetadataStripper.isAnimated(
                webp(riffChunk("ANIM", ByteArray(6))),
                ImageFormat.WEBP,
            ),
        )
        assertTrue(ImageMetadataStripper.isAnimated(gif(imageBlock()), ImageFormat.GIF))
    }

    // ---- JPEG -------------------------------------------------------------

    @Test
    fun `jpeg drops app segments and comments but keeps image data`() {
        val sos = jpegSegment(0xDA, byteArrayOf(1, 1, 0, 0, 0, 0))
        val entropy = byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56)
        val input = jpegHeader() +
            jpegSegment(0xE0, "JFIF\u0000\u0001\u0002".toByteArray(Charsets.ISO_8859_1)) +
            jpegSegment(0xE1, "Exif\u0000\u0000secret-location".toByteArray()) +
            jpegSegment(0xE2, "ICC_PROFILE\u0000profile".toByteArray()) +
            jpegSegment(0xE2, "MPF\u0000secret-index".toByteArray()) +
            jpegSegment(0xED, "Photoshop IPTC".toByteArray()) +
            jpegSegment(0xEE, "Adobe".toByteArray()) +
            jpegSegment(0xFE, "private comment".toByteArray()) +
            jpegSegment(0xDB, byteArrayOf(0, 1, 2, 3)) +
            jpegSegment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1)) +
            sos +
            entropy +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) +
            "trailing".toByteArray()

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.JPEG)!!

        assertTrue(stripped.containsAscii("JFIF"))
        assertTrue(stripped.containsAscii("ICC_PROFILE"))
        assertTrue(stripped.containsAscii("Adobe"))
        assertFalse(stripped.containsAscii("Exif"))
        assertFalse(stripped.containsAscii("MPF"))
        assertFalse(stripped.containsAscii("Photoshop"))
        assertFalse(stripped.containsAscii("private comment"))
        assertTrue(stripped.containsBytes(entropy))
        assertEquals(0xD9, stripped[stripped.size - 1].toInt() and 0xFF)
        assertFalse(stripped.containsAscii("trailing"))
    }

    @Test
    fun `jpeg with nothing to remove is returned unchanged`() {
        val input = jpeg()
        assertTrue(ImageMetadataStripper.strip(input, ImageFormat.JPEG) === input)
    }

    @Test
    fun `jpeg keeps every progressive scan and drops metadata between scans`() {
        val sos = jpegSegment(0xDA, byteArrayOf(1, 1, 0, 0, 0, 0))
        val input = jpegHeader() +
            jpegSegment(0xE1, "Exif\u0000\u0000first".toByteArray()) +
            sos + byteArrayOf(0x11, 0x22) +
            jpegSegment(0xE1, "Exif\u0000\u0000between".toByteArray()) +
            jpegSegment(0xC4, byteArrayOf(1, 2, 3, 4)) +
            sos + byteArrayOf(0x33, 0x44) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.JPEG)!!

        assertTrue(stripped.containsBytes(byteArrayOf(0x11, 0x22)))
        assertTrue(stripped.containsBytes(byteArrayOf(0x33, 0x44)))
        assertFalse(stripped.containsAscii("Exif"))
        assertEquals(0xD9, stripped[stripped.size - 1].toInt() and 0xFF)
    }

    // ---- PNG --------------------------------------------------------------

    @Test
    fun `png drops text exif time and unknown ancillary chunks`() {
        val input = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("tEXt", "Author\u0000Akza".toByteArray()),
            pngChunk("zTXt", "Comment\u0000\u0000compressed".toByteArray()),
            pngChunk("iTXt", "Title\u0000\u0000\u0000en\u0000Titel\u0000Berlin".toByteArray()),
            pngChunk("eXIf", "Exif".toByteArray()),
            pngChunk("tIME", ByteArray(7)),
            pngChunk("ruSt", "unknown".toByteArray()),
            pngChunk("IDAT", byteArrayOf(1, 2, 3)),
            pngChunk("IEND", ByteArray(0)),
        ) + byteArrayOf(9, 9, 9)

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.PNG)!!

        assertTrue(stripped.containsAscii("IHDR"))
        assertTrue(stripped.containsAscii("IDAT"))
        assertTrue(stripped.containsAscii("IEND"))
        assertFalse(stripped.containsAscii("tEXt"))
        assertFalse(stripped.containsAscii("zTXt"))
        assertFalse(stripped.containsAscii("iTXt"))
        assertFalse(stripped.containsAscii("eXIf"))
        assertFalse(stripped.containsAscii("tIME"))
        assertFalse(stripped.containsAscii("ruSt"))
        assertFalse(stripped.containsAscii("Berlin"))
        // Bytes after IEND are gone too.
        assertFalse(stripped.containsBytes(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `png keeps colour and transparency chunks`() {
        val input = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("iCCP", "profile".toByteArray()),
            pngChunk("tRNS", byteArrayOf(0)),
            pngChunk("IDAT", byteArrayOf(1)),
            pngChunk("IEND", ByteArray(0)),
        )
        val stripped = ImageMetadataStripper.strip(input, ImageFormat.PNG)!!
        assertTrue(stripped.containsAscii("iCCP"))
        assertTrue(stripped.containsAscii("tRNS"))
    }

    @Test
    fun `unknown critical png chunks make the stripper refuse the file`() {
        val input = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("CRIT", byteArrayOf(1)),
            pngChunk("IEND", ByteArray(0)),
        )
        assertNull(ImageMetadataStripper.strip(input, ImageFormat.PNG))
    }

    // ---- WebP -------------------------------------------------------------

    @Test
    fun `webp drops exif and xmp and clears their vp8x flags`() {
        val vp8x = byteArrayOf(0x2C, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val input = webp(
            riffChunk("VP8X", vp8x),
            riffChunk("ICCP", byteArrayOf(1, 2, 3)),
            riffChunk("VP8 ", byteArrayOf(9, 8, 7)),
            riffChunk("EXIF", "secret-exif".toByteArray()),
            riffChunk("XMP ", "secret-xmp".toByteArray()),
            riffChunk("ruSt", "secret-unknown".toByteArray()),
        )

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.WEBP)!!

        assertFalse(stripped.containsAscii("EXIF"))
        assertFalse(stripped.containsAscii("XMP "))
        assertFalse(stripped.containsAscii("secret-exif"))
        assertFalse(stripped.containsAscii("secret-xmp"))
        assertFalse(stripped.containsAscii("secret-unknown"))
        assertTrue(stripped.containsAscii("ICCP"))
        assertTrue(stripped.containsAscii("VP8 "))
        // RIFF size must describe the shorter file, and the ICC flag survives.
        assertEquals(stripped.size - 8, readIntLe(stripped, 4))
        assertEquals(0x20, stripped[20].toInt() and 0xFF)
    }

    @Test
    fun `webp without metadata is returned unchanged`() {
        val input = webp(riffChunk("VP8 ", byteArrayOf(9, 8, 7)))
        assertTrue(ImageMetadataStripper.strip(input, ImageFormat.WEBP) === input)
    }

    @Test
    fun `webp clears vp8x flags when exif precedes the extended header`() {
        val input = webp(
            riffChunk("EXIF", "secret-exif".toByteArray()),
            riffChunk("VP8X", byteArrayOf(0x2C, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
            riffChunk("VP8 ", byteArrayOf(1, 2, 3)),
        )

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.WEBP)!!

        assertFalse(stripped.containsAscii("EXIF"))
        assertEquals(0x20, stripped[20].toInt() and 0xFF)
        assertEquals(stripped.size - 8, readIntLe(stripped, 4))
    }

    // ---- GIF --------------------------------------------------------------

    @Test
    fun `gif drops comments and unknown extensions but keeps frames and loops`() {
        val comment = byteArrayOf(0x21, 0xFE.toByte(), 5) +
            "hello".toByteArray() + byteArrayOf(0)
        val netscape = byteArrayOf(0x21, 0xFF.toByte(), 11) +
            "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
        val input = gif(comment, netscape, imageBlock()) + byteArrayOf(9, 9)

        val stripped = ImageMetadataStripper.strip(input, ImageFormat.GIF)!!

        assertFalse(stripped.containsAscii("hello"))
        assertTrue(stripped.containsAscii("NETSCAPE2.0"))
        assertTrue(stripped.containsBytes(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0)))
        assertEquals(0x3B, stripped[stripped.size - 1].toInt() and 0xFF)
    }

    // ---- Fixtures ---------------------------------------------------------

    private fun jpegHeader(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpegHeader())
        segments.forEach { out.write(it) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(marker)
        val length = payload.size + 2
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngSignature)
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                ((data.size ushr 24) and 0xFF).toByte(),
                ((data.size ushr 16) and 0xFF).toByte(),
                ((data.size ushr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte(),
            ),
        )
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC is not inspected by the walker
        return out.toByteArray()
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    private fun riffChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(intLe(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun gif(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // 1x1 screen, no global colour table.
        out.write(byteArrayOf(1, 0, 1, 0, 0, 0, 0))
        blocks.forEach { out.write(it) }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun imageBlock(): ByteArray =
        byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0) +
            byteArrayOf(2, 2, 1, 2, 0)

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.containsAscii(text: String): Boolean =
        containsBytes(text.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (i in 0..size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
