package com.ikko.aura.source.local

import android.content.ContentUris
import android.provider.MediaStore
import com.ikko.aura.source.PlayableSource
import com.ikko.aura.source.SourceId
import com.ikko.aura.source.SourceProvider
import com.ikko.aura.source.SourceTrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own files. An item id is the MediaStore `_ID`, and its address is the content URI that
 * LocalMusicRepository has always put on a track.
 */
@Singleton
class MediaStoreSource @Inject constructor() : SourceProvider {
    override val id = SourceId.LOCAL

    override suspend fun playable(itemId: String): PlayableSource? {
        val mediaId = itemId.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        // No existence check: a file that has gone fails when the player opens it, and playback recovery answers that.
        return PlayableSource(
            ref = SourceTrackRef(SourceId.LOCAL, itemId),
            uri = uri.toString(),
            randomAccess = true,
        )
    }
}
