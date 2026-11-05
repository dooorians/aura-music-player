package com.ikko.aura.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * The colours of the validated Home and Library, all from the cover playing: the mesh's base, its six pools, the veil
 * over them, the ink of the text and the accent. See the brief's §2 (its five palettes are test sets this reproduces).
 */
@Immutable
data class EctoPalette(
    val base: Color,
    val cols: List<Color>,
    val scrimColor: Color,
    val scrim: Float,
    val deep: Color,
    val ink: Color,
    val accent: Color,
    /** The animated background's darkest colour, under everything (see [EctoMesh]). */
    val ground: Color = NeutralGround,
    /** Its five drifting colours: two deep, two middle, one pale and greyed (see [meshColoursOf]). */
    val mesh: List<Color> = NeutralMesh,
)

/** A cover with no colour: a cold slate, never pure black (as the reference shows for grey covers). */
private val NeutralGround = Color(0xFF15171D)
private val NeutralMesh = listOf(Color(0xFF191B21), Color(0xFF24272F), Color(0xFF2C2E36), Color(0xFF1C1E24), Color(0xFF35363F))

/**
 * The background's colours from a cover's own: the accent's hue and the theme's other hues, all brought down to the
 * reference's narrow band of light (0.09 to 0.18) and its measured saturations — the deep ones rich, the pale one greyed.
 * A cover with little colour gives a slate. Measured on 24 covers of the reference recording.
 */
fun meshColoursOf(accent: Color, glows: List<Color>, colourful: Boolean): Pair<Color, List<Color>> {
    if (!colourful) return NeutralGround to NeutralMesh
    fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    val a = hsl(accent)
    val hues = (listOf(accent) + glows).map { hsl(it)[0] }
    val h0 = hues[0]
    val h1 = hues.getOrNull(1) ?: (h0 + 18f)
    val h2 = hues.getOrNull(2) ?: (h0 - 14f)
    val k = a[1].coerceIn(0.3f, 1f)
    fun col(h: Float, sat: Float, l: Float) =
        Color(ColorUtils.HSLToColor(floatArrayOf((h % 360f + 360f) % 360f, (sat * k).coerceIn(0f, 0.8f), l)))
    return col(h0, 0.55f, 0.080f) to listOf(
        col(h0, 0.85f, 0.095f),
        col(h1, 0.62f, 0.140f),
        col(h0 + 6f, 0.48f, 0.160f),
        col(h2, 0.70f, 0.105f),
        col(h1, 0.26f, 0.200f),
    )
}

/**
 * A palette from a cover's [hue] and [saturation], in the proportions of the brief's "Hold The Girl" (a vivid cover): a
 * mid base, light and deep pools of its own hue and one warm counterpoint, a light ink and a pale accent. A cover with
 * little colour gets a heavier veil (as the brief's pale covers do), so the white type keeps its contrast.
 */
fun ectoPaletteFromCover(hue: Float?, saturation: Float): EctoPalette {
    val h = hue ?: 235f
    val vivid = if (hue == null) 0f else ((saturation - 0.2f) / 0.6f).coerceIn(0f, 1f)
    val s = if (hue == null) 0.10f else saturation.coerceIn(0.25f, 0.9f)
    fun col(dh: Float, sat: Float, l: Float) =
        Color(ColorUtils.HSLToColor(floatArrayOf((h + dh + 360f) % 360f, (s * sat).coerceIn(0f, 1f), l)))
    return EctoPalette(
        base = col(0f, 1.0f, 0.37f),
        cols = listOf(
            col(0f, 1.45f, 0.77f),
            col(-1f, 1.0f, 0.52f),
            col(-1f, 1.1f, 0.23f),
            col(0f, 1.45f, 0.68f),
            col(0f, 1.1f, 0.15f),
            col(139f, 0.8f, 0.50f),
        ),
        scrimColor = col(0f, 1.1f, 0.15f),
        scrim = 0.06f + (1f - vivid) * 0.5f,
        deep = col(0f, 1.1f, 0.26f),
        ink = col(-4f, 1.45f, 0.975f),
        accent = col(-3f, 1.45f, 0.89f),
    )
}

/** The palette of Home and Library, for the pages' own text and accents. */
val LocalEctoPalette = androidx.compose.runtime.staticCompositionLocalOf { ectoPaletteFromCover(null, 0f) }

private const val MESH_FADE_MS = 450

/**
 * The time the background moves by, shared by every drawing of it (a page's own ground is the same picture as the one
 * under it), advanced only by the one that is [drifting]. It never starts again from nought: a pause holds it, and it
 * wraps at 70 s, where every movement in the shader is back where it was (every period in it divides 70 s).
 */
private object MeshClock {
    val seconds = androidx.compose.runtime.mutableFloatStateOf(0f)
}

private const val MESH_PERIOD_S = 70f
private const val MESH_DOWN = 4

/**
 * The animated background (reconstructed from the reference recordings): a dark mesh of five colours drawn from the
 * cover, each centred on a point that orbits the screen (about 3.5 s a turn, each at its own pace), the whole field gently
 * warped so the edges between colours ripple and stream instead of ringing round discs. It changes about half its look
 * in a second, never quite repeats within a minute, and keeps within a narrow band of light, so the type over it reads.
 * A change of cover goes over in 0.45 s. Drawn as one shader on its own layer: only it is redrawn as it moves.
 */
@Composable
fun EctoMesh(palette: EctoPalette, modifier: Modifier = Modifier, drifting: Boolean = true) {
    val fade = tween<Color>(MESH_FADE_MS)
    val ground by animateColorAsState(palette.ground, fade, label = "mesh-ground")
    val m0 by animateColorAsState(palette.mesh[0], fade, label = "mesh-0")
    val m1 by animateColorAsState(palette.mesh[1], fade, label = "mesh-1")
    val m2 by animateColorAsState(palette.mesh[2], fade, label = "mesh-2")
    val m3 by animateColorAsState(palette.mesh[3], fade, label = "mesh-3")
    val m4 by animateColorAsState(palette.mesh[4], fade, label = "mesh-4")
    if (drifting) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last != 0L) {
                        val t = MeshClock.seconds.floatValue + ((now - last) / 1e9f).coerceAtMost(0.1f)
                        MeshClock.seconds.floatValue = t % MESH_PERIOD_S
                    }
                    last = now
                }
            }
        }
    }
    val shader = androidx.compose.runtime.remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { MeshShader() }.getOrNull() else null
    }
    // Drawn a quarter of the screen's size into a layer of its own, then enlarged: the field has no detail to lose, and the
    // shader runs for one pixel in sixteen (at full size it took the GPU 14 ms a frame on this device).
    Box(modifier.fillMaxSize()) {
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(
                        (constraints.maxWidth / MESH_DOWN).coerceAtLeast(1), (constraints.maxHeight / MESH_DOWN).coerceAtLeast(1),
                    ),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
            }
            .graphicsLayer {
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = MESH_DOWN.toFloat()
                scaleY = MESH_DOWN.toFloat()
            }
            .drawBehind {
                if (shader != null && android.os.Build.VERSION.SDK_INT >= 33) {
                    // Redrawn on every frame of the drift: the colours go in one by one (unboxed), so a frame allocates nothing.
                    shader.update(size.width, size.height, MeshClock.seconds.floatValue, ground, m0, m1, m2, m3, m4)
                    drawRect(shader.brush)
                } else {
                    // Before Android 13: the five colours as soft pools on the ground, still.
                    val cols = arrayOf(m0, m1, m2, m3, m4)
                    drawRect(ground)
                    val w = size.width
                    MeshPoints.forEachIndexed { i, p ->
                        val c = Offset(p[0] * w, p[1] * w)
                        drawCircle(Brush.radialGradient(listOf(cols[i], cols[i].copy(alpha = 0f)), c, p[2] * w * 1.6f), p[2] * w * 1.6f, c)
                    }
                }
            },
    )
    }
}

/** The five points: rest x, rest y (in widths), reach, orbit x, orbit y (in widths), turns in x and y per 70 s (about 3.5 s a turn, each its own), phase. */
private val MeshPoints = listOf(
    floatArrayOf(0.20f, 0.18f, 0.32f, 0.30f, 0.24f, 24f, 28f, 0.0f),
    floatArrayOf(0.84f, 0.36f, 0.30f, 0.26f, 0.30f, 29f, 25f, 1.7f),
    floatArrayOf(0.28f, 0.82f, 0.33f, 0.30f, 0.22f, 26f, 31f, 3.1f),
    floatArrayOf(0.78f, 1.02f, 0.34f, 0.24f, 0.26f, 31f, 26f, 4.4f),
    floatArrayOf(0.52f, 0.58f, 0.28f, 0.34f, 0.28f, 27f, 30f, 5.6f),
)

private const val MESH_SHADER = """
uniform float2 RES;
uniform float T;
uniform half4 GROUND;
uniform half4 C[5];
uniform float4 P[5];
uniform float4 M[5];

float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

half4 main(float2 fc) {
    float w = RES.x;
    float2 p = fc / w;
    const float TAU = 6.2831853;
    float u = TAU * T / 70.0;
    float a = TAU * T / 10.0;
    float b = TAU * T / 7.0;
    // The field is warped twice: its edges ripple and stream rather than ring round discs.
    p += 0.11 * float2(sin(p.y * 2.6 + b), sin(p.x * 2.3 - b + 1.3));
    p += 0.05 * float2(sin(p.y * 4.6 - a * 3.0 + 0.7), cos(p.x * 4.2 + b + 2.1));
    half3 col = GROUND.rgb * 0.2;
    float sum = 0.2;
    for (int i = 0; i < 5; i++) {
        float4 pp = P[i];
        float4 mm = M[i];
        float2 c = pp.xy + float2(pp.w * sin(u * mm.x + mm.z), mm.w * cos(u * mm.y + mm.z * 1.3));
        float2 d = p - c;
        float r = pp.z * (1.0 + 0.12 * sin(b * 2.0 + mm.z));
        float k = exp(-dot(d, d) / (r * r));
        col += C[i].rgb * half(k);
        sum += k;
    }
    col /= half(sum);
    // A breath of grain: a dark gradient this smooth would show steps without it.
    col += half((hash(fc) - 0.5) / 255.0);
    return half4(col, 1.0);
}
"""

@androidx.annotation.RequiresApi(33)
private class MeshShader {
    private val shader = android.graphics.RuntimeShader(MESH_SHADER)
    val brush = androidx.compose.ui.graphics.ShaderBrush(shader)
    private val cols = FloatArray(20)
    private val pts = FloatArray(20)
    private val mov = FloatArray(20)

    init {
        MeshPoints.forEachIndexed { i, p ->
            pts[i * 4] = p[0]; pts[i * 4 + 1] = p[1]; pts[i * 4 + 2] = p[2]; pts[i * 4 + 3] = p[3]
            mov[i * 4] = p[5]; mov[i * 4 + 1] = p[6]; mov[i * 4 + 2] = p[7]; mov[i * 4 + 3] = p[4]
        }
        shader.setFloatUniform("P", pts)
        shader.setFloatUniform("M", mov)
    }

    fun update(w: Float, h: Float, t: Float, ground: Color, c0: Color, c1: Color, c2: Color, c3: Color, c4: Color) {
        shader.setFloatUniform("RES", w, h)
        shader.setFloatUniform("T", t)
        shader.setFloatUniform("GROUND", ground.red, ground.green, ground.blue, 1f)
        put(0, c0); put(1, c1); put(2, c2); put(3, c3); put(4, c4)
        shader.setFloatUniform("C", cols)
    }

    private fun put(i: Int, c: Color) {
        cols[i * 4] = c.red; cols[i * 4 + 1] = c.green; cols[i * 4 + 2] = c.blue; cols[i * 4 + 3] = 1f
    }
}
