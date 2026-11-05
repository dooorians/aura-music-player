package com.ikko.aura.util

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs the blocking [block] on `Dispatchers.IO`, and calls [abort] if the caller is cancelled while it runs.
 *
 * A blocking socket read does not notice that its coroutine was cancelled: `withTimeoutOrNull(6_000) { request() }`
 * waited for the request's own connect and read timeouts (up to 20 s) before it gave up. With [abort] closing the
 * connection, the read fails at once, the thread is freed, and the cancellation reaches the caller on time.
 */
suspend fun <T> abortableBlocking(abort: () -> Unit, block: () -> T): T = coroutineScope {
    // The work never fails as a coroutine: its outcome is handed back as a value. Otherwise the error an abort provokes
    // ("socket closed") would replace the caller's cancellation, and a timeout would surface as an IOException.
    val work = async(Dispatchers.IO) { runCatching(block) }
    val outcome = try {
        work.await()
    } catch (e: CancellationException) {
        runCatching(abort)
        throw e
    }
    outcome.getOrThrow()
}
