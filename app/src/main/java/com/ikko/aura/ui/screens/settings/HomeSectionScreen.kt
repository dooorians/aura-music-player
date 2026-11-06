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
import com.ikko.aura.data.home.HomeSection
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.screens.home.SectionTile
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.SettingsViewModel
import com.ikko.aura.viewmodel.rememberAlbumTracks

/** What a row of Home hands to the page of everything it holds, the one that follows (the row's own, and what was left out of it). */
object HomeSectionAll {
    @Volatile var section: HomeSection? = null
}

/**
 * "Plus de …", "Pop", "Nouveautés": every album a row of Home holds (and the ones it left out for lack of room), as tiles in the
 * layout of the album grid of the settings, each one doing what it does on Home.
 */
@Composable
fun HomeSectionScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    homeViewModel: HomeWidgetsViewModel = hiltViewModel(),
) {
    val section = remember { HomeSectionAll.section }
    if (section == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val items = remember(section) { (section.albums + section.spare).distinctBy { it.key } }
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    // The album grid's layouts; the list has no tile of its own here, it takes two columns.
    val columns = if (ui.albumLayoutMode == AlbumLayoutMode.GRID_3) 3 else 2
    val u = designUnit()
    val pageKey = rememberSettingsPageKey()
    val tracksOf = rememberAlbumTracks()
    val resolveRef: suspend (com.ikko.aura.data.home.SectionAlbum) -> String? = remember(homeViewModel) {
        { item -> kotlinx.coroutines.withTimeoutOrNull(8_000L) { homeViewModel.albumRefOf(item) } }
    }
    val resolveSong: suspend (com.ikko.aura.data.home.SectionAlbum) -> com.ikko.aura.data.model.Track? = remember(homeViewModel) {
        { item -> kotlinx.coroutines.withTimeoutOrNull(8_000L) { homeViewModel.songFor(item.title, item.artist) } }
    }
    val title = remember(section) { listOfNotNull(section.kicker, section.title).joinToString(" ") }
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
                    item(key = "home_section_header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Box(Modifier.takeBackPadding(u * 40f)) { SettingsHeader(title) {} }
                    }
                    itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "tile" }) { index, item ->
                        SectionTile(
                            item = item, section = section,
                            modifier = Modifier.heroRise(index, rows = true),
                            navController = navController, playerViewModel = playerViewModel,
                            resolveRef = resolveRef, onLongPress = { menu.show(it) }, tracksOf = tracksOf,
                            onBlockArtist = homeViewModel::blockArtist,
                            prepareRef = { ref -> homeViewModel.prepareAlbum(ref) },
                            resolveSong = resolveSong,
                            pageKey = pageKey,
                        )
                    }
                }
            }
        }
    }
}

/** Takes back [by] of the horizontal padding a parent gave: the header sits at the page's own margins, not inside the grid's. */
private fun Modifier.takeBackPadding(by: androidx.compose.ui.unit.Dp): Modifier = this.layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}
