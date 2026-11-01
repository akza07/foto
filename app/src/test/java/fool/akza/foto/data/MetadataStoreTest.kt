package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search index is only as good as the text it stores, so the flattening and
 * the LIKE escaping (a literal `%` in a comment must not become a wildcard) are
 * covered here rather than discovered at query time.
 */
class MetadataStoreTest {

    private val labels = mapOf(
        1 to "Camera model",
        2 to "Exposure time",
        3 to "Rotated 90° clockwise",
    )
    private val label: (Int) -> String = { labels[it] ?: "unknown" }

    @Test
    fun `sections flatten to labeled lines`() {
        val text = MetadataStore.flattenSections(
            label,
            listOf(
                MetadataSection(
                    MetadataGroup.CAMERA,
                    listOf(
                        MetadataField(
                            MetadataLabel.Res(1),
                            MetadataValue.Text("Canon EOS R6"),
                        ),
                        MetadataField(
                            MetadataLabel.Tag("Lens Model"),
                            MetadataValue.Text("RF24-70mm F2.8 L IS USM"),
                        ),
                    ),
                ),
                MetadataSection(
                    MetadataGroup.IMAGE,
                    listOf(
                        MetadataField(
                            MetadataLabel.Res(2),
                            MetadataValue.Text("1/250 s"),
                        ),
                        MetadataField(
                            MetadataLabel.Res(1),
                            MetadataValue.Res(3),
                        ),
                    ),
                ),
            ),
        )
        val lines = text.lines()
        assertEquals("Camera model: Canon EOS R6", lines[0])
        assertEquals("Lens Model: RF24-70mm F2.8 L IS USM", lines[1])
        assertEquals("Exposure time: 1/250 s", lines[2])
        // Enumerated values are stored under their human wording, not the id.
        assertEquals("Camera model: Rotated 90° clockwise", lines[3])
    }

    @Test
    fun `queries match labels and values however punctuation is typed`() {
        val text = MetadataStore.normalizeForSearch(
            "Camera model: Canon EOS R6\nISO: 400\nLatitude: 37° 46' 29.6\" N\nComment (zTXt): hello",
        )
        assertTrue(text.contains("iso 400"))
        assertTrue(text.contains("canon eos r6"))
        assertTrue(text.contains("37 46 29 6 n"))
        assertTrue(text.contains("comment ztxt"))
        assertEquals("iso 400", MetadataStore.normalizeForSearch("  ISO:   400 "))
    }

    @Test
    fun `like patterns escape wildcards`() {
        assertEquals("%50\\%\\_off%", MetadataStore.likePattern("50%_off"))
        assertEquals("%a\\\\b%", MetadataStore.likePattern("a\\b"))
        assertTrue(MetadataStore.likePattern("canon").startsWith("%"))
    }
}
