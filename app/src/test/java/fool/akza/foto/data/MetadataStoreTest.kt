package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `sections contribute labeled words`() {
        val words = MetadataStore.metadataWords(
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
        // Labels and values are both searchable, and enumerated values are
        // indexed under their human wording rather than a resource id.
        assertTrue(words.containsAll(listOf("camera", "model", "canon", "eos", "r6")))
        assertTrue(words.containsAll(listOf("lens", "rf24", "70mm", "f2")))
        assertTrue(words.containsAll(listOf("exposure", "time", "1", "250", "s")))
        assertTrue(words.containsAll(listOf("rotated", "90", "clockwise")))
        // Display order is preserved, so the per-file cap keeps the top of the
        // sheet rather than an arbitrary tail.
        assertEquals("camera", words.first())
    }

    @Test
    fun `metadata words are matched the way a person types them`() {
        val words = MetadataStore.metadataWords(label, listOf(
            MetadataSection(
                MetadataGroup.TAGS,
                listOf(
                    MetadataField(MetadataLabel.Res(1), MetadataValue.Text("Canon EOS R6")),
                    MetadataField(MetadataLabel.Tag("ISO"), MetadataValue.Text("400")),
                    MetadataField(MetadataLabel.Res(1), MetadataValue.Text("37° 46' 29.6\" N")),
                ),
            ),
        ))
        assertTrue(matchesAllTokens(tokenizeForSearch("canon eos"), words))
        assertTrue(matchesAllTokens(tokenizeForSearch("iso 400"), words))
        assertTrue(matchesAllTokens(tokenizeForSearch("eos canon"), words))
        assertTrue(matchesAllTokens(tokenizeForSearch("canonn"), words))
        assertTrue(matchesAllTokens(tokenizeForSearch("46 37"), words))
        assertFalse(matchesAllTokens(tokenizeForSearch("nikon"), words))
    }
}
