package com.ikko.aura.util

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] that lets cancellation through.
 *
 * Plain [runCatching] also catches the [CancellationException] a cancelled coroutine gets back from a blocking call
 * (`withContext(IO) { request() }` throws it when the request returns after the job was cancelled), turns it into a
 * failure, and the code after it carries on as if the work had ended on its own: a search that had been replaced by a
 * newer one wrote its error over the newer one's results.
 *
 * A request that *fails* after its job was cancelled is the same case in disguise: `withContext` then throws the
 * request's own error (an [java.io.IOException], a timeout), not a [CancellationException]. That is why a failure is
 * only returned while the caller is still active; a cancelled caller gets its cancellation instead.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }
