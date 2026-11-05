package com.ikko.aura.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [map] with at most [parallelism] transforms running at once, results in the input's order. For I/O that is worth
 * overlapping (opening files, reading a database) but must not flood the device: the transforms run on the caller's
 * dispatcher, so call it from `Dispatchers.IO` for blocking work. A failure cancels the others and is rethrown.
 */
suspend fun <T, R> List<T>.mapBounded(parallelism: Int, transform: suspend (T) -> R): List<R> {
    require(parallelism >= 1) { "parallelism must be at least 1, was $parallelism" }
    if (size <= 1 || parallelism == 1) return map { transform(it) }
    val gate = Semaphore(parallelism)
    return coroutineScope { map { item -> async { gate.withPermit { transform(item) } } }.awaitAll() }
}
