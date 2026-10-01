package com.ikko.aura.ui.screens.search

import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.screens.ytm.YtmAlbumCard
import com.ikko.aura.ui.components.LiquidGlassPanel
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.SearchFilter
import com.ikko.aura.viewmodel.RemoteSection
import com.ikko.aura.viewmodel.SearchViewModel

@Composable
fun SearchPanel(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val ytm by searchViewModel.ytmState.collectAsStateWithLifecycle()
    val remote by searchViewModel.remoteState.collectAsStateWithLifecycle()
    val unifiedRemote by searchViewModel.unifiedRemoteState.collectAsStateWithLifecycle()
    val unifiedHasMore by searchViewModel.unifiedHasMore.collectAsStateWithLifecycle()
    val unifiedLoadingMore by searchViewModel.unifiedLoadingMore.collectAsStateWithLifecycle()
    val sourceOrder by searchViewModel.sourceOrder.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        Column(modifier = modifier.fillMaxSize()) {
            LiquidGlassPanel(
                shape = AuraShapes.PlayerPill,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        searchViewModel.setQuery(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    placeholder = {
                        Text(
                            text = "Titres, artistes, albums…",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                searchViewModel.setQuery("")
                            }) {
                                Icon(
                                    CupertinoIcons.Close,
                                    contentDescription = "Effacer",
                                    modifier = Modifier.size(20.dp),
                                    tint = AuraColors.TextPrimary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = AuraShapes.PlayerPill,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AuraColors.TextPrimary,
                        unfocusedTextColor = AuraColors.TextPrimary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = AuraColors.TextPrimary,
                    ),
                )
            }

            AnimatedVisibility(visible = query.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchFilterChip("Tout", uiState.filter == SearchFilter.ALL) { searchViewModel.setFilter(SearchFilter.ALL) }
                    SearchFilterChip("Titres", uiState.filter == SearchFilter.TRACKS) { searchViewModel.setFilter(SearchFilter.TRACKS) }
                    SearchFilterChip("Albums", uiState.filter == SearchFilter.ALBUMS) { searchViewModel.setFilter(SearchFilter.ALBUMS) }
                    SearchFilterChip("Artistes", uiState.filter == SearchFilter.ARTISTS) { searchViewModel.setFilter(SearchFilter.ARTISTS) }
                }
            }

            val searchState = when {
                query.isBlank() -> SearchContent.PROMPT
                !uiState.hasResults && !ytm.active && remote.none { it.hasContent } -> SearchContent.NO_RESULTS
                else -> SearchContent.RESULTS
            }
            
            AnimatedContent(
                targetState = searchState,
                transitionSpec = { AuraMotion.peerEnter togetherWith AuraMotion.peerExit },
                label = "search-state",
            ) { state ->
                when (state) {
                    SearchContent.PROMPT -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            CupertinoIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
                            tint = AuraColors.TextSecondary
                        )
                        Text(
                            text = "Qu'est-ce qu'on écoute ?",
                            style = MaterialTheme.typography.titleMedium,
                            color = AuraColors.TextPrimary,
                        )
                        Text(
                            text = "Recherche dans ta bibliothèque",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuraColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    SearchContent.NO_RESULTS -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            CupertinoIcons.Search, // Should ideally be a different icon, but Search is fine
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
                            tint = AuraColors.TextSecondary.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "Aucun résultat",
                            style = MaterialTheme.typography.titleMedium,
                            color = AuraColors.TextPrimary,
                        )
                        Text(
                            text = "pour « $query »",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuraColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    SearchContent.RESULTS -> {
                        // One block per source, drawn in the order of the priority the user set (Réglages).
                        val localBlock: LazyListScope.() -> Unit = {
                        if (uiState.artists.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ARTISTS)) {
                            item { SectionLabel("Artistes") }
                            items(uiState.artists, key = { "artist_${it.id}" }) { artist ->
                                ArtistResultRow(artist, modifier = Modifier.animateItem()) {
                                    onNavigate()
                                    navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString()))
                                }
                            }
                        }
                        if (uiState.albums.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ALBUMS)) {
                            item { SectionLabel("Albums") }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(uiState.albums, key = { "album_${it.id}" }) { album ->
                                        AlbumResultCard(album, modifier = Modifier.animateItem()) {
                                            onNavigate()
                                            navController.navigate(AuraDestination.AlbumPattern.route(album.id.toString()))
                                        }
                                    }
                                }
                            }
                        }
                        if (uiState.tracks.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                            item { SectionLabel("Titres") }
                            itemsIndexed(uiState.tracks, key = { _, track -> "track_${track.id}" }) { index, track ->
                                TrackListItem(
                                    track = track,
                                    onClick = {
                                        onNavigate()
                                        playerViewModel.playQueue(uiState.tracks, index)
                                        navController.navigate(AuraDestination.NowPlaying.route)
                                    },
                                    onLongClick = { menu.show(track) },
                                    modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                                )
                            }
                        }
                        }
                        val remoteBlock: LazyListScope.(RemoteSection) -> Unit = { section ->
                            if (section.artists.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ARTISTS)) {
                                item(key = "rs_artists_${section.sourceId}") { SectionLabel("Artistes · ${section.name}") }
                                items(section.artists.take(5), key = { "rs_artist_${section.sourceId}_$it" }) { name ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            onNavigate()
                                            navController.navigate(AuraDestination.RemoteArtistPattern.route(section.sourceId, name))
                                        }.padding(horizontal = 20.dp, vertical = 10.dp),
                                    ) {
                                        Text(name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            if (section.albums.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ALBUMS)) {
                                item(key = "rs_albums_${section.sourceId}") { SectionLabel("Albums · ${section.name}") }
                                item(key = "rs_albumrow_${section.sourceId}") {
                                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        items(section.albums, key = { "rs_album_${it.id}" }) { album ->
                                            YtmAlbumCard(album) {
                                                onNavigate()
                                                navController.navigate(streamedAlbumRoute(album.id))
                                            }
                                        }
                                    }
                                }
                            }
                            if (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS) {
                                if (section.hasContent) item(key = "rs_songs_${section.sourceId}") { SectionLabel("Titres · ${section.name}") }
                                if (section.loading) item(key = "rs_loading_${section.sourceId}") { YtmNote("Recherche…") }
                                if (section.error) item(key = "rs_error_${section.sourceId}") { YtmNote("${section.name} ne répond pas") }
                                itemsIndexed(section.songs, key = { _, track -> "rs_song_${track.id}" }) { index, track ->
                                    TrackListItem(
                                        track = track,
                                        onClick = {
                                            onNavigate()
                                            playerViewModel.playQueue(section.songs, index)
                                            navController.navigate(AuraDestination.NowPlaying.route)
                                        },
                                        onLongClick = { menu.show(track) },
                                        modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                                        trailingContent = {
                                            val kept = track.id in ytm.saved
                                            IconButton(onClick = {
                                                if (kept) searchViewModel.removeYtm(track) else searchViewModel.saveYtm(track)
                                            }) {
                                                Icon(
                                                    if (kept) CupertinoIcons.Check else CupertinoIcons.Add,
                                                    contentDescription = if (kept) "Retirer de la bibliothèque" else "Ajouter à la bibliothèque",
                                                    tint = AuraColors.TextPrimary,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        val ytmBlock: LazyListScope.() -> Unit = {
                        if (ytm.artists.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ARTISTS)) {
                            item { SectionLabel("Artistes · YouTube Music") }
                            items(ytm.artists.take(5), key = { "ytmartist_${it.name}" }) { artist ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onNavigate()
                                        navController.navigate(AuraDestination.YtmArtistPattern.route(artist.name))
                                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                                ) {
                                    Text(artist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (ytm.albums.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ALBUMS)) {
                            item { SectionLabel("Albums · YouTube Music") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(ytm.albums, key = { "ytmalbum_${it.id}" }) { album ->
                                        YtmAlbumCard(album) {
                                            onNavigate()
                                            navController.navigate(AuraDestination.YtmAlbumPattern.route(album.id))
                                        }
                                    }
                                }
                            }
                        }
                        if (ytm.active && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                            item { SectionLabel("Titres · YouTube Music") }
                            if (ytm.loading) item { YtmNote("Recherche…") }
                            if (ytm.empty) item { YtmNote("Aucun titre trouvé sur YouTube Music") }
                            if (ytm.error) item { YtmNote("YouTube Music ne répond pas pour le moment") }
                            itemsIndexed(ytm.results, key = { _, track -> "ytm_${track.id}" }) { index, track ->
                                val kept = track.id in ytm.saved
                                TrackListItem(
                                    track = track,
                                    onClick = {
                                        onNavigate()
                                        playerViewModel.playQueue(ytm.results, index)
                                        navController.navigate(AuraDestination.NowPlaying.route)
                                    },
                                    onLongClick = { menu.show(track) },
                                    modifier = Modifier.padding(horizontal = 20.dp).animateItem(),
                                    trailingContent = {
                                        IconButton(onClick = {
                                            if (kept) searchViewModel.removeYtm(track) else searchViewModel.saveYtm(track)
                                        }) {
                                            Icon(
                                                if (kept) CupertinoIcons.Check else CupertinoIcons.Add,
                                                contentDescription = if (kept) "Retirer de la bibliothèque" else "Ajouter à la bibliothèque",
                                                tint = AuraColors.TextPrimary,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        if (ytm.active && ytm.hasMore && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                            item {
                                Text(
                                    text = if (ytm.loadingMore) "Chargement…" else "Afficher plus de résultats",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AuraColors.TextPrimary,
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp, vertical = 6.dp)
                                        .clip(RoundedCornerShape(100))
                                        .background(AuraColors.TextPrimary.copy(alpha = 0.15f))
                                        .clickable(enabled = !ytm.loadingMore, onClick = searchViewModel::loadMoreYtm)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                        ) {
                            localBlock()
                            remoteBlock(unifiedRemote)
                            if (unifiedHasMore && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                                item {
                                    Text(
                                        text = if (unifiedLoadingMore) "Chargement…" else "Afficher plus de résultats",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = AuraColors.TextPrimary,
                                        modifier = Modifier
                                            .padding(horizontal = 20.dp, vertical = 6.dp)
                                            .clip(RoundedCornerShape(100))
                                            .background(AuraColors.TextPrimary.copy(alpha = 0.15f))
                                            .clickable(enabled = !unifiedLoadingMore, onClick = searchViewModel::loadMoreYtm)
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SearchContent { PROMPT, NO_RESULTS, RESULTS }

@Composable
private fun SearchFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(if (selected) AuraColors.TextPrimary else AuraColors.TextPrimary.copy(alpha = 0.15f))
    val textColor by animateColorAsState(if (selected) Color.Black else AuraColors.TextPrimary)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = AuraColors.TextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(top = 8.dp),
    )
}

@Composable
private fun ArtistResultRow(artist: Artist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleMedium,
            color = AuraColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AlbumResultCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.width(120.dp)) {
        SquircleCard(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onClick = onClick,
        ) {
            AlbumArt(uri = album.artworkUri, contentDescription = album.title, modifier = Modifier.fillMaxSize(), targetSizeDp = 120.dp, cacheKey = album.id.toString())
        }
        Text(
            text = album.title,
            style = MaterialTheme.typography.labelLarge,
            color = AuraColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun YtmNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AuraColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
