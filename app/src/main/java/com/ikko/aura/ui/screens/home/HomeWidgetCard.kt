package com.ikko.aura.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.currentStateAsState
import com.ikko.aura.data.home.WidgetArt
import com.ikko.aura.data.home.WidgetContent
import com.ikko.aura.data.home.WidgetSize
import com.ikko.aura.ui.components.AlbumArt
import com.ikko.aura.ui.components.AuraButton
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.glassEdge
import com.ikko.aura.ui.components.auroraBackdrop
import com.ikko.aura.ui.components.glassEdgeColor
import com.ikko.aura.ui.components.glassGradient
import com.ikko.aura.ui.components.rememberArtColors
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.ui.theme.LibraryTypography

/** How long a card's colours take to melt into the next track's. */
private const val COLOUR_FADE_MS = 900

private val NoCorners = RoundedCornerShape(0.dp)

/** The corner radius of a card of each size: the small tiles' corners are tighter, so they stay in proportion. */
private fun cornerFor(size: WidgetSize): Dp = when (size) {
    WidgetSize.COMPACT -> 22.dp
    WidgetSize.WIDE -> 24.dp
    WidgetSize.MEDIUM -> 26.dp
    WidgetSize.HERO -> 30.dp
}

/**
 * One Home widget, drawn for whatever [WidgetContent] it was given and whatever [size] it has been given.
 * Optimized for buttery-smooth 96Hz scrolling:
 * - Direct `when (size)` dispatch without heavy animation specs
 * - Direct GPU alpha blending without offscreen FBO render targets
 * - Hardware-accelerated, lightweight [WidgetActionButton]s sized generously for touch
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWidgetCard(
    content: WidgetContent,
    size: WidgetSize,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    showText: Boolean = true,
    mesh: Boolean = false,
    palette: List<Color>? = null,
    flip: Boolean = false,
) {
    val shape = RoundedCornerShape(cornerFor(size))
    // The gradient is for the small tiles only; the bigger cards keep their covers.
    val mesh = mesh && size == WidgetSize.COMPACT
    val tint = remember(content.hue) {
        Brush.linearGradient(
            listOf(Color.hsl(content.hue, 0.48f, 0.30f), Color.hsl((content.hue + 40f) % 360f, 0.52f, 0.17f)),
        )
    }
    val wide = size == WidgetSize.WIDE
    val artColors = if ((mesh || wide) && palette == null) rememberArtColors(content.artwork, content.hue) else emptyList()
    val colors = palette ?: artColors
    Box(
        modifier = modifier
            .clip(shape)
            .let { if (mesh) it else it.background(tint) }
            .semantics { if (!showText) contentDescription = "${content.title}, ${content.subtitle.orEmpty()}" }
            .combinedClickable(
                onClickLabel = "Ouvrir",
                onClick = onClick,
                onLongClickLabel = "Personnaliser",
                onLongClick = onMore,
            ),
    ) {
        // Behind the text: the old gradients. The wide cards get the still aurora of their album's colours, the small ones
        // a glass gradient; both melt into the next track's colours.
        if (wide) Crossfade(targetState = colors, modifier = Modifier.matchParentSize(), animationSpec = tween(COLOUR_FADE_MS), label = "aurora") { c ->
            Spacer(Modifier.fillMaxSize().auroraBackdrop(c))
        }
        if (mesh) {
            Crossfade(targetState = colors, modifier = Modifier.matchParentSize(), animationSpec = tween(COLOUR_FADE_MS), label = "gradient") { c ->
                Spacer(Modifier.fillMaxSize().glassGradient(c, seed = content.hue.toInt() * 31 + content.title.hashCode(), exact = palette != null, flip = flip))
            }
        }
        if (content.gif != null) GifBody(content.gif)
        val body = WidgetBody(
            title = if (showText) content.title.ifBlank { "Sans titre" } else "",
            caption = (if (content.isEmpty) content.hint else content.subtitle)?.takeIf { it.isNotBlank() && showText },
            canPlay = !content.isEmpty,
            showActions = showActions,
            mesh = mesh,
        )
        if (content.gif == null) when (size) {
            WidgetSize.COMPACT -> CompactBody(content, body, onClick, onPlay, onShuffle, onMore)
            WidgetSize.MEDIUM -> MediumBody(content, body, onClick, onPlay, onShuffle, onMore)
            WidgetSize.WIDE -> WideBody(content, body, onClick, onPlay, onMore)
            WidgetSize.HERO -> HeroBody(content, body, onClick, onPlay, onShuffle, onMore)
        }
        // The card's edge, over its cover. Each card takes the light from its own direction, worked out from its hue, so
        // neighbours never carry the same line.
        if (mesh) {
            // The lit glass of the references: an edge in the card's own colour, brightest across the top.
            Crossfade(targetState = colors, modifier = Modifier.matchParentSize(), animationSpec = tween(COLOUR_FADE_MS), label = "edge") { c ->
                Spacer(Modifier.fillMaxSize().glassEdge(shape, strength = 1.7f, angleDegrees = 80f + (content.hue * 3f) % 20f, tint = glassEdgeColor(c)))
            }
        } else {
            Spacer(Modifier.matchParentSize().glassEdge(shape, strength = 1.15f, angleDegrees = 25f + (content.hue * 7f) % 130f))
        }
    }
}

/** What every layout needs of the content, worked out once with the fallbacks applied. */
private class WidgetBody(val title: String, val caption: String?, val canPlay: Boolean, val showActions: Boolean, val mesh: Boolean)

@Composable
private fun CompactBody(
    content: WidgetContent,
    body: WidgetBody,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content.artwork.firstOrNull()?.takeIf { !body.mesh }?.let { art ->
            AlbumArt(
                uri = art.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = NoCorners,
                targetSizeDp = 100.dp,
                cacheKey = art.cacheKey,
            )
            // Dark gradient wash over artwork - direct blend, zero offscreen layer allocation!
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            1f to Color.Black.copy(alpha = 0.38f),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (body.title.isNotEmpty()) WidgetTitle(body.title, fontSize = 15.sp, maxLines = 1)
            }
            if (body.showActions) {
                ActionRow(
                    canPlay = body.canPlay,
                    openRoute = content.openRoute,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onMore = onMore,
                    buttonSize = 36.dp, // Generous, comfortable touch target
                    iconSize = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun MediumBody(
    content: WidgetContent,
    body: WidgetBody,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (!body.mesh) FullBleedCover(content.artwork.firstOrNull(), targetSize = 180.dp)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.58f),
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.65f),
                ),
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (body.title.isNotEmpty()) WidgetTitle(body.title, fontSize = 18.sp, maxLines = 2)
                body.caption?.let {
                    Spacer(Modifier.height(2.dp))
                    WidgetCaption(it, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (body.showActions) {
                ActionRow(
                    canPlay = body.canPlay,
                    openRoute = content.openRoute,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onMore = onMore,
                    buttonSize = 38.dp,
                    iconSize = 17.dp,
                )
            }
            if (content.isCarousel && content.artwork.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 58.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = true,
                ) {
                    items(content.artwork, key = { it.cacheKey }) { art ->
                        AlbumArt(
                            uri = art.uri,
                            contentDescription = "Album favori",
                            modifier = Modifier
                                .size(78.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClick = onOpen),
                            shape = RoundedCornerShape(14.dp),
                            targetSizeDp = 96.dp,
                            cacheKey = art.cacheKey,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WideBody(
    content: WidgetContent,
    body: WidgetBody,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // The text sits on the aurora at the left; the cover takes the right half, whole height, and melts into the
        // aurora towards the text. With no cover to show, the aurora has the whole card.
        val hasCover = content.artwork.firstOrNull()?.uri != null
        if (hasCover) Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Black, 1f to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) { FullBleedCover(content.artwork.firstOrNull(), targetSize = 160.dp) }
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.fillMaxWidth(if (hasCover) 0.55f else 0.8f)) {
                if (body.title.isNotEmpty()) WidgetTitle(body.title, fontSize = 17.sp, maxLines = 1)
                body.caption?.let { WidgetCaption(it, fontSize = 12.sp, maxLines = 2) }
            }
            Spacer(Modifier.weight(1f))
        if (body.showActions) {
            if (body.canPlay) {
                WidgetActionButton(
                    onClick = onPlay,
                    icon = CupertinoIcons.Play,
                    contentDescription = "Lire ${body.title}",
                    size = 40.dp,
                    iconSize = 18.dp,
                    primary = true,
                )
            } else if (content.openRoute != null) {
                WidgetActionButton(
                    onClick = onOpen,
                    icon = CupertinoIcons.ChevronRight,
                    contentDescription = "Ouvrir",
                    size = 38.dp,
                    iconSize = 17.dp,
                )
            }
        }
        }
    }
}

@Composable
private fun HeroBody(
    content: WidgetContent,
    body: WidgetBody,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (!body.mesh) FullBleedCover(content.artwork.firstOrNull(), targetSize = 260.dp)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.42f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.80f),
                ),
            ),
        )
        val others = content.artwork.drop(1)
        if (others.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                others.forEach { SmallCover(it, size = 44.dp, corner = 12.dp, bordered = true) }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (body.title.isNotEmpty()) WidgetTitle(body.title, fontSize = 26.sp, maxLines = 2, weight = FontWeight.ExtraBold)
            body.caption?.let {
                Spacer(Modifier.height(2.dp))
                WidgetCaption(it, fontSize = 13.sp, maxLines = 2)
            }
            if (body.showActions) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (body.canPlay) {
                        WidgetPrimaryPillButton(onClick = onPlay, text = "Lire", icon = CupertinoIcons.Play)
                        WidgetActionButton(
                            onClick = onShuffle,
                            icon = CupertinoIcons.Shuffle,
                            contentDescription = "Lecture aléatoire",
                            size = 42.dp,
                            iconSize = 18.dp,
                        )
                    } else if (content.openRoute != null) {
                        WidgetPrimaryPillButton(onClick = onOpen, text = "Ouvrir", icon = CupertinoIcons.ChevronRight)
                    }
                }
            }
        }
    }
}

/** A round action on a card, in the same dark glass and edge as every other round button of the app. */
@Composable
fun WidgetActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 16.dp,
    primary: Boolean = false,
) {
    AuraIconButton(onClick = onClick, modifier = modifier, size = size) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize), tint = Color.White)
    }
}

/** The pill "Lire" / "Ouvrir" on Hero cards: the app's own button. */
@Composable
private fun WidgetPrimaryPillButton(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
) {
    AuraButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(percent = 50), minHeight = height) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Play, shuffle (or open when route exists) and more action row. */
@Composable
private fun ActionRow(
    canPlay: Boolean,
    openRoute: String?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMore: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canPlay) {
            WidgetActionButton(
                onClick = onPlay,
                icon = CupertinoIcons.Play,
                contentDescription = "Lire",
                size = buttonSize,
                iconSize = iconSize,
            )
            WidgetActionButton(
                onClick = onShuffle,
                icon = CupertinoIcons.Shuffle,
                contentDescription = "Lecture aléatoire",
                size = buttonSize,
                iconSize = iconSize,
            )
        } else if (openRoute != null) {
            WidgetActionButton(
                onClick = onOpen,
                icon = CupertinoIcons.ChevronRight,
                contentDescription = "Ouvrir",
                size = buttonSize,
                iconSize = iconSize,
            )
        }
    }
}

@Composable
private fun WidgetTitle(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    maxLines: Int,
    weight: FontWeight = FontWeight.Bold,
) {
    Text(
        text = text,
        style = LibraryTypography.AlbumTitle.copy(fontSize = fontSize, lineHeight = fontSize * 1.18f, fontWeight = weight),
        color = Color.White,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WidgetCaption(text: String, fontSize: androidx.compose.ui.unit.TextUnit, maxLines: Int) {
    Text(
        text = text,
        style = LibraryTypography.AlbumArtist.copy(fontSize = fontSize, lineHeight = fontSize * 1.25f),
        color = Color.White.copy(alpha = 0.74f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The cover across the whole card, or — with none — the card's tint and a faint note. */
@Composable
private fun FullBleedCover(art: WidgetArt?, targetSize: Dp) {
    if (art != null) {
        AlbumArt(
            uri = art.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            shape = NoCorners,
            targetSizeDp = targetSize,
            cacheKey = art.cacheKey,
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                CupertinoIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxHeight(0.42f).aspectRatio(1f),
            )
        }
    }
}

/** A cover as a small square, or a note on the tint when there is none. */
@Composable
private fun SmallCover(art: WidgetArt?, size: Dp, corner: Dp, bordered: Boolean = false) {
    val shape = RoundedCornerShape(corner)
    val frame = Modifier.size(size).let { if (bordered) it.border(1.dp, Color.White.copy(alpha = 0.3f), shape) else it }
    if (art != null) {
        AlbumArt(
            uri = art.uri,
            contentDescription = null,
            modifier = frame,
            shape = shape,
            targetSizeDp = size,
            cacheKey = art.cacheKey,
        )
    } else {
        Box(frame.clip(shape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(
                CupertinoIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/**
 * A GIF of the user's, playing across the whole card, and only while some of the card is on screen.
 *
 * Its frames are drawn in software, at the GIF's own pace, on a thread of their own ([GifRenderer]) into two bitmaps in
 * turn: the drawable says when its next frame is due (through [android.graphics.drawable.Drawable.Callback]), the
 * picture is drawn into the bitmap not on screen, and the main thread is only told which bitmap to show. Measured
 * before this: each GIF frame drawn on the main thread took ~1.75 ms of it, and scrolling Home missed ~10% of its
 * deadlines against ~2% without the GIFs. Two other ways were measured and dropped: ticking a state at every vsync kept
 * the whole window, glass included, re-rendering at 96 Hz on an idle Home; leaving a started drawable to the
 * RenderThread made it render frames of its own (~6 ms each) out of step with the UI's.
 */
@Composable
private fun GifBody(path: String) {
    var drawable by remember(path) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    var onScreen by remember { mutableStateOf(false) }
    // Which of the two bitmaps is on screen, and a counter bumped with each new frame: both read in the draw phase only.
    var front by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(path) {
        drawable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.io.File(path))) { decoder, info, _ ->
                    val scale = minOf(1f, GIF_MAX_WIDTH_PX / info.size.width)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    val current = drawable
    // Only while the app is in front: the composition outlives a trip to the background, and the GIF went on drawing there.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val playing = onScreen && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val buffers = remember(current) {
        current?.let { d ->
            Array(2) { android.graphics.Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888) }
        }
    }
    val images = remember(buffers) { buffers?.map { it.asImageBitmap() } }
    androidx.compose.runtime.DisposableEffect(current, buffers, playing) {
        val d = current
        val bufs = buffers
        if (d == null || bufs == null) return@DisposableEffect onDispose { }
        val renderer = GifRenderer(d, bufs, front) { shown -> front = shown; version++ }
        val animated = d as? android.graphics.drawable.AnimatedImageDrawable
        if (animated != null && playing) {
            animated.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
            animated.start()
        }
        renderer.request()
        onDispose {
            animated?.stop()
            renderer.close()
        }
    }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                // Clipped by every ancestor: empty once scrolled out of the page or on the page not shown.
                val visible = coordinates.boundsInWindow().let { it.width > 0f && it.height > 0f }
                if (visible != onScreen) onScreen = visible
            }
            // A layer of its own: a new GIF frame re-records this picture only, not the card around it.
            .graphicsLayer { }
            .drawWithContent {
                version // read here, in the draw phase: a new GIF frame redraws the card and nothing recomposes
                val img = images?.getOrNull(front) ?: return@drawWithContent
                // Centre crop: the part of the picture with the card's proportions, scaled to fill it.
                val scale = maxOf(size.width / img.width, size.height / img.height)
                val srcW = (size.width / scale).toInt().coerceIn(1, img.width)
                val srcH = (size.height / scale).toInt().coerceIn(1, img.height)
                drawImage(
                    image = img,
                    srcOffset = androidx.compose.ui.unit.IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2),
                    srcSize = androidx.compose.ui.unit.IntSize(srcW, srcH),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            },
    )
}

/**
 * Draws a GIF's frames, one at a time and when each is due, on the shared `aura-gif` thread, into whichever of [buffers]
 * is not on screen, then hands that one to [onFrame] on the main thread. The next frame is not drawn until the main
 * thread has taken the last one, so the bitmap being shown is never the one being written.
 */
private class GifRenderer(
    private val drawable: android.graphics.drawable.Drawable,
    private val buffers: Array<android.graphics.Bitmap>,
    shown: Int,
    private val onFrame: (Int) -> Unit,
) {
    private val worker = gifThread
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val token = Any()
    @Volatile private var closed = false
    @Volatile private var front = shown
    // Both guarded by [lock]: a frame due while the last one is still being handed over is drawn once it has been.
    private val lock = Any()
    private var handing = false
    private var pending = false
    private val render = Runnable { renderNow() }
    /** Set by [callback] when the drawable asks for its next frame during a draw. Only touched on the worker thread. */
    private var scheduled = false

    // Held here, strongly: a drawable keeps only a weak reference to its callback, and an anonymous one with nothing else
    // holding it is collected — the GIF then never schedules its next frame.
    private val callback = object : android.graphics.drawable.Drawable.Callback {
        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = request()
        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) {
            scheduled = true
            // Never sooner than ~60 per second, whatever the file asks.
            worker.postAtTime(what, token, maxOf(`when`, android.os.SystemClock.uptimeMillis() + GIF_MIN_FRAME_MS))
        }
        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) = worker.removeCallbacks(what, token)
    }

    init {
        drawable.callback = callback
    }

    fun request() {
        if (!closed) worker.postAtTime(render, token, android.os.SystemClock.uptimeMillis())
    }

    private fun renderNow() {
        if (closed) return
        synchronized(lock) {
            if (handing) { // the main thread has not taken the last frame yet: it asks again when it has
                pending = true
                return
            }
        }
        val back = if (front == 0) 1 else 0
        val bitmap = buffers[back]
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        scheduled = false
        drawable.draw(android.graphics.Canvas(bitmap)) // in software, this also schedules the next frame through [callback]
        // Drawn less than a millisecond before its next frame is due, the drawable reports 0 ms to wait — which it takes to
        // mean "the RenderThread animates me" — and schedules nothing: the GIF would freeze there for good. Ask again soon.
        if (!scheduled && (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.isRunning == true) {
            worker.postAtTime(render, token, android.os.SystemClock.uptimeMillis() + GIF_RETRY_MS)
        }
        synchronized(lock) { handing = true }
        main.post {
            if (!closed) {
                front = back
                onFrame(back)
            }
            val again = synchronized(lock) {
                handing = false
                pending.also { pending = false }
            }
            if (again) request()
        }
    }

    fun close() {
        closed = true
        worker.removeCallbacksAndMessages(token)
        if (drawable.callback === callback) drawable.callback = null
    }

    private companion object {
        /** One thread for every GIF on Home: they are small, and each frame is drawn when it is due, not continuously. */
        val gifThread: android.os.Handler by lazy {
            val thread = android.os.HandlerThread("aura-gif", android.os.Process.THREAD_PRIORITY_DEFAULT).apply { start() }
            android.os.Handler(thread.looper)
        }
    }
}

private const val GIF_MIN_FRAME_MS = 16L
private const val GIF_RETRY_MS = 4L

private const val GIF_MAX_WIDTH_PX = 600f

/** How far the blurred covers' colours are pushed: 1 is the cover as it is. */
private const val COVER_SATURATION = 1.9f

