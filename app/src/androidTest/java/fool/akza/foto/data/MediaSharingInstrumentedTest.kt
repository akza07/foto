package fool.akza.foto.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ByteArrayOutputStream as ByteOutput
import java.util.zip.CRC32

/**
 * End-to-end check of the Android side of the share pipeline: a real file URI in,
 * a FileProvider URI out, and the cached copy must carry neither EXIF nor PNG
 * text. The pure byte rules are covered by [ImageMetadataStripperTest]; this
 * test guards the FileProvider wiring and the orientation bake.
 */
@RunWith(AndroidJUnit4::class)
class MediaSharingInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun jpegShareDropsExifAndBakesOrientation() = runBlocking {
        val file = File(context.cacheDir, "share-jpeg-${System.nanoTime()}.jpg")
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        source.recycle()

        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ARTIST, "Secret Author")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_ROTATE_90.toString(),
        )
        exif.saveAttributes()

        val bytes = shareBytes(
            MediaItem(
                id = 1,
                uri = Uri.fromFile(file),
                bucketId = "test",
                bucketName = "test",
                relativePath = null,
                dateAdded = 0,
                isVideo = false,
                displayName = file.name,
                mimeType = "image/jpeg",
                lastModified = file.lastModified(),
                sizeBytes = file.length(),
            )
        )
        file.delete()

        assertFalse(bytes.containsAscii("Exif"))
        assertFalse(bytes.containsAscii("Secret Author"))
        assertNull(ExifInterface(ByteArrayInputStream(bytes)).getAttribute(ExifInterface.TAG_ARTIST))

        // The EXIF rotation is now in the pixels: the 4x2 source arrives 2x4.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(2, decoded.width)
        assertEquals(4, decoded.height)
        decoded.recycle()
    }

    @Test
    fun pngShareDropsTextChunks() = runBlocking {
        val file = File(context.cacheDir, "share-png-${System.nanoTime()}.png")
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()

        val withText = insertBeforeIend(
            file.readBytes(),
            pngChunk("tEXt", "Author\u0000Secret Author".toByteArray()),
        )
        file.writeBytes(withText)

        val bytes = shareBytes(
            MediaItem(
                id = 2,
                uri = Uri.fromFile(file),
                bucketId = "test",
                bucketName = "test",
                relativePath = null,
                dateAdded = 0,
                isVideo = false,
                displayName = file.name,
                mimeType = "image/png",
                lastModified = file.lastModified(),
                sizeBytes = file.length(),
            )
        )
        file.delete()

        assertFalse(bytes.containsAscii("tEXt"))
        assertFalse(bytes.containsAscii("Secret Author"))
        assertEquals(2, BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.width)
    }

    @Test
    fun strippingDisabledSharesTheOriginalUri() = runBlocking {
        val file = File(context.cacheDir, "share-original-${System.nanoTime()}.jpg")
        writeJpeg(file)
        val item = item(file, id = 3, mime = "image/jpeg")

        val result = MediaSharing.prepare(context, listOf(item), stripMetadata = false)
        assertTrue(result is ShareResult.Ready)
        val send = (result as ShareResult.Ready).send
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(item.uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        file.delete()
        Unit
    }

    @Test
    fun videosPassThroughEvenWhenStrippingIsOn() = runBlocking {
        val file = File(context.cacheDir, "share-video-${System.nanoTime()}.mp4")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val item = item(file, id = 4, mime = "video/mp4", video = true)

        val result = MediaSharing.prepare(context, listOf(item), stripMetadata = true)
        assertTrue(result is ShareResult.Ready)
        val send = (result as ShareResult.Ready).send
        assertEquals(item.uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        file.delete()
        Unit
    }

    @Test
    fun multipleImagesUseSendMultipleWithFileProviderUris() = runBlocking {
        val first = File(context.cacheDir, "share-multi-a-${System.nanoTime()}.jpg")
        val second = File(context.cacheDir, "share-multi-b-${System.nanoTime()}.jpg")
        writeJpeg(first)
        writeJpeg(second)
        val items = listOf(
            item(first, id = 5, mime = "image/jpeg"),
            item(second, id = 6, mime = "image/jpeg"),
        )

        val result = MediaSharing.prepare(context, items, stripMetadata = true)
        assertTrue(result is ShareResult.Ready)
        val send = (result as ShareResult.Ready).send
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, uris.size)
        uris.forEach { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("fool.akza.foto.share", uri.authority)
        }
        first.delete()
        second.delete()
        Unit
    }

    private fun writeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
    }

    private fun item(
        file: File,
        id: Long,
        mime: String,
        video: Boolean = false,
    ): MediaItem = MediaItem(
        id = id,
        uri = Uri.fromFile(file),
        bucketId = "test",
        bucketName = "test",
        relativePath = null,
        dateAdded = 0,
        isVideo = video,
        displayName = file.name,
        mimeType = mime,
        lastModified = file.lastModified(),
        sizeBytes = file.length(),
    )

    private suspend fun shareBytes(item: MediaItem): ByteArray {
        val result = MediaSharing.prepare(context, listOf(item), stripMetadata = true)
        assertTrue("expected a prepared share, got $result", result is ShareResult.Ready)
        val send = (result as ShareResult.Ready).send
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertTrue("missing stream URI", uri != null)
        return context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
    }

    // ---- PNG fixture helpers ---------------------------------------------

    private fun insertBeforeIend(png: ByteArray, chunk: ByteArray): ByteArray {
        var pos = 8
        while (pos + 12 <= png.size) {
            val length = readIntBe(png, pos)
            if (length < 0 || pos + 12L + length > png.size) break
            if (String(png, pos + 4, 4, Charsets.US_ASCII) == "IEND") {
                return png.copyOfRange(0, pos) + chunk + png.copyOfRange(pos, png.size)
            }
            pos += 12 + length
        }
        return png
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteOutput()
        out.write(intBe(data.size))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.write(intBe(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun intBe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun ByteArray.containsAscii(text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
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
