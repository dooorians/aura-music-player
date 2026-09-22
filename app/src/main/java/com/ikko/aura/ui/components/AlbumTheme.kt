package com.ikko.aura.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.ikko.aura.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colours an album page is built from, read off the cover: nothing here is the picture itself,
 * only what it is made of.
 *
 * [accent] is the record's colour at a strength that works on a button and behind white text;
 * [solid] is the deep, flat colour the page rests on; [glows] are the two or three hues the top of
 * the page is lit with.
 */
@Immutable
class AlbumTheme(
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val solid: Color,
    val glows: List<Color>,
    /** Per-album jitter for where the pools of light sit, so similar palettes are lit differently. */
    val seed: Int = 0,
    /** The cover itself, for the backgrounds that are made from it. Null for the neutral theme. */
    val art: Bitmap? = null,
    /** The hue, in degrees, the cover has as a whole — what the library's accent is made from; null for a grey cover. */
    val hue: Float? = null,
) {
    fun withArt(seed: Int, art: Bitmap) = AlbumTheme(accent, accentSoft, onAccent, solid, glows, seed, art, hue)

    companion object {
        /** Before the cover has been read, and for a cover with no colour to speak of. */
        val Neutral = AlbumTheme(
            accent = Color(0xFFE6E6EA),
            accentSoft = Color(0xFFF4F4F6),
            onAccent = Color(0xFF0B0B0F),
            solid = Color(0xFF0E0E11),
            glows = listOf(Color(0xFF3A3A44), Color(0xFF26262E)),
        )
    }
}

/**
 * A colour made vivid enough to sit on a control — saturated, and in the mid-tones where white or
 * black type both read on it — or null when it has no colour to speak of.
 */
fun vividAccent(colour: Color): Color? {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour.toArgb(), it) }
    if (hsl[1] < NEUTRAL_SATURATION) return null
    return Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.55f, 0.95f), hsl[2].coerceIn(0.46f, 0.66f))))
}

private const val DOMINANT_MIN_SATURATION = 0.30f
private const val THEME_SIZE_PX = 192

/**
 * How bright the resting colour is, by perceived brightness rather than HSL lightness. At equal HSL
 * lightness a green-yellow is far brighter than a blue, and the page's floor came out visibly lifted
 * on some records and sunk on others; holding the luma fixed makes them one depth.
 */
private const val SOLID_LUMA = 0.118f
private const val NEUTRAL_SATURATION = 0.14f

/**
 * Loaded at the size the player already decodes covers at, under the same key, so opening an album
 * whose track is playing costs a cache hit rather than a decode.
 */
suspend fun buildAlbumTheme(context: Context, track: Track): AlbumTheme =
    withContext(Dispatchers.Default) {
        val bitmap = AlbumArtCache.load(context, track.artworkUri, THEME_SIZE_PX, track.albumId.toString())
        bitmap?.let { albumThemeFromBitmap(it, track.albumId.hashCode()) } ?: AlbumTheme.Neutral
    }

/** The theme of an already-decoded cover; the player view model reads it off the bitmap it has. */
fun albumThemeFromBitmap(bitmap: Bitmap, seed: Int): AlbumTheme {
    val theme = themeFrom(bitmap)
    if (theme === AlbumTheme.Neutral) return theme.withArt(seed, bitmap)
    val hue = auroraHue(bitmap) ?: return theme.withArt(seed, bitmap)
    // What the record looks like as a whole — the colour of the aurora behind the app — is the colour
    // everything else takes: the buttons, the artist's name, the flat colour an album page rests on.
    // Picked from a single swatch instead, they could be any colour of the sleeve (a teal button on a
    // red background) and the page read as unrelated parts.
    val accent = Color(
        ColorUtils.HSLToColor(floatArrayOf(hue.first, hue.second.coerceIn(0.48f, 0.84f), 0.52f)),
    )
    val solid = atLuma(
        Color(ColorUtils.HSLToColor(floatArrayOf(hue.first, hue.second.coerceIn(0.28f, 0.58f), 0.16f))),
        SOLID_LUMA,
    )
    return AlbumTheme(
        accent = accent,
        accentSoft = lerp(accent, Color.White, 0.72f),
        onAccent = if (accent.luminance() > 0.34f) Color(0xFF0B0B0F) else Color.White,
        solid = solid,
        glows = theme.glows,
        hue = hue.first,
    ).withArt(seed, bitmap)
}

/**
 * The hue and saturation the aurora made from this cover has as a whole, weighted towards its
 * coloured parts; null when it is essentially grey.
 */
private fun auroraHue(art: Bitmap): Pair<Float, Float>? {
    val frame = AuroraRenderer(art).also { it.render(30f) }
    var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
    for (i in 0 until AURORA_PIXELS) {
        val cr = frame.outR[i] shr 8; val cg = frame.outG[i] shr 8; val cb = frame.outB[i] shr 8
        val chroma = (maxOf(cr, cg, cb) - minOf(cr, cg, cb)).toDouble()
        val w = chroma * chroma + 1.0
        r += cr * w; g += cg * w; b += cb * w; weight += w
    }
    if (weight <= 0.0) return null
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt(), hsl)
    return if (hsl[1] < 0.12f) null else hsl[0] to hsl[1]
}

private fun themeFrom(bitmap: Bitmap): AlbumTheme {
    val palette = Palette.from(bitmap).maximumColorCount(16).resizeBitmapArea(96 * 96).generate()

    // The colour that takes up the most of the sleeve among those that are actually coloured. The
    // dominant swatch alone is no good — on a cover that is mostly white paper or black it is white or
    // black, which says nothing about the record — so greys are ignored, and where nothing coloured is
    // large enough the vibrant swatch stands in.
    val coloured = palette.swatches.filter { swatch ->
        val h = FloatArray(3).also { ColorUtils.colorToHSL(swatch.rgb, it) }
        h[1] >= DOMINANT_MIN_SATURATION && h[2] in 0.14f..0.90f
    }
    val base = (
        coloured.maxByOrNull { it.population }
            ?: palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch ?: palette.dominantSwatch
        )?.rgb ?: return AlbumTheme.Neutral

    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(base, it) }
    if (hsl[1] < NEUTRAL_SATURATION) return neutralFor(palette)

    val accent = vividAccent(Color(base)) ?: return neutralFor(palette)
    val accentSoft = lerp(accent, Color.White, 0.55f)
    val onAccent = if (accent.luminance() > 0.34f) Color(0xFF0B0B0F) else Color.White

    // The resting colour is the record's own colour, darkened — not a near-black with a tint. Same hue
    // as the accent and still richly saturated, so the page reads as this album's colour all the way
    // down (a deep wine for a red sleeve, a deep olive for a lime one), just dark enough to carry
    // white type.
    val solid = atLuma(
        Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.74f).coerceIn(0.38f, 0.64f), 0.15f))),
        SOLID_LUMA,
    )

    return AlbumTheme(accent, accentSoft, onAccent, solid, distinctGlows(palette, accent).map(::atmosphereTone))
}

/**
 * The hues the background is lit with: the accent first, then up to four more — whichever other
 * swatches sit furthest from what has been picked already around the colour wheel. Three shades of one blue would
 * light the page as one flat wash; a blue, the warm skin tone beside it and a deep violet light it
 * as a place.
 */
private fun distinctGlows(palette: Palette, accent: Color): List<Color> {
    val candidates = listOfNotNull(
        palette.lightVibrantSwatch, palette.darkVibrantSwatch, palette.lightMutedSwatch,
        palette.mutedSwatch, palette.darkMutedSwatch, palette.dominantSwatch,
    ).map { Color(it.rgb) }
    val hues = { c: Color ->
        FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    }
    val picked = mutableListOf(accent)
    val remaining = candidates.filter { hues(it)[1] > 0.18f }.toMutableList()
    while (picked.size < 5 && remaining.isNotEmpty()) {
        val next = remaining.maxBy { c ->
            picked.minOf { p ->
                val d = kotlin.math.abs(hues(c)[0] - hues(p)[0])
                minOf(d, 360f - d)
            }
        }
        picked += next
        remaining -= next
    }
    return picked
}

private fun atLuma(colour: Color, target: Float): Color {
    val luma = 0.2126f * colour.red + 0.7152f * colour.green + 0.0722f * colour.blue
    if (luma <= 0f) return colour
    val k = target / luma
    return Color((colour.red * k).coerceIn(0f, 1f), (colour.green * k).coerceIn(0f, 1f), (colour.blue * k).coerceIn(0f, 1f))
}

/**
 * A hue as the page's light wants it: the colour of the record, but greyed and taken to a mid-tone.
 * The saturated accent is for buttons; spread across the whole top of the page it is garish, and the
 * page carries white type, so nothing that lights it may be brighter than a mid-tone either.
 */
private fun atmosphereTone(colour: Color): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour.toArgb(), it) }
    hsl[1] = (hsl[1] * 0.50f).coerceIn(0.18f, 0.46f)
    hsl[2] = hsl[2].coerceIn(0.34f, 0.44f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** A black-and-white cover gets a neutral page, tinted a touch by whatever it does have. */
private fun neutralFor(palette: Palette): AlbumTheme {
    val tone = palette.dominantSwatch?.rgb?.let { Color(it) }
    return AlbumTheme.Neutral.let {
        if (tone == null) it
        else AlbumTheme(
            accent = it.accent,
            accentSoft = it.accentSoft,
            onAccent = it.onAccent,
            solid = lerp(it.solid, tone, 0.06f),
            glows = listOf(lerp(Color(0xFF34343C), tone, 0.35f), Color(0xFF26262E)),
        )
    }
}
