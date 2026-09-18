package fool.akza.foto.data

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the restore write step. The trash payload is the only
 * remaining copy of a deleted file, so a failed write must never be reported as
 * success.
 */
class TrashRestoreTest {

    private fun payload(bytes: ByteArray): File =
        File.createTempFile("foto-payload", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test
    fun `a null output stream is a hard failure`() {
        // Regression: this path previously returned true, after which restore
        // cleared IS_PENDING and deleted the trash payload — destroying the
        // last copy of the file.
        val payload = payload(byteArrayOf(1, 2, 3))

        val written = writeRestore(payload) { null }

        assertFalse(written)
        assertTrue("payload must survive a failed restore", payload.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), payload.readBytes())
    }

    @Test
    fun `payload bytes are copied to the output stream`() {
        val bytes = byteArrayOf(4, 5, 6, 7)
        val payload = payload(bytes)
        val output = ByteArrayOutputStream()

        val written = writeRestore(payload) { output }

        assertTrue(written)
        assertArrayEquals(bytes, output.toByteArray())
    }
}
