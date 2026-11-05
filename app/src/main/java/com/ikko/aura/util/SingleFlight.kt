package com.ikko.aura.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lets one caller at a time do the work for a [key]; the others that ask for the same key meanwhile wait for its
 * result instead of doing the same work again.
 *
 * Twenty rows of one album each asked for the same cover and two of them decoded it at once. Nothing stays behind
 * once the work is over, whichever way it ends: the entry is removed on a result, on none, on an error and on
 * cancellation, so a key can never be left "in progress".
 *
 * A waiter only keeps what the owner found. If the owner found nothing (null) or went away (cancelled, failed), the
 * waiter goes round again and does the work itself: a result of one caller is not an answer for another's, whose
 * input may differ (two tracks of one album, one with a picture and one without).
 */
internal class SingleFlight<K : Any, V : Any> {
    private val flights = ConcurrentHashMap<K, CompletableDeferred<V?>>()

    /** How many keys are being worked on right now; zero when everything has finished. */
    val inFlight: Int get() = flights.size

    suspend fun run(key: K, block: suspend () -> V?): V? {
        while (true) {
            val mine = CompletableDeferred<V?>()
            val other = flights.putIfAbsent(key, mine)
            if (other == null) {
                try {
                    val result = block()
                    // Removed before the waiters are woken: one that goes round again must find the key free.
                    flights.remove(key, mine)
                    mine.complete(result)
                    return result
                } finally {
                    flights.remove(key, mine)
                    // Already complete on a normal end; on an error or a cancellation this frees the waiters.
                    mine.cancel()
                }
            }
            val shared = try {
                other.await()
            } catch (e: CancellationException) {
                // Our own cancellation ends us; the owner's sends us round to do the work.
                currentCoroutineContext().ensureActive()
                null
            }
            if (shared != null) return shared
        }
    }
}
