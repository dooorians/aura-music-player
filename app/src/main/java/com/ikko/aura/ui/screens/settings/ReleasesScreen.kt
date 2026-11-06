package com.ikko.aura.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.model.Album
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.screens.library.AlbumGridCell
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.SettingsViewModel
import com.ikko.aura.viewmodel.rememberAlbumTracks

/** What an artist's page hands to the page of all its albums (or all its singles) that it opens: the title and the releases. */
object ArtistReleases {
    @Volatile var title: String = ""
    @Volatile var albums: List<Album> = emptyList()
}

/**
 * "Albums" or "Singles & EP" of an artist: every release, in the layout the album grid has in the settings (two columns, three
 * columns, or a list), each opening as it does from the library.
 */
@Composable
fun ReleasesScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val title = remember { ArtistReleases.title }
    val albums = remember { ArtistReleases.albums }
    if (albums.isEmpty()) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val isList = ui.albumLayoutMode == AlbumLayoutMode.LIST
    val columns = when (ui.albumLayoutMode) {
        AlbumLayoutMode.GRID_2 -> 2
        AlbumLayoutMode.GRID_3 -> 3
        AlbumLayoutMode.LIST -> 1
    }
    val u = designUnit()
    val pageKey = rememberSettingsPageKey()
    val tracksOf = rememberAlbumTracks()
    GradientScreenScaffold(applyStatusBarInset = false) {
        MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().settingsPageBody(),
                    contentPadding = PaddingValues(start = u * 40f, end = u * 40f, bottom = LocalPlayerPillHeight.current + u * 60f),
                    horizontalArrangement = Arrangement.spacedBy(u * 26f),
                    verticalArrangement = Arrangement.spacedBy(u * 32f),
                ) {
                    item(key = "releases_header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        // The header's own margins are the page's: the grid's padding is taken back.
                        Box(Modifier.layoutNoPadding(u * 40f)) { SettingsHeader(title) {} }
                    }
                    itemsIndexed(albums, key = { _, it -> it.id }, contentType = { _, _ -> if (isList) "album_row" else "album_cell" }) { index, album ->
                        Box(Modifier.heroRipple(pageKey = pageKey).heroRise(index, rows = true)) {
                            AlbumGridCell(
                                album = album,
                                isList = isList,
                                columns = columns,
                                navController = navController,
                                pageKey = pageKey,
                                onAlbumLongPress = {
                                    val ref = album.remoteId
                                    menu.show(
                                        if (ref != null) LibraryContextTarget.RemoteAlbumTarget(ref, album.title, album.artist, album.artworkUri?.toString())
                                        else LibraryContextTarget.AlbumTarget(album, tracksOf(album.id)),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Takes back [by] of the horizontal padding a parent gave: the header sits at the page's own margins, not inside the grid's. */
private fun Modifier.layoutNoPadding(by: androidx.compose.ui.unit.Dp): Modifier = this.layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}
