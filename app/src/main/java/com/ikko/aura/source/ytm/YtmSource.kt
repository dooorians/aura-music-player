package com.ikko.aura.source.ytm

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import javax.inject.Inject
import javax.inject.Singleton

/** [SourceId] of YouTube Music, and the scheme its items carry until the player opens them. */
val YTM: SourceId = SourceId("ytm")
const val YTM_SCHEME = "ytm"

/**
 * YouTube Music, as a place to play from. The answer costs no network: the address is `ytm://<videoId>`, turned into a
 * real stream address by [YtmDataSource] at the moment the player opens it. So a queue of fifty songs is not fifty
 * lookups before the first plays, and an address cannot expire while it waits in the queue.
 *
 * Off, it answers nothing — which is the kill switch: saved songs stay listed, and are skipped like any unplayable item.
 */
@Singleton
class YtmSource @Inject constructor(private val client: YtmClient) : SourceProvider {
    override val id: SourceId = YTM

    override suspend fun playable(itemId: String): PlayableSource? {
        if (!client.isEnabled()) return null
        return PlayableSource(
            ref = SourceTrackRef(YTM, itemId),
            uri = "$YTM_SCHEME://$itemId",
            mimeType = null,
            randomAccess = false,
        )
    }
}
