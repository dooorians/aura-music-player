package com.ikko.aura.ui.screens.search

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmLibrary
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.listTopBlur
import com.ikko.aura.ui.components.rememberPlayerAccent
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.RemoteSection
import com.ikko.aura.viewmodel.SearchFilter
import com.ikko.aura.viewmodel.SearchScope
import com.ikko.aura.viewmodel.SearchSource
import com.ikko.aura.viewmodel.SearchViewModel
import kotlin.math.roundToInt

private val Morph = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
private val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/**
 * The one clock of the search's own movement, in milliseconds since it opened (or since it started to close): every
 * element reads its place on it, with the delay and duration the board gives it, so that nothing is tuned by eye.
 */
private class SearchTimeline(private val clock: Animatable<Float, *>, val closing: Boolean) {
    /** 0..1 over [dur] ms from [delay] ms, eased by [ease]. */
    fun t(delay: Float, dur: Float, ease: androidx.compose.animation.core.Easing = LinearEasing): Float =
        ease.transform(((clock.value - delay) / dur).coerceIn(0f, 1f))
}

@Composable
fun SearchPanel(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the search is on its way out: the board's close is not the open played backwards. */
    closing: Boolean = false,
    /** How far the field has travelled, 0..1 (440 ms out, 380 back, on the board's curve). */
    progressProvider: () -> Float = { 1f },
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val ytm by searchViewModel.ytmState.collectAsStateWithLifecycle()
    val remote by searchViewModel.remoteState.collectAsStateWithLifecycle()
    val unifiedRemote by searchViewModel.unifiedRemoteState.collectAsStateWithLifecycle()
    val unifiedHasMore by searchViewModel.unifiedHasMore.collectAsStateWithLifecycle()
    val unifiedLoadingMore by searchViewModel.unifiedLoadingMore.collectAsStateWithLifecycle()
    val sources by searchViewModel.sources.collectAsStateWithLifecycle()
    val recents by searchViewModel.recents.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(SearchScope.ALL) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(closing) {
        if (closing) focusManager.clearFocus() else focusRequester.requestFocus()
    }
    val u = designUnit()
    val accent = rememberPlayerAccent()

    val clock = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        clock.snapTo(0f)
        clock.animateTo(900f, tween(900, easing = LinearEasing))
    }
    val timeline = remember(closing) { SearchTimeline(clock, closing) }

    val showLocal = scope != SearchScope.ONLINE
    val showOnline = scope != SearchScope.LOCAL

    // A search the listener acted on is one worth coming back to: remembered on leaving a result.
    val leave: () -> Unit = {
        searchViewModel.rememberQuery(query)
        onNavigate()
    }

    // What the results do when they arrive: a new set (the first letters typed, a change of scope) comes in one item
    // after the other, 55 ms apart, after 180 ms (100 for a change of scope); a set that is scrolled is just there.
    var resultsEpoch by remember { mutableIntStateOf(0) }
    var resultsEpochAt by remember { mutableStateOf(0L) }
    var resultsBaseDelay by remember { mutableIntStateOf(180) }
    fun newResults(delay: Int) {
        resultsBaseDelay = delay
        resultsEpochAt = SystemClock.uptimeMillis()
        resultsEpoch++
    }
    val resultsOn = query.isNotBlank()
    LaunchedEffect(resultsOn) { if (resultsOn) newResults(180) }
    var lastScope by remember { mutableStateOf(scope) }
    LaunchedEffect(scope) {
        if (scope != lastScope) {
            lastScope = scope
            if (resultsOn) newResults(100)
        }
    }

    val tracksOf = com.ikko.aura.viewmodel.rememberAlbumTracks()
    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = u * 40f, end = u * 40f, top = u * 96f),
            ) {
                // What the bar says while empty depends on where it looks.
                val onlineNames = sources.filter { it.enabled }.joinToString(", ") { it.name }
                val placeholder = when (scope) {
                    SearchScope.LOCAL -> "Dans ta bibliothèque"
                    SearchScope.ONLINE -> if (onlineNames.isEmpty()) "Rechercher en ligne" else "Rechercher sur $onlineNames"
                    SearchScope.ALL -> "Titres, albums, artistes"
                }
                SearchField(
                    query = query,
                    onQuery = { query = it; searchViewModel.setQuery(it) },
                    onClose = onNavigate,
                    progressProvider = progressProvider,
                    timeline = timeline,
                    placeholder = placeholder,
                    focusRequester = focusRequester,
                    accent = accent,
                )

                // Where to look, and the sources.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = u * 24f).settle(timeline, 0),
                    horizontalArrangement = Arrangement.spacedBy(u * 16f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScopeSelector(scope = scope, onScope = { scope = it }, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(u * 84f).clip(CircleShape).background(Color(0xFF15151B)).clickable { sheetOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CupertinoIcons.Gear, "Sources de recherche", tint = Color.White, modifier = Modifier.size(u * 42f))
                    }
                }

                // Filters by kind when the library is searched; the sources in use when the search goes online. The one
                // set leaves in 110 ms, the other comes in 90 ms later, 10 px up, in 280.
                Box(Modifier.fillMaxWidth().padding(top = u * 24f).height(u * 76f).settle(timeline, 1)) {
                    AnimatedContent(
                        targetState = scope == SearchScope.ONLINE,
                        transitionSpec = {
                            (fadeIn(tween(280, delayMillis = 90, easing = EaseOut)) +
                                slideInVertically(tween(280, delayMillis = 90, easing = EaseOut)) { with(androidx.compose.ui.unit.Density(1f)) { 10 } }) togetherWith
                                fadeOut(tween(110, easing = EaseIn))
                        },
                        label = "search-chips",
                    ) { online ->
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(u * 16f),
                        ) {
                            if (online) {
                                sources.forEach { src ->
                                    SourceChip(src.name, src.enabled, accent) { searchViewModel.toggleSource(src.id, !src.enabled) }
                                }
                            } else {
                                SearchFilterChip("Tout", uiState.filter == SearchFilter.ALL, accent) { searchViewModel.setFilter(SearchFilter.ALL) }
                                SearchFilterChip("Titres", uiState.filter == SearchFilter.TRACKS, accent) { searchViewModel.setFilter(SearchFilter.TRACKS) }
                                SearchFilterChip("Albums", uiState.filter == SearchFilter.ALBUMS, accent) { searchViewModel.setFilter(SearchFilter.ALBUMS) }
                                SearchFilterChip("Artistes", uiState.filter == SearchFilter.ARTISTS, accent) { searchViewModel.setFilter(SearchFilter.ARTISTS) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(u * 24f))

                val localHas = showLocal && uiState.hasResults
                val onlineHas = showOnline && (ytm.active || remote.any { it.hasContent } || unifiedRemote.hasContent)
                val searchState = when {
                    query.isBlank() -> SearchContent.PROMPT
                    !localHas && !onlineHas -> SearchContent.NO_RESULTS
                    else -> SearchContent.RESULTS
                }

                // The recent searches leave in 140 ms (accelerating) at the first letter; what comes has its own entrance.
                AnimatedContent(
                    targetState = searchState,
                    transitionSpec = { fadeIn(tween(1)) togetherWith fadeOut(tween(140, easing = EaseIn)) },
                    label = "search-state",
                ) { state ->
                    when (state) {
                        SearchContent.PROMPT -> if (recents.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize().settle(timeline, 2),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    CupertinoIcons.Search, null,
                                    modifier = Modifier.size(u * 120f).padding(bottom = u * 32f),
                                    tint = AuraColors.TextSecondary,
                                )
                                Text(
                                    text = "Qu'est-ce qu'on écoute ?",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuraColors.TextPrimary,
                                )
                                Text(
                                    text = "Recherche dans ta bibliothèque et en ligne",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AuraColors.TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        } else {
                            // Scrolls: with the keyboard up there is room for a line or two only.
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = u * 8f, bottom = u * 12f).settle(timeline, 2),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "Recherches récentes", color = Color(0xFF7F7F89), fontFamily = AuraFontFamily,
                                        fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f),
                                    )
                                    Text(
                                        "Effacer", color = accent, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
                                        fontSize = designSp(32f), lineHeight = designSp(40f),
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                                        ) { searchViewModel.clearRecents() },
                                    )
                                }
                                recents.forEachIndexed { idx, recent ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(u * 114f)
                                            .settle(timeline, 3 + idx)
                                            .clickable { query = recent; searchViewModel.setQuery(recent) },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(u * 28f),
                                    ) {
                                        Icon(CupertinoIcons.ClockFilled, null, tint = Color(0xFF7F7F89), modifier = Modifier.size(u * 44f))
                                        Text(
                                            recent, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
                                            fontSize = designSp(38f), lineHeight = designSp(46f), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                        )
                                        Icon(CupertinoIcons.ChevronRight, null, tint = Color(0xFF7F7F89), modifier = Modifier.size(u * 40f))
                                    }
                                }
                            }
                        }
                        SearchContent.NO_RESULTS -> Column(
                            modifier = Modifier.fillMaxSize().resultRise(0, resultsEpoch, resultsEpochAt, resultsBaseDelay),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                CupertinoIcons.Search, null,
                                modifier = Modifier.size(u * 120f).padding(bottom = u * 32f),
                                tint = AuraColors.TextSecondary.copy(alpha = 0.5f),
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
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        SearchContent.RESULTS -> {
                            @Composable fun Modifier.rise(n: Int) = resultRise(n, resultsEpoch, resultsEpochAt, resultsBaseDelay)
                            val keepIcon: @Composable (Track) -> (@Composable () -> Unit) = { track ->
                                {
                                    val kept = track.id in ytm.saved
                                    Box(
                                        Modifier
                                            .size(u * 76f)
                                            .clip(CircleShape)
                                            .background(Color.Transparent)
                                            .clickable { if (kept) searchViewModel.removeYtm(track) else searchViewModel.saveYtm(track) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (kept) CupertinoIcons.Check else CupertinoIcons.ArrowDownCircle,
                                            contentDescription = if (kept) "Retirer de la bibliothèque" else "Ajouter à la bibliothèque",
                                            tint = if (kept) accent else Color.White,
                                            modifier = Modifier.size(u * 44f),
                                        )
                                    }
                                }
                            }
                            // One block per source, drawn in the order of the priority the user set (Réglages).
                            val localBlock: LazyListScope.() -> Unit = {
                                if (showLocal && showOnline && uiState.hasResults) {
                                    item(key = "group_local", contentType = "group_label") { GroupLabel("Dans ta bibliothèque", Modifier.rise(0)) }
                                }
                                if (uiState.artists.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ARTISTS)) {
                                    itemsIndexed(uiState.artists, key = { _, a -> "artist_${a.id}" }, contentType = { _, _ -> "artist" }) { n, artist ->
                                        SearchRow(
                                            modifier = Modifier.animateItem().rise(n),
                                            title = artist.name, subtitle = "Artiste", query = query, accent = accent,
                                            cover = { InitialCover(artist.name) },
                                            onClick = {
                                                leave()
                                                navController.navigate(AuraDestination.ArtistPattern.route(artist.id.toString()))
                                            },
                                        )
                                    }
                                }
                                if (uiState.albums.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ALBUMS)) {
                                    itemsIndexed(uiState.albums, key = { _, a -> "album_${a.id}" }, contentType = { _, _ -> "album" }) { n, album ->
                                        SearchRow(
                                            modifier = Modifier.animateItem().rise(n + uiState.artists.size.coerceAtMost(3)),
                                            title = album.title, subtitle = "Album · ${album.artist}", query = query, accent = accent,
                                            cover = { RowCover(album.artworkUri, album.id.toString(), 30f) },
                                            onClick = {
                                                leave()
                                                navController.navigate(AuraDestination.AlbumPattern.route(album.id.toString()))
                                            },
                                            onLongClick = { menu.show(LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) },
                                        )
                                    }
                                }
                                if (uiState.tracks.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                                    itemsIndexed(uiState.tracks, key = { _, t -> "track_${t.id}" }, contentType = { _, _ -> "track" }) { index, track ->
                                        SearchRow(
                                            modifier = Modifier.animateItem().rise(index),
                                            title = track.title, subtitle = "Titre · ${track.artist}", query = query, accent = accent,
                                            cover = { RowCover(track.artworkUri, track.albumId.toString(), 22f) },
                                            onClick = {
                                                leave()
                                                playerViewModel.playQueue(uiState.tracks, index)
                                                navController.navigate(AuraDestination.NowPlaying.route)
                                            },
                                            onLongClick = { menu.show(track) },
                                        )
                                    }
                                }
                            }
                            val remoteBlock: LazyListScope.(RemoteSection) -> Unit = { section ->
                                if (section.artists.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ARTISTS)) {
                                    itemsIndexed(section.artists.take(5), key = { _, n -> "rs_artist_${section.sourceId}_$n" }, contentType = { _, _ -> "remote_artist" }) { n, name ->
                                        SearchRow(
                                            modifier = Modifier.rise(n),
                                            title = name, subtitle = "Artiste", query = query, accent = accent,
                                            cover = { InitialCover(name) },
                                            onClick = {
                                                leave()
                                                navController.navigate(AuraDestination.RemoteArtistPattern.route(section.sourceId, name))
                                            },
                                        )
                                    }
                                }
                                if (section.albums.isNotEmpty() && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.ALBUMS)) {
                                    itemsIndexed(section.albums, key = { _, a -> "rs_album_${a.id}" }, contentType = { _, _ -> "remote_album" }) { n, album: YtmAlbumRef ->
                                        SearchRow(
                                            modifier = Modifier.rise(n),
                                            title = album.title, subtitle = "Album · ${album.artist}", query = query, accent = accent,
                                            cover = { RowCover(album.thumbnailUrl?.let(android.net.Uri::parse), "ytmalbum:${album.id}", 30f) },
                                            onClick = {
                                                leave()
                                                navController.navigate(streamedAlbumRoute(album.id, online = true))
                                            },
                                            onLongClick = {
                                                menu.show(LibraryContextTarget.RemoteAlbumTarget(album.id, album.title, album.artist, album.thumbnailUrl))
                                            },
                                        )
                                    }
                                }
                                if (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS) {
                                    if (section.loading) item(key = "rs_loading_${section.sourceId}", contentType = "note") { YtmNote("Recherche…") }
                                    if (section.error) item(key = "rs_error_${section.sourceId}", contentType = "note") {
                                        YtmNote(if (section.sourceId == "unified") "Aucune source ne répond. Es-tu hors ligne ?" else "${section.name} ne répond pas")
                                    }
                                    itemsIndexed(section.songs, key = { _, track -> "rs_song_${track.id}" }, contentType = { _, _ -> "remote_song" }) { index, track ->
                                        SearchRow(
                                            modifier = Modifier.animateItem().rise(index),
                                            title = track.title,
                                            subtitle = "Titre · ${track.artist} · ${sourceLabelOf(track, sources)}",
                                            query = query, accent = accent,
                                            cover = { RowCover(track.artworkUri, track.albumId.toString(), 22f) },
                                            onClick = {
                                                leave()
                                                playerViewModel.playQueue(section.songs, index)
                                                navController.navigate(AuraDestination.NowPlaying.route)
                                            },
                                            onLongClick = { menu.show(track) },
                                            trailing = keepIcon(track),
                                        )
                                    }
                                }
                            }
                            val resultsState = rememberLazyListState()
                            LazyColumn(
                                state = resultsState,
                                modifier = Modifier.fillMaxWidth().listTopBlur(resultsState),
                                contentPadding = PaddingValues(bottom = LocalPlayerPillHeight.current + 12.dp),
                            ) {
                                if (showLocal) localBlock()
                                if (showOnline) {
                                    if (showLocal && (unifiedRemote.hasContent || ytm.active)) {
                                        item(key = "group_online", contentType = "group_label") { GroupLabel("En ligne", Modifier.rise(0)) }
                                    }
                                    remoteBlock(unifiedRemote)
                                }
                                if (showOnline && unifiedHasMore && (uiState.filter == SearchFilter.ALL || uiState.filter == SearchFilter.TRACKS)) {
                                    item(key = "more", contentType = "more") {
                                        Text(
                                            text = if (unifiedLoadingMore) "Chargement…" else "Afficher plus de résultats",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = AuraColors.TextPrimary,
                                            modifier = Modifier
                                                .padding(vertical = 10.dp)
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
            SourcesSheet(
                open = sheetOpen,
                sources = sources,
                accent = accent,
                onToggle = searchViewModel::toggleSource,
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

private enum class SearchContent { PROMPT, NO_RESULTS, RESULTS }

/** Which source a streamed result came from, read from the id (`jiosaavn:…`, `ytm:…`, an added source's own prefix). */
private fun sourceLabelOf(track: Track, sources: List<SearchSource>): String {
    val prefix = track.id.substringBefore(':', "")
    return when {
        prefix.isEmpty() -> "Bibliothèque"
        prefix == "jiosaavn" -> "JioSaavn"
        prefix == "ytm" -> "YouTube Music"
        else -> sources.firstOrNull { it.id == prefix }?.name ?: if (YtmLibrary.isStreamed(track)) "En ligne" else "Bibliothèque"
    }
}

/** One result: a cover, the name with the letters typed in the accent, and what it is. */
@Composable
private fun SearchRow(
    title: String,
    subtitle: String,
    query: String,
    accent: Color,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val u = designUnit()
    val highlighted = remember(title, query, accent) { highlight(title, query, accent) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(u * 124f)
            .then(
                if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickableCompat(onClick, onLongClick),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 26f),
    ) {
        Box(Modifier.size(u * 108f)) { cover() }
        Column(Modifier.weight(1f)) {
            Text(
                text = highlighted, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = designSp(40f), lineHeight = designSp(48f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle, color = Color(0xFF8B8B95), fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold,
                fontSize = designSp(32f), lineHeight = designSp(40f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun highlight(text: String, query: String, accent: Color) = buildAnnotatedString {
    val q = query.trim()
    val i = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (i < 0) {
        append(text)
    } else {
        append(text.substring(0, i))
        withStyle(SpanStyle(color = accent)) { append(text.substring(i, i + q.length)) }
        append(text.substring(i + q.length))
    }
}

@Composable
private fun RowCover(uri: android.net.Uri?, cacheKey: String, radiusDesignPx: Float) {
    val u = designUnit()
    AlbumArt(
        uri = uri,
        contentDescription = null,
        shape = RoundedCornerShape(u * radiusDesignPx),
        modifier = Modifier.fillMaxSize(),
        targetSizeDp = u * 108f,
        cacheKey = cacheKey,
    )
}

@Composable
private fun InitialCover(name: String) {
    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF1F1F27)), contentAlignment = Alignment.Center) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            color = Color(0xFFC4C4CD), fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
            fontSize = designSp(44f),
        )
        com.ikko.aura.data.rememberArtistImage(name)?.let { photo ->
            com.ikko.aura.ui.components.AlbumArt(
                uri = photo, contentDescription = name, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                modifier = Modifier.fillMaxSize(), targetSizeDp = 64.dp, cacheKey = photo.toString(),
            )
        }
    }
}

/** A chip of the design: 76 px tall, a pale pill; lit in the accent when it is the one in use. */
@Composable
private fun SearchFilterChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val u = designUnit()
    // The fill is read while drawing: its 200 ms fade redraws the chip, it does not recompose it.
    val bg = animateColorAsState(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f), tween(200), label = "chip-bg")
    val fg by animateColorAsState(if (selected) accent else Color(0xFFE6E6EC), tween(200), label = "chip-fg")
    Box(
        modifier = Modifier
            .height(u * 76f)
            .clip(CircleShape)
            .drawBehind { drawRect(bg.value) }
            .clickable(onClick = onClick)
            .padding(horizontal = u * 34f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f), maxLines = 1)
    }
}

/** A source in the online filter row: a coloured dot says whether it is searched (200 ms). */
@Composable
private fun SourceChip(name: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val u = designUnit()
    val bg = animateColorAsState(if (on) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f), tween(200), label = "src-bg")
    val fg by animateColorAsState(if (on) accent else Color(0xFF8E93A1), tween(200), label = "src-fg")
    Row(
        modifier = Modifier
            .height(u * 76f)
            .clip(CircleShape)
            .drawBehind { drawRect(bg.value) }
            .clickable(onClick = onClick)
            .padding(horizontal = u * 30f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 14f),
    ) {
        Box(Modifier.size(u * 14f).clip(CircleShape).background(fg))
        Text(name, color = fg, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f), maxLines = 1)
    }
}

/** Local / En ligne / Tout, with a thumb that slides under the chosen one (300 ms). */
@Composable
private fun ScopeSelector(scope: SearchScope, onScope: (SearchScope) -> Unit, modifier: Modifier = Modifier) {
    val u = designUnit()
    val options = SCOPE_OPTIONS
    // Which third the thumb is over, animated: the same 300 ms as the thumb's offset it stands for (u * 8 + cell * slot).
    val slot = animateFloatAsState(options.indexOfFirst { it.first == scope }.toFloat(), tween(300, easing = Morph), label = "scope-thumb")
    // A plain Box: the cell's width is worked out while measuring the thumb, not by subcomposing the whole selector.
    Box(
        modifier = modifier.height(u * 84f).clip(CircleShape).background(Color(0xFF15151B)),
    ) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val cell = (constraints.maxWidth.toDp() - u * 16f) / 3
                    // Modifier.size(cell, u * 68f), held inside what the selector allows.
                    val w = cell.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                    val h = (u * 68f).roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                    layout(placeable.width, placeable.height) {
                        // The slide is a layer property: a frame of it moves the thumb without measuring anything again.
                        placeable.placeWithLayer(0, 0) {
                            translationX = (u * 8f).toPx() + cell.toPx() * slot.value
                            translationY = (u * 8f).toPx()
                        }
                    }
                }
                .clip(CircleShape)
                .background(Color(0xFF30303C)),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = u * 8f)) {
            options.forEach { (value, label) ->
                val fg by animateColorAsState(if (value == scope) Color.White else Color(0xFF8E93A1), tween(200), label = "scope-fg")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onScope(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = fg, fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f), maxLines = 1)
                }
            }
        }
    }
}

/**
 * The search field. It starts where the header's field is (18 px lower, narrower by the avatar and its gap) and grows
 * into the full width in 440 ms; its colour goes up a tone. The magnifier turns away (-80°, half size, 240 ms from 60)
 * as the back arrow turns in (300 ms from 140); "Rechercher" gives way to what the bar says once open. On the way out:
 * the arrow leaves in 200 ms, the magnifier comes back from 120 ms, and the field is back in 380.
 */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    progressProvider: () -> Float,
    timeline: SearchTimeline,
    placeholder: String,
    focusRequester: FocusRequester,
    accent: Color,
) {
    val u = designUnit()
    val unitPx = with(LocalDensity.current) { u.toPx() }
    val closing = timeline.closing
    val pillClipPath = remember { Path() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(u * 112f)
            .graphicsLayer {
                val p = progressProvider().coerceIn(0f, 1f)
                translationY = (1f - p) * 18f * unitPx
            }
            .drawWithContent {
                val p = progressProvider().coerceIn(0f, 1f)
                val w = (size.width - (1f - p) * 136f * unitPx).coerceAtLeast(size.height)
                val r = size.height * 0.5f
                pillClipPath.reset()
                pillClipPath.addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = w,
                        bottom = size.height,
                        cornerRadius = CornerRadius(r, r),
                    ),
                )
                clipPath(pillClipPath) {
                    drawRect(
                        color = lerp(Color(0xFF1A1A21), Color(0xFF272731), p),
                        size = Size(w, size.height),
                    )
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = u * 24f)
                .size(u * 80f),
            contentAlignment = Alignment.Center,
        ) {
            // The loupe stays as it is (no arrow takes its place): the system's back closes the search.
            Icon(CupertinoIcons.Search, null, tint = Color(0xFF7F7F89), modifier = Modifier.size(u * 58f))
        }
        Box(Modifier.align(Alignment.CenterStart).padding(start = u * 124f, end = u * 100f)) {
            val style = TextStyle(
                fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
                fontSize = designSp(44f), lineHeight = designSp(52f), color = Color.White,
            )
            val hint = style.copy(fontWeight = FontWeight.SemiBold, color = Color(0xFF7F7F89))
            if (query.isEmpty()) {
                // "Rechercher" (the header's word) while the field is still the header's; then what the bar says.
                Text(
                    "Rechercher", style = hint, maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        alpha = if (closing) timeline.t(140f, 240f) else 1f - timeline.t(0f, 160f)
                    },
                )
                AnimatedContent(
                    targetState = placeholder,
                    transitionSpec = { fadeIn(tween(180, delayMillis = 100)) togetherWith fadeOut(tween(90)) },
                    label = "search-placeholder",
                    modifier = Modifier.graphicsLayer {
                        alpha = if (closing) 1f - timeline.t(0f, 120f) else timeline.t(200f, 240f)
                    },
                ) { text -> Text(text, style = hint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        // The clear cross comes in 200 ms (after 60) with the first letter.
        val clearAlpha = remember { Animatable(0f) }
        LaunchedEffect(query.isNotEmpty()) {
            if (query.isNotEmpty()) {
                delay(60)
                clearAlpha.animateTo(1f, tween(200))
            } else {
                clearAlpha.animateTo(0f, tween(120))
            }
        }
        if (query.isNotEmpty() || clearAlpha.value > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = u * 24f)
                    .size(u * 80f)
                    .graphicsLayer {
                        val p = progressProvider().coerceIn(0f, 1f)
                        translationX = -(1f - p) * 136f * unitPx
                        alpha = clearAlpha.value
                    }
                    .clip(CircleShape)
                    .clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Close, "Effacer", tint = Color.White, modifier = Modifier.size(u * 46f))
            }
        }
    }
}

/**
 * The sheet of sources, rising from the bottom: the scrim comes in 300 ms, the panel in 440 (decelerating), and its
 * lines rise 16 px, 45 ms apart from 180 ms; going out the scrim goes in 260 (from 60) and the panel in 320. One switch
 * per source. Wi-Fi only is not here: the app has no such setting, and a switch that did nothing would be worse than none.
 */
@Composable
private fun SourcesSheet(
    open: Boolean,
    sources: List<SearchSource>,
    accent: Color,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val u = designUnit()
    val clock = remember { Animatable(0f) }
    val panelP = remember { Animatable(0f) }
    val scrimP = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
            coroutineScope {
                if (panelP.value < 0.05f) clock.snapTo(0f)
                launch { clock.animateTo(900f, tween(900, easing = LinearEasing)) }
                launch { scrimP.animateTo(1f, tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                launch { panelP.animateTo(1f, tween(440, easing = EaseOut)) }
            }
        } else if (shown) {
            coroutineScope {
                launch {
                    delay(40)
                    scrimP.animateTo(0f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
                launch {
                    panelP.animateTo(0f, tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
            }
            if (!open) shown = false
        }
    }
    if (!shown) return
    fun t(delay: Float, dur: Float, ease: androidx.compose.animation.core.Easing = LinearEasing) =
        ease.transform(((clock.value - delay) / dur).coerceIn(0f, 1f))

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = scrimP.value
            }
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = size.height * (1f - panelP.value)
                }
                .clip(RoundedCornerShape(topStart = u * 52f, topEnd = u * 52f))
                .background(Color(0xFF17171E))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = u * 40f, end = u * 40f, top = u * 40f, bottom = u * 64f),
        ) {
            Column(Modifier.graphicsLayer { rise(::t, 0, u.toPx()) }) {
                Text(
                    "Sources de recherche", color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                    fontSize = designSp(44f), lineHeight = designSp(52f),
                )
                Text(
                    "Choisis où chercher", color = Color(0xFF8B8B95), fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = designSp(30f), lineHeight = designSp(38f),
                )
            }
            Spacer(Modifier.height(u * 24f))
            sources.forEachIndexed { i, src ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(u * 112f)
                        .graphicsLayer { rise(::t, 1 + i, u.toPx()) }
                        .clickable { onToggle(src.id, !src.enabled) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(u * 26f),
                ) {
                    Text(
                        src.name, color = Color.White, fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                        fontSize = designSp(38f), lineHeight = designSp(46f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SourceSwitch(src.enabled, accent)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.rise(
    t: (Float, Float, androidx.compose.animation.core.Easing) -> Float,
    index: Int,
    unitPx: Float,
) {
    val p = t(180f + index * 45f, 320f, EaseOut)
    alpha = p
    translationY = 16f * unitPx * (1f - p)
}

/** The design's switch: a 112 x 64 pill, a 48 px knob that slides over in 220 ms. */
@Composable
private fun SourceSwitch(on: Boolean, accent: Color) {
    val u = designUnit()
    // Both colours read while drawing, like the knob's slide: the switch's 220 ms turn recomposes nothing.
    val bg = animateColorAsState(if (on) accent else Color(0xFF3A3A46), tween(220), label = "sw-bg")
    val knob = animateColorAsState(if (on) Color(0xFF241810) else Color(0xFF9A9AA6), tween(220), label = "sw-knob")
    val x by animateDpAsState(if (on) u * 56f else u * 8f, tween(220, easing = Morph), label = "sw-x")
    Box(Modifier.size(u * 112f, u * 64f).clip(CircleShape).drawBehind { drawRect(bg.value) }) {
        Box(
            Modifier
                .graphicsLayer {
                    translationX = x.toPx()
                    translationY = (u * 8f).toPx()
                }
                .size(u * 48f)
                .clip(CircleShape)
                .drawBehind { drawRect(knob.value) },
        )
    }
}

/**
 * An item that comes in once the search is open: it rises 20 px and appears in 360 ms (decelerating), [index] steps of
 * 40 ms after 160 ms; going out it leaves in 120 ms, 10 px down, all together.
 */
private fun Modifier.settle(timeline: SearchTimeline, index: Int): Modifier = this.graphicsLayer {
    val unit = density * 0.4f
    if (timeline.closing) {
        val t = timeline.t(0f, 120f, EaseIn)
        alpha = 1f - t
        translationY = 10f * unit * t
    } else {
        val t = timeline.t(160f + 40f * index, 360f, EaseOut)
        alpha = t
        translationY = 20f * unit * (1f - t)
    }
}

/**
 * A result of a new set: it rises 20 px and appears in 360 ms, 55 ms after the one before it, after [baseDelay] ms. Only
 * the first ten, and only while the set is new: one scrolled into view later is simply there.
 */
@Composable
private fun Modifier.resultRise(index: Int, epoch: Int, epochAt: Long, baseDelay: Int): Modifier {
    if (index >= 10) return this
    val fresh = remember(epoch) { SystemClock.uptimeMillis() - epochAt < 900L }
    val p = remember(epoch) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(epoch) {
        if (fresh) {
            kotlinx.coroutines.delay(baseDelay + index * 55L)
            p.animateTo(1f, tween(360, easing = EaseOut))
        }
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = 20f * density * 0.4f * (1f - p.value)
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color(0xFF7F7F89),
        fontFamily = AuraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = designSp(32f),
        lineHeight = designSp(40f),
        modifier = modifier.padding(vertical = 8.dp).padding(top = 8.dp),
    )
}

@Composable
private fun YtmNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AuraColors.TextSecondary,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

/** The three scopes of the selector, made once rather than on every recomposition of it. */
private val SCOPE_OPTIONS = listOf(SearchScope.LOCAL to "Local", SearchScope.ONLINE to "En ligne", SearchScope.ALL to "Tout")
