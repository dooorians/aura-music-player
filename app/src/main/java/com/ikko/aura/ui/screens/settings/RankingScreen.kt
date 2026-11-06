package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.rememberPlayerAccent
import com.ikko.aura.ui.screens.library.LibraryListRow
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What an artist's page hands to the ranking page it opens: the songs in their order, and the line under each (its album, a
 * single's kind, its year). Held in memory for the one page that follows; a page reached without it (the app was restarted
 * on it) simply closes.
 */
object ArtistRanking {
    @Volatile var tracks: List<Track> = emptyList()
    @Volatile var subtitles: Map<String, String> = emptyMap()
}

/** "Classement des morceaux": every song of the artist the page ranks, as the library's songs are drawn. */
@Composable
fun RankingScreen(navController: NavHostController, playerViewModel: PlayerViewModel) {
    val tracks = remember { ArtistRanking.tracks }
    val subtitles = remember { ArtistRanking.subtitles }
    if (tracks.isEmpty()) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val u = designUnit()
    val pageKey = rememberSettingsPageKey()
    val accent = rememberPlayerAccent()
    val nowPlayingId by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = playerViewModel.uiState.value.currentTrack?.id)

    GradientScreenScaffold(applyStatusBarInset = false) {
        MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().settingsPageBody(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + u * 60f),
                ) {
                    item(key = "ranking_header", contentType = "header") {
                        SettingsHeader("Classement des morceaux") {}
                    }
                    itemsIndexed(tracks, key = { _, it -> it.id }, contentType = { _, _ -> "track" }) { index, track ->
                        Box(
                            Modifier
                                .heroRipple(pageKey = pageKey)
                                .heroRise(index, rows = true)
                                .padding(horizontal = u * 40f),
                        ) {
                            LibraryListRow(
                                coverUri = track.artworkUri, coverKey = track.albumId.toString(), coverDescription = track.album,
                                title = track.title, subtitle = subtitles[track.id].orEmpty(), trailingText = null,
                                playing = track.id == nowPlayingId, accent = accent, compact = false,
                                onClick = { playerViewModel.playQueue(tracks, index) },
                                onLongClick = { menu.show(track) },
                            )
                        }
                    }
                }
            }
        }
    }
}
