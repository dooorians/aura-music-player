package com.ikko.aura.ui.screens.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ikko.aura.data.lyrics.LyricLine
import com.ikko.aura.ui.components.AuraIconButton
import com.ikko.aura.ui.components.SweepLyricLine
import com.ikko.aura.ui.components.rememberLyricClock
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LyricsUiState
import com.ikko.aura.viewmodel.LyricsViewModel
import com.ikko.aura.viewmodel.PlayerViewModel
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ════════════════════════════════════════════════════════════════════════
// BitChord Lyrics Engine Constants & Physics
// ════════════════════════════════════════════════════════════════════════

private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.48f, 0.28f, 0.16f, 0.08f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 0.5.dp, 1.2.dp, 2.0.dp, 2.8.dp)
private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 420

private const val INACTIVE_SCALE = 0.90f

private val GAP_DOT_SIZE = 11.dp
private val GAP_DOT_GAP = 9.dp
private const val GAP_DOTS = 3
private const val GAP_DOT_REST = 0.28f

private val FallbackColors = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

/**
 * Lyrics screen matching BitChord's implementation tailored for compact square screen:
 *
 * 1. **MeshGradientBackground**: 4 luminous drifting color blobs sampled from the album palette,
 *    blurred into an organic mesh gradient with high contrast and deep rich base.
 * 2. **Lyrics Engine**: Centered vertical scroll, active/inactive scale, falloff alpha and blur,
 *    and cubic bezier easing.
 * 3. **Color Differentiation**: Active lyric is vivid glowing white, inactive lyrics are subtly
 *    tinted with an album-palette-derived grayish tone with progressive alpha falloff.
 * 4. **Real-time Instrumental Break**: 3 progressive dots filling up in real-time as the musical
 *    break elapses.
 * 5. **Fading Edges**: Soft progressive DstIn gradient edges.
 */
@Composable
fun LyricsScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
    isTransitioning: Boolean = false,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val positionMs by playerViewModel.positionMs.collectAsStateWithLifecycle()
    val lyricsState by lyricsViewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack = playerState.currentTrack

    LaunchedEffect(currentTrack?.id) { lyricsViewModel.loadFor(currentTrack) }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    val lyricsPalette = remember(playerState.meshColors, playerState.albumTheme) {
        playerState.meshColors.ifEmpty {
            listOfNotNull(
                playerState.albumTheme.accent,
                playerState.albumTheme.accentSoft,
                playerState.albumTheme.solid,
            ).ifEmpty { FallbackColors }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── BitChord MeshGradientBackground (The true Aurora mesh) ──────
        MeshGradientBackground(
            palette = lyricsPalette,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Lyrics Content ─────────────────────────────────────────────
        when (val state = lyricsState) {
            is LyricsUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            is LyricsUiState.Unavailable -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Paroles indisponibles pour ce titre",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            is LyricsUiState.Available -> BitChordLyricsPanel(
                lines = state.lines,
                positionMs = { positionMs },
                isPlaying = playerState.isPlaying,
                screenHeightDp = screenHeightDp,
                palette = lyricsPalette,
                onSeek = playerViewModel::seekTo,
            )
        }

        // ── Top Bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AuraIconButton(onClick = { navController.popBackStack() }, size = 42.dp) {
                Icon(CupertinoIcons.ChevronLeft, contentDescription = "Retour", modifier = Modifier.size(20.dp), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            AuraIconButton(onClick = { lyricsViewModel.refetch(currentTrack) }, size = 42.dp) {
                Icon(CupertinoIcons.Refresh, contentDescription = "Rechercher les paroles à nouveau", modifier = Modifier.size(18.dp), tint = Color.White)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// BitChord MeshGradientBackground (Living Aurora Mesh)
// ════════════════════════════════════════════════════════════════════════

@Composable
private fun MeshGradientBackground(
    palette: List<Color>,
    modifier: Modifier = Modifier,
    driftMillis: Int = 8_000,
    blurRadius: Dp = 80.dp,
) {
    val tuned = (palette.ifEmpty { FallbackColors } + FallbackColors)
        .take(4)
        .map { it.tuned() }

    val colorSpec: AnimationSpec<Color> = tween(1400)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "meshColor$index").value
    }
    val baseColor by animateColorAsState(tuned.first().dimmed(), colorSpec, label = "meshBase")

    val phase = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            phase.animateTo(
                targetValue = phase.value + (2 * Math.PI).toFloat(),
                animationSpec = tween(driftMillis * 4, easing = LinearEasing),
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.35f
                scaleY = 1.35f
            }
            .background(baseColor)
            .blur(blurRadius),
    ) {
        val anchors = listOf(
            Offset(0.20f, 0.25f),
            Offset(0.80f, 0.20f),
            Offset(0.75f, 0.80f),
            Offset(0.25f, 0.75f),
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.65f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.94f), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // High-contrast scrim so white lyrics stay luminous while colors remain vivid
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.10f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
    }
}

// ════════════════════════════════════════════════════════════════════════
// BitChord Lyrics List Panel
// ════════════════════════════════════════════════════════════════════════

@Composable
private fun BitChordLyricsPanel(
    lines: List<LyricLine>,
    positionMs: () -> Long,
    isPlaying: Boolean,
    screenHeightDp: Int,
    palette: List<Color>,
    onSeek: (Long) -> Unit,
) {
    val position = positionMs()
    val clock = rememberLyricClock(position, isPlaying)
    val listState = rememberLazyListState()

    val activeIndex = remember(lines, position) {
        lines.indexOfLast { it.timeMs <= position }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isFirstPlacement by remember { mutableStateOf(true) }

    // Scroll to center active lyric vertically on the 4.02" screen
    LaunchedEffect(activeIndex, isDragged) {
        if (isDragged) return@LaunchedEffect
        val targetIndex = activeIndex.coerceAtLeast(0)
        if (targetIndex in lines.indices) {
            if (isFirstPlacement) {
                listState.scrollToItem(targetIndex, 0)
                isFirstPlacement = false
            } else {
                listState.animateScrollToItem(targetIndex, 0)
            }
        }
    }

    // Typography tuned for compact 4.02-inch display (~432dp x 496dp)
    val activeStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start,
    )
    val inactiveStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Start,
    )

    // Palette-derived subtle grayish tint for inactive lyrics
    val palettePrimary = palette.firstOrNull() ?: FallbackColors.first()
    val inactiveTintColor = remember(palettePrimary) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(palettePrimary.toArgb(), hsl)
        hsl[1] = 0.18f // subtle album hue warmth/coolness
        hsl[2] = 0.72f // soft grayish slate tone
        Color(ColorUtils.HSLToColor(hsl))
    }

    val topPaddingDp = (screenHeightDp / 2 - 32).coerceAtLeast(100).dp
    val bottomPaddingDp = (screenHeightDp / 2 + 32).dp

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .fadingEdges(topFade = 52.dp, bottomFade = 56.dp),
        contentPadding = PaddingValues(
            top = topPaddingDp,
            bottom = bottomPaddingDp,
            start = 22.dp,
            end = 22.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val offset = if (activeIndex < 0) index else index - activeIndex
            val distance = abs(offset)
            val isActive = index == activeIndex

            val step = distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)
            val lineAlpha by animateFloatAsState(
                targetValue = if (isActive) 1f else LINE_FALLOFF_ALPHA[step],
                animationSpec = tween(durationMillis = LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricAlpha-$index",
            )
            val blur by animateDpAsState(
                targetValue = if (isActive) 0.dp else LINE_FALLOFF_BLUR[step],
                animationSpec = tween(durationMillis = LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "lyricBlur-$index",
            )
            val scale by animateFloatAsState(
                targetValue = if (isActive) 1f else INACTIVE_SCALE,
                animationSpec = spring(
                    dampingRatio = 0.65f, // Apple bouncy spring
                    stiffness = 160f,     // Soft, fluid transition
                ),
                label = "lyricScale-$index",
            )

            if (line.isGap) {
                // Real-time progressive instrumental break from BitChord
                val until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                val swell by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.68f, // Apple fluid focus spring
                        stiffness = 160f,
                    ),
                    label = "gapSwell-$index",
                )

                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .height(34.dp * swell)
                        .clipToBounds(),
                ) {
                    Box(
                        modifier = Modifier
                            .blur(blur, BlurredEdgeTreatment.Unbounded)
                            .clickable { onSeek(line.timeMs) }
                            .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                            .graphicsLayer {
                                val grow = 0.85f + 0.15f * swell
                                scaleX = grow
                                scaleY = grow
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                alpha = lineAlpha * swell
                            }
                            .drawBehind {
                                val span = (until - line.timeMs).coerceAtLeast(1L)
                                val through = ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                                val radius = GAP_DOT_SIZE.toPx() / 2f
                                val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                                repeat(GAP_DOTS) { dot ->
                                    val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                    val baseDotColor = if (isActive) Color.White else inactiveTintColor
                                    drawCircle(
                                        color = baseDotColor.copy(
                                            alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit,
                                        ),
                                        radius = radius,
                                        center = Offset(radius + dot * stride, size.height / 2f),
                                    )
                                }
                            },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = lineAlpha
                        }
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .clickable { onSeek(line.timeMs) },
                ) {
                    if (isActive && line.isWordSynced) {
                        SweepLyricLine(
                            line = line,
                            positionProvider = { clock.longValue },
                            style = activeStyle,
                            litColor = Color.White,
                            dimColor = inactiveTintColor.copy(alpha = 0.40f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = line.background?.let { "${line.text} ${it.text}" } ?: line.text,
                            style = if (isActive) activeStyle else inactiveStyle,
                            color = if (isActive) Color.White else inactiveTintColor,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    line.background?.let { backing ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = backing.text,
                            style = (if (isActive) activeStyle else inactiveStyle).copy(fontSize = 17.sp, lineHeight = 22.sp),
                            color = if (isActive) Color.White.copy(alpha = 0.75f) else inactiveTintColor.copy(alpha = 0.65f),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// BitChord Fading Edges Mask
// ════════════════════════════════════════════════════════════════════════

private fun Modifier.fadingEdges(topFade: Dp = 48.dp, bottomFade: Dp = 64.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val top = topFade.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = top,
            ),
            blendMode = BlendMode.DstIn,
        )
        val bottom = bottomFade.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - bottom,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// ════════════════════════════════════════════════════════════════════════
// BitChord Color Tuning
// ════════════════════════════════════════════════════════════════════════

private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.6f).coerceIn(0.55f, 1f)
    hsl[2] = hsl[2].coerceIn(0.32f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.08f
    return Color(ColorUtils.HSLToColor(hsl))
}
