package com.ikko.aura.playback

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.TrackBindings
import com.ikko.aura.source.TrackKey

class TrackResolver(
    private val bindings: TrackBindings,
    private val providerFor: (SourceId) -> SourceProvider?,
    private val order: (List<SourceTrackRef>) -> List<SourceTrackRef> = { it },
    private val dynamicFallback: (suspend (String, String) -> List<PlayableSource>)? = null
) {
    constructor(
        bindings: TrackBindings,
        providers: Map<SourceId, SourceProvider>,
        order: (List<SourceTrackRef>) -> List<SourceTrackRef> = { it },
        dynamicFallback: (suspend (String, String) -> List<PlayableSource>)? = null
    ) : this(bindings, { id: SourceId -> providers[id] }, order, dynamicFallback)

    suspend fun candidates(key: TrackKey, title: String? = null, artist: String? = null, exclude: Set<SourceTrackRef> = emptySet()): List<PlayableSource> {
        val direct = order(bindings.bindingsFor(key).filterNot { it in exclude })
            .mapNotNull { ref -> providerFor(ref.source)?.playable(ref.itemId) }
            .filter { it.mimeType == null || it.mimeType.startsWith("audio/") }
        if (direct.isNotEmpty()) return direct
        
        if (title != null && artist != null && dynamicFallback != null) {
            val fallbackCandidates = dynamicFallback.invoke(title, artist).filterNot { it.ref in exclude }
            if (fallbackCandidates.isNotEmpty()) return fallbackCandidates
        }
        
        return emptyList()
    }
}
