package com.ikko.aura.playback

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.TrackBindings
import com.ikko.aura.source.TrackKey

/**
 * Turns a track key into the ways it can be played, best first. It lives with the player service and not with the
 * screens: a screen hands over a key and never holds an address, so the answer can be chosen — and chosen again,
 * when one fails — at the moment of playing.
 */
class TrackResolver(
    private val bindings: TrackBindings,
    private val providers: Map<SourceId, SourceProvider>,
    /** How candidates are ranked: nearest first. The identity until there is more than one source to rank. */
    private val order: (List<SourceTrackRef>) -> List<SourceTrackRef> = { it },
) {
    /** Every way to play [key] now, best first; [exclude] holds the ones that have just failed. */
    suspend fun candidates(key: TrackKey, exclude: Set<SourceTrackRef> = emptySet()): List<PlayableSource> =
        order(bindings.bindingsFor(key).filterNot { it in exclude })
            .mapNotNull { ref -> providers[ref.source]?.playable(ref.itemId) }
            // Audio only, whatever a source offers: an item whose type is known and is not audio/* is never played.
            .filter { it.mimeType == null || it.mimeType.startsWith("audio/") }
}
