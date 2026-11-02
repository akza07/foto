package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index stamp decides whether a file is re-read. If it misses a change that
 * alters what the file yields — the photo-location grant, a rename — search
 * keeps matching stale text forever, so the components of the stamp are pinned
 * here.
 */
class MetadataIndexerTest {

    @Test
    fun `a reload with nothing changed keeps the same stamp`() {
        val first = stamp(location = true)
        assertEquals(first, stamp(location = true))
    }

    @Test
    fun `granting photo location invalidates every row`() {
        assertNotEquals(stamp(location = true), stamp(location = false))
    }

    @Test
    fun `a rename or move invalidates the row`() {
        assertNotEquals(stamp(location = true, name = "old.jpg"), stamp(location = true, name = "new.jpg"))
        assertNotEquals(stamp(location = true, album = "Camera"), stamp(location = true, album = "Trips"))
        assertNotEquals(stamp(location = true, file = "1:100"), stamp(location = true, file = "2:100"))
        assertNotEquals(stamp(location = true, path = "DCIM/A/"), stamp(location = true, path = "DCIM/B/"))
    }

    @Test
    fun `a missing display name is still a stable stamp`() {
        assertEquals(stamp(location = true, name = null), stamp(location = true, name = null))
    }

    @Test
    fun `separator characters in names cannot collide`() {
        assertNotEquals(
            MetadataIndexer.stampFor("s", "a:b", "c", true),
            MetadataIndexer.stampFor("s", "a", "b:c", true),
        )
    }

    @Test
    fun `a complete non-empty list prunes files that are gone`() {
        assertTrue(MetadataIndexer.shouldPrune(complete = true, itemCount = 3))
    }

    @Test
    fun `a partial list never prunes`() {
        // The launch snapshot carries only its first screen: pruning from it wiped
        // the rest of the index, and the real scan re-indexed everything.
        assertFalse(MetadataIndexer.shouldPrune(complete = false, itemCount = 3))
    }

    @Test
    fun `an empty list never prunes`() {
        // Not proof of an empty library: filters or a provider miss can empty it.
        assertFalse(MetadataIndexer.shouldPrune(complete = true, itemCount = 0))
        assertFalse(MetadataIndexer.shouldPrune(complete = false, itemCount = 0))
    }

    private fun stamp(
        location: Boolean,
        name: String? = "photo.jpg",
        album: String = "Camera",
        file: String = "1700000000:2400000",
        path: String? = "DCIM/Camera/",
    ) = MetadataIndexer.stampFor(
        stamp = file,
        displayName = name,
        bucketName = album,
        relativePath = path,
        locationGranted = location,
    )
}
