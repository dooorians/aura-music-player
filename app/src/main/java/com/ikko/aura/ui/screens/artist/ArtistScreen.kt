package com.ikko.aura.ui.screens.artist

import com.ikko.aura.ui.components.heroPageOut
import com.ikko.aura.ui.components.heroAnchor
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.heroTarget
import com.ikko.aura.ui.components.suppressEdgeBackRipple
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.ikko.aura.ui.components.topScrollPx
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.AuraDestination
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.GradientScreenScaffold
import com.ikko.aura.ui.components.SquircleCard
import com.ikko.aura.ui.components.TrackListItem
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.ui.theme.AuraShapes
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.ArtistUiState
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.navigation.route
import com.ikko.aura.viewmodel.ArtistViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.ButtonTone
import androidx.compose.ui.unit.sp

@Composable
fun ArtistScreen(
    artistId: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    artistViewModel: ArtistViewModel = hiltViewModel(),
) {
    val uiState by artistViewModel.uiState.collectAsStateWithLifecycle()
    ArtistPageContent(uiState, navController, playerViewModel, heroKey = "artist:$artistId")
}

/**
 * The artist page itself, for any artist: a local one from [ArtistScreen], a YouTube Music one from its own screen.
 * One layout, so the two cannot drift apart.
 */
@Composable
fun ArtistPageContent(
    uiState: ArtistUiState,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    heroKey: String? = "artist:page",
    /** The page of a source is still being fetched: it is there at once, its parts as grey placeholders. */
    loading: Boolean = false,
    /** Whether this is the artist's page on the sources (true) or in the library (false): the side of the source switch that is on. */
    online: Boolean = false,
) {
    val links: com.ikko.aura.viewmodel.ArtistLinksViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    // The source switch changes the side shown, in place: this page's own side (the route's), or the other one, which [links] holds.
    val routeState = uiState
    val routeLoading = loading
    var showOnline by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(online) }
    val noOnline = remember { kotlinx.coroutines.flow.MutableStateFlow(com.ikko.aura.viewmodel.YtmArtistState()) }
    val noLocal = remember { kotlinx.coroutines.flow.MutableStateFlow<com.ikko.aura.viewmodel.ArtistUiState?>(null) }
    val otherOnline by (if (showOnline && !online) links.online(routeState.name) else noOnline).collectAsStateWithLifecycle()
    val otherLocal by (if (!showOnline && online) links.local(routeState.name) else noLocal).collectAsStateWithLifecycle()
    val uiState = when {
        showOnline == online -> routeState
        showOnline -> com.ikko.aura.viewmodel.artistUiStateOf(otherOnline).copy(name = routeState.name)
        else -> otherLocal ?: routeState
    }
    val loading = if (showOnline != online && showOnline) otherOnline.loading else routeLoading
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val hero = com.ikko.aura.ui.components.LocalHero.current

    // Not while the player is open over this page: its own back closes the player, and this one would leave the page too
    // (and play the page's return) from under it.
    val sheetCard = com.ikko.aura.LocalSheetCard.current
    androidx.activity.compose.BackHandler(enabled = sheetCard.owner == null || sheetCard.leavingSideways) {
        // Opened from an album's page (its artist), this page has no trip of its own to close: the open one is the album's,
        // and closing it here would hide that page for good.
        val under = navController.previousBackStackEntry?.destination?.route.orEmpty()
        val overHeroPage = listOf("album/", "ytm_album/", "remote_album/", "playlist/").any { under.startsWith(it) }
        if (hero.canClose && !overHeroPage) hero.startClosing()
        navController.popBackStack()
    }

    val nowPlayingId by remember(playerViewModel) {
        playerViewModel.uiState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = playerViewModel.uiState.value.currentTrack?.id,
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbPx = with(density) { 56.dp.roundToPx().coerceAtLeast(1) }
    androidx.compose.runtime.LaunchedEffect(uiState.tracks, thumbPx) {
        uiState.tracks.take(12).forEach { track ->
            com.ikko.aura.ui.components.AlbumArtCache.load(
                context, track.artworkUri, thumbPx, track.albumId.toString(), shrink = true,
            )
        }
    }

    // A page that began as placeholders: what comes in once it has loaded arrives softly, in the places the placeholders held.
    val beganLoading = remember { loading }
    val loadedAt = remember { LongArray(1) }
    if (beganLoading && !loading && loadedAt[0] == 0L) loadedAt[0] = System.nanoTime()
    // As an album page is lit with its cover, this page is lit with the artist's picture: the background and the buttons take its colours.
    val photo = com.ikko.aura.data.rememberArtistImage(uiState.name)
    val theme by androidx.compose.runtime.produceState<com.ikko.aura.ui.components.AlbumTheme?>(null, photo) {
        value = photo?.let { uri ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.ikko.aura.ui.components.AlbumArtCache.load(context, uri, 192, uri.toString())
                    ?.let { com.ikko.aura.ui.components.albumThemeFromBitmap(it, uri.toString().hashCode()) }
            }
        }
    }
    val pageTheme = com.ikko.aura.ui.theme.LocalPageTheme.current
    val pageThemeState = remember { androidx.compose.runtime.mutableStateOf<com.ikko.aura.ui.components.AlbumTheme?>(null) }
    pageThemeState.value = theme
    androidx.compose.runtime.DisposableEffect(theme) {
        val mine = theme
        if (mine != null) pageTheme.value = mine
        onDispose { if (pageTheme.value === mine) pageTheme.value = null }
    }
    androidx.compose.runtime.CompositionLocalProvider(com.ikko.aura.ui.theme.LocalPageTheme provides pageThemeState) {
    GradientScreenScaffold(
        applyStatusBarInset = false,
        modifier = Modifier.suppressEdgeBackRipple(),
    ) {
      val tracksOf = com.ikko.aura.viewmodel.rememberAlbumTracks()
      MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heroPageOut(),
            contentPadding = PaddingValues(top = statusBarTop, bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                    // As the album pages: the picture on the left, the name and the counts beside it, then one row of actions.
                    val u = com.ikko.aura.ui.components.designUnit()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        photo?.let { photo ->
                            com.ikko.aura.ui.components.AlbumArt(
                                uri = photo, contentDescription = uiState.name, shape = RoundedCornerShape(50),
                                modifier = Modifier.size(u * 240f), targetSizeDp = u * 240f, cacheKey = photo.toString(),
                            )
                            Spacer(Modifier.width(u * 40f))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 6f)) {
                            Text(
                                text = uiState.name,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = com.ikko.aura.ui.components.designSp(66f),
                                lineHeight = com.ikko.aura.ui.components.designSp(76f),
                                letterSpacing = com.ikko.aura.ui.components.designSp(66f * -0.02f),
                                color = AuraColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroTarget(
                                    heroKey ?: "",
                                    text = com.ikko.aura.ui.components.HeroText(
                                        uiState.name, androidx.compose.ui.geometry.Rect.Zero,
                                        MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold), AuraColors.TextPrimary,
                                        maxLines = 2,
                                    ),
                                ),
                            )
                            if (loading) {
                                // The real line's own height (a transparent space in its style), the grey bar inside it.
                                Box(Modifier.heroRise(0), contentAlignment = Alignment.CenterStart) {
                                    Text("\u00A0", style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1)
                                    LoadingBar(Modifier.width(150.dp).height(14.dp))
                                }
                            } else Text(
                                text = "${uiState.albums.size} album${if (uiState.albums.size > 1) "s" else ""} · " +
                                    "${uiState.tracks.size} titre${if (uiState.tracks.size > 1) "s" else ""}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = com.ikko.aura.ui.components.designSp(32f),
                                lineHeight = com.ikko.aura.ui.components.designSp(40f),
                                color = AuraColors.TextSecondary,
                                maxLines = 1,
                                modifier = Modifier.heroRise(0).arriving(0, loadedAt, beganLoading),
                            )
                        }
                    }

                    Spacer(Modifier.height(u * 40f))

                    if (loading) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heroRise(1)) {
                        LoadingBar(Modifier.size(width = 122.dp, height = 44.dp), RoundedCornerShape(16.dp))
                        LoadingBar(Modifier.size(width = 130.dp, height = 44.dp), RoundedCornerShape(16.dp))
                    } else {
                        val localId = links.localIdOf(routeState.name)
                        val toast = androidx.compose.ui.platform.LocalContext.current
                        com.ikko.aura.ui.components.ActionBar(
                            online = showOnline,
                            // The same artist, on the other side, in place: the page stays, its songs and its albums change.
                            onSource = { toOnline ->
                                if (!toOnline && localId == null) {
                                    android.widget.Toast.makeText(toast, "Cet artiste n'est pas dans ta bibliothèque", android.widget.Toast.LENGTH_SHORT).show()
                                } else showOnline = toOnline
                            },
                            localAvailable = localId != null || !online,
                            onShuffle = { if (uiState.tracks.isNotEmpty()) playerViewModel.playShuffled(uiState.tracks) },
                            onPlay = { if (uiState.tracks.isNotEmpty()) playerViewModel.playQueue(uiState.tracks, 0) },
                            modifier = Modifier.heroRise(1).arriving(1, loadedAt, beganLoading),
                            // The three dots: like this artist, or block them.
                            add = {
                                val liked by links.likedKeys.collectAsStateWithLifecycle()
                                val blocked by links.blockedKeys.collectAsStateWithLifecycle()
                                val key = com.ikko.aura.reco.ArtistNames.primaryKey(routeState.name)
                                val menuState = com.ikko.aura.ui.components.rememberGlassMenuState()
                                Box(Modifier.glassMenuAnchor(menuState)) {
                                    com.ikko.aura.ui.components.MoreButton(onClick = { menuState.open() })
                                    com.ikko.aura.ui.components.DesignMenu(state = menuState, width = 304.dp, shade = 0.30f) {
                                        val go: (() -> Unit) -> () -> Unit = { action -> { menuState.close(); action() } }
                                        com.ikko.aura.ui.components.DesignMenuRow(
                                            if (key in liked) "Je n'aime plus cet artiste" else "J'aime cet artiste",
                                            go { links.toggleLike(routeState.name) },
                                            icon = if (key in liked) CupertinoIcons.StarFilled else CupertinoIcons.Star,
                                        )
                                        com.ikko.aura.ui.components.DesignMenuRow(
                                            if (key in blocked) "Débloquer cet artiste" else "Bloquer cet artiste",
                                            go { links.toggleBlock(routeState.name) },
                                            icon = CupertinoIcons.Close, danger = key !in blocked,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (uiState.albums.isNotEmpty() || loading) {
                item {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.heroRise(2).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                item {
                    // From the player, the playing song's album is already there, with its own cover, for the copy to
                    // land on; the others are grey until the page has them.
                    val landingId = if (hero.fromPlayer) hero.coverAlbumId else null
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (loading) {
                            itemsIndexed(List(4) { it }, key = { _, it -> "loading$it" }) { index, _ ->
                                if (index == 0 && landingId != null) {
                                    ArtistAlbumCard(
                                        album = Album(
                                            id = landingId, title = hero.coverAlbumTitle.orEmpty(), artist = uiState.name,
                                            artistId = 0L, artworkUri = hero.uri, trackCount = 0, year = 0,
                                        ),
                                        index = index,
                                        artistHeroKey = heroKey,
                                        navController = navController,
                                        onLongClick = {},
                                        interactive = false,
                                    )
                                } else {
                                    Column(Modifier.width(140.dp).heroRise(1 + index)) {
                                        LoadingBar(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp))
                                        Box(Modifier.padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
                                            Text("\u00A0", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1)
                                            LoadingBar(Modifier.width(96.dp).height(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (!loading) itemsIndexed(uiState.albums, key = { _, it -> it.id }) { index, album ->
                            ArtistAlbumCard(
                                album = album,
                                index = index,
                                artistHeroKey = heroKey,
                                navController = navController,
                                onLongClick = {
                                    val ref = album.remoteId
                                    menu.show(
                                        if (ref != null) com.ikko.aura.ui.components.LibraryContextTarget.RemoteAlbumTarget(ref, album.title, album.artist, album.artworkUri?.toString())
                                        else com.ikko.aura.ui.components.LibraryContextTarget.AlbumTarget(album, tracksOf(album.id)),
                                    )
                                },
                                arrival = Modifier.arriving(index, loadedAt, beganLoading),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (uiState.singles.isNotEmpty() && !loading) {
                item {
                    Text(
                        text = "Singles & EP",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AuraColors.TextPrimary,
                        modifier = Modifier.heroRise(4).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(uiState.singles, key = { _, it -> "single${it.id}" }) { index, album ->
                            ArtistAlbumCard(
                                album = album,
                                index = index,
                                artistHeroKey = heroKey,
                                navController = navController,
                                onLongClick = {
                                    val ref = album.remoteId
                                    menu.show(
                                        if (ref != null) com.ikko.aura.ui.components.LibraryContextTarget.RemoteAlbumTarget(ref, album.title, album.artist, album.artworkUri?.toString())
                                        else com.ikko.aura.ui.components.LibraryContextTarget.AlbumTarget(album, tracksOf(album.id)),
                                    )
                                },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            item {
                Text(
                    text = "Titres",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.heroRise(5).padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (loading) items(6) { index ->
                // The row's own measures (68 dp, the cover 4 dp in, the words 12 dp after it, 20.4 and 16 dp lines).
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heroRise(index, rows = true),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(4.dp))
                    LoadingBar(Modifier.size(56.dp), RoundedCornerShape(8.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Box(Modifier.height(20.4.dp), contentAlignment = Alignment.CenterStart) { LoadingBar(Modifier.width(170.dp).height(14.dp)) }
                        Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) { LoadingBar(Modifier.width(110.dp).height(11.dp)) }
                    }
                }
            }
            if (!loading) itemsIndexed(uiState.tracks, key = { _, it -> it.id }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = {
                        playerViewModel.playQueue(uiState.tracks, index)
                    },
                    artSize = 56.dp,
                    // The artist is the page's own title: the album is what tells the rows apart.
                    subtitle = track.album,
                    isPlayingTrack = nowPlayingId == track.id,
                    onLongClick = { menu.show(track) },
                    modifier = Modifier
                        .heroRise(index, rows = true)
                        .padding(horizontal = 16.dp),
                )
            }
        }
        }
      }
    }
    }
}

/**
 * What comes in after a page's placeholders (within 0.7 s of its loading): it fades in and rises a little, [index] steps
 * of 35 ms after the one before. After that, or on a page that never had placeholders, it is simply there (so a row
 * scrolled into view later does not play it again).
 */
@Composable
private fun Modifier.arriving(index: Int, loadedAt: LongArray, beganLoading: Boolean): Modifier {
    val animate = remember { beganLoading && loadedAt[0] != 0L && System.nanoTime() - loadedAt[0] < 700_000_000L }
    if (!animate) return this
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index.coerceAtMost(8) * 35L)
        p.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
    }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * rise
    }
}

/** A part of a page that is still being fetched: a soft grey shape that breathes. */
@Composable
private fun LoadingBar(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "loading").let { it.animateFloat(
        initialValue = 0.07f,
        targetValue = 0.15f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "loading-pulse",
    ) }
    Box(
        modifier
            .clip(shape)
            .drawBehind { drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = pulse)) },
    )
}

@Composable
private fun ArtistAlbumCard(
    album: Album,
    index: Int,
    artistHeroKey: String?,
    navController: NavHostController,
    onLongClick: () -> Unit,
    /** False for the stand-in of an album the page has not received yet: it only receives the cover. */
    interactive: Boolean = true,
    arrival: Modifier = Modifier,
) {
    val hero = com.ikko.aura.ui.components.LocalHero.current
    val heroKey = "album:${album.id}"
    val coverAnchor = androidx.compose.runtime.remember { com.ikko.aura.ui.components.HeroAnchor() }
    val titleAnchor = androidx.compose.runtime.remember { com.ikko.aura.ui.components.HeroAnchor() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleStyle = MaterialTheme.typography.labelLarge
    // The card the player's cover lands on when the artist was opened from the player.
    val landing = artistHeroKey != null && hero.fromPlayer && hero.landsOn(album.id, album.title)
    // And the cards beside it, then the rest, come in one after the other once it is there.
    val rise = if (!landing && artistHeroKey != null) {
        Modifier.heroRise(1 + index)
    } else Modifier
    Column(modifier = Modifier.width(140.dp).heroRipple(heroKey, pageKey = artistHeroKey).then(rise).then(arrival)) {
        SquircleCard(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .heroAnchor(heroKey, coverAnchor)
                .then(
                    if (landing) Modifier.heroTarget(artistHeroKey!!, radiusPx = with(density) { 8.dp.toPx() }) else Modifier,
                ),
            onClick = {
                if (interactive && hero.begin(
                    key = heroKey,
                    uri = album.artworkUri,
                    cacheKey = album.id.toString(),
                    cover = coverAnchor,
                    radiusPx = with(density) { 8.dp.toPx() },
                    title = titleAnchor to com.ikko.aura.ui.components.HeroText(album.title, androidx.compose.ui.geometry.Rect.Zero, titleStyle, androidx.compose.ui.graphics.Color.White),
                    sub = null,
                    sizeDp = 140.dp,
                    anyOfKind = album.remoteId != null,
                )) navController.navigate(album.route())
            },
            onLongClick = onLongClick,
        ) {
            AlbumArt(uri = album.artworkUri, contentDescription = album.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f), targetSizeDp = 140.dp, cacheKey = album.id.toString())
        }
        Text(
            text = album.title,
            style = titleStyle,
            color = AuraColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .heroAnchor(heroKey, titleAnchor, com.ikko.aura.ui.components.HeroState.ROLE_TITLE)
                // From the player, the song's title lands here as this card's caption.
                .then(
                    if (landing) {
                        Modifier.heroTarget(
                            artistHeroKey!!,
                            isSub = true,
                            text = com.ikko.aura.ui.components.HeroText(
                                album.title, androidx.compose.ui.geometry.Rect.Zero, titleStyle, AuraColors.TextPrimary,
                            ),
                        )
                    } else Modifier,
                ),
        )
        // The year it came out: the albums are listed from the most recent to the oldest.
        if (album.year > 0) {
            Text(
                text = album.year.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = AuraColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
