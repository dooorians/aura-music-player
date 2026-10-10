package com.ikko.aura.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.random.Random

/**
 * The "F" backgrounds of the Claude Design project: a base colour and six large soft blobs of colour (blurred by 120 px of
 * the 1080-px design), a grain over them in Overlay at 14 %, and a dark fall-off over the bottom of the screen. One
 * [ImmersivePalette] per mood or per cover. The design is the phone's own size (1080 x 1240 px), so its pixels are
 * laid out directly: see [designUnit].
 */
@Stable
class ImmersivePalette(val base: Color, val blobs: List<Color>) {
    override fun equals(other: Any?) = other is ImmersivePalette && other.base == base && other.blobs == blobs
    override fun hashCode() = base.hashCode() * 31 + blobs.hashCode()
}

private fun c(hex: Long) = Color(0xFF000000 or hex)
private fun p(base: Long, vararg blobs: Long) = ImmersivePalette(c(base), blobs.map { c(it) })

/** The six moods of Home, as drawn (their file names: FHome*). */
object ImmersivePalettes {
    val Favoris = p(0x4a2fd0, 0xa98ff7, 0x2a1a9a, 0x7a3fe0, 0xb573e6, 0x1b0d6b, 0x3342c4)
    val Decouverte = p(0x1e5fe0, 0x7fc0ff, 0x3aa0ff, 0x0b2cb4, 0x6ad0ff, 0x0a1a7a, 0x2b8af0)
    val Nouveautes = p(0xd62a8a, 0xff7ac0, 0xff9ad4, 0xa00f64, 0xff3aa0, 0x7a0b52, 0xe85ab4)
    val Energie = p(0xe8620b, 0xffb02f, 0xffd060, 0xb03a05, 0xff7a1a, 0x8c2a07, 0xff9a20)
    val Chill = p(0x0b8f80, 0x1fbf90, 0x1a9ac0, 0x04504a, 0x2fd0a0, 0x033d3c, 0x0a7ea8)
    val Nuit = p(0x14123a, 0x5a4ae0, 0x8a6aff, 0x0a0820, 0x3a2f9a, 0x1b1650, 0x2a3aa8)
    // The three cards that have no mood of their own: each its own hue, away from the six above.
    val Or = p(0xc88a0a, 0xffd84a, 0xfff08a, 0x7a4a05, 0xffb82a, 0x5a3504, 0xe6a010)
    val Vert = p(0x2f9a1a, 0xa8e04a, 0x6fd02a, 0x12520a, 0x4ac03a, 0x0c3a08, 0x8ad040)
    val Cramoisi = p(0xb81430, 0xff5a6a, 0xff8a8a, 0x6a0818, 0xe8304a, 0x4a0410, 0xff4a5a)
}

/**
 * A palette made from a cover's hue, in the proportions of the drawn ones: [dark] for the library (the brown of FTitres:
 * a deep base, two light patches) and not for an album's page (the violet of FAlbum: a mid base, light and deep patches).
 * [hue] null is a cover with no colour: a grey-blue.
 */
fun immersiveFromCover(hue: Float?, saturation: Float, dark: Boolean): ImmersivePalette {
    val h = hue ?: 235f
    val s = if (hue == null) 0.12f else saturation.coerceIn(0.35f, 0.85f)
    fun col(dh: Float, sat: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf((h + dh + 360f) % 360f, (s * sat).coerceIn(0f, 1f), l)))
    return if (dark) {
        ImmersivePalette(
            base = col(0f, 1f, 0.18f),
            blobs = listOf(col(0f, 1f, 0.54f), col(0f, 1f, 0.33f), col(0f, 1f, 0.07f), col(4f, 1.1f, 0.44f), col(0f, 1f, 0.04f), col(-4f, 1f, 0.26f)),
        )
    } else {
        ImmersivePalette(
            base = col(0f, 1f, 0.5f),
            blobs = listOf(col(0f, 1f, 0.74f), col(-12f, 1f, 0.23f), col(8f, 1f, 0.62f), col(35f, 0.9f, 0.67f), col(-8f, 1f, 0.37f), col(-25f, 1f, 0.58f)),
        )
    }
}

/** The palette a Home mood wears, from the cards' ambience. */
fun immersiveOfAmbience(ambience: com.ikko.aura.data.home.CardAmbience): ImmersivePalette = when (ambience) {
    com.ikko.aura.data.home.CardAmbience.FAVORIS -> ImmersivePalettes.Favoris
    com.ikko.aura.data.home.CardAmbience.DECOUVERTE -> ImmersivePalettes.Decouverte
    com.ikko.aura.data.home.CardAmbience.NOUVEAUTES -> ImmersivePalettes.Nouveautes
    com.ikko.aura.data.home.CardAmbience.ENERGIE -> ImmersivePalettes.Energie
    com.ikko.aura.data.home.CardAmbience.CHILL -> ImmersivePalettes.Chill
    com.ikko.aura.data.home.CardAmbience.NUIT -> ImmersivePalettes.Nuit
}

/** What a page that is lit with a palette of its own (Home's moods, an album, a card's playlist) tells the background. */
val LocalImmersivePalette = compositionLocalOf<androidx.compose.runtime.MutableState<ImmersivePalette?>> {
    mutableStateOf(null)
}

/** One design pixel, in dp: the design is 1080 px wide and the phone's width is that. */
@Composable
fun designUnit(): Dp = (LocalConfiguration.current.screenWidthDp / 1080f).dp

// Blob boxes of the design: left, top, size, and opacity (design px).
private val BlobBoxes = listOf(
    floatArrayOf(-270f, -280f, 900f, 0.95f),
    floatArrayOf(580f, -160f, 760f, 0.90f),
    floatArrayOf(-370f, 250f, 820f, 0.95f),
    floatArrayOf(590f, 390f, 860f, 0.90f),
    floatArrayOf(-270f, 740f, 860f, 0.95f),
    floatArrayOf(490f, 820f, 820f, 0.90f),
)

private val grainBitmap: Bitmap by lazy {
    val rnd = Random(3)
    val px = IntArray(128 * 128) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    Bitmap.createBitmap(px, 128, 128, Bitmap.Config.ARGB_8888)
}

/** One palette, drawn: base, blurred blobs. A layer that is still unless the palette changes, so it is rendered once. */
@Composable
private fun BlobLayer(palette: ImmersivePalette, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(palette.base) }
            .graphicsLayer {
                // 120 px of the design are 120 px of the screen: the phone is the design's size.
                val sigma = size.width / 1080f * 120f
                renderEffect = BlurEffect(sigma, sigma, TileMode.Decal)
            }
            .drawBehind {
                val k = size.width / 1080f
                BlobBoxes.forEachIndexed { i, b ->
                    val color = palette.blobs.getOrNull(i) ?: return@forEachIndexed
                    val r = b[2] * k / 2f
                    drawCircle(color.copy(alpha = b[3]), radius = r, center = Offset(b[0] * k + r, b[1] * k + r))
                }
            },
    )
}

/**
 * The background itself. A change of palette is a cross-fade between two still layers, not an animation of the blobs.
 * Draws nothing beyond its own layers: [alpha] lets the player's screens show what is under them.
 */
@Composable
fun ImmersiveBackground(
    palette: ImmersivePalette,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    /** Applied to the picture itself (full strength): where the frosted buttons' backdrop is recorded. */
    pictureModifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(palette) }
    var previous by remember { mutableStateOf<ImmersivePalette?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(palette) {
        if (palette != shown) {
            previous = shown
            shown = palette
            fade.snapTo(0f)
            fade.animateTo(1f, tween(320))
            previous = null
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
      Box(Modifier.fillMaxSize().then(pictureModifier)) {
        previous?.let { BlobLayer(it) }
        BlobLayer(shown, Modifier.graphicsLayer { this.alpha = if (previous == null) 1f else fade.value })
        // Grain at 14 % in Overlay, then the fall-off over the bottom 340 px.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    brush = ShaderBrush(BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)),
                    alpha = 0.14f,
                    blendMode = BlendMode.Overlay,
                )
                val k = size.width / 1080f
                val h = 340f * k
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.38f),
                        startY = size.height - h, endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(size.width, h),
                )
            },
        )
      }
    }
}

/** A cover's own colour (hue, saturation), for [immersiveFromCover]. */
fun hueAndSaturation(color: Color): Pair<Float, Float> {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    return hsl[0] to hsl[1]
}

/** A size of the design in px, as text size (the screen's width is the design's, so this does not follow the font scale). */
@Composable
fun designSp(px: Float): androidx.compose.ui.unit.TextUnit {
    val d = androidx.compose.ui.platform.LocalDensity.current
    return with(d) { (designUnit() * px).toSp() }
}
