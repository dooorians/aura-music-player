package com.ikko.aura.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The header's title, "Accueil" turning into "Bibliothèque" letter by letter as the pages are swiped (the brief's §8, a
 * port of its prototype). Each letter of one word becomes a letter of the other in the same place; the shapes on the way
 * are the interpolation of the two glyphs' signed distance fields; each place's width is interpolated too, so the word
 * grows without one letter running over another. All lengths are design px (the 1080-wide board) unless said otherwise.
 */

private const val WORD_A = "Accueil"
private const val WORD_B = "Bibliothèque"

/** The pairs of letters, chosen by likeness of shape, in reading order; "" is no letter on that side. */
private val SEQ = listOf(
    "A" to "B", "" to "i", "" to "b", "" to "l", "" to "i", "c" to "o", "" to "t",
    "" to "h", "c" to "è", "" to "q", "u" to "u", "e" to "e", "i" to "", "l" to "",
)

private const val FONT_PX = 84f
private const val LINE_PX = 96f
private const val TRACKING_EM = -0.03f

// The prototype's parameters (its object TP).
private const val STAG = 0.40f
private const val E_EMPTY = 3f
private const val CL = 7f
private const val INF_K = 1.2f
private const val SOFT = 1.2f
private const val BLUR_R = 5.5f
private const val SMK = 1f
private const val SMP = 0.8f
private const val W_SKEL = 2.5f
private const val GD = 0.06f
private const val BS = 4f
private const val S0 = 0.3f
private const val DL = 0.5f
private const val PS = 0.22f
private const val R = 3
private const val SPREAD = 36f
private const val PAD = 44

/** Where the shader's canvas sits around the title's own box (the prototype's OX, OY, CW, CH). */
internal const val TITLE_CANVAS_OX = 30f
internal const val TITLE_CANVAS_OY = 40f
internal const val TITLE_CANVAS_W = 1020f
internal const val TITLE_CANVAS_H = 180f

private class Cell(val x: Int, val y: Int, val w: Int, val h: Int, val cx: Float)

private class Slot(val ca: String, val cb: String, val wa: Float, val wb: Float, val cellA: Cell?, val cellB: Cell?)

/** Everything the morph needs, made once (the shape depends on nothing but the font). */
private class MorphAtlas(val image: Bitmap, val slots: List<Slot>, val base: Float)

private fun titleTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, com.ikko.aura.R.font.plus_jakarta_sans) ?: Typeface.DEFAULT_BOLD

private fun titlePaint(typeface: Typeface, sizePx: Float, tracking: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.typeface = typeface
    textSize = sizePx
    fontVariationSettings = "'wght' 800"
    if (tracking) letterSpacing = TRACKING_EM
    color = android.graphics.Color.WHITE
}

/** The baseline in a 96 px line box, from the font's own metrics (the prototype's BASE). */
private fun baselineOf(paint: Paint): Float {
    val fm = paint.fontMetrics
    return (LINE_PX - (fm.descent - fm.ascent)) / 2f - fm.ascent
}

/** Where each letter starts and where the word ends, with the real shaping (kerning and the -0.03 em tracking). */
private fun caretXs(paint: Paint, word: String): FloatArray =
    FloatArray(word.length + 1) { k -> paint.getRunAdvance(word, 0, word.length, 0, word.length, false, k) }

// --- Euclidean distance transform (Felzenszwalb & Huttenlocher) ---
private const val BIG = 1e20

private fun edt1(f: DoubleArray, d: DoubleArray, v: IntArray, z: DoubleArray, n: Int) {
    var k = 0
    v[0] = 0
    z[0] = -BIG
    z[1] = BIG
    for (q in 1 until n) {
        var s = ((f[q] + q * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2 * q - 2 * v[k])
        while (s <= z[k]) {
            k--
            s = ((f[q] + q * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2 * q - 2 * v[k])
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = BIG
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val r = (q - v[k]).toDouble()
        d[q] = r * r + f[v[k]]
    }
}

private fun edt(g: DoubleArray, w: Int, h: Int) {
    val n = max(w, h)
    val f = DoubleArray(n)
    val d = DoubleArray(n)
    val v = IntArray(n)
    val z = DoubleArray(n + 1)
    for (x in 0 until w) {
        for (y in 0 until h) f[y] = g[y * w + x]
        edt1(f, d, v, z, h)
        for (y in 0 until h) g[y * w + x] = d[y]
    }
    for (y in 0 until h) {
        for (x in 0 until w) f[x] = g[y * w + x]
        edt1(f, d, v, z, w)
        for (x in 0 until w) g[y * w + x] = d[x]
    }
}

/** Three passes of a box blur, a near-Gaussian: the smoothed field that simplifies the shapes mid-way. */
private fun smooth(src: FloatArray, w: Int, h: Int, rad: Int): FloatArray {
    val a = src.copyOf()
    val t = FloatArray(w * h)
    fun boxH(s: FloatArray, dst: FloatArray) {
        for (y in 0 until h) {
            val o = y * w
            var acc = 0f
            var c = 0
            var x = 0
            while (x < rad && x < w) { acc += s[o + x]; c++; x++ }
            for (xx in 0 until w) {
                if (xx + rad < w) { acc += s[o + xx + rad]; c++ }
                if (xx - rad - 1 >= 0) { acc -= s[o + xx - rad - 1]; c-- }
                dst[o + xx] = acc / c
            }
        }
    }
    fun boxV(s: FloatArray, dst: FloatArray) {
        for (x in 0 until w) {
            var acc = 0f
            var c = 0
            var y = 0
            while (y < rad && y < h) { acc += s[y * w + x]; c++; y++ }
            for (yy in 0 until h) {
                if (yy + rad < h) { acc += s[(yy + rad) * w + x]; c++ }
                if (yy - rad - 1 >= 0) { acc -= s[(yy - rad - 1) * w + x]; c-- }
                dst[yy * w + x] = acc / c
            }
        }
    }
    repeat(3) { boxH(a, t); boxV(t, a) }
    return a
}

private fun encode(d: Float): Int = ((d / SPREAD).coerceIn(-1f, 1f) * 0.5f + 0.5f).let { (it * 255f).roundToInt() }

/**
 * Builds the atlas: each letter of the two words drawn at 3x with a margin, its signed distance to its outline (positive
 * outside, in px at 1x, clipped at ±36) in red, and that field smoothed in green, 8 bits each (under 0.3 px of error).
 */
private fun buildAtlas(context: Context): MorphAtlas {
    val typeface = titleTypeface(context)
    val atRest = titlePaint(typeface, FONT_PX, tracking = true)
    val base = baselineOf(atRest)
    val big = titlePaint(typeface, FONT_PX * R, tracking = false)
    val pad = PAD * R
    val chars = (WORD_A + WORD_B).map { it.toString() }.distinct()
    val cellH = (LINE_PX * R + 2 * pad).toInt()
    val maxW = 2048
    var x = 0
    var y = 0
    var atlasW = 0
    val placed = chars.map { c ->
        val w = (big.measureText(c) + 2 * pad).toInt() + 1
        if (x + w > maxW) { x = 0; y += cellH }
        val cell = Triple(c, x, y) to w
        x += w
        atlasW = max(atlasW, x)
        cell
    }
    val atlasH = y + cellH
    val pixels = IntArray(atlasW * atlasH) { android.graphics.Color.rgb(encode(SPREAD), encode(SPREAD), 0) }
    val cells = HashMap<String, Cell>()
    for ((meta, w) in placed) {
        val (c, cx0, cy0) = meta
        val h = cellH
        val glyph = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(glyph).drawText(c, pad.toFloat(), pad + base * R, big)
        val n = w * h
        val alpha = ByteArray(n)
        glyph.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
        glyph.recycle()
        val go = DoubleArray(n) { BIG }
        val gi = DoubleArray(n)
        var sx = 0.0
        var sa = 0.0
        for (j in 0 until n) {
            val a8 = alpha[j].toInt() and 0xFF
            if (a8 == 0) continue
            sx += (j % w + 0.5) * a8
            sa += a8
            if (a8 == 255) {
                go[j] = 0.0
                gi[j] = BIG
            } else {
                val dd = 0.5 - a8 / 255.0
                go[j] = if (dd > 0) dd * dd else 0.0
                gi[j] = if (dd < 0) dd * dd else 0.0
            }
        }
        edt(go, w, h)
        edt(gi, w, h)
        val field = FloatArray(n) { k -> ((sqrt(go[k]) - sqrt(gi[k])) / R).toFloat().coerceIn(-SPREAD, SPREAD) }
        val soft = smooth(field, w, h, (BLUR_R * R).roundToInt())
        for (yy in 0 until h) for (xx in 0 until w) {
            val k = yy * w + xx
            pixels[(cy0 + yy) * atlasW + cx0 + xx] = android.graphics.Color.rgb(encode(field[k]), encode(soft[k]), 0)
        }
        cells[c] = Cell(cx0, cy0, w, h, if (sa > 0) ((sx / sa - pad) / R).toFloat() else 0f)
    }
    val image = Bitmap.createBitmap(pixels, atlasW, atlasH, Bitmap.Config.ARGB_8888)
    val xa = caretXs(atRest, WORD_A)
    val xb = caretXs(atRest, WORD_B)
    var ia = 0
    var ib = 0
    val slots = SEQ.map { (ca, cb) ->
        val wa = if (ca.isNotEmpty()) xa[ia + 1] - xa[ia] else 0f
        val wb = if (cb.isNotEmpty()) xb[ib + 1] - xb[ib] else 0f
        if (ca.isNotEmpty()) ia++
        if (cb.isNotEmpty()) ib++
        Slot(ca, cb, wa, wb, cells[ca], cells[cb])
    }
    return MorphAtlas(image, slots, base)
}

private const val SHADER = """
uniform shader atlas;
uniform float4 UA[14];
uniform float4 UB[14];
uniform float4 US[14];
uniform float4 UO[14];
uniform float4 UT[14];
uniform float PPX;
uniform float RR;
uniform float PADP;
uniform float SP;
uniform float EE;
uniform float CLIP;
uniform float2 OFF;

float g(float4 c, float2 p, float k) {
    if (c.z <= 0.0) return EE;
    float2 t = p * RR + float2(PADP);
    if (t.x < 0.0 || t.y < 0.0 || t.x > c.z || t.y > c.w) return SP;
    half4 v = atlas.eval(c.xy + t);
    float a = (float(v.r) - 0.5) * 2.0 * SP;
    float b = (float(v.g) - 0.5) * 2.0 * SP;
    return mix(a, b, k);
}

half4 main(float2 fc) {
    float2 p = fc / PPX - OFF;
    float al = 0.0;
    for (int i = 0; i < 14; i++) {
        float4 s = US[i];
        float4 m = UT[i];
        float2 la = p - float2(s.x, 0.0) - m.xy;
        float2 lb = p - float2(s.y, 0.0) - m.xy;
        float da = min(g(UA[i], la / m.z + m.xy, m.w) * m.z, CLIP);
        float db = min(g(UB[i], lb / m.z + m.xy, m.w) * m.z, CLIP);
        float4 f = UO[i];
        float dk = (1.0 - s.z) * (da + f.x) + s.z * (db + f.y);
        float ew = 1.0 / PPX + f.z;
        al = max(al, clamp(0.5 - dk / ew, 0.0, 1.0) * f.w);
    }
    return half4(al);
}
"""

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun sm(t: Float): Float { val c = clamp01(t); return c * c * (3f - 2f * c) }

/** The slots' uniforms at progress [p] (the prototype's renderTitle, in its "wave" mode). */
private fun fillUniforms(a: MorphAtlas, p: Float, us: FloatArray, uo: FloatArray, ut: FloatArray) {
    val n = a.slots.size
    var x = 0f
    for (i in 0 until n) {
        val s = a.slots[i]
        val l: Float
        val d: Float
        if (s.cb.isEmpty()) { l = DL; d = 0f } else { l = 1f - STAG; d = if (n > 1) i.toFloat() / (n - 1) * STAG else 0f }
        var q = clamp01((p - d) / l)
        var w: Float
        var pa: Float
        var pb: Float
        var kb: Float
        var oa = 0f
        var ob = 0f
        var so = 0f
        var al = 1f
        var sc = 1f
        var pvx = 0f
        var smk = 0f
        if (s.ca.isEmpty() || s.cb.isEmpty()) {
            // Born or ending: the place opens (or closes), the letter grows from its line, sharpens and gains weight.
            val born = s.ca.isEmpty()
            val u = if (born) q else 1f - q
            val uw = sm(u)
            val ug = sm(clamp01((u - GD) / (1f - GD)))
            val wg = if (born) s.wb else s.wa
            w = wg * uw
            val pos = x + (w - wg) / 2f
            pa = pos
            pb = pos
            kb = if (born) 1f else 0f
            sc = max(S0, uw.pow(0.7f))
            al = ug
            val off = W_SKEL * (1f - ug) * (1f - ug)
            if (born) ob = off else oa = off
            so = BS * (1f - ug)
            pvx = if (born) (s.cellB?.cx ?: 0f) else (s.cellA?.cx ?: 0f)
        } else {
            val qs = sm(clamp01((q - PS) / (1f - 2f * PS)))
            q = sm(q)
            w = s.wa + (s.wb - s.wa) * q
            if (s.ca == s.cb) {
                pa = x; pb = x; kb = 1f
            } else {
                // Changing shape: the two glyphs share their centre of gravity on the way.
                val cxA = s.cellA?.cx ?: 0f
                val cxB = s.cellB?.cx ?: 0f
                val cA = x + (w - s.wa) / 2f + cxA
                val cB = x + (w - s.wb) / 2f + cxB
                val c = cA + (cB - cA) * q
                pa = c - cxA
                pb = c - cxB
                kb = qs
                val bump = max(0f, sin(PI.toFloat() * qs)) // sin(π) is a hair below 0, and pow() of it is NaN
                oa = -INF_K * bump
                ob = oa
                so = SOFT * bump.pow(1.5f)
                smk = SMK * bump.pow(SMP)
            }
        }
        us[i * 4] = pa; us[i * 4 + 1] = pb; us[i * 4 + 2] = kb; us[i * 4 + 3] = 0f
        uo[i * 4] = oa; uo[i * 4 + 1] = ob; uo[i * 4 + 2] = so; uo[i * 4 + 3] = al
        ut[i * 4] = pvx; ut[i * 4 + 1] = a.base - 30f; ut[i * 4 + 2] = sc; ut[i * 4 + 3] = smk
        x += w
    }
}

@androidx.annotation.RequiresApi(33)
private class MorphRenderer(val atlas: MorphAtlas) {
    val shader = RuntimeShader(SHADER)
    val brush = ShaderBrush(shader)
    private val us = FloatArray(56)
    private val uo = FloatArray(56)
    private val ut = FloatArray(56)

    init {
        val bs = BitmapShader(atlas.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("atlas", bs)
        val ua = FloatArray(56)
        val ub = FloatArray(56)
        atlas.slots.forEachIndexed { i, s ->
            s.cellA?.let { c -> ua[i * 4] = c.x.toFloat(); ua[i * 4 + 1] = c.y.toFloat(); ua[i * 4 + 2] = c.w.toFloat(); ua[i * 4 + 3] = c.h.toFloat() }
            s.cellB?.let { c -> ub[i * 4] = c.x.toFloat(); ub[i * 4 + 1] = c.y.toFloat(); ub[i * 4 + 2] = c.w.toFloat(); ub[i * 4 + 3] = c.h.toFloat() }
        }
        shader.setFloatUniform("UA", ua)
        shader.setFloatUniform("UB", ub)
        shader.setFloatUniform("RR", R.toFloat())
        shader.setFloatUniform("PADP", (PAD * R).toFloat())
        shader.setFloatUniform("SP", SPREAD)
        shader.setFloatUniform("EE", E_EMPTY)
        shader.setFloatUniform("CLIP", CL)
        shader.setFloatUniform("OFF", TITLE_CANVAS_OX, TITLE_CANVAS_OY)
    }

    fun update(p: Float, ppx: Float) {
        fillUniforms(atlas, p, us, uo, ut)
        shader.setFloatUniform("US", us)
        shader.setFloatUniform("UO", uo)
        shader.setFloatUniform("UT", ut)
        shader.setFloatUniform("PPX", ppx)
    }
}

/**
 * The title, in a box of [TITLE_CANVAS_W] x [TITLE_CANVAS_H] design px placed [TITLE_CANVAS_OX], [TITLE_CANVAS_OY] above
 * and left of the title's own place. White: the caller composes it (0.42, added to the background: the vibrancy).
 * At rest (p 0 or 1) it is the text itself, drawn with the same font and baseline; between, the morph.
 */
@Composable
fun MorphingTitle(progress: () -> Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val atlas by produceState<MorphAtlas?>(null) {
        value = runCatching { withContext(Dispatchers.Default) { buildAtlas(context) } }.getOrNull()
    }
    // AGSL needs Android 13; before it, the soft cross-fade.
    val renderer: Any? = remember(atlas) {
        if (android.os.Build.VERSION.SDK_INT >= 33) atlas?.let { a -> runCatching { MorphRenderer(a) }.getOrNull() } else null
    }
    val restPaint = remember { titlePaint(titleTypeface(context), FONT_PX, tracking = true) }
    val restBase = remember { baselineOf(restPaint) }
    // The first letter's own margin (the B's is wider than the A's): the title's ink, not its box, sits on the page's left edge.
    val bearings = remember(restPaint) {
        FloatArray(2) { i ->
            val w = if (i == 0) WORD_A else WORD_B
            android.graphics.Rect().also { restPaint.getTextBounds(w, 0, 1, it) }.left.toFloat()
        }
    }
    val label = if (progress() > 0.5f) WORD_B else WORD_A
    Canvas(
        modifier.semantics {
            heading()
            contentDescription = label
        },
    ) {
        val ppx = size.width / TITLE_CANVAS_W
        val p = progress().coerceIn(0f, 1f)
        translate(left = -(bearings[0] * (1f - p) + bearings[1] * p) * ppx) {
        val r = renderer
        if (r != null && p > 0.0005f && p < 0.9995f && android.os.Build.VERSION.SDK_INT >= 33) {
            val mr = r as MorphRenderer
            mr.update(p, ppx)
            drawRect(mr.brush)
        } else if (r == null && p > 0.0005f && p < 0.9995f) {
            // No shader (it would not build): a soft cross-fade between the two words.
            val e = sm(p)
            drawWord(WORD_A, restPaint, restBase, ppx, 1f - e)
            drawWord(WORD_B, restPaint, restBase, ppx, e)
        } else {
            drawWord(if (p >= 0.5f) WORD_B else WORD_A, restPaint, restBase, ppx, 1f)
        }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWord(word: String, paint: Paint, base: Float, ppx: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        nc.save()
        nc.scale(ppx, ppx)
        paint.alpha = (alpha * 255f).roundToInt()
        nc.drawText(word, TITLE_CANVAS_OX, TITLE_CANVAS_OY + base, paint)
        paint.alpha = 255
        nc.restore()
    }
}
