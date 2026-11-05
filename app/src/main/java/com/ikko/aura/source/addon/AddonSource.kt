package com.ikko.aura.source.addon

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef

/**
 * For Phase 1, we hardcode the single addon id.
 * In Phase 2, this will be generated dynamically per addon from the DB.
 */
class AddonSource(
    private val client: AddonClient,
    private val addonId: String,
    private val rootUrl: String
) : SourceProvider {

    override val id: SourceId = SourceId(addonId)

    override suspend fun playable(itemId: String): PlayableSource? {
        // Encode the rootUrl so it can be safely passed as the host in the URI
        val encodedRoot = android.util.Base64.encodeToString(
            rootUrl.toByteArray(Charsets.UTF_8), 
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        
        return PlayableSource(
            ref = SourceTrackRef(id, itemId),
            uri = "addon://$encodedRoot/$itemId",
            mimeType = null,
            randomAccess = false,
        )
    }
}
