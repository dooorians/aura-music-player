package com.ikko.aura.source.remote

import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import com.ikko.aura.source.ytm.YtmDownloads

/**
 * One added source, as a place to play from. The address of a song is worked out here, with no network: a downloaded
 * song plays from its file, whatever the source is doing; a source switched off answers nothing.
 */
class RemoteProvider(
    override val id: SourceId,
    private val sources: RemoteSources,
    private val downloads: YtmDownloads,
) : SourceProvider {

    override suspend fun playable(itemId: String): PlayableSource? {
        downloads.fileFor("${id.value}:$itemId")?.let { (file, mime) ->
            return PlayableSource(
                ref = SourceTrackRef(id, itemId),
                uri = android.net.Uri.fromFile(file).toString(),
                mimeType = mime,
                randomAccess = true,
            )
        }
        val client = sources.client(id.value) ?: return null
        return PlayableSource(
            ref = SourceTrackRef(id, itemId),
            uri = client.streamUrl(itemId),
            mimeType = null,
            randomAccess = false,
        )
    }
}
