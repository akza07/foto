package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `.nomedia` marker detection, which is applied to SAF tree browsing
 * (MediaStore never indexes the marker itself).
 */
class NoMediaTest {

    // ---- noMediaDirFor -----------------------------------------------------

    @Test
    fun `a directory containing a nomedia child is a marker`() {
        assertEquals(
            "DCIM/Secret",
            noMediaDirFor("DCIM/Secret", listOf("a.jpg", ".nomedia")),
        )
    }

    @Test
    fun `a directory without a nomedia child is not a marker`() {
        assertNull(noMediaDirFor("DCIM/Camera", listOf("a.jpg", "b.mp4")))
        assertNull(noMediaDirFor("DCIM/Camera", emptyList()))
    }

    @Test
    fun `a similarly named file is not a marker`() {
        assertNull(noMediaDirFor("DCIM/Camera", listOf(".nomedia.bak", "nomedia")))
    }

    @Test
    fun `a marker at the tree root is the empty path`() {
        assertEquals("", noMediaDirFor("", listOf(".nomedia")))
    }

    // ---- isUnderNoMedia ----------------------------------------------------

    @Test
    fun `the marker directory itself is hidden`() {
        assertTrue(isUnderNoMedia("DCIM/Secret", setOf("DCIM/Secret")))
    }

    @Test
    fun `descendants of a marker directory are hidden`() {
        assertTrue(isUnderNoMedia("DCIM/Secret/Sub", setOf("DCIM/Secret")))
    }

    @Test
    fun `a similarly prefixed sibling is not hidden`() {
        assertFalse(isUnderNoMedia("DCIM/Secret2", setOf("DCIM/Secret")))
        assertFalse(isUnderNoMedia("DCIM/SecretStuff/a", setOf("DCIM/Secret")))
        assertFalse(isUnderNoMedia("DCIM/Camera", setOf("DCIM/Secret")))
    }

    @Test
    fun `a root marker hides everything under the tree`() {
        assertTrue(isUnderNoMedia("", setOf("")))
        assertTrue(isUnderNoMedia("DCIM/Camera", setOf("")))
    }

    @Test
    fun `no markers hides nothing`() {
        assertFalse(isUnderNoMedia("DCIM/Camera", emptySet()))
    }

    @Test
    fun `a null directory is never hidden`() {
        assertFalse(isUnderNoMedia(null, setOf("DCIM/Secret")))
    }
}
