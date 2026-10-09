package com.ikko.aura.ui.screens.home

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.home.CardAmbience
import com.ikko.aura.ui.theme.AuraFontFamily
import com.ikko.aura.viewmodel.ForYouCard
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A blurred patch of colour: where it sits (% of the card), its size (× the card's width), opacity, stretch and turn. */
private class Blob(val color: Color, val x: Float, val y: Float, val size: Float, val alpha: Float, val stretch: Float, val rot: Float)

/** The light fold: colour, opacity, angle, vertical position (%), width and height (× the card's width), light edge. */
private class Sheen(val color: Color, val alpha: Float, val angle: Float, val y: Float, val w: Float, val h: Float, val edge: Float)

private class Look(val base0: Color, val base1: Color, val angle: Float, val blobs: List<Blob>, val sheen: Sheen)

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** The six moods, from the design: two deep tones of one hue, four patches around them, one fold of light. */
private val Looks: Map<CardAmbience, Look> = mapOf(
    CardAmbience.FAVORIS to Look(c(0x4f17d6), c(0x8b3cf2), 160f, listOf(
        Blob(c(0x3510c4), 12f, 18f, 1.15f, 1f, 1.0f, 0f), Blob(c(0xff8db6), 88f, 32f, 1.0f, .95f, 1.35f, 30f),
        Blob(c(0xffb9a6), 74f, 88f, .95f, .9f, 1.1f, -20f), Blob(c(0xa63ef6), 18f, 86f, 1.0f, 1f, 1.0f, 0f)),
        Sheen(c(0xffd3e6), .55f, -36f, 26f, 1.6f, .56f, 180f)),
    CardAmbience.DECOUVERTE to Look(c(0x0b2cb4), c(0x2b5ef0), 200f, listOf(
        Blob(c(0x1cc8d8), 14f, 90f, 1.2f, .95f, 1.5f, 15f), Blob(c(0x081c8c), 90f, 14f, 1.1f, 1f, 1.2f, -35f),
        Blob(c(0x7090ff), 78f, 70f, .75f, .85f, 1.0f, 0f), Blob(c(0x2848e6), 10f, 20f, .85f, 1f, 1.3f, 50f)),
        Sheen(c(0xb0f2ff), .45f, 22f, 68f, 1.4f, .72f, 0f)),
    CardAmbience.NOUVEAUTES to Look(c(0xdf1d4c), c(0xff5a7e), 130f, listOf(
        Blob(c(0xffe1e8), 46f, 46f, .62f, .9f, 1.6f, -45f), Blob(c(0xc40e3b), 92f, 78f, 1.1f, 1f, 1.0f, 0f),
        Blob(c(0xff90a9), 8f, 12f, .95f, .95f, 1.3f, 25f), Blob(c(0xff2b56), 14f, 96f, .9f, 1f, 1.1f, 0f)),
        Sheen(c(0xffffff), .5f, -64f, 44f, 1.2f, .44f, 180f)),
    CardAmbience.ENERGIE to Look(c(0xe0160b), c(0xff3a14), 180f, listOf(
        Blob(c(0xff8a1f), 42f, 52f, 1.05f, .9f, 1.25f, 10f), Blob(c(0xbd0c07), 6f, 94f, 1.0f, 1f, 1.0f, 0f),
        Blob(c(0xffb547), 96f, 4f, .7f, .95f, 1.7f, -40f), Blob(c(0xff4d1a), 90f, 70f, .9f, 1f, 1.0f, 0f)),
        Sheen(c(0xffd590), .5f, -58f, 10f, 1.3f, .34f, 180f)),
    CardAmbience.CHILL to Look(c(0x05625b), c(0x0b8f80), 140f, listOf(
        Blob(c(0x34e4a6), 50f, 8f, 1.1f, .85f, 1.8f, 0f), Blob(c(0x0a7ea8), 6f, 50f, .95f, 1f, 1.2f, 70f),
        Blob(c(0x033d3c), 60f, 100f, 1.2f, 1f, 1.5f, 0f), Blob(c(0x80f0c5), 94f, 56f, .6f, .8f, 1.0f, 0f)),
        Sheen(c(0xc8fff1), .38f, -4f, 58f, 1.8f, .5f, 0f)),
    CardAmbience.NUIT to Look(c(0x28187a), c(0x4a2fb2), 190f, listOf(
        Blob(c(0x8a6cff), 24f, 30f, 1.0f, .95f, 1.2f, -25f), Blob(c(0x160b4e), 90f, 10f, 1.15f, 1f, 1.0f, 0f),
        Blob(c(0xd18dff), 80f, 84f, .8f, .85f, 1.45f, 35f), Blob(c(0x3c58e6), 8f, 92f, .9f, 1f, 1.0f, 0f)),
        Sheen(c(0xe8daff), .42f, 30f, 36f, 1.5f, .6f, 180f)),
)

/** Film grain, drawn over the card as Overlay at this strength: it breaks the bands of a smooth gradient. */
private const val GRAIN_ALPHA = 0.14f

/** How far the patches wander, as a fraction of the card's width, at full motion. */
private const val DRIFT = 0.09f

private val grainBitmap: Bitmap by lazy {
    val rnd = Random(7)
    val px = IntArray(128 * 128) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    Bitmap.createBitmap(px, 128, 128, Bitmap.Config.ARGB_8888)
}

/**
 * The card's colours, drawn in one pass: base gradient, the drifting patches, the fold of light, the grain.
 * [phase] is the point in a slow loop (0..1) the patches are at; every wander is a whole number of turns of it, so the
 * loop joins up. [motion] 0 is still.
 */
private fun DrawScope.drawAmbience(look: Look, seed: Int, phase: Float, motion: Float) {
    val w = size.width
    val h = size.height
    val centre = Offset(w / 2f, h / 2f)

    // CSS angles: 0 is towards the top, clockwise.
    val a = look.angle * PI.toFloat() / 180f
    val dir = Offset(sin(a), -cos(a))
    val len = abs(w * sin(a)) + abs(h * cos(a))
    drawRect(Brush.linearGradient(listOf(look.base0, look.base1), centre - dir * (len / 2f), centre + dir * (len / 2f)))

    val amp = w * DRIFT * motion
    look.blobs.forEachIndexed { i, b ->
        val k1 = 1 + (i + seed) % 3
        val k2 = 1 + (i * 2 + seed + 1) % 3
        val p1 = 2f * PI.toFloat() * (phase * k1 + 0.37f * i + 0.11f * seed)
        val p2 = 2f * PI.toFloat() * (phase * k2 + 0.61f * i + 0.23f * seed)
        val cx = b.x / 100f * w + amp * sin(p1)
        val cy = b.y / 100f * h + amp * cos(p2)
        val grow = 1f + 0.07f * motion * sin(p1 + p2)
        val size = w * b.size
        val reach = 0.34f * size * hypot(b.stretch, 1f / b.stretch)   // where the colour has faded to nothing
        translate(cx, cy) {
            rotate(b.rot + 7f * motion * sin(p2 - p1), Offset.Zero) {
                scale(b.stretch * grow, grow / b.stretch, Offset.Zero) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to b.color, 0.41f to b.color, 0.62f to b.color.copy(alpha = 0.40f), 1f to Color.Transparent,
                            center = Offset.Zero, radius = reach,
                        ),
                        radius = reach,
                        center = Offset.Zero,
                        alpha = b.alpha,
                    )
                }
            }
        }
    }

    val s = look.sheen
    val sw = s.w * w
    val sh = s.h * w
    val sp = 2f * PI.toFloat() * (phase + 0.17f * seed)
    translate(w / 2f + w * 0.05f * motion * sin(sp), s.y / 100f * h + w * 0.04f * motion * cos(sp)) {
        rotate(s.angle + 6f * motion * sin(sp * 2f), Offset.Zero) {
            val top = -sh / 2f
            val bottom = sh / 2f
            val (from, to) = if (s.edge >= 90f) top to bottom else bottom to top
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent, 0.18f to s.color.copy(alpha = 0.25f), 0.38f to s.color,
                    0.60f to s.color.copy(alpha = 0.35f), 0.82f to Color.Transparent, 1f to Color.Transparent,
                    startY = from, endY = to,
                ),
                topLeft = Offset(-sw / 2f, top),
                size = Size(sw, sh),
                alpha = s.alpha,
                blendMode = BlendMode.Screen,
            )
        }
    }

    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.52f to Color.Black.copy(alpha = 0.10f), 1f to Color.Black.copy(alpha = 0.26f),
            startY = h * 0.52f, endY = h,
        ),
        topLeft = Offset(0f, h * 0.52f),
        size = Size(w, h * 0.48f),
    )
    drawRect(
        brush = ShaderBrush(BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)),
        alpha = GRAIN_ALPHA,
        blendMode = BlendMode.Overlay,
    )
}

/**
 * One "Pour toi" card. [unit] is the size of one pixel of the 1080-px-wide design, in dp, so a card and its type scale
 * with the screen. [phase] is the shared point of the slow loop the colours drift on (read only while drawing).
 */
@Composable
fun ForYouCardView(
    card: ForYouCard,
    width: Dp,
    height: Dp,
    unit: Dp,
    phase: () -> Float,
    motion: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = Looks.getValue(card.ambience)
    val seed = card.ambience.ordinal * 31 + 7
    val density = LocalDensity.current
    fun sp(px: Float) = with(density) { (px * unit.value).dp.toSp() }
    val w = width.value / unit.value   // the card's width in design pixels
    val titleScale = min(0.165f, 1.32f / max(1, card.heading.split(' ').maxOf { it.length }))
    val face = TextStyle(fontFamily = AuraFontFamily, color = Color.White)

    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(width * 0.081f))
            // Its own layer, drawn offscreen: Screen and Overlay blend with the card's colours, never with the page.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind { drawAmbience(look, seed, phase(), motion) }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // The title is centred on the card itself, not on what is left between the logo and the text below it, so it
        // sits in the same place on every card whatever the artists line says.
        Box(Modifier.fillMaxSize().padding(horizontal = width * 0.068f), contentAlignment = Alignment.Center) {
            Text(
                card.heading,
                style = face.copy(
                    fontSize = sp(w * titleScale), lineHeight = sp(w * titleScale * 0.98f), fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.03f).em, textAlign = TextAlign.Center,
                ),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(width * 0.068f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Aura",
                style = face.copy(fontSize = sp(w * 0.042f), fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier.align(Alignment.End),
            )
            Column(verticalArrangement = Arrangement.spacedBy(width * 0.008f)) {
                Text(card.kicker, style = face.copy(fontSize = sp(w * 0.034f), fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.80f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.artists.isNotBlank()) {
                    Text(card.artists, style = face.copy(fontSize = sp(w * 0.038f), lineHeight = sp(w * 0.05f), fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val Float.em get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Em)
