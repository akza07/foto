package fool.akza.foto.data.semantic

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` that lets cancellation through.
 *
 * Kotlin's `runCatching` catches `CancellationException` too, which turns a
 * normal composition/scope teardown into a logged "failure" and, worse, can
 * keep a cancelled pass running. Semantic work is long and cancellable, so the
 * distinction matters.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Result.failure(error)
}
