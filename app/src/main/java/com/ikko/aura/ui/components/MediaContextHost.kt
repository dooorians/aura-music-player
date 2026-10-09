package com.ikko.aura.ui.components

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ikko.aura.source.ytm.YtmLibrary
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.navigation.streamedArtistRoute
import com.ikko.aura.viewmodel.PlayerViewModel

/** Opens the context menu. Handed to [MediaContextHost]'s content so any row can raise one. */
@Stable
class MediaContextController internal constructor(
    private val onShow: (LibraryContextTarget) -> Unit,
    private val onDelete: (List<Track>) -> Unit = {},
) {
    /** Deletes these songs' files, after the system's own confirmation (or lets go of streamed ones). */
    fun delete(tracks: List<Track>) = onDelete(tracks)
    fun show(target: LibraryContextTarget) = onShow(target)
    fun show(track: Track) = onShow(LibraryContextTarget.TrackTarget(track))
}

/**
 * Everything a long press needs, in one place: the sheet, the playlist picker, and the system's
 * deletion confirmation with the rescan that follows it.
 *
 * It exists because the same menu belongs on every list of tracks — the library, an album, the
 * search results, the queue — and the deletion half of it is three moving parts (a
 * [MediaStore.createDeleteRequest], a launcher for the system's answer, and a rescan) that no
 * screen should have to assemble for itself. A screen wraps its content in this and calls
 * [MediaContextController.show]; nothing else about the menu is its business.
 *
 * [onLibraryChanged] fires after a confirmed deletion, for screens whose own list has to be
 * rebuilt from MediaStore rather than just redrawn.
 */
@Composable
fun MediaContextHost(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onLibraryChanged: () -> Unit = {},
    content: @Composable (MediaContextController) -> Unit,
) {
    var target by remember { mutableStateOf<LibraryContextTarget?>(null) }
    // Held across the system's confirmation dialog: the sheet is gone by the time the answer
    // comes back, so the target alone cannot say what was deleted.
    var pendingDeletion by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlistTargetIds by remember { mutableStateOf<List<String>?>(null) }
    val context = LocalContext.current

    // Deleting media the app did not write needs the system's own confirmation on modern Android:
    // MediaStore hands back a PendingIntent and the user answers it, not us.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            playerViewModel.onTracksDeleted(pendingDeletion)
            onLibraryChanged()
        }
        pendingDeletion = emptyList()
        target = null
    }

    fun deleteTracks(doomed: List<Track>) {
        if (doomed.isEmpty()) return
        // Streamed songs have no file of the system's to delete: what goes is their download, and their place in
        // the library.
        if (doomed.all(YtmLibrary::isStreamed)) {
            playerViewModel.unkeepStreamed(doomed)
            target = null
            return
        }
        pendingDeletion = doomed
        val request = MediaStore.createDeleteRequest(context.contentResolver, doomed.map { it.contentUri })
        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    val controller = remember { MediaContextController(onShow = { target = it }, onDelete = { deleteTracks(it) }) }
    val keptStreamed by playerViewModel.keptStreamedIds.collectAsState()
    val downloads by playerViewModel.downloadStates.collectAsState()
    val scope = rememberCoroutineScope()
    val albumsVm: com.ikko.aura.viewmodel.AlbumTracksViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val favoriteAlbums by albumsVm.favoriteAlbumIds.collectAsState()

    content(controller)

    target?.let { shown ->
        MediaContextSheet(
            target = shown,
            onDismiss = { target = null },
            onPlayNext = { playerViewModel.playNext(shown.tracks()) },
            onAddToQueue = { playerViewModel.addToQueue(shown.tracks()) },
            // A streamed song is made known first, so the playlist can name it afterwards.
            onAddToPlaylist = {
                scope.launch {
                    playerViewModel.rememberForUse(shown.tracks())
                    playlistTargetIds = shown.tracks().map { it.id }
                }
            },
            streamed = (shown as? LibraryContextTarget.TrackTarget)?.track?.let(YtmLibrary::isStreamed) == true,
            keptInLibrary = (shown as? LibraryContextTarget.TrackTarget)?.track?.id in keptStreamed,
            onToggleKeep = {
                val track = (shown as? LibraryContextTarget.TrackTarget)?.track ?: return@MediaContextSheet
                if (track.id in keptStreamed) playerViewModel.unkeepStreamed(listOf(track)) else playerViewModel.keepStreamed(listOf(track))
            },
            download = (shown as? LibraryContextTarget.TrackTarget)?.track?.id?.let { downloads[it] },
            onDownload = { (shown as? LibraryContextTarget.TrackTarget)?.track?.let { playerViewModel.downloadStreamed(listOf(it)) } },
            onRemoveDownload = { (shown as? LibraryContextTarget.TrackTarget)?.track?.let { playerViewModel.removeDownloads(listOf(it)) } },
            streamedHasAlbum = (shown as? LibraryContextTarget.TrackTarget)?.track?.remoteAlbumId != null,
            onOpenAlbum = {
                val streamedAlbum = (shown as? LibraryContextTarget.TrackTarget)?.track?.streamedAlbumRoute()
                if (streamedAlbum != null) {
                    navController.navigate(streamedAlbum)
                    return@MediaContextSheet
                }
                val albumId = (shown as? LibraryContextTarget.TrackTarget)?.track?.albumId
                    ?: return@MediaContextSheet
                navController.navigate(AuraDestination.AlbumPattern.route(albumId.toString()))
            },
            onOpenArtist = {
                val streamedTrack = (shown as? LibraryContextTarget.TrackTarget)?.track?.takeIf(YtmLibrary::isStreamed)
                if (streamedTrack != null) {
                    navController.navigate(streamedTrack.streamedArtistRoute())
                    return@MediaContextSheet
                }
                val streamedAlbumArtist = (shown as? LibraryContextTarget.AlbumTarget)
                    ?.takeIf { it.album.remoteId != null }?.tracks?.firstOrNull()
                if (streamedAlbumArtist != null) {
                    navController.navigate(streamedAlbumArtist.streamedArtistRoute())
                    return@MediaContextSheet
                }
                val artistId = when (shown) {
                    is LibraryContextTarget.TrackTarget -> shown.track.artistId
                    is LibraryContextTarget.AlbumTarget -> shown.album.artistId
                }
                navController.navigate(AuraDestination.ArtistPattern.route(artistId.toString()))
            },
            streamedAlbum = (shown as? LibraryContextTarget.AlbumTarget)?.album?.remoteId != null,
            albumFavorite = (shown as? LibraryContextTarget.AlbumTarget)?.takeIf { it.album.remoteId == null }?.let { it.album.id in favoriteAlbums },
            onToggleAlbumFavorite = { (shown as? LibraryContextTarget.AlbumTarget)?.let { albumsVm.toggleFavorite(it.album.id) } },
            onDelete = { deleteTracks(shown.tracks()) },
        )
    }

    playlistTargetIds?.let { ids ->
        AddToPlaylistDialog(
            trackIds = ids,
            onDismiss = {
                playlistTargetIds = null
                target = null
            },
        )
    }
}

/** What a menu action applies to: the track itself, or every track on the album. */
internal fun LibraryContextTarget.tracks(): List<Track> = when (this) {
    is LibraryContextTarget.TrackTarget -> listOf(track)
    is LibraryContextTarget.AlbumTarget -> tracks
}
