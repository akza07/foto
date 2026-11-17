package fool.akza.foto.data.similar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroupedFolderNamingTest {

    // ---- nextGroupedName --------------------------------------------------

    @Test
    fun `nextGroupedName of empty list is Grouped(1)`() {
        assertEquals("Grouped(1)", nextGroupedName(emptyList()))
    }

    @Test
    fun `nextGroupedName skips to one past the highest existing suffix`() {
        assertEquals("Grouped(3)", nextGroupedName(listOf("Grouped(1)", "Grouped(2)")))
    }

    @Test
    fun `nextGroupedName ignores names that are not Grouped(n)`() {
        assertEquals(
            "Grouped(1)",
            nextGroupedName(listOf("Camera", "Grouped", "Grouped(x)", "Grouped(1")),
        )
    }

    @Test
    fun `nextGroupedName is case-insensitive and trims whitespace`() {
        assertEquals("Grouped(5)", nextGroupedName(listOf("  grouped(4)  ")))
    }

    @Test
    fun `nextGroupedName uses the highest number even with gaps`() {
        assertEquals("Grouped(8)", nextGroupedName(listOf("Grouped(1)", "Grouped(7)")))
    }

    // ---- folderNameError --------------------------------------------------

    @Test
    fun `folderNameError rejects a blank name`() {
        assertNotNull(folderNameError("   ", emptyList()))
    }

    @Test
    fun `folderNameError rejects a duplicate name case-insensitively`() {
        assertNotNull(folderNameError("Grouped(1)", listOf("grouped(1)")))
    }

    @Test
    fun `folderNameError rejects path separators and dot segments`() {
        assertNotNull(folderNameError("/", emptyList()))
        assertNotNull(folderNameError("a/b", emptyList()))
        assertNotNull(folderNameError("a\\b", emptyList()))
        assertNotNull(folderNameError(".", emptyList()))
        assertNotNull(folderNameError("..", emptyList()))
    }

    @Test
    fun `folderNameError rejects characters unsupported by removable storage`() {
        assertNotNull(folderNameError("a:b", emptyList()))
        assertNotNull(folderNameError("a*b", emptyList()))
        assertNotNull(folderNameError("a?b", emptyList()))
        assertNotNull(folderNameError("a\"b", emptyList()))
        assertNotNull(folderNameError("a<b", emptyList()))
        assertNotNull(folderNameError("a>b", emptyList()))
        assertNotNull(folderNameError("a|b", emptyList()))
        assertNotNull(folderNameError("name.", emptyList()))
    }

    @Test
    fun `folderNameError accepts a novel single-segment name`() {
        assertNull(folderNameError("Beach sunsets", emptyList()))
    }

    // ---- commonFolderOf ---------------------------------------------------

    @Test
    fun `commonFolderOf returns the shared path for one directory`() {
        assertEquals(
            "Pictures/Camera",
            commonFolderOf(listOf("Pictures/Camera", "Pictures/Camera")),
        )
    }

    @Test
    fun `commonFolderOf returns the deepest shared ancestor`() {
        assertEquals(
            "Pictures",
            commonFolderOf(listOf("Pictures/Camera", "Pictures/WhatsApp")),
        )
    }

    @Test
    fun `commonFolderOf is null when no item has a path`() {
        assertNull(commonFolderOf(listOf(null, null)))
        assertNull(commonFolderOf(emptyList()))
    }

    @Test
    fun `commonFolderOf ignores blanks and normalises slashes`() {
        assertEquals("Pictures", commonFolderOf(listOf("/Pictures/", "")))
    }

    // ---- parentFolder -----------------------------------------------------

    @Test
    fun `parentFolder returns the parent of a nested folder`() {
        assertEquals("Pictures", parentFolder("Pictures/Camera"))
    }

    @Test
    fun `parentFolder is null at the root level`() {
        assertNull(parentFolder("Pictures"))
    }
}
