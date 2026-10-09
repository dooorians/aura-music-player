package com.ikko.aura.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.ikko.aura.data.home.WidgetArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.random.Random

private const val COLOUR_SETTLE_MS = 450L

private val MeshBase = Color(0xFF07080F)

/**
 * The colours of the albums a card is made of — one rich colour per cover, read from a small copy of the picture — so a
 * card that stands for several albums can wear all of them. [fallbackHue] colours it until the pictures are in.
 */
@Composable
fun rememberArtColors(arts: List<WidgetArt>, fallbackHue: Float): List<Color> {
    val context = LocalContext.current
    // Kept across changes of [arts]: the old colours stay until the new ones are in, so the card never flashes a fallback.
    var colors by remember(fallbackHue) { mutableStateOf(listOf(Color.hsl(fallbackHue, 0.65f, 0.45f))) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(arts) {
        // A burst of track changes restarts this effect each time, so only the last one gets as far as the pictures; the
        // very first load does not wait.
        if (settled) delay(COLOUR_SETTLE_MS)
        settled = true
        val found = arts.take(5).mapNotNull { art ->
            val bitmap = AlbumArtCache.load(context, art.uri, 48, art.cacheKey, shrink = true) ?: return@mapNotNull null
            withContext(Dispatchers.Default) { richColorOf(bitmap) }
        }
        if (found.isNotEmpty()) colors = found
    }
    return colors
}

/** The colour a cover is remembered by: its pixels averaged by how vivid they are, then pulled to a saturated mid-light. */
private fun richColorOf(bitmap: Bitmap): Color {
    val small = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
    val hsv = FloatArray(3)
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    for (y in 0 until 12) for (x in 0 until 12) {
        val c = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(c, hsv)
        val w = hsv[1] * hsv[2] + 0.03f
        r += android.graphics.Color.red(c) * w; g += android.graphics.Color.green(c) * w; b += android.graphics.Color.blue(c) * w
        total += w
    }
    if (small !== bitmap) small.recycle()
    android.graphics.Color.RGBToHSV((r / total).toInt(), (g / total).toInt(), (b / total).toInt(), hsv)
    hsv[1] = hsv[1].coerceIn(0.7f, 1f)
    hsv[2] = hsv[2].coerceIn(0.7f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * A card of lit glass, after the references: the covers' colours run side by side along the top, glowing, and fall away
 * to near-black towards the bottom, with a soft bloom of light at the top and a faint sheen across it. [seed] decides how
 * far down the colour reaches and where the bloom sits, so each card has its own, the same every time.
 */
fun Modifier.glassGradient(colors: List<Color>, seed: Int, exact: Boolean = false, flip: Boolean = false): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val rnd = Random(seed)
    // Yellow-greens are turned to amber (they read as acid on a dark card), and at most three covers are kept, spread
    // across the list, so the gradient stays a few colours that belong together.
    val all = colors.ifEmpty { listOf(Color(0xFF3B5BDB)) }.let { c -> if (exact) c else c.map(::calmed) }
    val own = if (all.size <= 3) all else listOf(all.first(), all[all.size / 2], all.last())
    // One cover gives one colour: it runs into a neighbour on the colour wheel so the top is never flat.
    val top = if (own.size == 1) listOf(own[0], shifted(own[0], if (hueOf(own[0]) in 140f..260f) 50f else -40f)) else own
    // An exact palette is a colour somebody chose: it is drawn as given, not toned down.
    val lit = if (exact) top else top.map { tinted(it, 0.7f, 0.7f) }
    val deep = tinted(own[0], 0.7f, 0.06f)
    // Where the colour comes from and where it fades to differs card to card: from the top, the bottom, a side or a corner.
    val from = listOf(
        Offset(0.5f, 0f), Offset(0.5f, 1f), Offset(0f, 0.5f), Offset(1f, 0.5f),
        Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f), Offset(1f, 1f),
    )[rnd.nextInt(8)]
    // Flipped, the colour comes from the opposite side and runs the other way.
    val start = if (flip) Offset(w * (1f - from.x), h * (1f - from.y)) else Offset(w * from.x, h * from.y)
    val end = if (flip) Offset(w * from.x, h * from.y) else Offset(w * (1f - from.x), h * (1f - from.y))
    // The far end is dim but still coloured: the second colour, darkened, not black.
    val farColor = tinted(if (exact) top[0] else top.getOrElse(1) { top[0] }, 0.85f, 0.2f + 0.12f * rnd.nextFloat())
    val reach = 0.7f + 0.3f * rnd.nextFloat()
    // An exact palette keeps its first colour for most of the width and lets the others in only towards the end.
    val horizontal = if (flip) Brush.horizontalGradient(lit.reversed(), startX = 0f, endX = w) else if (exact && lit.size > 1) {
        Brush.horizontalGradient(
            0f to lit[0], 0.5f to lit[0], 1f to lit.last(), startX = 0f, endX = w,
        )
    } else Brush.horizontalGradient(lit, startX = 0f, endX = w)
    val fall = Brush.linearGradient(
        0f to Color.Transparent,
        reach * 0.4f to farColor.copy(alpha = 0.12f),
        reach to farColor.copy(alpha = 0.8f),
        1f to farColor,
        start = start,
        end = end,
    )
    val bloomAt = Offset(start.x + (end.x - start.x) * 0.05f, start.y + (end.y - start.y) * 0.05f)
    val bloomColor = tinted(top[rnd.nextInt(top.size)], 0.45f, 0.9f)
    val bloom = Brush.radialGradient(
        0f to bloomColor.copy(alpha = 0.45f),
        0.5f to bloomColor.copy(alpha = 0.14f),
        1f to Color.Transparent,
        center = bloomAt,
        radius = w * 0.55f,
    )
    // A second, softer light at the far end, in another of the card's colours, so the dim side is never a black wall.
    val farGlow = Brush.radialGradient(
        0f to lit[rnd.nextInt(lit.size)].copy(alpha = 0.3f),
        1f to Color.Transparent,
        center = end,
        radius = w * 0.6f,
    )
    val sheen = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.08f),
        0.35f to Color.Transparent,
        start = Offset.Zero,
        end = Offset(w, h * 0.6f),
    )
    onDrawBehind {
        drawRect(deep)
        drawRect(horizontal)
        drawRect(fall)
        drawRect(farGlow)
        scale(1.5f, 0.7f, pivot = bloomAt) { drawCircle(bloom, radius = w * 0.55f, center = bloomAt) }
        drawRect(sheen)
    }
}

/** The colour a card's edge is lit with: the card's own, pale. */
fun glassEdgeColor(colors: List<Color>): Color = tinted(colors.firstOrNull() ?: Color(0xFF3B5BDB), 0.4f, 1f)

/** [color] with its hue turned by [degrees]. */
private fun shifted(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** [color]'s hue at the given saturation and lightness (HSV value). */
private fun tinted(color: Color, saturation: Float, value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = saturation
    hsv[2] = value
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun hueOf(color: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[0]
}

/** Yellow-greens (hue 48 to 125) moved to amber; every other colour is left alone. */
private fun calmed(color: Color): Color {
    val h = hueOf(color)
    return if (h in 48f..125f) shifted(color, 30f + (h - 48f) * 0.15f - h) else color
}

/**
 * A still aurora of the album's colours: a few big blurred pools of light on a deep ground, always in the same places,
 * so a card wearing it never moves or shimmers. It is what a cover fades into.
 */
fun Modifier.auroraBackdrop(colors: List<Color>): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val own = colors.ifEmpty { listOf(Color(0xFF3B5BDB)) }.map(::calmed)
    val pools = (if (own.size >= 3) own else own + shifted(own[0], 45f) + shifted(own[0], -45f)).map { tinted(it, 0.75f, 0.78f) }
    val ground = tinted(own[0], 0.7f, 0.09f)
    val spots = listOf(
        Triple(Offset(w * 0.6f, h * 0.15f), w * 0.55f, pools[0]),
        Triple(Offset(w * 0.95f, h * 0.85f), w * 0.5f, pools[1]),
        Triple(Offset(w * 0.72f, h * 0.55f), w * 0.4f, pools[2]),
        Triple(Offset(w * 0.4f, h * 1.0f), w * 0.4f, pools[0]),
    ).map { (centre, radius, color) ->
        Triple(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.85f), 0.5f to color.copy(alpha = 0.35f), 1f to Color.Transparent,
                center = centre, radius = radius,
            ),
            centre, radius,
        )
    }
    onDrawBehind {
        drawRect(ground)
        spots.forEach { (brush, centre, radius) -> drawCircle(brush, radius = radius, center = centre) }
    }
}
