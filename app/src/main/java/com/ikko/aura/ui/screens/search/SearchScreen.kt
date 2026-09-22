package com.ikko.aura.ui.screens.search

import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.LiquidGlassPanel
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.SearchViewModel

/**
 * Search, as a panel rather than a page.
 *
 * There is no search tab any more: a dedicated space for it meant the one thing you reach for
 * mid-thought was three swipes away from wherever you were. Pulled down from the top of any page
 * instead — see [com.ikko.aura.PullToSearch] — it arrives where you already are, and this is what
 * it puts there.
 *
 * [onNavigate] fires for anything that leaves the panel, so the caller can close it in the same
 * gesture rather than leaving it open behind the screen it just opened.
 */
@Composable
fun SearchPanel(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    // The field owns its text, and the view model is only told about it.
    //
    // Bound the other way — value = uiState.query — every keystroke made a round trip: onValueChange
    // set a flow, a combine filtered the whole library on Dispatchers.Default, and the result came
    // back to the main thread a frame or more later. Typing faster than that round trip meant each
    // new character was applied against a value that no longer reflected the last one, and the
    // letters came out reordered or missing. A text field has to update on the same frame as the
    // key that changed it; nothing asynchronous may sit between the two.
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
    Column(modifier = modifier.fillMaxSize()) {
            // The same material and the same silhouette as the player pill — the two are a pair,
            // one arriving from each edge of the screen. The field itself is drawn with no
            // container and no border of its own: the glass is the container, and a second one
            // inside it would read as a box within a box.
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
                    // Centred, with nothing in front of it. A leading icon anchors the text to
                    // the left and the pill then reads as a form field; centred and bare it reads
                    // as the pill it is — the same object as the player's, turned into a prompt.
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    placeholder = {
                        Text(
                            text = "Titres, artistes, albums…",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
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

            // Prompt / no-results / results are peers of one another, so they use the app's peer
            // motion rather than swapping instantly. Keyed on which of the three is showing, not
            // on the query, so typing inside a state that already has results doesn't re-animate
            // the whole list on every keystroke.
            val searchState = when {
                query.isBlank() -> SearchContent.PROMPT
                !uiState.hasResults -> SearchContent.NO_RESULTS
                else -> SearchContent.RESULTS
            }
            AnimatedContent(
                targetState = searchState,
                transitionSpec = { AuraMotion.peerEnter togetherWith AuraMotion.peerExit },
                label = "search-state",
            ) { state ->
            when (state) {
                SearchContent.PROMPT -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Recherche dans ta bibliothèque locale",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                    )
                }
                SearchContent.NO_RESULTS -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Aucun résultat pour « $query »",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuraColors.TextSecondary,
                    )
                }
                SearchContent.RESULTS -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                ) {
                    if (uiState.artists.isNotEmpty()) {
                        item { SectionLabel("Artistes") }
                        items(uiState.artists, key = { "artist_${it.id}" }) { artist ->
                            ArtistResultRow(artist) {
                                onNavigate()
                                navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString()))
                            }
                        }
                    }
                    if (uiState.albums.isNotEmpty()) {
                        item { SectionLabel("Albums") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(uiState.albums, key = { "album_${it.id}" }) { album ->
                                    AlbumResultCard(album) {
                                        onNavigate()
                                        navController.navigate(AuraDestination.AlbumPattern.route(album.id.toString()))
                                    }
                                }
                            }
                        }
                    }
                    if (uiState.tracks.isNotEmpty()) {
                        item { SectionLabel("Titres") }
                        itemsIndexed(uiState.tracks, key = { _, track -> "track_${track.id}" }) { index, track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    onNavigate()
                                    playerViewModel.playQueue(uiState.tracks, index)
                                },
                                onLongClick = { menu.show(track) },
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
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
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = AuraColors.TextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun ArtistResultRow(artist: Artist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
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
private fun AlbumResultCard(album: Album, onClick: () -> Unit) {
    Column(modifier = Modifier.width(120.dp)) {
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
