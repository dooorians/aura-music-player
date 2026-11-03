package com.ikko.aura.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.HeroAnchor
import com.ikko.aura.ui.components.HeroState
import com.ikko.aura.ui.components.HeroText
import com.ikko.aura.ui.components.LibraryContextTarget
import com.ikko.aura.ui.components.LocalEctoPalette
import com.ikko.aura.ui.components.LocalHero
import com.ikko.aura.ui.components.LocalPageMotion
import com.ikko.aura.ui.components.MediaContextHost
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.heroAnchor
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LocalPlayerPillHeight
import com.ikko.aura.viewmodel.ForYouCard
import com.ikko.aura.viewmodel.HomeWidgetsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import com.ikko.aura.viewmodel.TabCapsuleViewModel
import com.ikko.aura.viewmodel.rememberAlbumTracks
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** The dark glass of the validated design ("DK"): black at 26 %, a hairline of white at 7 % inside its edge, no blur. */
internal fun Modifier.darkGlass(shape: androidx.compose.ui.graphics.Shape, ring: Dp): Modifier =
    this
        .background(Color.Black.copy(alpha = 0.26f), shape)
        .border(ring, Color.White.copy(alpha = 0.07f), shape)

/**
 * A progressive black scrim under the header: opaque at the very top (status bar and header area),
 * then smoothly transitioning down to transparent so scrolling cards and content slide smoothly
 * under it without hard cuts or blur artifacts, matching the Bitchord behavior.
 */
@Composable
internal fun Modifier.underHeaderScrim(strength: () -> Float = { 1f }): Modifier = this

@Composable
internal fun Modifier.underHeaderBlur(): Modifier = this

/**
 * Where the first cards start: the library's first row (148 tall, its words centred) under the same header has its words at
 * 240 + 43; the cards take that place, so the two pages leave the same room under the header.
 */
private const val HOME_TOP = 255f

/** White at its own alpha, added to the background (the vibrancy of the design's translucent titles). */
internal fun Modifier.vibrant(): Modifier = graphicsLayer { blendMode = BlendMode.Plus }

/**
 * Home, as validated (the brief's §5.1): the four albums played last, as dark-glass rectangles under the header, then
 * "Mixes pour toi" and two landscape banners (Chill, Nuit). A cover goes to its album's page the way it always has (the
 * hero trip); the rest of the page steps back around it and comes back with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModel: HomeWidgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()
    // What is shown catches up with what was played only out of sight: with the app away, or Library showing.
    // The activity's own lifecycle: this page's (its navigation entry's) stops each time an album is opened over it.
    var host: android.content.Context = androidx.compose.ui.platform.LocalContext.current
    while (host is android.content.ContextWrapper && host !is androidx.lifecycle.LifecycleOwner) host = host.baseContext
    (host as? androidx.lifecycle.LifecycleOwner)?.let { activity ->
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP, activity) { viewModel.refreshCarousels() }
    }
    val pages = LocalPageMotion.current
    pages?.let { motion ->
        LaunchedEffect(motion) {
            androidx.compose.runtime.snapshotFlow { motion.p >= 0.999f }
                .collect { away -> if (away) viewModel.refreshCarousels() }
        }
    }
    val u = designUnit()
    val palette = LocalEctoPalette.current
    val tracksOf = rememberAlbumTracks()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selection = hiltViewModel<TabCapsuleViewModel>().selection
    val recent = remember(state.resumeAlbums) { state.resumeAlbums.take(4) }
    val recentPairs = remember(recent) { recent.chunked(2) }
    val mixes = remember(state.cards) {
        // "Tes préférés" and "Découvertes"; the first cards there are when one of them has nothing to play.
        val wanted = listOf("fav", "disc").mapNotNull { id -> state.cards.firstOrNull { it.id == id } }
        (wanted + state.cards).distinctBy { it.id }.take(2)
    }

    // The covers are decoded ahead at their size, so they are there at once.
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverPx = with(androidx.compose.ui.platform.LocalDensity.current) { (u * 130f).toPx().toInt() }.coerceAtLeast(1)
    val sectionCoverPx = with(androidx.compose.ui.platform.LocalDensity.current) { (u * 316f).toPx().toInt() }.coerceAtLeast(1)
    LaunchedEffect(recent, state.sections, coverPx, sectionCoverPx) {
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            recent.forEach { album ->
                launch {
                    gate.withPermit {
                        com.ikko.aura.ui.components.AlbumArtCache.load(context, album.artworkUri, coverPx, album.id.toString(), shrink = true)
                    }
                }
            }
            state.sections.take(2).forEach { section ->
                section.albums.forEach { item ->
                    val uri = item.album?.artworkUri ?: item.artworkUrl.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse)
                    val key = item.album?.id?.toString() ?: "sec:${item.key}"
                    if (uri != null) {
                        launch {
                            gate.withPermit {
                                com.ikko.aura.ui.components.AlbumArtCache.load(context, uri, sectionCoverPx, key, shrink = true)
                            }
                        }
                    }
                }
            }
        }
    }

    MediaContextHost(navController = navController, playerViewModel = playerViewModel) { menu ->
        val resolveRef: suspend (com.ikko.aura.data.home.SectionAlbum) -> String? = remember(viewModel) {
            { item -> kotlinx.coroutines.withTimeoutOrNull(4_000L) { viewModel.albumRefOf(item) } }
        }
        val onMenuLongPress = remember(menu) { { target: LibraryContextTarget -> menu.show(target) } }

        val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val sharedHomeScroll = com.ikko.aura.LocalHomeScrollPx.current
        androidx.compose.runtime.LaunchedEffect(homeListState) {
            androidx.compose.runtime.snapshotFlow {
                if (homeListState.firstVisibleItemIndex > 0) 1000f
                else homeListState.firstVisibleItemScrollOffset.toFloat()
            }.collect { px ->
                sharedHomeScroll?.floatValue = px
            }
        }
        LazyColumn(
            state = homeListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = u * HOME_TOP, bottom = LocalPlayerPillHeight.current + u * 24f),
        ) {
            // The four albums played last, two by two.
            itemsIndexed(recentPairs, key = { _, pair -> "recent_${pair.joinToString("_") { it.id.toString() }}" }) { row, pair ->
                if (row > 0) Spacer(Modifier.height(u * 18f))
                Row(Modifier.padding(horizontal = u * 40f), horizontalArrangement = Arrangement.spacedBy(u * 20f)) {
                    pair.forEach { album ->
                        RecentAlbumRect(
                            album = album,
                            ink = Color.White.copy(alpha = 0.62f),
                            modifier = Modifier.weight(1f),
                            // An album opens its page, and nothing else: it is the listener who starts the music from there.
                            onClick = { navController.navigate(album.route()) },
                            onLongClick = { menu.show(LibraryContextTarget.AlbumTarget(album, tracksOf(album.id))) },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (mixes.isNotEmpty()) {
                item(key = "mixes_header_and_row") {
                    Spacer(Modifier.height(u * 54f))
                    Row(
                        Modifier.fillMaxWidth().height(u * 56f).padding(horizontal = u * 40f).heroRipple(fast = true),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Mixes pour toi", color = Color.White.copy(alpha = 0.62f), maxLines = 1,
                            style = TextStyle(
                                fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold,
                                fontSize = designSp(46f), lineHeight = designSp(54f), letterSpacing = designSp(46f * -0.02f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    navController.navigate(com.ikko.aura.navigation.AuraDestination.Playlists.route)
                                }
                                .padding(horizontal = u * 20f, vertical = u * 12f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Tout voir", color = Color.White.copy(alpha = 0.62f), maxLines = 1,
                                style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = designSp(32f), lineHeight = designSp(40f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(u * 24f))
                    Row(Modifier.padding(horizontal = u * 40f), horizontalArrangement = Arrangement.spacedBy(u * 20f)) {
                        mixes.forEach { card ->
                            MixBanner(
                                card = card,
                                modifier = Modifier.weight(1f),
                                onOpen = {
                                    val route = card.content.openRoute
                                    if (route != null) navController.navigate(route)
                                    else playerViewModel.playQueue(card.content.tracks, card.content.startIndex, context = card.id)
                                },
                                onPlay = { playerViewModel.playQueue(card.content.tracks, card.content.startIndex, context = card.id) },
                            )
                        }
                        if (mixes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // What is played again and what the external sources offer for it, in rows of the same form as the mixes.
            items(state.sections, key = { it.id }) { section ->
                HomeSectionBlock(
                    section = section,
                    navController = navController,
                    playerViewModel = playerViewModel,
                    resolveRef = resolveRef,
                    onLongPress = onMenuLongPress,
                    tracksOf = tracksOf,
                    onBlockArtist = viewModel::blockArtist,
                    prepareRef = { ref -> viewModel.prepareAlbum(ref) },
                )
            }
        }
    }
}

/** A row under the mixes: its heading (the first album's cover, a line over the title), then its albums two by two. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSectionBlock(
    section: com.ikko.aura.data.home.HomeSection,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    resolveRef: suspend (com.ikko.aura.data.home.SectionAlbum) -> String?,
    onLongPress: (LibraryContextTarget) -> Unit,
    tracksOf: (Long) -> List<com.ikko.aura.data.model.Track>,
    onBlockArtist: (String) -> Unit,
    prepareRef: suspend (String) -> Unit,
) {
    val u = designUnit()
    // The radio of one artist whose long press asked to stop recommending them: confirmed before it is done.
    var artistToBlock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hero = LocalHero.current
    val ink = Color.White.copy(alpha = 0.62f)
    val soft = Color.White.copy(alpha = 0.42f)
    val coverShape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover
    val titleFontSize = designSp(34f)
    val titleLineHeight = designSp(42f)
    val titleStyle = remember(titleFontSize, titleLineHeight) {
        TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = titleFontSize, lineHeight = titleLineHeight)
    }
    val subFontSize = designSp(30f)
    val subLineHeight = designSp(38f)
    val subStyle = remember(subFontSize, subLineHeight) {
        TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = subFontSize, lineHeight = subLineHeight)
    }
    val headFontSize = designSp(46f)
    val headLineHeight = designSp(54f)
    val headLetterSpacing = designSp(46f * -0.02f)
    val headingStyle = remember(headFontSize, headLineHeight, headLetterSpacing) {
        TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = headFontSize, lineHeight = headLineHeight, letterSpacing = headLetterSpacing)
    }
    val lead = section.albums.first()
    Spacer(Modifier.height(u * 54f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = u * 40f).heroRipple(fast = true),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 24f),
    ) {
        // "Plus de <artist>": the artist's own photo, round. A row that is not about an artist has none: the first album's cover
        // is right under it, and showing it twice only repeats.
        val photo = if (section.id.startsWith("artist:") || section.id.startsWith("similar:")) com.ikko.aura.data.rememberArtistImage(section.title) else null
        if (photo != null) {
            val headShape = androidx.compose.foundation.shape.RoundedCornerShape(50)
            Box(Modifier.size(u * 96f).clip(headShape)) {
                AlbumArt(
                    uri = photo, contentDescription = null, shape = headShape, modifier = Modifier.fillMaxSize(),
                    targetSizeDp = u * 96f, cacheKey = photo.toString(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            if (section.kicker != null) {
                Text(
                    section.kicker, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = subStyle,
                )
            }
            Text(
                section.title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = headingStyle,
            )
            if (section.subtitle != null) {
                Text(
                    section.subtitle, color = soft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = subStyle,
                )
            }
        }
    }
    Spacer(Modifier.height(u * 24f))
    val albumRows = remember(section.albums) { section.albums.chunked(com.ikko.aura.data.home.HomeSections.PER_ROW) }
    albumRows.forEachIndexed { row, pair ->
        if (row > 0) Spacer(Modifier.height(u * 32f))
        // Three by three, as the library's grid: tiles of 316 with 26 between them.
        Row(Modifier.padding(horizontal = u * 40f), horizontalArrangement = Arrangement.spacedBy(u * 26f)) {
            pair.forEach { item ->
                val local = item.album
                // An album of a source has no page key yet: it is given one of the kind, the page answers to it by its kind.
                val heroKey = "album:" + (local?.id?.toString() ?: "ext-${item.key}")
                val coverAnchor = remember { HeroAnchor() }
                val titleAnchor = remember { HeroAnchor() }
                val subAnchor = remember { HeroAnchor() }
                // The finger going down on an album of a source: its page is found and read already, so the tap opens it at once.
                val warmOnTouch = Modifier.pointerInput(item.key) {
                    if (local != null || item.isArtist || item.playsItems) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            scope.launch { (item.ref ?: resolveRef(item))?.let { prepareRef(it) } }
                            waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        }
                    }
                }
                val onItemClick: () -> Unit = {
                    if (item.isArtist) {
                        navController.navigate(com.ikko.aura.navigation.AuraDestination.YtmArtistPattern.route(item.title))
                    } else if (local != null) {
                        hero.begin(
                            key = heroKey, uri = local.artworkUri, cacheKey = local.id.toString(), cover = coverAnchor,
                            radiusPx = with(density) { 8.dp.toPx() },
                            title = titleAnchor to HeroText(local.title, Rect.Zero, titleStyle, ink),
                            sub = subAnchor to HeroText(local.artist, Rect.Zero, subStyle, soft),
                            sizeDp = u * 316f,
                            visibleFromY = with(density) { (u * 236f).toPx() },
                            anyOfKind = local.remoteId != null,
                        )
                        navController.navigate(local.route())
                    } else if (item.playsItems) {
                        // A radio or a mood: its songs play, there is no page to open.
                        val songs = item.items.map { with(com.ikko.aura.reco.RecoItemTracks) { it.toTrack() } }
                        val radioOf = item.blockableArtist
                        // A radio of one artist is that artist's own songs, with a little of what is close to them.
                        if (radioOf != null) playerViewModel.startArtistRadio(radioOf, songs, section.id)
                        else playerViewModel.playQueue(songs, 0, context = section.id)
                    } else {
                        // An album of a source opens its page, and nothing else. Its page is the one the source gave, or found from its title and
                        // its artist; either way the trip of the cover and the words goes with it, there and back.
                        fun openOnline(ref: String) {
                            hero.begin(
                                key = heroKey, uri = item.artworkUrl.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
                                cacheKey = "sec:${item.key}", cover = coverAnchor,
                                radiusPx = with(density) { 8.dp.toPx() },
                                title = titleAnchor to HeroText(item.title, Rect.Zero, titleStyle, ink),
                                sub = subAnchor to HeroText(item.artist, Rect.Zero, subStyle, soft),
                                sizeDp = u * 316f,
                                visibleFromY = with(density) { (u * 236f).toPx() },
                                anyOfKind = true,
                            )
                            navController.navigate(com.ikko.aura.navigation.streamedAlbumRoute(ref, online = true))
                        }
                        val directRef = item.ref
                        if (directRef != null) {
                            openOnline(directRef)
                        } else {
                            scope.launch {
                                val ref = resolveRef(item)
                                if (ref != null) {
                                    openOnline(ref)
                                } else if (item.items.isNotEmpty()) {
                                    // No page exists for it (a source that gives none): its songs are all there is to open.
                                    playerViewModel.playQueue(item.items.map { with(com.ikko.aura.reco.RecoItemTracks) { it.toTrack() } }, 0, context = section.id)
                                }
                            }
                        }
                    }
                }
                val onItemLongClick: () -> Unit = {
                    if (item.blockableArtist != null) artistToBlock = item.blockableArtist
                    else if (local != null) onLongPress(LibraryContextTarget.AlbumTarget(local, tracksOf(local.id)))
                    else if (!item.playsItems && !item.isArtist) scope.launch {
                        // An album of a source: the menu acts on its songs, fetched when an action needs them.
                        val ref = resolveRef(item)
                        val own = item.items.map { with(com.ikko.aura.reco.RecoItemTracks) { it.toTrack() } }
                        if (ref != null || own.isNotEmpty()) {
                            onLongPress(LibraryContextTarget.RemoteAlbumTarget(ref, item.title, item.artist, item.artworkUrl.takeIf { u -> u.isNotEmpty() }, own))
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .heroRipple(heroKey)
                        .then(warmOnTouch)
                        .combinedClickable(onClick = onItemClick, onLongClick = onItemLongClick),
                    verticalArrangement = Arrangement.spacedBy(u * 12f),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .heroAnchor(heroKey, coverAnchor)
                            .clip(coverShape),
                    ) {
                        if (item.isArtist) {
                            // The artist's own photo, never an album's cover (it would not look like them); a plain disc with
                            // their initial while the photo is not known.
                            val photo = com.ikko.aura.data.rememberArtistImage(item.title)
                            if (photo != null) {
                                AlbumArt(
                                    uri = photo, contentDescription = item.title, shape = CircleShape, modifier = Modifier.fillMaxSize(),
                                    targetSizeDp = u * 316f, cacheKey = photo.toString(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                    Text(
                                        item.title.take(1).uppercase(), color = Color.White.copy(alpha = 0.5f),
                                        style = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = designSp(110f)),
                                    )
                                }
                            }
                        } else if (item.artist == "Station") {
                            RadioStationTile(
                                item = item,
                                targetSizeDp = u * 316f,
                                shape = coverShape,
                            )
                        } else {
                            AlbumArt(
                                uri = local?.artworkUri ?: item.artworkUrl.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse),
                                contentDescription = item.title, shape = coverShape, modifier = Modifier.fillMaxSize(),
                                targetSizeDp = u * 316f, cacheKey = local?.id?.toString() ?: "sec:${item.key}",
                            )
                        }
                    }
                    Column {
                        val isStation = item.artist == "Station"
                        Text(
                            item.title, color = ink, style = titleStyle,
                            maxLines = if (isStation) 2 else 1,
                            softWrap = isStation,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.heroAnchor(heroKey, titleAnchor, HeroState.ROLE_TITLE),
                        )
                        if (isStation) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(u * 8f),
                                modifier = Modifier.heroAnchor(heroKey, subAnchor, HeroState.ROLE_SUB),
                            ) {
                                StationWaveIcon(color = soft, size = u * 28f)
                                Text(
                                    "Station", color = soft, style = subStyle,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                item.artist, color = soft, style = subStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heroAnchor(heroKey, subAnchor, HeroState.ROLE_SUB),
                            )
                        }
                    }
                }
            }
            repeat(com.ikko.aura.data.home.HomeSections.PER_ROW - pair.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    artistToBlock?.let { artist ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { artistToBlock = null },
            title = { Text("Ne plus recommander $artist ?") },
            text = { Text("$artist ne sera plus proposé nulle part (playlists, Accueil, Automix), ni ceux qui lui ressemblent. Tu peux le débloquer dans Réglages > Recommandations > Artistes bloqués.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { artistToBlock = null; onBlockArtist(artist) }) { Text("Bloquer") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { artistToBlock = null }) { Text("Annuler") } },
        )
    }
}

/** 490 x 150: the cover (138, corners 22) 6 px in, then the title, in the ink, on two lines at most. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentAlbumRect(
    album: Album,
    ink: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val u = designUnit()
    val hero = LocalHero.current
    val heroKey = "album:${album.id}"
    val coverAnchor = remember { HeroAnchor() }
    val titleAnchor = remember { HeroAnchor() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shape = remember { RoundedCornerShape(u * 38f) }
    // The prototype's corner (22): about 16 % of the cover, the proportion every album cover takes (see the library).
    val coverShape = remember { RoundedCornerShape(u * 22f) }
    val recFontSize = designSp(38f)
    val recLineHeight = designSp(46f)
    val recLetterSpacing = designSp(38f * -0.01f)
    val titleStyle = remember(recFontSize, recLineHeight, recLetterSpacing) {
        TextStyle(
            fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold,
            fontSize = recFontSize, lineHeight = recLineHeight, letterSpacing = recLetterSpacing,
        )
    }
    Row(
        modifier
            .height(u * 150f)
            .heroRipple(heroKey)
            .darkGlass(shape, u * 1.5f)
            .clip(shape)
            .combinedClickable(
                onClick = {
                    if (hero.begin(
                            key = heroKey,
                            uri = album.artworkUri,
                            cacheKey = album.id.toString(),
                            cover = coverAnchor,
                            radiusPx = with(density) { (u * 22f).toPx() },
                            title = titleAnchor to HeroText(album.title, Rect.Zero, titleStyle, ink, maxLines = 2),
                            sub = null,
                            sizeDp = u * 130f,
                            visibleFromY = with(density) { (u * 236f).toPx() },
                            anyOfKind = album.remoteId != null,
                        )
                    ) onClick()
                },
                onLongClick = onLongClick,
            )
            .padding(start = u * 10f, end = u * 20f), // 10 around the cover, as in the mock-up
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 24f),
    ) {
        // The cover of the mock-up: its own corners and a fine light ring inside its edge, over the picture.
        Box(
            Modifier.size(u * 130f).heroAnchor(heroKey, coverAnchor).clip(coverShape)
                .border(u * 1f, Color.White.copy(alpha = 0.3f), coverShape),
        ) {
            AlbumArt(
                uri = album.artworkUri,
                contentDescription = album.title,
                shape = coverShape,
                modifier = Modifier.fillMaxSize(),
                targetSizeDp = u * 130f,
                cacheKey = album.id.toString(),
                keepLoading = true,
            )
        }
        Text(
            album.title, color = ink, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heroAnchor(heroKey, titleAnchor, HeroState.ROLE_TITLE),
        )
    }
}

/**
 * 490 x 296, corners 24: the mix's cover filling it, a dark fall at its foot, its name and line, a play button. It opens
 * its playlist the way the library's playlist tiles do: the name and the line travel to the page, the rest of Home steps
 * back around it and comes back with it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixBanner(card: ForYouCard, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val u = designUnit()
    val hero = LocalHero.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shape = RoundedCornerShape(u * 24f)
    // A mix wears its gradient (see rememberCardArt), never an album's cover: the same picture as its page.
    // Five of the mixes have an animated artwork (the same, whatever the day); the others wear their gradient.
    val artwork = com.ikko.aura.ui.components.mixArtworkOf(card.id)
    val ambienceArt = if (artwork == null) rememberMixArt(card.ambience, card.content.tracks, 512) else null
    // It plays only while Home is the page on show.
    val pageMotion = LocalPageMotion.current
    val homeOnShow by remember(pageMotion) { androidx.compose.runtime.derivedStateOf { (pageMotion?.p ?: 0f) < 0.999f } }
    // The playlist's own key, the one its page answers to: "playlist/<id>" is the route of a page that has a trip.
    val route = card.content.openRoute
    val heroKey = route?.takeIf { it.startsWith("playlist/") }?.let { "playlist:" + it.substringAfter("playlist/") }
    val coverAnchor = remember { HeroAnchor() }
    val titleAnchor = remember { HeroAnchor() }
    val subAnchor = remember { HeroAnchor() }
    val bannerTitleFontSize = designSp(40f)
    val bannerTitleLineHeight = designSp(48f)
    val titleStyle = remember(bannerTitleFontSize, bannerTitleLineHeight) {
        TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = bannerTitleFontSize, lineHeight = bannerTitleLineHeight)
    }
    val bannerSubFontSize = designSp(28f)
    val bannerSubLineHeight = designSp(34f)
    val subStyle = remember(bannerSubFontSize, bannerSubLineHeight) {
        TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = bannerSubFontSize, lineHeight = bannerSubLineHeight)
    }
    val line = mixLine(card)
    Box(
        modifier
            .height(u * 296f)
            .heroRipple(heroKey ?: "mix:${card.id}")
            .clip(shape)
            .background(Color(0xFF1A1A20))
            .clickable {
                if (heroKey == null) onOpen()
                else if (hero.begin(
                        key = heroKey,
                        uri = null,
                        cacheKey = null,
                        cover = coverAnchor,
                        radiusPx = with(density) { (u * 24f).toPx() },
                        title = titleAnchor to HeroText(card.heading, Rect.Zero, titleStyle, Color.White.copy(alpha = 0.7f), maxLines = 1),
                        sub = subAnchor to HeroText(line, Rect.Zero, subStyle, Color.White.copy(alpha = 0.78f), maxLines = 1),
                        drawCover = false,
                        visibleFromY = with(density) { (u * 236f).toPx() },
                    )
                ) onOpen()
            },
    ) {
        Box(Modifier.fillMaxSize().then(if (heroKey != null) Modifier.heroAnchor(heroKey, coverAnchor) else Modifier)) {
            if (artwork != null) com.ikko.aura.ui.components.MixArtworkView(artwork, Modifier.fillMaxSize(), playing = homeOnShow)
            // Fades in over the dark ground once it is made (the first time only: after that it is kept).
            else androidx.compose.animation.Crossfade(targetState = ambienceArt, animationSpec = androidx.compose.animation.core.tween(300), label = "mix-art") { art ->
                if (art != null) {
                    androidx.compose.foundation.Image(
                        art.asImageBitmap(), card.heading,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(u * 170f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = u * 24f, bottom = u * 22f, end = u * 110f)) {
            Text(
                card.heading, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = titleStyle,
                modifier = if (heroKey != null) Modifier.heroAnchor(heroKey, titleAnchor, HeroState.ROLE_TITLE) else Modifier,
            )
            Text(
                line, color = Color.White.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = subStyle,
                modifier = if (heroKey != null) Modifier.heroAnchor(heroKey, subAnchor, HeroState.ROLE_SUB) else Modifier,
            )
        }
        // The button's touch area is the button: Compose widens any clickable to 48 dp, and this one is smaller (about 30), so the
        // widened area ran out under the banner's corner and a tap there played instead of opening the playlist.
        val viewConfiguration = androidx.compose.ui.platform.LocalViewConfiguration.current
        val exactTouch = remember(viewConfiguration) {
            object : androidx.compose.ui.platform.ViewConfiguration by viewConfiguration {
                override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize.Zero
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides exactTouch) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = u * 16f, bottom = u * 16f)
                    .size(u * 80f)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.26f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Play, "Lire ${card.heading}", tint = Color.White, modifier = Modifier.size(u * 32f))
            }
        }
    }
}

/** The banner's line, as the design writes it for its two mixes. */
private fun mixLine(card: ForYouCard): String = when (card.id) {
    "fav" -> "Ce que tu aimes"
    "disc" -> "Une sélection pour toi"
    "chill" -> "Ambiance calme"
    "night" -> "Pour finir la journée"
    else -> card.kicker
}

private val STATION_PALETTES = listOf(
    18f to 0.86f,  // Warm amber orange
    4f to 0.84f,   // Crimson red
    8f to 0.52f,   // Dusty rose terracotta
    192f to 0.48f, // Slate ocean teal
    205f to 0.64f, // Cerulean blue
    28f to 0.82f,  // Golden copper
    268f to 0.56f, // Deep violet
    158f to 0.54f, // Emerald teal
)

private val STATION_HSV_CACHE = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Float>>()

@Composable
private fun RadioStationTile(
    item: com.ikko.aura.data.home.SectionAlbum,
    targetSizeDp: Dp,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val seedIdx = (item.title.hashCode() and 0x7FFFFFFF) % STATION_PALETTES.size
    val defaultPair = STATION_PALETTES[seedIdx]
    val cachedPair = STATION_HSV_CACHE[item.key] ?: defaultPair
    val hsvState = remember(item.key) { androidx.compose.runtime.mutableStateOf(cachedPair) }
    val hsvPair = hsvState.value

    LaunchedEffect(item.artworkUrl) {
        if (STATION_HSV_CACHE.containsKey(item.key)) return@LaunchedEffect
        val url = item.artworkUrl.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.ikko.aura.ui.components.AlbumArtCache.load(context, android.net.Uri.parse(url), 96, "stpal:${item.key}")
            }.getOrNull()
        } ?: return@LaunchedEffect
        val extracted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var bestH = defaultPair.first
            var bestS = defaultPair.second
            var bestScore = -1f
            val hsv = FloatArray(3)
            val stepX = (bmp.width / 12).coerceAtLeast(1)
            val stepY = (bmp.height / 12).coerceAtLeast(1)
            for (y in 0 until bmp.height step stepY) {
                for (x in 0 until bmp.width step stepX) {
                    android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (v in 0.18f..0.92f && s >= 0.22f) {
                        val score = s * (1f - kotlin.math.abs(v - 0.55f))
                        if (score > bestScore) {
                            bestScore = score
                            bestH = hsv[0]
                            bestS = s.coerceIn(0.45f, 0.88f)
                        }
                    }
                }
            }
            bestH to bestS
        }
        STATION_HSV_CACHE[item.key] = extracted
        hsvState.value = extracted
    }

    val hue = hsvPair.first
    val sat = hsvPair.second
    val gradient = remember(hue, sat) {
        val topColor = Color.hsv(hue, (sat * 0.85f).coerceIn(0.38f, 0.82f), 0.34f)
        val midColor = Color.hsv(hue, sat.coerceIn(0.42f, 0.88f), 0.56f)
        val bottomColor = Color.hsv(hue, sat.coerceIn(0.46f, 0.92f), 0.78f)
        Brush.verticalGradient(listOf(topColor, midColor, bottomColor))
    }

    val multi = item.title.endsWith("et autres artistes")
    val leadArtistName = remember(item.title) { item.title.removeSuffix(" et autres artistes").trim() }
    val leadPhoto = com.ikko.aura.data.rememberArtistImage(leadArtistName)
    val c1ArtistName = remember(item.items) { item.items.getOrNull(1)?.artist?.substringBefore(',')?.trim() }
    val c1Photo = c1ArtistName?.let { com.ikko.aura.data.rememberArtistImage(it) }
    val c2ArtistName = remember(item.items) { item.items.getOrNull(2)?.artist?.substringBefore(',')?.trim() }
    val c2Photo = c2ArtistName?.let { com.ikko.aura.data.rememberArtistImage(it) }

    val covers = remember(item.artworkUrl, item.items) {
        (listOf(item.artworkUrl) + item.items.map { it.artworkUrl })
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
    }

    val w = targetSizeDp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient, shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
    ) {
        if (multi && covers.size >= 2) {
            val c0 = covers[0]
            val c1 = covers.getOrElse(1) { c0 }
            val c2 = covers.getOrElse(2) { c1 }
            AlbumArt(
                uri = leadPhoto ?: android.net.Uri.parse(c0),
                contentDescription = item.title,
                shape = CircleShape,
                modifier = Modifier
                    .padding(start = w * 0.065f, top = w * 0.055f)
                    .size(w * 0.56f),
                targetSizeDp = targetSizeDp * 0.56f,
                cacheKey = leadPhoto?.toString() ?: "stc0:$c0",
            )
            AlbumArt(
                uri = c1Photo ?: android.net.Uri.parse(c1),
                contentDescription = null,
                shape = CircleShape,
                modifier = Modifier
                    .padding(start = w * 0.605f, top = w * 0.395f)
                    .size(w * 0.31f),
                targetSizeDp = targetSizeDp * 0.31f,
                cacheKey = c1Photo?.toString() ?: "stc1:$c1",
            )
            AlbumArt(
                uri = c2Photo ?: android.net.Uri.parse(c2),
                contentDescription = null,
                shape = CircleShape,
                modifier = Modifier
                    .padding(start = w * 0.325f, top = w * 0.655f)
                    .size(w * 0.255f),
                targetSizeDp = targetSizeDp * 0.255f,
                cacheKey = c2Photo?.toString() ?: "stc2:$c2",
            )
        } else {
            val c0 = covers.firstOrNull()
            AlbumArt(
                uri = leadPhoto ?: c0?.let(android.net.Uri::parse),
                contentDescription = item.title,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(w * 0.72f),
                targetSizeDp = targetSizeDp * 0.72f,
                cacheKey = leadPhoto?.toString() ?: "stc0:${c0 ?: item.key}",
            )
        }
    }
}

@Composable
private fun StationWaveIcon(color: Color, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.085f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(color = color, radius = w * 0.095f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        val r1 = w * 0.25f
        drawArc(
            color = color, startAngle = 140f, sweepAngle = 80f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r1, cy - r1),
            size = androidx.compose.ui.geometry.Size(r1 * 2f, r1 * 2f), style = stroke,
        )
        drawArc(
            color = color, startAngle = -40f, sweepAngle = 80f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r1, cy - r1),
            size = androidx.compose.ui.geometry.Size(r1 * 2f, r1 * 2f), style = stroke,
        )
        val r2 = w * 0.42f
        drawArc(
            color = color, startAngle = 136f, sweepAngle = 88f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r2, cy - r2),
            size = androidx.compose.ui.geometry.Size(r2 * 2f, r2 * 2f), style = stroke,
        )
        drawArc(
            color = color, startAngle = -44f, sweepAngle = 88f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r2, cy - r2),
            size = androidx.compose.ui.geometry.Size(r2 * 2f, r2 * 2f), style = stroke,
        )
    }
}
