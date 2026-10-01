package com.ikko.aura.source.jiosaavn

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JioSaavnSource @Inject constructor() : SourceProvider {

    override val id = SourceId("jiosaavn")
    
    override suspend fun playable(itemId: String): PlayableSource? {
        return PlayableSource(
            ref = SourceTrackRef(id, itemId),
            uri = "jiosaavn://$itemId",
            mimeType = null,
            randomAccess = false,
        )
    }
}
