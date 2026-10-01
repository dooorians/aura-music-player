package com.ikko.aura.source.local

import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.TrackBindings
import com.ikko.aura.source.TrackKey

/**
 * Until a catalogue records which sources hold a track, a key is its own single binding: a bare id is a local track
 * (every key stored today), and "source:item" is that source's item.
 */
object LegacyLocalBindings : TrackBindings {
    override suspend fun bindingsFor(key: TrackKey): List<SourceTrackRef> = listOf(SourceTrackRef.decode(key.value))
}
