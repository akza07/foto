package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search is expected to behave like a search box, not a substring test: word
 * order is free, prefixes count, and typos of a few characters are forgiven.
 * These rules decide every result, so they are pinned here.
 */
class FuzzyMatchTest {

    @Test
    fun `text is split into lowercase tokens`() {
        assertEquals(listOf("canon", "eos", "r6"), tokenizeForSearch("Canon EOS-R6"))
        assertEquals(listOf("37", "46", "29", "6", "n"), tokenizeForSearch("37° 46' 29.6\" N"))
        assertEquals(listOf("f", "1", "8"), tokenizeForSearch("f/1.8"))
        assertEquals(emptyList<String>(), tokenizeForSearch("  --  "))
    }

    @Test
    fun `exact and prefix words match`() {
        assertEquals(TokenMatch.EXACT, matchToken("canon", "canon"))
        assertEquals(TokenMatch.PREFIX, matchToken("can", "canon"))
        assertEquals(TokenMatch.PREFIX, matchToken("lighth", "lighthouse"))
        assertNull(matchToken("beach", "canon"))
    }

    @Test
    fun `short tokens are never fuzzy`() {
        // Three letters or fewer must be right, otherwise everything matches.
        assertEquals(0, maxEditsFor("cat"))
        assertNull(matchToken("cat", "car"))
        assertNull(matchToken("iso", "eso"))
        assertEquals(TokenMatch.EXACT, matchToken("iso", "iso"))
    }

    @Test
    fun `one typo is forgiven for medium tokens`() {
        assertEquals(1, maxEditsFor("canon"))
        assertEquals(TokenMatch.FUZZY, matchToken("canonn", "canon"))
        assertEquals(TokenMatch.FUZZY, matchToken("camon", "canon"))
        assertNull(matchToken("cxmonn", "canon"))
    }

    @Test
    fun `transpositions count as one edit`() {
        assertEquals(TokenMatch.FUZZY, matchToken("lighthosue", "lighthouse"))
        assertEquals(TokenMatch.FUZZY, matchToken("expousre", "exposure"))
    }

    @Test
    fun `long tokens forgive two typos`() {
        assertEquals(2, maxEditsFor("lighthouse"))
        // Two substitutions.
        assertEquals(TokenMatch.FUZZY, matchToken("lighxthoxse", "lighthouse"))
        // Substitution plus transposition.
        assertEquals(TokenMatch.FUZZY, matchToken("lighothuse", "lighthouse"))
        assertNull(matchToken("lightousekeeperzz", "lighthouse"))
    }

    @Test
    fun `length differences are bounded by the budget`() {
        assertNull(matchToken("canon", "xcanonser"))
        assertEquals(TokenMatch.FUZZY, matchToken("canon", "xcanon"))
        // A longer word that merely starts with the query is a prefix match.
        assertEquals(TokenMatch.PREFIX, matchToken("canon", "canons"))
    }

    @Test
    fun `every query token must match some word`() {
        val words = tokenizeForSearch("Camera model: Canon EOS R6, 35 mm, ISO 400")
        assertTrue(matchesAllTokens(listOf("eos", "canon"), words))
        assertTrue(matchesAllTokens(listOf("canonn", "r6"), words))
        assertTrue(matchesAllTokens(listOf("iso", "400"), words))
        assertFalse(matchesAllTokens(listOf("nikon", "eos"), words))
        assertFalse(matchesAllTokens(emptyList(), words))
    }

    @Test
    fun `unicode words keep their letters`() {
        val words = tokenizeForSearch("Kraków, Polska")
        assertEquals(listOf("kraków", "polska"), words)
        assertEquals(TokenMatch.PREFIX, matchToken("krak", words[0]))
    }
}
