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
 * Off, it answers nothing for a song that is only streamed — which is the kill switch. A downloaded song is a file, and plays.
 */
@Singleton
class YtmSource @Inject constructor(
    private val client: YtmClient,
    private val downloads: YtmDownloads,
) : SourceProvider {
    override val id: SourceId = YTM

    override suspend fun playable(itemId: String): PlayableSource? {
        // A downloaded song plays from its file, whatever the network and the switch.
        downloads.fileFor(YtmLibrary.keyOf(itemId))?.let { (file, mime) ->
            return PlayableSource(
                ref = SourceTrackRef(YTM, itemId),
                uri = android.net.Uri.fromFile(file).toString(),
                mimeType = mime,
                randomAccess = true,
            )
        }
        if (!client.isEnabled()) return null
        return PlayableSource(
            ref = SourceTrackRef(YTM, itemId),
            uri = "$YTM_SCHEME://$itemId",
            mimeType = null,
            randomAccess = false,
        )
    }
}
