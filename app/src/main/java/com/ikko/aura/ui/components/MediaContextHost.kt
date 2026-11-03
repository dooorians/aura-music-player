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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.route
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
    var infoTrack by remember { mutableStateOf<Track?>(null) }
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
    val keptStreamed by playerViewModel.keptStreamedIds.collectAsState()
    val downloads by playerViewModel.downloadStates.collectAsState()
    val scope = rememberCoroutineScope()
    val albumsVm: com.ikko.aura.viewmodel.AlbumTracksViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val favoriteAlbums by albumsVm.favoriteAlbumIds.collectAsState()
    val likedIds by playerViewModel.likedTrackIds.collectAsState()
    val likedAlbums by playerViewModel.likedAlbumKeys.collectAsState()

    var rootPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var pressAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var origin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val controller = remember {
        MediaContextController(onShow = { origin = pressAt; target = it }, onDelete = { deleteTracks(it) })
    }
    // The last finger down, in window coordinates: the menu opens out of it.
    androidx.compose.foundation.layout.Box(
        Modifier
            .onGloballyPositioned { rootPos = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        e.changes.firstOrNull { it.pressed }?.let { pressAt = it.position + rootPos }
                    }
                }
            },
    ) { content(controller) }

    // What an action applies to: for an album of another source, its songs are fetched first.
    fun withTracks(shown: LibraryContextTarget, run: (List<Track>) -> Unit) {
        if (shown !is LibraryContextTarget.RemoteAlbumTarget) { run(shown.tracks()); return }
        scope.launch {
            val tracks = shown.ref?.let { playerViewModel.streamedAlbumTracks(it) }.orEmpty().ifEmpty { shown.tracks }
            if (tracks.isEmpty()) android.widget.Toast.makeText(context, "Album indisponible", android.widget.Toast.LENGTH_SHORT).show()
            else run(tracks)
        }
    }

    target?.let { shown ->
        MediaContextSheet(
            target = shown,
            onDismiss = { target = null },
            origin = origin,
            onPlayNext = { withTracks(shown) { playerViewModel.playNext(it) } },
            onAddToQueue = { withTracks(shown) { playerViewModel.addToQueue(it) } },
            // A streamed song is made known first, so the playlist can name it afterwards.
            onAddToPlaylist = {
                withTracks(shown) { tracks ->
                    scope.launch {
                        playerViewModel.rememberForUse(tracks)
                        playlistTargetIds = tracks.map { it.id }
                    }
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
            // Any streamed song: one that does not carry its album has it looked up by name when asked.
            streamedHasAlbum = (shown as? LibraryContextTarget.TrackTarget)?.track?.let(YtmLibrary::isStreamed) == true,
            onOpenAlbum = {
                val streamedTrack = (shown as? LibraryContextTarget.TrackTarget)?.track?.takeIf(YtmLibrary::isStreamed)
                if (streamedTrack != null) {
                    scope.launch {
                        val route = playerViewModel.streamedAlbumRouteOf(streamedTrack)
                        if (route != null) navController.navigate(route)
                        else android.widget.Toast.makeText(context, "Album introuvable", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@MediaContextSheet
                }
                (shown as? LibraryContextTarget.RemoteAlbumTarget)?.ref?.let { navController.navigate(streamedAlbumRoute(it, online = true)); return@MediaContextSheet }
                (shown as? LibraryContextTarget.AlbumTarget)?.let { navController.navigate(it.album.route()); return@MediaContextSheet }
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
                (shown as? LibraryContextTarget.RemoteAlbumTarget)?.let { remote ->
                    withTracks(remote) { navController.navigate(it.first().streamedArtistRoute()) }
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
                    is LibraryContextTarget.RemoteAlbumTarget -> return@MediaContextSheet
                }
                navController.navigate(AuraDestination.ArtistPattern.route(artistId.toString()))
            },
            streamedAlbum = (shown as? LibraryContextTarget.AlbumTarget)?.album?.remoteId != null,
            albumFavorite = (shown as? LibraryContextTarget.AlbumTarget)?.takeIf { it.album.remoteId == null }?.let { it.album.id in favoriteAlbums },
            onToggleAlbumFavorite = { (shown as? LibraryContextTarget.AlbumTarget)?.let { albumsVm.toggleFavorite(it.album.id) } },
            onDelete = { deleteTracks(shown.tracks()) },
            liked = when (shown) {
                is LibraryContextTarget.TrackTarget -> shown.track.id in likedIds
                is LibraryContextTarget.RemoteAlbumTarget -> com.ikko.aura.reco.AlbumKeys.of(shown.ref, shown.title, shown.artist).any { it in likedAlbums }
                else -> false
            },
            onToggleLike = {
                if (shown is LibraryContextTarget.RemoteAlbumTarget) {
                    // The album counts at once; its songs, when its page answers, count with it.
                    scope.launch {
                        val page = shown.ref?.let { playerViewModel.streamedAlbumTracks(it) }.orEmpty().ifEmpty { shown.tracks }
                        playerViewModel.toggleLikeAlbum(shown.ref, shown.title, shown.artist, page)
                    }
                } else withTracks(shown) { it.forEach(playerViewModel::toggleLikeOf) }
            },
            onDislike = {
                if (shown is LibraryContextTarget.RemoteAlbumTarget) {
                    // The album is refused whether or not its page answers: its own songs (the ones the tile holds, which are the ones
                    // that were recommended) and the page's, and the album itself, are what is kept.
                    scope.launch {
                        val page = shown.ref?.let { playerViewModel.streamedAlbumTracks(it) }.orEmpty()
                        playerViewModel.dislikeAlbum(shown.ref, shown.title, shown.artist, (shown.tracks + page).distinctBy { it.id })
                    }
                } else withTracks(shown) { it.forEach(playerViewModel::dislike) }
            },
            onShare = { shareMedia(context, shown) },
            onPlay = { withTracks(shown) { playerViewModel.playQueue(it, 0) } },
            onShuffle = { withTracks(shown) { playerViewModel.playShuffled(it) } },
            onRadio = {
                withTracks(shown) { playerViewModel.startRadio((shown as? LibraryContextTarget.TrackTarget)?.track ?: it.first()) }
            },
            onDownloadAll = when {
                shown is LibraryContextTarget.RemoteAlbumTarget -> ({ withTracks(shown) { playerViewModel.downloadStreamed(it) } })
                shown is LibraryContextTarget.AlbumTarget && shown.album.remoteId != null -> ({ playerViewModel.downloadStreamed(shown.tracks) })
                else -> null
            },
            onInfo = { infoTrack = (shown as? LibraryContextTarget.TrackTarget)?.track },
        )
    }

    infoTrack?.let { TrackInfoDialog(it) { infoTrack = null } }

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
    is LibraryContextTarget.RemoteAlbumTarget -> emptyList()
}

/** Shares what was pressed: a local song's file, or the names of a streamed song or an album. */
private fun shareMedia(context: android.content.Context, target: LibraryContextTarget) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
    when (target) {
        is LibraryContextTarget.TrackTarget -> {
            val t = target.track
            if (YtmLibrary.isStreamed(t)) {
                intent.type = "text/plain"
                intent.putExtra(android.content.Intent.EXTRA_TEXT, "${t.title} - ${t.artist}")
            } else {
                intent.type = "audio/*"
                intent.putExtra(android.content.Intent.EXTRA_STREAM, t.contentUri)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        is LibraryContextTarget.AlbumTarget -> {
            intent.type = "text/plain"
            intent.putExtra(android.content.Intent.EXTRA_TEXT, "${target.album.title} - ${target.album.artist}")
        }
        is LibraryContextTarget.RemoteAlbumTarget -> {
            intent.type = "text/plain"
            intent.putExtra(android.content.Intent.EXTRA_TEXT, "${target.title} - ${target.artist}")
        }
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** What the app knows of a song, in its own words. */
@Composable
private fun TrackInfoDialog(track: Track, onDismiss: () -> Unit) {
    fun mmss(ms: Long): String = (ms / 1000).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
    val lines = buildList {
        add("Titre" to track.title)
        add("Artiste" to track.artist)
        if (track.album.isNotBlank()) add("Album" to track.album)
        if (track.year > 0) add("Année" to track.year.toString())
        if (track.durationMs > 0) add("Durée" to mmss(track.durationMs))
        if (track.format != com.ikko.aura.data.model.AudioFormat.UNKNOWN) add("Format" to track.format.name)
        if (track.bitrateKbps > 0) add("Débit" to "${track.bitrateKbps} kb/s")
        if (track.sampleRateHz > 0) add("Fréquence" to "${track.sampleRateHz / 1000.0} kHz · ${track.bitDepth} bits")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Informations sur le titre") },
        text = {
            androidx.compose.foundation.layout.Column {
                lines.forEach { (k, v) ->
                    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 3.dp)) {
                        androidx.compose.material3.Text(k, modifier = Modifier.width(92.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f))
                        androidx.compose.material3.Text(v)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Fermer") } },
    )
}
