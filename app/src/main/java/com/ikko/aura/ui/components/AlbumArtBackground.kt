package com.ikko.aura.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.ikko.aura.ui.theme.AuraColors
import com.ikko.aura.ui.theme.LocalAuraBatterySaver
import com.ikko.aura.ui.theme.AuraMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Apple Music-style background: a heavily blurred, slowly panning/zooming crop of the
 * playing track's own artwork, darkened with a scrim for legibility. Falls back to a
 * static gradient (the extracted palette) when there's no artwork, in monochrome mode, or
 * on low-power devices where a continuous animated bitmap blur would be wasteful.
 */
@Composable
fun AlbumArtBackground(
    artworkUri: Uri?,
    // Album id rather than the track's own URI: every track carries a different artworkUri even
    // within one album, so keying on it reloaded, re-blurred and cross-faded the *same* cover on
    // every track change. Keyed by album, the backdrop only changes when the cover actually does.
    artworkKey: String?,
    fallbackColors: List<Color>,
    modifier: Modifier = Modifier,
    // The AMOLED setting is for the rest of the app. The player is designed around the blurred
    // cover sitting behind its glass panels — with the backdrop blacked out there is nothing for
    // them to refract and they collapse into flat grey rectangles — so opening it lifts the black
    // rather than being overruled by the setting.
    forceArtwork: Boolean = false,
    /**
     * Applied to the **settled** backdrop only — the cover that is fully arrived, its pan and its
     * scrim. This is what [com.ikko.aura.ui.components.LiquidGlassPanel] records and samples.
     *
     * The cover on its way out is drawn *above* this, outside whatever this modifier records, and
     * that placement is the whole point. A cross-fade inside the recorded layer marks it dirty on
     * every frame of the fade, and each of those frames costs the player's six glass surfaces a
     * full colour-matrix, blur and lens pass — measured over six track changes as 9.9% janky
     * frames at p90 42ms. Drawn outside, the recorded layer is finished the instant the new cover
     * lands, the glass runs its chain once, and the fade costs one more full-screen image.
     *
     * What it buys is one small inaccuracy: for the length of the fade the glass refracts the
     * incoming cover while the screen still mostly shows the outgoing one. Through a 40% dark
     * tint and an 8dp blur, on panels a few hundred pixels wide, that is not something anyone can
     * see — and it is the difference between a smooth track change and a stuttering one.
     */
    settledModifier: Modifier = Modifier,
) {
    val batterySaver = LocalAuraBatterySaver.current
    val context = LocalContext.current
    // Deliberately not keyed on the artwork: resetting to null while the next cover decodes is
    // what made the change abrupt — the old image vanished to black before the new one arrived.
    // Holding the previous bitmap until its replacement is ready lets the two cross-fade instead.
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(artworkKey) {
        // The backdrop waits for the track change to finish before it moves.
        //
        // Swapping it is the single most expensive frame the player has: the recorded layer is
        // new, so all six glass surfaces re-record it and push it through their colour matrix,
        // blur and lens in one go — traced at 22ms of draw on the UI thread. Landing that in the
        // same 280ms as the artwork slide, the title slide, the lyric capsule resizing and the
        // up-next row shifting is what made a track change feel choppy; landing it just after,
        // when nothing else is moving, costs the same milliseconds where there is room for them.
        //
        // It also coalesces a fast swipe for free: each new track cancels this effect before the
        // delay is out, so flicking through ten tracks decodes, blurs and swaps one backdrop —
        // the one you stopped on — instead of ten.
        //
        // Invisible, because the backdrop is a heavily blurred wash that then cross-fades over
        // 280ms anyway. Skipped on the very first cover, where there is nothing to wait for.
        if (bitmap != null) delay(BACKDROP_SETTLE_MS)
        val source = AlbumArtCache.load(context, artworkUri, BACKDROP_SIZE_PX, artworkKey)
        bitmap = source?.let { withContext(Dispatchers.Default) { boxBlurred(it) } }
    }

    // The cover is for the player alone. Everywhere else the background is the atmosphere, and this
    // stops being drawn (and its pan stops costing anything) once the atmosphere has covered it.
    val blackedOut = !forceArtwork
    val blackAlpha = animateFloatAsState(
        targetValue = if (blackedOut) 1f else 0f,
        // Quicker going than coming: the artwork is what is left behind as the player clears, and
        // it lingered for the whole 320ms after the page it sat behind had already returned.
        animationSpec = tween(if (blackedOut) AuraMotion.SHEET_EXIT_MS else AuraMotion.SHEET_ARTWORK_IN_MS),
        label = "aura-backdrop-amoled",
    )
    // Composed until the black has finished covering it rather than the instant the setting says
    // so: leaving the player fades back to AMOLED instead of cutting to it a frame before the
    // transition has even started. Once covered it is dropped again, so the pan animation and the
    // full-screen draw stop costing anything — which is the whole point of the setting.
    val artComposed = !blackedOut || blackAlpha.value < 1f

    // Frozen under the player. The pan is a slow drift nobody watches directly, but every frame
    // of it re-records the backdrop layer, and the player has six liquid glass surfaces sampling
    // that layer through a blur and a lens shader — so the drift was costing six shader chains at
    // 90Hz. Measured on the device: 12.6% janky frames sitting still on the player with it on, 0%
    // with it off. The mockup's backdrop is static anyway; everywhere else it keeps moving.
    //
    // And only while the cover is drawn at all. Everywhere but the player it is covered by the atmosphere
    // (see [artComposed]) and nothing reads the pan — but an infinite animation runs whether or not
    // anything reads it, and this one asked for a frame every vsync, 96 times a second, on every page of
    // the app while nothing was moving. The main thread woke, the compositor was asked for frames, the
    // aurora's surface was re-posted: an idle screen was never idle.
    val panning = !batterySaver && !forceArtwork && artComposed
    val transition = rememberInfiniteTransition(label = "aura-art-pan")
    val pan = if (panning) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(PAN_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "aura-art-pan-progress",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    var shown by remember { mutableStateOf(bitmap) }
    var outgoing by remember { mutableStateOf<Bitmap?>(null) }
    // 1 while the outgoing cover is still fully there, 0 once it has gone.
    val leaving = remember { Animatable(0f) }
    LaunchedEffect(bitmap) {
        val current = bitmap ?: return@LaunchedEffect
        // No rapid-change guard any more: the settle delay above means a burst of track changes
        // only ever produces one new bitmap, so there is no burst left here to defend against.
        if (shown == null) {
            outgoing = null
            shown = current
            leaving.snapTo(0f)
        } else {
            outgoing = shown
            shown = current
            leaving.snapTo(1f)
            leaving.animateTo(0f, tween(ARTWORK_CROSSFADE_MS, easing = FastOutSlowInEasing))
            outgoing = null
        }
    }

    val settled = shown

    // The whole cover layer fades over the aurora behind it: in as the player opens, out as it closes.
    // The glass records the cover inside this, at full strength, so the fade never reaches it.
    Box(modifier.fillMaxSize().graphicsLayer { alpha = 1f - blackAlpha.value }) {
        // Everything the glass samples, and nothing that is mid-animation.
        Box(Modifier.fillMaxSize().then(settledModifier)) {
            if (artComposed) {
                if (settled != null) {
                    PannedArt(settled, pan)
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    fallbackColors.ifEmpty { AuraColors.DefaultMesh },
                                ),
                            ),
                    )
                }
                // Inside the record, so the glass refracts the backdrop at the brightness it is
                // actually drawn at rather than the raw cover.
                Box(Modifier.fillMaxSize().background(AuraColors.GlassScrim))
            }
        }

        // The cover being left behind: opaque at the start of the fade, gone at the end, carrying
        // its own scrim so the two are dissolving between like for like.
        val leavingArt = outgoing
        if (artComposed && leavingArt != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = leaving.value },
            ) {
                PannedArt(leavingArt, pan)
                Box(Modifier.fillMaxSize().background(AuraColors.GlassScrim))
            }
        }
    }
}

/**
 * One cover under the slow pan. [progress] is read inside the [graphicsLayer] block, so a frame of
 * drift re-transforms the layer rather than recomposing anything.
 */
@Composable
private fun PannedArt(bitmap: Bitmap, progress: State<Float>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val zoom = 1.35f + 0.15f * progress.value
                scaleX = zoom
                scaleY = zoom
                translationX = (progress.value - 0.5f) * size.width * 0.12f
                translationY = (0.5f - progress.value) * size.height * 0.10f
            },
    ) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The blur is baked into a small bitmap once per artwork, off the UI thread, instead of being a
 * `RenderEffect` on the drawn layer.
 *
 * The original version set `RenderEffect.createBlurEffect(140f, 140f)` *inside* the
 * `graphicsLayer` block. That block re-runs on every frame of the infinite pan animation, so a
 * new RenderEffect was allocated and a full-screen 140px blur pipeline rebuilt ~90 times a
 * second, permanently, behind every screen — including while scrolling the library. Relying on
 * bilinear upscaling alone was cheap but showed visible blocking on high-contrast artwork, so
 * the smoothing is done properly here, once: three box-blur passes approximate a Gaussian, and
 * at this size that is a couple of milliseconds of work per track change.
 *
 * The source is 192px rather than the 128 it started at. At 128 the blur flattened the cover into
 * a single colour wash, which is fine as a tint behind a list but not behind the player, where the
 * backdrop is the thing the glass panels refract — a wash gives them nothing to bend. Half again
 * the resolution keeps the cover's large shapes legible through the blur. It changes no per-frame
 * cost: the blur runs once per album on a background thread, and what is drawn each frame is one
 * full-screen image either way.
 */
private const val BACKDROP_SIZE_PX = 192
private const val BLUR_RADIUS = 9
private const val BLUR_PASSES = 3
// Shortened from 650ms: the crossfade draws two full-screen images at once and Haze re-samples
// all of it every frame, so its length is also the length of the app's most expensive draw. A
// swipe through the queue triggers one per track, and at 650ms they were overlapping.
private const val ARTWORK_CROSSFADE_MS = 280

/** How long after a track change the backdrop waits before moving. See [AlbumArtBackground]. */
private const val BACKDROP_SETTLE_MS = 320L
private const val PAN_DURATION_MS = 14_000


private fun boxBlurred(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 1 || h <= 1) return src
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val scratch = IntArray(w * h)
    repeat(BLUR_PASSES) {
        // Each pass writes its output transposed, so running it twice blurs both axes and
        // restores the original orientation.
        boxBlurAxis(pixels, scratch, w, h)
        boxBlurAxis(scratch, pixels, h, w)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxBlurAxis(src: IntArray, dst: IntArray, width: Int, height: Int) {
    val radius = BLUR_RADIUS.coerceAtMost(width - 1)
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val c = src[row + i.coerceIn(0, width - 1)]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        for (x in 0 until width) {
            dst[x * height + y] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val incoming = src[row + (x + radius + 1).coerceAtMost(width - 1)]
            val outgoing = src[row + (x - radius).coerceAtLeast(0)]
            r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
            g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
            b += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}
