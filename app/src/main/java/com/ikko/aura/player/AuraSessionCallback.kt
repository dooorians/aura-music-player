package com.ikko.aura.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.source.TrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where a queued item becomes something playable. Every controller adds items through here — Aura's own screens, a
 * headset, the system's own resume — so this is the one place a track is turned into a source: they hand over a
 * mediaId, and it is answered with the address to play it from now.
 *
 * For a local track the answer is the URI the item already carries, and it is returned untouched. If nothing resolves
 * the item is kept as it came: refusing it would leave the queue as it was while the screens, which mirror the queue
 * position for position, had already moved on, and an item that cannot be played is playback recovery's to handle.
 */
class AuraSessionCallback(
    private val resolver: TrackResolver,
    private val snapshots: QueueSnapshotStore,
    private val scope: CoroutineScope,
) : MediaSession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val result = SettableFuture.create<MutableList<MediaItem>>()
        scope.launch {
            // Never completed exceptionally, whatever the resolver does.
            result.set(mediaItems.mapTo(ArrayList(mediaItems.size)) { resolveOrKeep(it) })
        }
        return result
    }

    /**
     * The play button of a headset or the lock screen, pressed after the process was killed: the system asks the session
     * to resume what was playing. It is answered from the queue written down by [QueueSnapshotWriter], with each item
     * resolved afresh. Nothing to resume — or nothing that resolves — is a refusal, which is what the default always was.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            val resumed = runCatching { resumable() }.getOrNull()
            if (resumed != null) result.set(resumed) else result.setException(UnsupportedOperationException("nothing to resume"))
        }
        return result
    }

    private suspend fun resumable(): MediaSession.MediaItemsWithStartPosition? {
        val saved = snapshots.read() ?: return null
        val addressOf = HashMap<String, String>()
        saved.items.forEach { entry ->
            if (entry.id !in addressOf) {
                resolver.candidates(TrackKey(entry.id)).firstOrNull()?.let { addressOf[entry.id] = it.uri }
            }
        }
        val snapshot = saved.surviving { it in addressOf } ?: return null
        val items = snapshot.items.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.id)
                .setUri(addressOf.getValue(entry.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title.ifEmpty { null })
                        .setArtist(entry.artist.ifEmpty { null })
                        .setAlbumTitle(entry.album.ifEmpty { null })
                        .build(),
                )
                .build()
        }
        return MediaSession.MediaItemsWithStartPosition(items, snapshot.index, snapshot.positionMs)
    }

    private suspend fun resolveOrKeep(item: MediaItem): MediaItem {
        val best = runCatching { resolver.candidates(TrackKey(item.mediaId), item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString()).firstOrNull() }.getOrNull()
            ?: return item
        // The cover update (PlayerController.updateItemArtwork) replaces an item and comes through here as well: one
        // already on the right address is returned as it is, with the metadata it has just been given.
        if (item.localConfiguration?.uri?.toString() == best.uri) return item
        return item.buildUpon().setUri(best.uri).setMimeType(best.mimeType).build()
    }
}
