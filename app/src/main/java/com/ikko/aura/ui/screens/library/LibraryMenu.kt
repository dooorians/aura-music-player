package com.ikko.aura.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ikko.aura.ui.screens.home.underHeaderBlur
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.model.Album
import com.ikko.aura.navigation.route
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.HeroAnchor
import com.ikko.aura.ui.components.HeroState
import com.ikko.aura.ui.components.HeroText
import com.ikko.aura.ui.components.LocalHero
import com.ikko.aura.ui.components.ProvideTabEntry
import com.ikko.aura.ui.components.designSp
import com.ikko.aura.ui.components.designUnit
import com.ikko.aura.ui.components.glassMenuAnchor
import com.ikko.aura.ui.components.glassShadow
import com.ikko.aura.ui.components.glassSurface
import com.ikko.aura.ui.components.GlassMenuState
import com.ikko.aura.ui.components.heroAnchor
import com.ikko.aura.ui.components.heroRipple
import com.ikko.aura.ui.components.tabStagger
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LibraryFilter
import com.ikko.aura.viewmodel.LibrarySort

/** The validated language's ink: slightly tinted white, and its muted level (66 %). */
// The pages' words are translucent: white at 62 % (what is read) and 42 % (what goes with it), over the mesh.
internal val LibInk = Color.White.copy(alpha = 0.62f)
internal val LibMuted = Color.White.copy(alpha = 0.42f)

@Composable
private fun ts(sizePx: Float, linePx: Float, weight: FontWeight, spacingEm: Float = 0f): TextStyle =
    TextStyle(
        fontFamily = AuraFontFamily,
        fontWeight = weight,
        fontSize = designSp(sizePx),
        lineHeight = designSp(linePx),
        letterSpacing = if (spacingEm == 0f) androidx.compose.ui.unit.TextUnit.Unspecified else designSp(sizePx * spacingEm),
    )

/**
 * The library's menu: Titres, Albums, Artistes, Playlists, each with its count and a chevron, then
 * "Ajoutés récemment" - the albums that arrived last, in three columns. No card round it: the words are translucent over the
 * mesh (white at its own alpha, added to it). The pages open from their row ([onOpen] gives the row's bounds in the window);
 * an album goes to its page the way every album does, by the hero's trip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryRoot(
    counts: List<Pair<LibraryFilter, Int>>,
    recent: List<Album>,
    navController: androidx.navigation.NavHostController,
    onAlbumLongPress: (Album) -> Unit,
    onOpen: (LibraryFilter, Rect) -> Unit,
    rowBounds: MutableMap<LibraryFilter, Rect>,
    scrollState: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
) {
    val u = designUnit()
    val hero = LocalHero.current
    val tileDensity = androidx.compose.ui.platform.LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = u * 212f, bottom = com.ikko.aura.ui.theme.LocalPlayerPillHeight.current + u * 24f),
    ) {
        val panel = RoundedCornerShape(u * 40f)
        Column(
            Modifier
                .padding(horizontal = u * 40f)
                .fillMaxWidth()
                .heroRipple(fast = true)
                .clip(panel),
        ) {
            counts.forEachIndexed { i, (filter, count) ->
                val bounds = remember { arrayOf(Rect.Zero) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(u * 148f)
                        .onGloballyPositioned { bounds[0] = it.boundsInRoot(); rowBounds[filter] = bounds[0] }
                        .clickable { onOpen(filter, bounds[0]) },
                ) {
                    Row(
                        // No margin of their own: the words start on the page's margin and the chevrons end on it.
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u * 32f),
                    ) {
                        // The row's icon, in a 58 box on the page's margin, translucent like the words.
                        // Each glyph has its own empty margin in its drawing: it is moved left by that much (measured on the
                        // device) so that the glyph itself starts on the page's margin, as the title does.
                        Box(Modifier.size(u * 58f).offset(x = -u * filterIconInset(filter)), contentAlignment = Alignment.Center) {
                            Icon(
                                filterIcon(filter), null,
                                tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(u * 54f),
                            )
                        }
                        // Translucent, as the page's title: white at its own alpha, added to the mesh (its vibrancy).
                        Text(
                            filterLabel(filter), color = Color.White.copy(alpha = 0.62f), style = ts(54f, 62f, FontWeight.Bold),
                            maxLines = 1, modifier = Modifier.weight(1f),
                        )
                        Text(
                            java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(count),
                            color = Color.White.copy(alpha = 0.42f),
                            style = ts(38f, 46f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                            maxLines = 1, modifier = Modifier,
                        )
                        // The chevron's own margin to the right is taken off the same way: its tip ends on the page's right margin.
                        Box(Modifier.width(u * 34f).offset(x = u * 11f), contentAlignment = Alignment.Center) {
                            Icon(
                                CupertinoIcons.ChevronRight, null,
                                tint = Color.White.copy(alpha = 0.34f), modifier = Modifier.size(u * 46f),
                            )
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            Text(
                "Ajoutés récemment", color = Color.White.copy(alpha = 0.62f), style = ts(46f, 54f, FontWeight.ExtraBold),
                maxLines = 1,
                // Goes with the rest of the page when an album is opened from below it (and comes back with it).
                modifier = Modifier.heroRipple(fast = true).padding(start = u * 40f, top = u * 56f, bottom = u * 24f),
            )
            val coverShape = com.ikko.aura.ui.theme.AuraShapes.AlbumCover
            val titleStyle = ts(34f, 42f, FontWeight.ExtraBold)
            val artistStyle = ts(30f, 38f, FontWeight.SemiBold)
            recent.chunked(3).forEachIndexed { row, trio ->
                if (row > 0) Spacer(Modifier.height(u * 32f))
                Row(Modifier.padding(horizontal = u * 40f), horizontalArrangement = Arrangement.spacedBy(u * 26f)) {
                    trio.forEach { album ->
                        val heroKey = "album:${album.id}"
                        val coverAnchor = remember { HeroAnchor() }
                        val titleAnchor = remember { HeroAnchor() }
                        val subAnchor = remember { HeroAnchor() }
                        Column(Modifier.weight(1f).heroRipple(heroKey), verticalArrangement = Arrangement.spacedBy(u * 12f)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .heroAnchor(heroKey, coverAnchor)
                                    .clip(coverShape)
                                    .combinedClickable(
                                        onClick = {
                                            if (hero.begin(
                                                    key = heroKey,
                                                    uri = album.artworkUri,
                                                    cacheKey = album.id.toString(),
                                                    cover = coverAnchor,
                                                    radiusPx = with(tileDensity) { 8.dp.toPx() },
                                                    title = titleAnchor to HeroText(album.title, Rect.Zero, titleStyle, Color.White.copy(alpha = 0.62f)),
                                                    sub = subAnchor to HeroText(album.artist, Rect.Zero, artistStyle, Color.White.copy(alpha = 0.42f)),
                                                    sizeDp = u * 316f,
                                                    visibleFromY = with(tileDensity) { (u * 236f).toPx() },
                                                )
                                            ) navController.navigate(album.route())
                                        },
                                        onLongClick = { onAlbumLongPress(album) },
                                    ),
                            ) {
                                AlbumArt(
                                    uri = album.artworkUri, contentDescription = album.title, shape = coverShape,
                                    modifier = Modifier.fillMaxSize(), targetSizeDp = u * 316f, cacheKey = album.id.toString(),
                                )
                            }
                            Column {
                                Text(
                                    album.title, color = Color.White.copy(alpha = 0.62f), style = titleStyle, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.heroAnchor(heroKey, titleAnchor, HeroState.ROLE_TITLE),
                                )
                                Text(
                                    album.artist, color = Color.White.copy(alpha = 0.42f), style = artistStyle, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.heroAnchor(heroKey, subAnchor, HeroState.ROLE_SUB),
                                )
                            }
                        }
                    }
                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How far, in design px, each icon's drawing sits in from the left edge of its box. */
internal fun filterIconInset(filter: LibraryFilter): Float = when (filter) {
    LibraryFilter.TRACKS -> 13f
    LibraryFilter.ALBUMS -> 7f
    LibraryFilter.ARTISTS -> 8f
    LibraryFilter.PLAYLISTS -> 3f
}

@Composable
internal fun filterIcon(filter: LibraryFilter): androidx.compose.ui.graphics.vector.ImageVector = when (filter) {
    LibraryFilter.TRACKS -> CupertinoIcons.MusicNote
    LibraryFilter.ALBUMS -> CupertinoIcons.AlbumFilled
    LibraryFilter.ARTISTS -> CupertinoIcons.PersonFilled
    LibraryFilter.PLAYLISTS -> CupertinoIcons.PlaylistMusic
}

internal fun filterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.TRACKS -> "Morceaux"
    LibraryFilter.ALBUMS -> "Albums"
    LibraryFilter.ARTISTS -> "Artistes"
    LibraryFilter.PLAYLISTS -> "Playlists"
}

/** A round glass button of the pages' headers (96 across by default). */
@Composable
internal fun GlassCircle(
    size: Dp,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .glassSurface(CircleShape, pressed)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The header of a page opened from the menu: title at 40, 112 with designSp(84f) (same position and size as Accueil and Bibliothèque),
 * and on the right the compact search button, along with page-specific action buttons (shuffle + sort for tracks/albums/artists, + for playlists).
 * No profile avatar, no back button.
 */
@Composable
internal fun LibraryPageHeader(
    filter: LibraryFilter,
    count: Int,
    accent: Color,
    sort: LibrarySort,
    sortMenu: GlassMenuState,
    profile: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onSearch: () -> Unit,
    onProfile: (() -> Unit)? = null,
    onShuffle: () -> Unit,
    onCreate: () -> Unit,
    visibleProgress: Float = 1f,
) {
    val u = designUnit()
    Box(
        Modifier
            .fillMaxWidth()
            .height(u * 236f)
            .graphicsLayer {
                val clamped = visibleProgress.coerceIn(0f, 1f)
                alpha = clamped
                translationY = -u.toPx() * 50f * (1f - clamped)
            }
            .heroRipple(fast = true),
    ) {
        // Page Title: same style, position and translucency as Accueil and Bibliothèque (x = 40, y = 112, 84sp ExtraBold, alpha 42%)
        Text(
            text = filterLabel(filter),
            color = Color.White.copy(alpha = 0.42f),
            style = TextStyle(
                fontFamily = AuraFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = designSp(84f),
                lineHeight = designSp(96f),
                letterSpacing = designSp(84f * -0.03f),
            ),
            maxLines = 1,
            modifier = Modifier
                .offset(x = u * 40f, y = u * 112f)
                .tabStagger(1, 12, 300, risePx = 16f, baseMs = 0),
        )

        // Actions row aligned to the right at y = 112f
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = -(u * 40f), y = u * 112f)
                .tabStagger(2, 12, 300, risePx = 16f, baseMs = 0),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u * 16f),
        ) {
            // Search button (same icon & circular container as Accueil / Bibliothèque)
            Box(
                Modifier
                    .size(u * 96f)
                    .glassSurface(CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CupertinoIcons.Search, contentDescription = "Rechercher", tint = Color.White, modifier = Modifier.size(u * 44f))
            }

            if (filter == LibraryFilter.PLAYLISTS) {
                // New playlist button (+)
                Box(
                    Modifier
                        .size(u * 96f)
                        .glassSurface(CircleShape)
                        .clip(CircleShape)
                        .clickable(onClick = onCreate),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CupertinoIcons.Add, contentDescription = "Nouvelle playlist", tint = Color.White, modifier = Modifier.size(u * 46f))
                }
            } else {
                // Shuffle button
                Box(
                    Modifier
                        .size(u * 96f)
                        .glassSurface(CircleShape)
                        .clip(CircleShape)
                        .clickable(onClick = onShuffle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CupertinoIcons.Shuffle, contentDescription = "Lecture aléatoire", tint = Color.White, modifier = Modifier.size(u * 44f))
                }

                // Sort button (sort icon only, anchor for sortMenu)
                Box(
                    Modifier
                        .size(u * 96f)
                        .glassSurface(CircleShape)
                        .clip(CircleShape)
                        .glassMenuAnchor(sortMenu)
                        .clickable(onClick = sortMenu::open),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CupertinoIcons.Sort, contentDescription = "Trier", tint = Color.White, modifier = Modifier.size(u * 42f))
                }
            }
        }
    }
}

/** The page comes in whole, with the screen that carries it: its parts do not take turns. */
@Composable
internal fun LibraryPageArrival(content: @Composable () -> Unit) {
    content()
}
