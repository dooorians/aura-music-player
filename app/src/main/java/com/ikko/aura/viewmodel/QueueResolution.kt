package com.ikko.aura.viewmodel

import com.ikko.aura.source.ytm.YtmLibrary

/**
 * The entries of a queue, looked up by id.
 *
 * A queue of streamed songs needs nothing from the device's library, so it is resolved without it: [local] — which
 * waits for the first scan of the device — is only asked for when some id is a local file's. Waiting on it
 * unconditionally left a streaming-only user (nothing local, nothing kept) with a player the screens never attached to.
 */
internal suspend fun <T> resolveQueueEntries(
    ids: Collection<String>,
    idOf: (T) -> String,
    streamed: suspend () -> List<T>,
    local: suspend () -> List<T>,
): Map<String, T> {
    val needsLocal = ids.any { !YtmLibrary.isStreamedId(it) }
    val localEntries = if (needsLocal) local() else emptyList()
    return (localEntries + streamed()).associateBy(idOf)
}
