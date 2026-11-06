package com.ikko.aura.ui.screens.artist

import com.ikko.aura.ui.components.heroPageOut
import com.ikko.aura.ui.components.heroAnchor
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.heroRise
import com.ikko.aura.ui.components.softArrival
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
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
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
    val sideState = when {
        showOnline == online -> routeState
        showOnline -> com.ikko.aura.viewmodel.artistUiStateOf(otherOnline).copy(name = routeState.name)
        // Never the online songs under the library's side: an artist the library does not have is an empty page.
        else -> otherLocal ?: com.ikko.aura.viewmodel.ArtistUiState(name = routeState.name)
    }
    // Only the side that is being fetched is loading: the library's side never is, whatever the page's own side is doing.
    val sideLoading = when {
        showOnline == online -> routeLoading
        showOnline -> otherOnline.loading
        else -> false
    }
    // The other side is still being fetched when the switch moves: the page keeps what it shows (the switch already on its new
    // side) until that side is there, instead of dropping to grey placeholders and bringing its parts back one by one: the page
    // flashed. Only a page that has shown a side of itself has something to keep.
    val lastShown = remember { arrayOfNulls<com.ikko.aura.viewmodel.ArtistUiState>(1) }
    val keepLast = sideLoading && showOnline != online && lastShown[0] != null
    val uiState = if (keepLast) lastShown[0]!! else sideState
    val loading = sideLoading
    // The header keeps its real parts (and the switch, already on its new side) while the sections below show their placeholders.
    val headerLoading = loading && !keepLast
    if (!loading) lastShown[0] = uiState
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
    val photoState = com.ikko.aura.data.rememberArtistImageState(uiState.name)
    val photo = photoState.uri
    // The picture was not there when the page opened: it comes in softly when it arrives.
    val photoLate = remember(uiState.name) { photoState.uri == null }
    // The picture of an artist never seen comes a moment after the page: its place is kept (a grey disc) until the lookup is over,
    // so the name beside it does not move left and then right again. A lookup that never ends lets go of the place after a while.
    var photoWaitOver by remember(uiState.name) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(uiState.name) {
        kotlinx.coroutines.delay(15_000)
        photoWaitOver = true
    }
    val photoPending = !photoState.resolved && !photoWaitOver
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
        // The whole page steps back when an album opens from it, and returns from the touched card outwards, as a Home card does;
        // not when the trip is this page's own (it rises by itself).
        val rippleKey = heroKey ?: "artist:page"
        // The first title under the line sits where an album page's first row does (the line to the top of the letters: the
        // same on both pages); the sections after it keep the gap they had.
        val hasAlbumsSection = uiState.albums.isNotEmpty() || loading
        val hasSinglesSection = uiState.singles.isNotEmpty() && !loading
        fun sectionTitlePadding(first: Boolean) = PaddingValues(start = 16.dp, end = 16.dp, top = if (first) 5.dp else 12.dp, bottom = 12.dp)
        // The five songs that lead the artist's page, in the order the page has them (a source's own is its ranking).
        // A local artist's: the songs played most. An artist of a source: the source's own order.
        val fullRanking = remember(uiState.tracks, uiState.ranked) { uiState.ranked.ifEmpty { uiState.tracks } }
        val ranking = remember(fullRanking) { fullRanking.take(5) }
        // The ranking appears whole: while the line under one of its five songs is still being found (a song of a source has no album of
        // its own) it stays as grey shapes, and its rows come in together, each with its line. After a while it is shown as it is.
        var rankingWaitOver by remember(uiState.name) { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(uiState.name) {
            kotlinx.coroutines.delay(4500)
            rankingWaitOver = true
        }
        val rankingKnown = ranking.all { t ->
            (t.album.isNotBlank() && t.album != "YouTube Music") || uiState.subtitleOf.containsKey(t.id) || uiState.albumOf.containsKey(t.id)
        }
        val rankingReady = rankingKnown || rankingWaitOver
        val hasRanking = !loading && ranking.isNotEmpty() && rankingReady
        val showRankingSkeleton = loading || (ranking.isNotEmpty() && !rankingReady)
        // The ranking came in after the page (its lines were waited for): its rows fade in as they arrive.
        val rankingLate = remember { !hasRanking }
        val rankAccent = com.ikko.aura.ui.components.rememberPlayerAccent()
        // Artists close to this one come from another source, often after the rest of the page: when they arrive late they fade in.
        val similarLate = remember { uiState.similar.isEmpty() }
        // What a side of the page brings in when the switch moves (its sections, not the header the switch is in) arrives softly: it
        // fades in and rises a little, instead of standing there all at once.
        val sideArrival = remember { androidx.compose.animation.core.Animatable(1f) }
        val firstSide = remember { showOnline }
        val switchedOnce = remember { BooleanArray(1) }
        androidx.compose.runtime.LaunchedEffect(showOnline, loading) {
            if (showOnline != firstSide) switchedOnce[0] = true
            if (!switchedOnce[0]) return@LaunchedEffect
            sideArrival.snapTo(0f)
            sideArrival.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
        }
        val sideRise = with(density) { 12.dp.toPx() }
        val sideIn = Modifier.graphicsLayer {
            val p = sideArrival.value
            alpha = p
            translationY = (1f - p) * sideRise
        }
        // The title of the albums (or the singles): its arrow opens the page of all of them, as the ranking's opens its page. Not
        // while the page is still loading: there is nothing to open yet.
        @Composable
        fun ReleasesTitle(text: String, kind: String, list: List<Album>, rise: Int, first: Boolean) {
            val tripKey = com.ikko.aura.navigation.settingsTripKey("releases/$kind:${uiState.name}")
            val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
            val open = !loading && list.isNotEmpty()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(sideIn)
                    .heroRipple(pageKey = rippleKey)
                    .then(if (open) Modifier.heroAnchor(tripKey, titleAnchor) else Modifier)
                    .heroRise(rise).arriving(rise, loadedAt, beganLoading)
                    .then(
                        if (open) Modifier.clickable {
                            com.ikko.aura.ui.screens.settings.ArtistReleases.title = text
                            com.ikko.aura.ui.screens.settings.ArtistReleases.albums = list
                            if (hero.begin(
                                    key = tripKey, uri = null, cacheKey = null, cover = titleAnchor, radiusPx = 0f,
                                    title = null, sub = null, drawCover = false,
                                )
                            ) navController.navigate(AuraDestination.ReleasesPattern.route(kind, uiState.name)) { launchSingleTop = true }
                        } else Modifier,
                    )
                    .padding(sectionTitlePadding(first = first)),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (open) Icon(CupertinoIcons.ChevronRight, contentDescription = null, tint = AuraColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
        fun rankingSubtitle(track: com.ikko.aura.data.model.Track): String {
            // The line the catalogue gave whole, with the page: the album, its kind and its year at once.
            uiState.subtitleOf[track.id]?.let { return it }
            // A streamed song carries no album of its own ("YouTube Music" is the source, not an album): the page looks it up.
            val looked = uiState.albumOf[track.id]
            // Still being looked for: nothing yet. Looked for and not found: the artist, as the library's rows have.
            val albumName = track.album.takeUnless { it.isBlank() || it == "YouTube Music" }
                ?: looked?.ifBlank { null } ?: return if (looked == null && !rankingWaitOver) "" else track.artist
            val small = uiState.singles.firstOrNull { it.title.equals(albumName, ignoreCase = true) }
            val release = small ?: uiState.albums.firstOrNull { it.title.equals(albumName, ignoreCase = true) }
            val kind = when {
                small == null -> ""
                small.title.equals(track.title, ignoreCase = true) -> " - Single"
                else -> " - EP"
            }
            val year = release?.year?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 }
            return albumName + kind + (year?.let { " · $it" } ?: "")
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heroPageOut(),
            contentPadding = PaddingValues(top = statusBarTop, bottom = LocalPlayerPillHeight.current + 12.dp),
        ) {
            item(key = "artist_header", contentType = "artist_header") {
                Column(modifier = Modifier.heroRipple(pageKey = rippleKey).padding(horizontal = 16.dp, vertical = 20.dp)) {
                    // As the album pages: the picture on the left, the name and the counts beside it, then one row of actions.
                    val u = com.ikko.aura.ui.components.designUnit()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The picture's place: a grey disc while it is not known (one never played comes a moment after the page), and the
                        // picture itself, which fades in over the disc when it comes late, instead of standing there all at once.
                        if (photo != null || headerLoading || photoPending) {
                            Box(Modifier.size(u * 240f).heroRise(0)) {
                                if (photo == null || photoLate) LoadingBar(Modifier.fillMaxSize(), CircleShape)
                                photo?.let { photo ->
                                    val fade = remember { androidx.compose.animation.core.Animatable(if (photoLate) 0f else 1f) }
                                    androidx.compose.runtime.LaunchedEffect(photo) {
                                        if (photoLate) fade.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                    }
                                    com.ikko.aura.ui.components.AlbumArt(
                                        uri = photo, contentDescription = uiState.name, shape = RoundedCornerShape(50),
                                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                                        targetSizeDp = u * 240f, cacheKey = photo.toString(),
                                    )
                                }
                            }
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
                                        // The very style of the title below: the copy must measure exactly like it, or
                                        // the last letters move during the trip and snap back when the real one lands.
                                        androidx.compose.ui.text.TextStyle(
                                            fontFamily = com.ikko.aura.ui.theme.AuraFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = com.ikko.aura.ui.components.designSp(66f),
                                            lineHeight = com.ikko.aura.ui.components.designSp(76f),
                                            letterSpacing = com.ikko.aura.ui.components.designSp(66f * -0.02f),
                                        ),
                                        AuraColors.TextPrimary,
                                        maxLines = 2,
                                    ),
                                ),
                            )
                            if (headerLoading) {
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

                    // The real row of actions stands there while the page loads (the switch, the buttons): they are the same on every
                    // artist, so grey shapes in their place only had to be swapped for them a moment later.
                    run {
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
                            modifier = Modifier.heroRise(1),
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

            // While the page loads: the same section as grey shapes, in the places the real one will take.
            if (showRankingSkeleton) {
                item(key = "artist_ranking_title_loading", contentType = "section_title") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heroRise(2).padding(sectionTitlePadding(first = true)),
                    ) {
                        Text(
                            text = "Classement des morceaux",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AuraColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(CupertinoIcons.ChevronRight, contentDescription = null, tint = AuraColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                items(5, key = { "ranking_skeleton_$it" }, contentType = { "track_skeleton" }) { index ->
                    // The row of the library's songs, as shapes: its cover and its two lines.
                    val u = com.ikko.aura.ui.components.designUnit()
                    Row(
                        Modifier.fillMaxWidth().then(sideIn).height(u * 138f).padding(horizontal = 16.dp).heroRise(index, rows = true),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u * 28f),
                    ) {
                        LoadingBar(Modifier.size(u * 116f), com.ikko.aura.ui.theme.AuraShapes.AlbumCover)
                        Column {
                            Box(contentAlignment = Alignment.CenterStart) {
                                Text(" ", fontSize = com.ikko.aura.ui.components.designSp(40f), lineHeight = com.ikko.aura.ui.components.designSp(48f), color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1)
                                LoadingBar(Modifier.width(170.dp).height(14.dp))
                            }
                            Box(contentAlignment = Alignment.CenterStart) {
                                Text(" ", fontSize = com.ikko.aura.ui.components.designSp(32f), lineHeight = com.ikko.aura.ui.components.designSp(40f), color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1)
                                LoadingBar(Modifier.width(110.dp).height(11.dp))
                            }
                        }
                    }
                }
                item(key = "artist_ranking_gap_loading", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
            }

            if (hasRanking) {
                item(key = "artist_ranking_title", contentType = "section_title") {
                    val tripKey = com.ikko.aura.navigation.settingsTripKey("ranking/${uiState.name}")
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heroRipple(pageKey = rippleKey)
                            .heroAnchor(tripKey, titleAnchor)
                            .heroRise(2).arriving(0, loadedAt, beganLoading)
                            // The arrow opens the page of the whole ranking, as a row of the settings opens its page: a trip from the title.
                            .clickable {
                                com.ikko.aura.ui.screens.settings.ArtistRanking.tracks = fullRanking
                                com.ikko.aura.ui.screens.settings.ArtistRanking.subtitles = fullRanking.associate { it.id to rankingSubtitle(it) }
                                if (hero.begin(
                                        key = tripKey, uri = null, cacheKey = null, cover = titleAnchor, radiusPx = 0f,
                                        title = null, sub = null, drawCover = false,
                                    )
                                ) navController.navigate(AuraDestination.RankingPattern.route(uiState.name)) { launchSingleTop = true }
                            }
                            .padding(sectionTitlePadding(first = true)),
                    ) {
                        Text(
                            text = "Classement des morceaux",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AuraColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(CupertinoIcons.ChevronRight, contentDescription = null, tint = AuraColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                itemsIndexed(ranking, key = { _, it -> "ranking_${it.id}" }, contentType = { _, _ -> "track" }) { index, track ->
                    Box(Modifier.then(sideIn).heroRipple(pageKey = rippleKey).heroRise(index, rows = true).arriving(index, loadedAt, beganLoading).then(if (rankingLate) Modifier.softArrival(index) else Modifier).padding(horizontal = 16.dp)) {
                    // The very row of the library's songs; under the title, the album as the sources name it (a single says so) and its year.
                    com.ikko.aura.ui.screens.library.LibraryListRow(
                        coverUri = track.artworkUri, coverKey = track.albumId.toString(), coverDescription = track.album,
                        title = track.title, subtitle = rankingSubtitle(track), trailingText = null,
                        playing = nowPlayingId == track.id, accent = rankAccent, compact = false,
                        onClick = { playerViewModel.playQueue(ranking, index) },
                        onLongClick = { menu.show(track) },
                    )
                    }
                }
                item(key = "artist_ranking_gap", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
            }

            if (uiState.albums.isNotEmpty() || loading) {
                item(key = "artist_albums_title", contentType = "section_title") {
                    ReleasesTitle("Albums", "albums", uiState.albums, 3, !(hasRanking || showRankingSkeleton))
                }
                item(key = "artist_albums_row", contentType = "album_row") {
                    // From the player, the playing song's album is already there, with its own cover, for the copy to
                    // land on; the others are grey until the page has them.
                    val landingId = if (hero.fromPlayer) hero.coverAlbumId else null
                    LazyRow(
                        modifier = Modifier.then(sideIn).arriving(0, loadedAt, beganLoading),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (loading) {
                            items(4, key = { "loading$it" }, contentType = { "album_card_loading" }) { index ->
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
                        if (!loading) itemsIndexed(uiState.albums, key = { _, it -> it.id }, contentType = { _, _ -> "album_card" }) { index, album ->
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
                item(key = "artist_albums_gap", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
            }

            if (uiState.singles.isNotEmpty() && !loading) {
                item(key = "artist_singles_title", contentType = "section_title") {
                    ReleasesTitle("Singles & EP", "singles", uiState.singles, 5, !(hasRanking || showRankingSkeleton) && !hasAlbumsSection)
                }
                item(key = "artist_singles_row", contentType = "album_row") {
                    LazyRow(
                        modifier = Modifier.then(sideIn).arriving(0, loadedAt, beganLoading),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(uiState.singles, key = { _, it -> "single${it.id}" }, contentType = { _, _ -> "album_card" }) { index, album ->
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
                item(key = "artist_singles_gap", contentType = "gap") { Spacer(Modifier.height(16.dp)) }
            }

            // Artists close to this one (an artist of a source only: a page of the library has nothing here).
            if (!loading && uiState.similar.isNotEmpty()) {
                item(key = "artist_similar_title", contentType = "section_title") {
                    val tripKey = com.ikko.aura.navigation.settingsTripKey("similar/${uiState.name}")
                    val titleAnchor = remember { com.ikko.aura.ui.components.HeroAnchor() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heroRipple(pageKey = rippleKey)
                            .then(sideIn)
                            .then(if (similarLate) Modifier.softArrival(0) else Modifier)
                            .heroAnchor(tripKey, titleAnchor)
                            .heroRise(6)
                            .clickable {
                                com.ikko.aura.ui.screens.settings.SimilarArtists.list = uiState.similar
                                if (hero.begin(
                                        key = tripKey, uri = null, cacheKey = null, cover = titleAnchor, radiusPx = 0f,
                                        title = null, sub = null, drawCover = false,
                                    )
                                ) navController.navigate(AuraDestination.SimilarPattern.route(uiState.name)) { launchSingleTop = true }
                            }
                            .padding(sectionTitlePadding(first = !(hasRanking || showRankingSkeleton) && !hasAlbumsSection && !hasSinglesSection)),
                    ) {
                        Text(
                            text = "Artistes similaires",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AuraColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(CupertinoIcons.ChevronRight, contentDescription = null, tint = AuraColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                item(key = "artist_similar_row", contentType = "similar_row") {
                    Row(
                        Modifier.fillMaxWidth().then(sideIn).then(if (similarLate) Modifier.softArrival(1) else Modifier).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        uiState.similar.take(3).forEachIndexed { index, artist ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .heroRipple(pageKey = rippleKey)
                                    .heroRise(7 + index)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { navController.navigate(AuraDestination.YtmArtistPattern.route(artist.name)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AlbumArt(
                                    uri = artist.pictureUrl?.let(android.net.Uri::parse), contentDescription = artist.name,
                                    shape = CircleShape, modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                    targetSizeDp = 140.dp, cacheKey = artist.pictureUrl ?: artist.name,
                                )
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AuraColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
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
