package fool.akza.foto.data

import java.io.ByteArrayOutputStream

/** An image container Foto can inspect and (for most of them) rewrite. */
internal enum class ImageFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif"),

    /** HEIF/HEIC/AVIF: recognised, but it has to be re-encoded to lose metadata. */
    HEIF("image/heif", "heic"),

    /** Anything else: re-encoded when it can be decoded. */
    OTHER("image/jpeg", "jpg"),
}

/**
 * Lossless, format-aware removal of embedded metadata.
 *
 * The privacy-sensitive parts of a photo are not in its pixels: they are in the
 * JPEG APPn segments (EXIF, XMP, IPTC, maker notes), the PNG textual chunks
 * (`tEXt`, `zTXt`, `iTXt`), the WebP `EXIF`/`XMP ` chunks, and the GIF comment
 * extensions. This walker drops those without decoding or re-compressing the
 * image, so sharing a photo never costs quality.
 *
 * Colour-management chunks (`iCCP`, `ICCP`) are deliberately kept: they describe
 * how the pixels should be displayed, not who took them, and removing them
 * would shift the colours in the recipient's viewer.
 *
 * Everything here is pure byte processing so it can run in JVM tests. Files that
 * cannot be rewritten safely (HEIF/AVIF, unknown containers, an unknown critical
 * PNG chunk) return null; the caller re-encodes them instead.
 */
internal object ImageMetadataStripper {

    private val PngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Detects the container from its magic bytes, falling back to [mime]. */
    fun detect(bytes: ByteArray, mime: String?): ImageFormat {
        if (bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8) {
            return ImageFormat.JPEG
        }
        if (bytes.size >= PngSignature.size &&
            bytes.copyOfRange(0, PngSignature.size).contentEquals(PngSignature)
        ) {
            return ImageFormat.PNG
        }
        if (bytes.size >= 12 &&
            ascii(bytes, 0, 4) == "RIFF" &&
            ascii(bytes, 8, 4) == "WEBP"
        ) {
            return ImageFormat.WEBP
        }
        if (bytes.size >= 6 && (ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a")) {
            return ImageFormat.GIF
        }
        if (isHeif(bytes, mime)) return ImageFormat.HEIF
        return ImageFormat.OTHER
    }

    /** True when [format] holds more than one frame and must not be re-encoded. */
    fun isAnimated(bytes: ByteArray, format: ImageFormat): Boolean = when (format) {
        // Even a one-frame GIF is better passed through untouched: decoding it
        // to JPEG would flatten transparency and any future frames.
        ImageFormat.GIF -> true
        ImageFormat.PNG -> hasPngChunk(bytes, "acTL")
        ImageFormat.WEBP -> hasRiffChunk(bytes, "ANIM") || hasRiffChunk(bytes, "ANMF")
        else -> false
    }

    /**
     * Returns [bytes] with metadata removed, or null when this container cannot
     * be rewritten losslessly. The returned array may be the input instance when
     * there was nothing to remove.
     */
    fun strip(bytes: ByteArray, format: ImageFormat): ByteArray? = when (format) {
        ImageFormat.JPEG -> stripJpeg(bytes)
        ImageFormat.PNG -> stripPng(bytes)
        ImageFormat.WEBP -> stripWebp(bytes)
        ImageFormat.GIF -> stripGif(bytes)
        ImageFormat.HEIF, ImageFormat.OTHER -> null
    }

    // ---- JPEG -------------------------------------------------------------

    /**
     * Walks JPEG markers and drops every APPn segment (EXIF APP1, XMP APP1,
     * MPF APP2, IPTC/Photoshop APP13, maker notes, …) plus COM comments. The
     * JFIF APP0, an ICC profile APP2 and the Adobe APP14 transform marker are
     * kept because they describe how to render the pixels, not who took them.
     * Bytes after EOI are discarded.
     */
    private fun stripJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(0xFF)
        out.write(0xD8)
        var pos = 2
        var changed = false
        while (pos + 1 < bytes.size) {
            if (u8(bytes, pos) != 0xFF) return null
            val markerStart = pos
            while (pos < bytes.size && u8(bytes, pos) == 0xFF) pos++
            if (pos >= bytes.size) return null
            val marker = u8(bytes, pos)
            pos++
            when {
                marker == 0xD9 -> {
                    out.write(bytes, markerStart, pos - markerStart)
                    return if (changed || pos != bytes.size) out.toByteArray() else bytes
                }
                marker == 0xDA -> {
                    if (pos + 2 > bytes.size) return null
                    val length = readU16Be(bytes, pos)
                    if (length < 2 || pos + length > bytes.size) return null
                    out.write(bytes, markerStart, pos + length - markerStart)
                    var scan = pos + length
                    var terminator = -1
                    while (scan + 1 < bytes.size) {
                        if (u8(bytes, scan) == 0xFF) {
                            val next = u8(bytes, scan + 1)
                            // FF00 is stuffed entropy data and RSTn are in-band
                            // restart markers; anything else begins a new segment.
                            if (next == 0x00 || next in 0xD0..0xD7) {
                                scan += 2
                                continue
                            }
                            terminator = scan
                            break
                        }
                        scan++
                    }
                    if (terminator < 0) return null
                    out.write(bytes, pos + length, terminator - (pos + length))
                    pos = terminator
                }
                marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xD8 -> {
                    out.write(bytes, markerStart, pos - markerStart)
                }
                marker == 0x00 -> return null
                else -> {
                    if (pos + 2 > bytes.size) return null
                    val length = readU16Be(bytes, pos)
                    if (length < 2 || pos + length > bytes.size) return null
                    val payloadStart = pos + 2
                    val keep = when (marker) {
                        // JFIF only; JFXX (thumbnail) and every other APP0 go.
                        0xE0 -> isJfif(bytes, payloadStart, length - 2)
                        // Keep colour management, drop MPF and other APP2 data.
                        0xE2 -> isIccProfile(bytes, payloadStart, length - 2)
                        // Adobe APP14 carries the colour-transform hint needed
                        // to decode some CMYK/YCCK files correctly.
                        0xEE -> true
                        in 0xE1..0xEF, 0xFE -> false
                        else -> true
                    }
                    if (keep) {
                        out.write(bytes, markerStart, pos + length - markerStart)
                    } else {
                        changed = true
                    }
                    pos += length
                }
            }
        }
        return null
    }

    private fun isJfif(bytes: ByteArray, offset: Int, length: Int): Boolean =
        length >= 5 && ascii(bytes, offset, 5) == "JFIF\u0000"

    private fun isIccProfile(bytes: ByteArray, offset: Int, length: Int): Boolean =
        length >= 12 && ascii(bytes, offset, 12) == "ICC_PROFILE\u0000"

    // ---- PNG --------------------------------------------------------------

    /** Chunks that describe pixels/colour/transparency (or animation) and are safe to keep. */
    private val PngSafeChunks = setOf(
        "IHDR", "PLTE", "IDAT", "IEND",
        "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs",
        "cICP", "mDCv", "cLLi",
        "acTL", "fcTL", "fdAT",
    )

    /**
     * Copies only safe PNG chunks. Textual chunks (`tEXt`, `zTXt`, `iTXt`), the
     * `eXIf` block, `tIME` and every unknown ancillary chunk are dropped, so
     * keywords such as Author/Comment never leave the device. An unknown
     * critical chunk makes us give up rather than produce an undecodable file.
     */
    private fun stripPng(bytes: ByteArray): ByteArray? {
        if (bytes.size < PngSignature.size || !bytes.copyOfRange(0, PngSignature.size)
                .contentEquals(PngSignature)
        ) {
            return null
        }
        val out = ByteArrayOutputStream(bytes.size)
        out.write(PngSignature)
        var pos = PngSignature.size
        var changed = false
        while (pos + 12 <= bytes.size) {
            val length = readIntBe(bytes, pos)
            if (length < 0 || pos + 12L + length > bytes.size) return null
            val type = ascii(bytes, pos + 4, 4)
            val end = pos + 12 + length
            val keep = type in PngSafeChunks
            if (!keep) {
                // A lowercase first letter marks an ancillary chunk (safe to
                // drop); an unknown critical chunk means we don't understand
                // this file, so let the re-encoder deal with it.
                if (type.isEmpty() || !type[0].isLowerCase()) return null
                changed = true
            } else {
                out.write(bytes, pos, end - pos)
            }
            pos = end
            if (type == "IEND") {
                return if (changed || pos != bytes.size) out.toByteArray() else bytes
            }
        }
        return null
    }

    private fun hasPngChunk(bytes: ByteArray, wanted: String): Boolean {
        if (bytes.size < PngSignature.size || !bytes.copyOfRange(0, PngSignature.size)
                .contentEquals(PngSignature)
        ) {
            return false
        }
        var pos = PngSignature.size
        while (pos + 12 <= bytes.size) {
            val length = readIntBe(bytes, pos)
            if (length < 0 || pos + 12L + length > bytes.size) return false
            if (ascii(bytes, pos + 4, 4) == wanted) return true
            pos += 12 + length
        }
        return false
    }

    // ---- WebP -------------------------------------------------------------

    /** Chunks WebP decoders need; everything else is metadata or unknown. */
    private val WebpSafeChunks = setOf(
        "VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP",
    )

    /**
     * Rebuilds a RIFF/WEBP container without its `EXIF` and `XMP ` chunks. When
     * the extended `VP8X` header is present, its EXIF/XMP feature bits are
     * cleared as well so decoders don't expect chunks that are no longer there.
     * The ICC profile is kept (see the class comment); unknown chunks are
     * dropped because WebP decoders ignore them, while they are a common place
     * for vendor metadata to hide.
     */
    private fun stripWebp(bytes: ByteArray): ByteArray? {
        if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WEBP") {
            return null
        }
        val out = ByteArrayOutputStream(bytes.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        var pos = 12
        var changed = false
        var vp8xFlagsOffset = -1
        while (pos + 8 <= bytes.size) {
            val fourcc = ascii(bytes, pos, 4)
            val size = readIntLe(bytes, pos + 4)
            if (size < 0 || pos + 8L + size > bytes.size) return null
            val padded = size + (size and 1)
            if (pos + 8L + padded > bytes.size) return null
            val keep = fourcc in WebpSafeChunks
            if (keep) {
                if (fourcc == "VP8X" && size >= 1) vp8xFlagsOffset = out.size() + 8
                out.write(bytes, pos, 8 + padded)
            } else {
                changed = true
            }
            pos += 8 + padded
        }
        if (pos != bytes.size) return null
        if (!changed) return bytes

        val result = out.toByteArray()
        if (vp8xFlagsOffset >= 0) {
            // VP8X flags: bit 3 = EXIF, bit 2 = XMP (bit 5 is the ICC flag and
            // stays set because the ICC chunk is preserved).
            result[vp8xFlagsOffset] =
                (result[vp8xFlagsOffset].toInt() and 0x08.inv() and 0x04.inv()).toByte()
        }
        writeIntLe(result, 4, result.size - 8)
        return result
    }

    private fun hasRiffChunk(bytes: ByteArray, wanted: String): Boolean {
        if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WEBP") {
            return false
        }
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val fourcc = ascii(bytes, pos, 4)
            val size = readIntLe(bytes, pos + 4)
            if (size < 0 || pos + 8L + size > bytes.size) return false
            if (fourcc == wanted) return true
            pos += 8 + size + (size and 1)
        }
        return false
    }

    // ---- GIF --------------------------------------------------------------

    /**
     * Rebuilds the GIF block stream, keeping the loop extension and every image
     * frame while dropping comment, plain-text, XMP and unknown application
     * extensions. Bytes after the trailer are discarded.
     */
    private fun stripGif(bytes: ByteArray): ByteArray? {
        if (bytes.size < 13) return null
        val header = ascii(bytes, 0, 6)
        if (header != "GIF87a" && header != "GIF89a") return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 13)
        var pos = 13
        val packed = u8(bytes, 10)
        if (packed and 0x80 != 0) {
            val tableSize = 3 * (1 shl ((packed and 0x07) + 1))
            if (pos + tableSize > bytes.size) return null
            out.write(bytes, pos, tableSize)
            pos += tableSize
        }
        var changed = false
        while (pos < bytes.size) {
            when (u8(bytes, pos)) {
                0x3B -> {
                    out.write(bytes, pos, 1)
                    pos++
                    return if (changed || pos != bytes.size) out.toByteArray() else bytes
                }
                0x21 -> {
                    if (pos + 2 > bytes.size) return null
                    val label = u8(bytes, pos + 1)
                    val end = skipGifSubBlocks(bytes, pos + 2) ?: return null
                    val keep = when (label) {
                        0xF9 -> true
                        0xFF -> isGifLoopExtension(bytes, pos + 2) ||
                            isGifIccExtension(bytes, pos + 2)
                        else -> false
                    }
                    if (keep) {
                        out.write(bytes, pos, end - pos)
                    } else {
                        changed = true
                    }
                    pos = end
                }
                0x2C -> {
                    if (pos + 10 > bytes.size) return null
                    val imagePacked = u8(bytes, pos + 9)
                    var dataStart = pos + 10
                    if (imagePacked and 0x80 != 0) {
                        dataStart += 3 * (1 shl ((imagePacked and 0x07) + 1))
                    }
                    // LZW minimum code size, then the image data sub-blocks.
                    val end = skipGifSubBlocks(bytes, dataStart + 1) ?: return null
                    out.write(bytes, pos, end - pos)
                    pos = end
                }
                else -> return null
            }
        }
        return null
    }

    private fun isGifLoopExtension(bytes: ByteArray, at: Int): Boolean {
        if (at >= bytes.size || u8(bytes, at) != 11 || at + 12 > bytes.size) return false
        val identifier = ascii(bytes, at + 1, 11)
        return identifier == "NETSCAPE2.0" || identifier == "ANIMEXTS1.0"
    }

    /** Keeps the embedded colour profile, which describes colour, not identity. */
    private fun isGifIccExtension(bytes: ByteArray, at: Int): Boolean {
        if (at >= bytes.size || u8(bytes, at) != 11 || at + 12 > bytes.size) return false
        return ascii(bytes, at + 1, 11) == "ICCRGBG1012"
    }

    /** Returns the offset just past the NUL-terminated sub-block chain. */
    private fun skipGifSubBlocks(bytes: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < bytes.size) {
            val size = u8(bytes, pos)
            pos++
            if (size == 0) return pos
            if (pos + size > bytes.size) return null
            pos += size
        }
        return null
    }

    // ---- Helpers ----------------------------------------------------------

    private fun isHeif(bytes: ByteArray, mime: String?): Boolean {
        if (bytes.size >= 12 && ascii(bytes, 4, 4) == "ftyp") {
            val brand = ascii(bytes, 8, 4)
            if (brand in setOf(
                    "heic", "heix", "hevc", "hevx", "heim", "heis",
                    "mif1", "msf1", "avif", "avis",
                )
            ) {
                return true
            }
        }
        val m = mime?.lowercase() ?: return false
        return m.contains("heic") || m.contains("heif") || m.contains("avif")
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String {
        if (offset < 0 || count < 0 || offset + count > bytes.size) return ""
        return String(bytes, offset, count, Charsets.US_ASCII)
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    private fun readU16Be(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 24) or
            (u8(bytes, offset + 1) shl 16) or
            (u8(bytes, offset + 2) shl 8) or
            u8(bytes, offset + 3)

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or
            (u8(bytes, offset + 1) shl 8) or
            (u8(bytes, offset + 2) shl 16) or
            (u8(bytes, offset + 3) shl 24)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
