package fool.akza.foto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the rename field: the extension must survive untouched,
 * hidden files must not lose their leading dot, and invalid names are rejected
 * before they reach MediaStore/SAF.
 */
class RenameNameTest {

    @Test
    fun `splits the last extension`() {
        assertEquals(RenameNameParts("IMG_1234", ".jpg"), splitRenameName("IMG_1234.jpg"))
        assertEquals(RenameNameParts("holiday.clip", ".mp4"), splitRenameName("holiday.clip.mp4"))
    }

    @Test
    fun `names without an extension stay wholly editable`() {
        assertEquals(RenameNameParts("noext", ""), splitRenameName("noext"))
        assertEquals(RenameNameParts("photo.", ""), splitRenameName("photo."))
        assertEquals(RenameNameParts(".hidden", ""), splitRenameName(".hidden"))
    }

    @Test
    fun `blank names are rejected`() {
        assertEquals(RenameNameError.Empty, renameNameError(""))
        assertEquals(RenameNameError.Empty, renameNameError("   "))
    }

    @Test
    fun `path separators and dot-only names are rejected`() {
        assertEquals(RenameNameError.InvalidCharacters, renameNameError("a/b"))
        assertEquals(RenameNameError.InvalidCharacters, renameNameError("a\\b"))
        assertEquals(RenameNameError.InvalidCharacters, renameNameError("."))
        assertEquals(RenameNameError.InvalidCharacters, renameNameError(".."))
    }

    @Test
    fun `ordinary names pass`() {
        assertNull(renameNameError("IMG_1234"))
        assertNull(renameNameError("holiday 2026"))
        assertNull(renameNameError("a.b"))
    }
}
