package fool.akza.foto.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [driveDeleteQueue], the state machine behind the API 29
 * per-item delete consent flow.
 */
class DeleteQueueTest {

    @Test
    fun `deletes everything when no consent is needed`() = runBlocking {
        val round = driveDeleteQueue(listOf("a", "b", "c")) { DeleteAttempt.Deleted }

        assertEquals(listOf("a", "b", "c"), round.deleted)
        assertEquals(emptyList<String>(), round.failed)
        assertEquals(emptyList<String>(), round.pending)
    }

    @Test
    fun `items deleted before a consent request are retained`() = runBlocking {
        // Regression: a successful direct delete that preceded the first consent
        // prompt used to be discarded. The file was gone from disk but the UI
        // never suppressed it, never offered Undo, and dropped the rest of the
        // batch.
        val outcomes = mapOf(
            "a" to DeleteAttempt.Deleted,
            "b" to DeleteAttempt.Deleted,
            "c" to DeleteAttempt.NeedsConsent,
            "d" to DeleteAttempt.Deleted,
        )

        val round = driveDeleteQueue(listOf("a", "b", "c", "d")) { outcomes.getValue(it) }

        assertEquals(listOf("a", "b"), round.deleted)
        assertEquals(emptyList<String>(), round.failed)
        // From the consent point onward the queue is still pending.
        assertEquals(listOf("c", "d"), round.pending)
    }

    @Test
    fun `a consent request on the first item leaves the whole queue pending`() = runBlocking {
        val round = driveDeleteQueue(listOf("a", "b")) { DeleteAttempt.NeedsConsent }

        assertTrue(round.deleted.isEmpty())
        assertEquals(listOf("a", "b"), round.pending)
    }

    @Test
    fun `failures are reported without stopping the walk`() = runBlocking {
        val outcomes = mapOf(
            "a" to DeleteAttempt.Failed,
            "b" to DeleteAttempt.Deleted,
        )

        val round = driveDeleteQueue(listOf("a", "b")) { outcomes.getValue(it) }

        assertEquals(listOf("b"), round.deleted)
        assertEquals(listOf("a"), round.failed)
        assertEquals(emptyList<String>(), round.pending)
    }

    @Test
    fun `an empty queue completes immediately`() = runBlocking {
        val round = driveDeleteQueue(emptyList<String>()) { DeleteAttempt.Deleted }

        assertTrue(round.deleted.isEmpty())
        assertTrue(round.failed.isEmpty())
        assertTrue(round.pending.isEmpty())
    }
}
