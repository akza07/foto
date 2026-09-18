package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the collision-avoiding document naming used by copy/move/restore. */
class DocumentNamingTest {

    @Test
    fun `uses the requested name when it is free`() {
        assertEquals("img.jpg", createUniqueDocument("img.jpg") { it })
    }

    @Test
    fun `appends a numeric suffix before the extension on collision`() {
        val taken = setOf("img.jpg")

        val created = createUniqueDocument("img.jpg") { if (it in taken) null else it }

        assertEquals("img (1).jpg", created)
    }

    @Test
    fun `counts up past repeated collisions`() {
        val taken = setOf("img.jpg", "img (1).jpg", "img (2).jpg")

        val created = createUniqueDocument("img.jpg") { if (it in taken) null else it }

        assertEquals("img (3).jpg", created)
    }

    @Test
    fun `appends the suffix when there is no extension`() {
        val created = createUniqueDocument("notes") { if (it == "notes") null else it }

        assertEquals("notes (1)", created)
    }

    @Test
    fun `returns null when every attempt is refused`() {
        assertNull(createUniqueDocument("img.jpg") { null })
    }
}
