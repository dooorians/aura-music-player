package com.ikko.aura.ui.screens.library

import com.ikko.aura.ui.theme.AuraMotion
import androidx.compose.animation.togetherWith
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.MediaContextHost
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import com.ikko.aura.ui.components.AlphabetIndex
import com.ikko.aura.ui.components.ScrollToTopButton
import com.ikko.aura.ui.components.indexLetterOf
import com.ikko.aura.ui.components.rememberScrollTarget
import com.ikko.aura.ui.components.ScrollTarget
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistSummary
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.collapsingHeight
import com.ikko.aura.ui.components.rememberCollapseOffset
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.theme.PhosphorIcons
import com.ikko.aura.viewmodel.LibraryFilter
import com.ikko.aura.viewmodel.LibrarySort
import com.ikko.aura.viewmodel.LibraryViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.PlaylistsViewModel
import kotlin.math.roundToInt

private val LIBRARY_HEADER_HEIGHT = 126.dp

@Composable
fun LibraryScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val headerHeightPx = with(density) { LIBRARY_HEADER_HEIGHT.toPx() }
    var headerOffsetPx by rememberCollapseOffset()
    // Same rule as the top nav row: collapse while scrolling down, and only come back once the
    // list has run out of upward scroll (leftover in onPostScroll), so the header stays out of the
    // way until you are actually back at the top rather than reappearing on the first reversal.
    val headerScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtLeast(-headerHeightPx)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceAtMost(0f)
                val used = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, used)
            }
        }
    }

    val scope = rememberCoroutineScope()

    /**
     * Puts the header where the new scroll position says it should be, after a jump the nested
     * scroll never saw. At the top the header belongs open; anywhere else it belongs out of the
     * way, which is the whole reason the strip and the back-to-top button exist.
     */
    fun settleHeaderAfterJump(atTop: Boolean) {
        headerOffsetPx = if (atTop) 0f else -headerHeightPx
    }

    MediaContextHost(
        navController = navController,
        playerViewModel = playerViewModel,
        onLibraryChanged = libraryViewModel::rescan,
    ) { context ->
    GradientScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .collapsingHeight(headerHeightPx) { headerOffsetPx }
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .offset { IntOffset(0, headerOffsetPx.roundToInt()) },
            ) {
                LibraryHeader(
                    currentSort = uiState.sort,
                    onSortSelected = libraryViewModel::setSort,
                    onSmartShuffle = {
                        scope.launch {
                            val queue = libraryViewModel.smartShuffle()
                            if (queue.isNotEmpty()) {
                                playerViewModel.playQueue(queue, 0)
                                navController.navigate(AuraDestination.NowPlaying.route)
                            }
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                FilterChipsRow(
                    selected = uiState.filter,
                    onSelected = libraryViewModel::setFilter,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(headerScrollConnection),
        ) {
            // The four filters are peers, exactly like the top tabs, so they animate with the
            // same vocabulary — switching them used to swap content instantly with nothing at all.
            val scanning = uiState.isScanning && uiState.tracks.isEmpty()
            AnimatedContent(
                targetState = if (scanning) null else uiState.filter,
                transitionSpec = { AuraMotion.peerEnter togetherWith AuraMotion.peerExit },
                label = "library-filter",
            ) { filter ->
                when (filter) {
                    null -> ScanningState()
                    LibraryFilter.TRACKS -> TrackList(
                        tracks = uiState.tracks,
                        sort = uiState.sort,
                        onTrackClick = { index ->
                            playerViewModel.playQueue(uiState.tracks, index)
                            navController.navigate(AuraDestination.NowPlaying.route)
                        },
                        onTrackLongPress = { context.show(it) },
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.ALBUMS -> AlbumGrid(
                        albums = uiState.albums,
                        sort = uiState.sort,
                        navController = navController,
                        onAlbumLongPress = { album ->
                            context.show(
                                LibraryContextTarget.AlbumTarget(
                                    album = album,
                                    tracks = uiState.tracks.filter { it.albumId == album.id },
                                ),
                            )
                        },
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.ARTISTS -> ArtistList(
                        artists = uiState.artists,
                        navController = navController,
                        onJumped = ::settleHeaderAfterJump,
                    )
                    LibraryFilter.PLAYLISTS -> PlaylistsList(
                        playlists = playlists,
                        onCreate = playlistsViewModel::createPlaylist,
                        onOpen = { id -> navController.navigate(AuraDestination.PlaylistPattern.route(id.toString())) },
                    )
                }
            }
        }
    }
    }
}

// Must match HomeScreen's SECTION_HEADER_HEIGHT so "Library" and "Favoris" sit at the same
// height when switching tabs, regardless of the taller IconButton in this row's trailing slot.
private val LIBRARY_TITLE_ROW_HEIGHT = 56.dp

@Composable
private fun LibraryHeader(
    currentSort: LibrarySort,
    onSortSelected: (LibrarySort) -> Unit,
    onSmartShuffle: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LIBRARY_TITLE_ROW_HEIGHT)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Bibliothèque",
            style = MaterialTheme.typography.headlineLarge,
            color = AuraColors.TextPrimary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
        // Not the plain shuffle: this one sequences the set so consecutive tracks are ones
        // Automix can actually mix — see SmartShuffle.
        IconButton(onClick = onSmartShuffle, modifier = Modifier.size(52.dp)) {
            Icon(
                PhosphorIcons.Shuffle,
                contentDescription = "Aléatoire intelligent",
                tint = AuraColors.TextPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(52.dp)) {
                Icon(
                    PhosphorIcons.Sort,
                    contentDescription = "Trier",
                    tint = AuraColors.TextPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Nom") }, onClick = { onSortSelected(LibrarySort.NAME); menuExpanded = false })
                DropdownMenuItem(text = { Text("Artiste") }, onClick = { onSortSelected(LibrarySort.ARTIST); menuExpanded = false })
                DropdownMenuItem(text = { Text("Date d'ajout") }, onClick = { onSortSelected(LibrarySort.DATE_ADDED); menuExpanded = false })
            }
        }
        }
    }
}


@Composable
private fun FilterChipsRow(selected: LibraryFilter, onSelected: (LibraryFilter) -> Unit) {
    val filters = listOf(
        LibraryFilter.PLAYLISTS to "Playlists",
        LibraryFilter.ALBUMS to "Albums",
        LibraryFilter.ARTISTS to "Artistes",
        LibraryFilter.TRACKS to "Titres",
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(filters, key = { it.first }) { (filter, label) ->
            val isSelected = selected == filter
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(filter) },
                label = { Text(label, style = MaterialTheme.typography.titleMedium) },
                shape = AuraShapes.Pill,
                border = null,
                modifier = Modifier.height(46.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AuraColors.GlassFill,
                    labelColor = AuraColors.TextSecondary,
                    selectedContainerColor = AuraColors.TextPrimary,
                    selectedLabelColor = AuraColors.Black,
                ),
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    sort: LibrarySort,
    onTrackClick: (Int) -> Unit,
    onTrackLongPress: (Track) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    IndexedList(
        target = rememberScrollTarget(gridState),
        indexKeys = tracks.indexKeysFor(sort) { it.title to it.artist },
        onJumped = onJumped,
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = INDEX_GUTTER, top = 8.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(index) },
                    onLongClick = { onTrackLongPress(track) },
                    artSize = 52.dp,
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    sort: LibrarySort,
    navController: NavHostController,
    onAlbumLongPress: (Album) -> Unit,
    onJumped: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    IndexedList(
        target = rememberScrollTarget(gridState),
        onJumped = onJumped,
        // Albums are always ordered alphabetically — by title, or by artist when that is what
        // was chosen — so unlike the track list they always have letters to jump to.
        indexKeys = remember(albums, sort) {
            albums.map { if (sort == LibrarySort.ARTIST) it.artist else it.title }
        },
    ) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = INDEX_GUTTER, top = 12.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Column {
                SquircleCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    onClick = { navController.navigate(AuraDestination.AlbumPattern.route(album.id.toString())) },
                    onLongClick = { onAlbumLongPress(album) },
                ) {
                    AlbumArt(
                        uri = album.artworkUri,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize(),
                        targetSizeDp = 180.dp,
                        cacheKey = album.id.toString(),
                    )
                }
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuraColors.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
    }
}

@Composable
private fun ArtistList(
    artists: List<Artist>,
    navController: NavHostController,
    onJumped: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    IndexedList(
        target = rememberScrollTarget(listState),
        indexKeys = remember(artists) { artists.map { it.name } },
        onJumped = onJumped,
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = INDEX_GUTTER, top = 8.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString())) }
                    .padding(vertical = 14.dp),
            ) {
                Column {
                    Text(artist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
                    Text(
                        "${artist.trackCount} ${if (artist.trackCount > 1) "titres" else "titre"} · " +
                            "${artist.albumCount} ${if (artist.albumCount > 1) "albums" else "album"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<PlaylistSummary>,
    onCreate: (String) -> Unit,
    onOpen: (Long) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
    ) {
        item {
            if (creating) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nom de la playlist") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AuraColors.TextPrimary,
                            unfocusedTextColor = AuraColors.TextPrimary,
                            focusedBorderColor = AuraColors.TextPrimary,
                            unfocusedBorderColor = AuraColors.GlassBorder,
                        ),
                    )
                    IconButton(onClick = {
                        onCreate(newName)
                        newName = ""
                        creating = false
                    }) {
                        Icon(PhosphorIcons.Add, contentDescription = "Créer", tint = AuraColors.TextPrimary)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Add, contentDescription = null, tint = AuraColors.TextPrimary)
                    Text(
                        "Nouvelle playlist",
                        style = MaterialTheme.typography.titleMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        if (playlists.isEmpty() && !creating) {
            item {
                Text(
                    text = "Aucune playlist pour le moment",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraColors.TextSecondary,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(playlist.id) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(AuraShapes.SquircleSmall)
                        .background(AuraColors.GlassFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PhosphorIcons.PlaylistMusic, contentDescription = null, tint = AuraColors.TextPrimary)
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextPrimary)
                    Text(
                        "${playlist.trackCount} titre${if (playlist.trackCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AuraColors.TextPrimary)
            Text(
                "Scan de la bibliothèque…",
                style = MaterialTheme.typography.bodyLarge,
                color = AuraColors.TextSecondary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Keeps the index strip clear of the content it scrubs. Everything else keeps its own 16dp.
 */
private val INDEX_GUTTER = 34.dp

/**
 * The names the alphabet strip files this list under, or null when the strip should not be shown
 * at all.
 *
 * Only an alphabetical ordering has letters to jump to: under "date d'ajout" the list is in no
 * relation to the alphabet, and a strip over it would take you somewhere arbitrary and look
 * broken doing it. Under "artiste" it indexes the artist, because that is what the list is
 * ordered by — indexing the title there would be the same lie.
 */
private inline fun <T> List<T>.indexKeysFor(sort: LibrarySort, keys: (T) -> Pair<String, String>): List<String>? =
    when (sort) {
        LibrarySort.NAME -> map { keys(it).first }
        LibrarySort.ARTIST -> map { keys(it).second }
        LibrarySort.DATE_ADDED -> null
    }

/**
 * Wraps a list in the two things that make a long one navigable: PowerAmp's alphabet strip down
 * the right edge, and a way back to the top once scrolling back would be a chore.
 *
 * Both read the scroll position in composition, which is what they are for — but only this
 * wrapper does, so a scroll invalidates this one composable and not the list inside it.
 */
@Composable
private fun IndexedList(
    target: ScrollTarget,
    indexKeys: List<String>?,
    /**
     * Called after a jump that did not come from a finger on the list — the alphabet strip or the
     * back-to-top button. [atTop] says where it landed.
     *
     * The collapsing header is driven by nested scroll, and a programmatic scroll does not
     * produce any: jumping to the Ms left the header fully open above a list scrolled halfway
     * down, and jumping back to the top left it collapsed with nothing above it to collapse for.
     * Both wasted the space the header is supposed to give back.
     */
    onJumped: (atTop: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (indexKeys != null && indexKeys.size >= MIN_ITEMS_FOR_INDEX) {
            val available = remember(indexKeys) { indexKeys.map(::indexLetterOf).toSet() }
            AlphabetIndex(
                available = available,
                onLetterPicked = { letter ->
                    val destination = indexKeys.indexOfFirst { indexLetterOf(it) == letter }
                    if (destination >= 0) {
                        scope.launch { target.jumpTo(destination) }
                        onJumped(destination == 0)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp, top = 4.dp, bottom = LocalPlayerPillHeight.current + 12.dp),
            )
        }

        // Derived rather than read straight: `firstVisibleIndex` and `layoutInfo` are snapshot
        // state that moves on every single frame of a scroll, so reading them here recomposed
        // this whole node — and re-invoked `content()`, the list itself — ninety times a second
        // for a boolean that flips twice in a library. derivedStateOf keeps the reads inside its
        // own observer and only notifies when the answer actually changes.
        val pastHalfway by remember(target) {
            derivedStateOf {
                target.itemCount > 0 && target.firstVisibleIndex > target.itemCount / 2
            }
        }
        AnimatedVisibility(
            visible = pastHalfway,
            enter = fadeIn(tween(AuraMotion.PEER_MS)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(AuraMotion.PEER_MS)) + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalPlayerPillHeight.current + 14.dp),
        ) {
            ScrollToTopButton(
                onClick = {
                    scope.launch { target.animateToTop() }
                    onJumped(true)
                },
            )
        }
    }
}

/** Below this a list fits in a couple of flicks and the strip is only in the way. */
private const val MIN_ITEMS_FOR_INDEX = 25
