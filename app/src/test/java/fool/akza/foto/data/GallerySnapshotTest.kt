package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch snapshot is silent when it breaks: the grid simply never paints
 * early, or paints the wrong album. The codec is therefore pinned here, without
 * a Context or an android.net.Uri (records use plain strings).
 */
class GallerySnapshotTest {

    private fun record(
        name: String = "photo.jpg",
        uri: String = "content://media/external/images/media/7",
    ) = GallerySnapshot.ItemRecord(
        id = 7,
        uri = uri,
        bucketId = "12345",
        bucketName = "DCIM/Camera",
        relativePath = "DCIM/Camera/",
        dateAdded = 1_760_000_000,
        isVideo = false,
        displayName = name,
        safTreeUri = null,
        mimeType = "image/jpeg",
        lastModified = 1_760_000_100,
        sizeBytes = 57_515,
    )

    private fun folder(covers: List<GallerySnapshot.ItemRecord> = listOf(record("a.jpg"), record("b.jpg"))) =
        GallerySnapshot.FolderRecord(
            bucketId = "DCIM/Camera",
            name = "Camera",
            count = 250,
            dateAdded = 1_760_000_000,
            safTreeUri = null,
            safDocId = null,
            mediaStoreBucketId = "12345",
            covers = covers,
        )

    @Test
    fun `an item survives a round trip`() {
        val parsed = GallerySnapshot.decodeItem(GallerySnapshot.encodeItem(record()))!!
        assertEquals(record(), parsed)
    }

    @Test
    fun `a folder with covers survives a round trip`() {
        val parsed = GallerySnapshot.decodeFolder(GallerySnapshot.encodeFolder(folder()))!!
        assertEquals(folder(), parsed)
    }

    @Test
    fun `separators inside names do not corrupt the line`() {
        val tricky = record(name = "a,b\tc\nd\\e.jpg")
        assertEquals(tricky, GallerySnapshot.decodeItem(GallerySnapshot.encodeItem(tricky)))
        // A cover list must not split on a comma that belongs to a name.
        val withTrickyCover = folder(covers = listOf(tricky))
        assertEquals(
            withTrickyCover,
            GallerySnapshot.decodeFolder(GallerySnapshot.encodeFolder(withTrickyCover)),
        )
    }

    @Test
    fun `a video keeps its flag`() {
        val video = record().copy(isVideo = true, mimeType = "video/mp4")
        val parsed = GallerySnapshot.decodeItem(GallerySnapshot.encodeItem(video))!!
        assertTrue(parsed.isVideo)
        assertEquals("video/mp4", parsed.mimeType)
    }

    @Test
    fun `a folder without covers still decodes`() {
        val parsed = GallerySnapshot.decodeFolder(GallerySnapshot.encodeFolder(folder(emptyList())))!!
        assertEquals(0, parsed.covers.size)
        assertEquals(250, parsed.count)
    }

    @Test
    fun `corrupt lines are ignored instead of throwing`() {
        assertNull(GallerySnapshot.decodeFolder("F\tonly-two"))
        assertNull(GallerySnapshot.decodeItem("I\t1\t"))
        assertNull(GallerySnapshot.decodeItem("garbage"))
    }
}
