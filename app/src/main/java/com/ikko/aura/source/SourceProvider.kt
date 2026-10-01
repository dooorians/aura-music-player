package com.ikko.aura.source

/**
 * A place tracks can be played from. For now it only answers one question — how to play this item — and grows
 * with the sources: listing and syncing when a catalogue exists, health when one can be unreachable.
 */
interface SourceProvider {
    val id: SourceId

    /** How to play [itemId] now, or null if this source cannot (it is not there, or not reachable). */
    suspend fun playable(itemId: String): PlayableSource?
}

/** Which sources hold a track. The catalogue will answer from a table; until it exists see LegacyLocalBindings. */
fun interface TrackBindings {
    suspend fun bindingsFor(key: TrackKey): List<SourceTrackRef>
}
