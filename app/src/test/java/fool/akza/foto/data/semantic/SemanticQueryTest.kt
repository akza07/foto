package fool.akza.foto.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticQueryTest {

    @Test
    fun `colour and object concatenation is split`() {
        assertEquals("a photo of blue shirt", SemanticSearch.normalizeQuery("blueshirt"))
        assertEquals("a photo of green jacket", SemanticSearch.normalizeQuery("greenjacket"))
    }

    @Test
    fun `camel case is split`() {
        assertEquals("a photo of Blue Shirt", SemanticSearch.normalizeQuery("BlueShirt"))
    }

    @Test
    fun `whitespace is trimmed and collapsed`() {
        assertEquals("a photo of blue shirt", SemanticSearch.normalizeQuery("  blue   shirt "))
    }

    @Test
    fun `short query becomes a visual phrase`() {
        assertEquals("a photo of sunset", SemanticSearch.normalizeQuery("sunset"))
    }

    @Test
    fun `long query is passed through`() {
        assertEquals(
            "a dog sitting on the floor",
            SemanticSearch.normalizeQuery("a dog sitting on the floor"),
        )
    }

    @Test
    fun `blank query stays empty`() {
        assertEquals("", SemanticSearch.normalizeQuery("   "))
    }
}
