package fool.akza.foto.data

/**
 * Outcome of one delete attempt, free of Android types so the queue walk can be
 * unit-tested. Maps onto [DeleteResult]: [Deleted] / [NeedsConsent] / [Failed].
 */
internal enum class DeleteAttempt { Deleted, NeedsConsent, Failed }

/** Result of walking a delete queue until it is exhausted or consent is needed. */
internal data class DeleteRound<T>(
    /** Items confirmed deleted this round, in order. */
    val deleted: List<T>,
    /** Items whose original could not be removed; the caller rolls them back. */
    val failed: List<T>,
    /** Items still pending from the first consent point onward (or empty). */
    val pending: List<T>,
)

/**
 * Calls [delete] on each item in [items], stopping at the first
 * [DeleteAttempt.NeedsConsent].
 *
 * Everything deleted before that point is still reported in
 * [DeleteRound.deleted]. On API 29 a successful direct delete can precede a
 * consent request (the system only prompts for files it doesn't already trust
 * the app to touch), and dropping those results deletes a file with no Undo
 * entry and abandons the rest of the batch.
 */
internal suspend fun <T> driveDeleteQueue(
    items: List<T>,
    delete: suspend (T) -> DeleteAttempt,
): DeleteRound<T> {
    val deleted = mutableListOf<T>()
    val failed = mutableListOf<T>()
    items.forEachIndexed { index, item ->
        when (delete(item)) {
            DeleteAttempt.Deleted -> deleted += item
            DeleteAttempt.NeedsConsent -> return DeleteRound(
                deleted = deleted,
                failed = failed,
                pending = items.drop(index),
            )
            DeleteAttempt.Failed -> failed += item
        }
    }
    return DeleteRound(deleted = deleted, failed = failed, pending = emptyList())
}
