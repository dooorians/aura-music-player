package com.ikko.aura.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.ui.components.backdrop.backdrops.LayerBackdrop
import com.ikko.aura.ui.theme.CupertinoIcons
import com.ikko.aura.viewmodel.LibraryFilter
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.ikko.aura.ui.components.backdrop.backdrops.layerBackdrop
import com.ikko.aura.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.ikko.aura.ui.theme.LocalAuraBatterySaver
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ikko.aura.ui.theme.LibraryTypography
import com.ikko.aura.ui.theme.LocalCoverColors
import kotlin.math.abs
import kotlin.math.min

/**
 * What the library tells the tab capsule about its scrolling, and what the capsule tells the library
 * about the room it takes. Provided once at the root.
 */
@Stable
class TabCapsuleState {
    /** 0 hidden, 1 shown; overshoots on the way in and winds up on the way out. Read in graphics layers. */
    val shown = Animatable(0f)

    /** Set by the library while the list is scrolled down; cleared by a little scrolling up, or at the top. */
    var scrolledAway by mutableStateOf(false)

    /** The room, in dp, the capsule asks of a list beneath it, animated so the list's end moves smoothly. */
    val inset = Animatable(0f)

    private var down = 0f
    private var up = 0f

    /** Fed the library's vertical scroll: negative is the list moving up (the finger going up). */
    fun onScroll(dy: Float, density: Float, atTop: Boolean) {
        if (atTop) { scrolledAway = false; down = 0f; up = 0f; return }
        if (dy < 0f) {
            up = 0f
            down += -dy
            if (down > PageMotionSpec.SCROLL_DOWN_HIDES_DP * density) scrolledAway = true
        } else if (dy > 0f) {
            down = 0f
            up += dy
            if (up > PageMotionSpec.SCROLL_UP_SHOWS_DP * density) scrolledAway = false
        }
    }
}

val LocalTabCapsule = compositionLocalOf<TabCapsuleState?> { null }

/** A scroll listener for the library's content: feeds [state], consumes nothing. */
@Composable
fun rememberCapsuleScrollConnection(state: TabCapsuleState?, density: Float, atTop: () -> Boolean): NestedScrollConnection =
    remember(state, density) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    state?.onScroll(available.y, density, atTop())
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

private data class CapsuleTab(
    val filter: LibraryFilter,
    val label: String,
    val icon: @Composable () -> ImageVector,
    val iconFilled: @Composable () -> ImageVector,
)

private val Tabs = listOf(
    CapsuleTab(LibraryFilter.PLAYLISTS, "Playlists", { CupertinoIcons.Library }, { CupertinoIcons.Library }),
    CapsuleTab(LibraryFilter.ALBUMS, "Albums", { CupertinoIcons.Album }, { CupertinoIcons.AlbumFilled }),
    CapsuleTab(LibraryFilter.ARTISTS, "Artistes", { CupertinoIcons.Person }, { CupertinoIcons.PersonFilled }),
    CapsuleTab(LibraryFilter.TRACKS, "Titres", { CupertinoIcons.MusicNote }, { CupertinoIcons.MusicNote }),
)

private val CapsuleShape = RoundedCornerShape(50)

/**
 * The library's four views as one glass capsule above the mini player: the same glass as the player
 * and the pill, its active tab a lit pastille. It comes in with a bouncy spring, stretching with its
 * own speed, and goes out with a wind-up — a little rise before it drops — then stays out of the way
 * of the list, which is what [TabCapsuleState] is told by the library.
 */
@Composable
fun TabCapsule(
    state: TabCapsuleState,
    selected: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(state) {
        // A light that crosses it as it arrives; never visible at rest.
        var wasBelow = true
        snapshotFlow { state.shown.value > 0.05f && state.shown.targetValue == 1f }.collect { arriving ->
            if (arriving && wasBelow) {
                sheen.snapTo(0f)
                sheen.animateTo(1f, androidx.compose.animation.core.tween(PageMotionSpec.SHEEN_MS))
            }
            wasBelow = !arriving
        }
    }

    val selectedIndex = libraryTabIndex(selected)
    val scope = rememberCoroutineScope()
    // What the capsule shows as chosen: the view, but from the moment a finger lifts rather than when the
    // list has answered, so the pastille does not start back toward the old tab in between.
    var chosen by remember { mutableIntStateOf(selectedIndex) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barWidthPx by remember { mutableFloatStateOf(0f) }

    // The lit pastille is one shape. At rest it sits on the chosen tab. Under a finger it is the finger's:
    // its position is the finger's, exactly, frame for frame — no spring between them — after a short glide
    // onto it from where it was. On lifting, it settles onto the tab the finger lifted from.
    val rest = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    val grip = remember { Animatable(0f) }

    val pressScale = remember { Animatable(1f) }
    var touchPivot by remember { mutableStateOf(TransformOrigin.Center) }
    var pressAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** Where, in tabs from the first, a finger at [x] puts the pastille's left edge (fractional). */
    fun pastilleAt(x: Float): Float {
        val pad = with(density) { TAB_PAD.toPx() }
        val gap = with(density) { TAB_GAP.toPx() }
        val w = (barWidthPx - 2 * pad - (Tabs.size - 1) * gap) / Tabs.size
        return ((x - pad - w / 2f) / (w + gap)).coerceIn(0f, (Tabs.size - 1).toFloat())
    }
    fun shownAt(): Float = rest.value + (finger - rest.value) * grip.value

    LaunchedEffect(selectedIndex) {
        chosen = selectedIndex
        if (!dragging) rest.animateTo(selectedIndex.toFloat(), androidx.compose.animation.core.spring(CAPSULE_REST_DAMPING, 520f, 0.001f))
    }
    // The tab lit up (filled icon, white label) is the one the pastille is nearest to.
    val displayIndex by remember { derivedStateOf { if (dragging) (finger + 0.5f).toInt() else chosen } }
    val currentOnSelect by rememberUpdatedState(onSelect)

    val cover = LocalCoverColors.current
    androidx.compose.runtime.CompositionLocalProvider(LocalAuraBackdrop provides backdrop) {
        // The touch zone is 48 dp tall, the drawing 45: the pointer handler is on the taller box, and the
        // capsule's own motion is applied to both together.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(PageMotionSpec.CAPSULE_TOUCH_HEIGHT_DP.dp)
                .graphicsLayer {
                    val p = state.shown.value
                    val v = min(abs(state.shown.velocity) / PageMotionSpec.CAPSULE_STRETCH_FULL_AT, 1f)
                    val scale = PageMotionSpec.CAPSULE_SCALE_FROM + (1f - PageMotionSpec.CAPSULE_SCALE_FROM) * p
                    scaleX = scale * (1f - PageMotionSpec.CAPSULE_STRETCH_X * v)
                    scaleY = scale * (1f + PageMotionSpec.CAPSULE_STRETCH_Y * v)
                    translationY = (1f - p) * PageMotionSpec.CAPSULE_RISE_DP.dp.toPx()
                    alpha = (1.6f * p).coerceIn(0f, 1f)
                }
                .onSizeChanged { barWidthPx = it.width.toFloat() }
                // One handler for the whole capsule, so a finger can go down on one tab, slide to another
                // and lift: what is chosen is where it lifts, not where it landed.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val pad = TAB_PAD.toPx()
                    val gap = TAB_GAP.toPx()
                    fun tabAt(x: Float): Int {
                        val w = (size.width - 2 * pad - (Tabs.size - 1) * gap) / Tabs.size
                        return ((x - pad) / (w + gap)).toInt().coerceIn(0, Tabs.size - 1)
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (state.shown.value <= PageMotionSpec.CAPSULE_INTERACTIVE_ABOVE) continue
                            val pressStart = System.currentTimeMillis()
                            val wPx = this.size.width.toFloat()
                            val hPx = this.size.height.toFloat()
                            if (wPx > 0f && hPx > 0f) {
                                touchPivot = TransformOrigin(
                                    (down.position.x / wPx).coerceIn(0f, 1f),
                                    (down.position.y / hPx).coerceIn(0f, 1f),
                                )
                            }
                            pressAnimJob?.cancel()
                            pressAnimJob = scope.launch {
                                pressScale.animateTo(
                                    targetValue = 1.045f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.82f, // Apple interactive soft cushion
                                        stiffness = 160f,
                                    ),
                                )
                            }
                            finger = pastilleAt(down.position.x)
                            dragging = true
                            scope.launch {
                                rest.stop()
                                grip.animateTo(1f, androidx.compose.animation.core.tween(GRIP_MS))
                            }
                            var last = down.position.x
                            do {
                                val change = awaitPointerEvent().changes.first()
                                if (change.pressed) {
                                    last = change.position.x
                                    finger = pastilleAt(last)
                                    change.consume()
                                }
                            } while (change.pressed)
                            val elapsed = System.currentTimeMillis() - pressStart
                            pressAnimJob?.cancel()
                            pressAnimJob = scope.launch {
                                val minHoldMs = 100L
                                if (elapsed < minHoldMs) kotlinx.coroutines.delay(minHoldMs - elapsed)
                                pressScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.58f, // Apple .bouncy: soft organic overshoot
                                        stiffness = 135f,
                                    ),
                                )
                            }
                            val index = tabAt(last)
                            chosen = index
                            val from = shownAt()
                            dragging = false
                            scope.launch {
                                // Hand over from the finger's position to the resting one without a jump.
                                rest.snapTo(from)
                                grip.snapTo(0f)
                                rest.animateTo(index.toFloat(), androidx.compose.animation.core.spring(CAPSULE_REST_DAMPING, 520f, 0.001f))
                            }
                            currentOnSelect(Tabs[index].filter)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val dynamicTint = androidx.compose.runtime.remember(cover.accent) { androidx.compose.ui.graphics.Color(androidx.core.graphics.ColorUtils.blendARGB(CapsuleGlassTint.toArgb(), cover.accent.toArgb(), 0.18f)) }
            LiquidGlassPanel(
                shape = CapsuleShape,
                tint = dynamicTint,
                tintAlpha = CAPSULE_GLASS_ALPHA,
                blurRadiusDp = CAPSULE_BLUR_DP,
                dropShadow = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PageMotionSpec.CAPSULE_HEIGHT_DP.dp)
                    .graphicsLayer {
                        scaleX = pressScale.value
                        scaleY = pressScale.value
                        transformOrigin = touchPivot
                    }
                    .clip(CapsuleShape)
                    // Clears what the page drew here before the glass draws. See FloatingPlayerPill for what that buys.
                    .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
                    .sheen(position = { 1.3f - 1.7f * sheen.value }, strength = { if (sheen.value in 0.001f..0.999f) 1f else 0f }),
            ) {
                // The lit pastille sits under the labels: the accent at 26%, with a dp of the accent at 62%.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(TAB_PAD)
                        .layout { measurable, constraints ->
                            val gap = TAB_GAP.roundToPx()
                            // Never below zero: measured narrower than its gaps for a frame at launch, it asked for a
                            // negative width and crashed the app.
                            val w = ((constraints.maxWidth - (Tabs.size - 1) * gap) / Tabs.size).coerceAtLeast(0)
                            val placeable = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place((shownAt() * (w + gap)).roundToInt(), 0)
                            }
                        }
                        .clip(CapsuleShape)
                        .drawBehind { drawRect(cover.accent.copy(alpha = 0.26f)) }
                        .border(BorderStroke(1.dp, cover.accent.copy(alpha = 0.62f)), CapsuleShape),
                )
                Row(
                    Modifier.fillMaxSize().padding(TAB_PAD),
                    horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
                ) {
                    Tabs.forEachIndexed { index, tab ->
                        val active = index == displayIndex
                        val tint by androidx.compose.animation.animateColorAsState(
                            targetValue = if (active) Color.White else cover.tint.copy(alpha = 0.84f),
                            animationSpec = androidx.compose.animation.core.tween(160),
                            label = "tab-tint",
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (active) tab.iconFilled() else tab.icon(),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    tab.label,
                                    color = tint,
                                    style = if (active) LibraryTypography.CapsuleLabelActive else LibraryTypography.CapsuleLabel,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dark navy over the blur — rgba(8, 12, 28, 0.56) — so the labels read over any cover, however pale or bright. */
private val CapsuleGlassTint = Color(0xFF080C1C)
private const val CAPSULE_GLASS_ALPHA = 0.56f
private const val CAPSULE_BLUR_DP = 16f

private val TAB_PAD = 4.dp
private val TAB_GAP = 2.dp

/** How long the pastille takes to glide from where it was onto a finger that has just landed. */
private const val GRIP_MS = 90

/** Where a view sits in the capsule, left to right — also the direction the list slides when it changes. */
fun libraryTabIndex(filter: LibraryFilter): Int = Tabs.indexOfFirst { it.filter == filter }.coerceAtLeast(0)

/**
 * Whether the capsule should be out. In when the library has come about 90% of the way and stays in
 * until the list is scrolled down or the page starts to be pulled back toward the first one.
 */
fun capsuleWanted(page: Float, dragging: Boolean, scrolledAway: Boolean, onTabs: Boolean): Boolean =
    onTabs &&
        page in PageMotionSpec.CAPSULE_APPEARS_AT_PAGE..(2f - PageMotionSpec.CAPSULE_APPEARS_AT_PAGE) &&
        !(dragging && abs(page - 1f) > 0.02f) &&
        !scrolledAway

/** Where the capsule's bottom edge is from the screen's, given the room reserved for the player. */
fun capsuleBottom(reservedForPlayer: Dp, navBar: Dp, hasPlayer: Boolean): Dp =
    // The touch box is 1.5 dp taller than the drawing at each end, hence the extra 1.5 off.
    if (hasPlayer) reservedForPlayer + PageMotionSpec.CAPSULE_GAP_TO_PLAYER_DP.dp - 7.5.dp else navBar + 10.5.dp

/** The capsule's highlight settling on its tab: barely a bounce (~0.3% overshoot; it was 0.8, ~1.5%). */
private const val CAPSULE_REST_DAMPING = 0.88f
