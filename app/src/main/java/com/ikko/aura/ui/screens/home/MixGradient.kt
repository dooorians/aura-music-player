package com.ikko.aura.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.ikko.aura.data.home.CardAmbience
import com.ikko.aura.data.model.Track
import com.ikko.aura.ui.components.AlbumArtCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * The picture of a mix, made from the covers of its own songs: their colours, set out one of many ways. Three families
 * (soft pools of colour, one of the 18 gradients of albumArtCreator repainted in the covers' colours, a sweep of them
 * around a point), each with its own draw of places, sizes, angles, mirrors and zooms, so that two mixes - or one mix on two
 * days - rarely look alike, and always look like what is in them.
 */
internal object MixGradients {
    /** The most albums whose covers colour a mix: the first ones of its list, the same for the banner and for the page. */
    const val ALBUMS = 6

    private val Backgrounds = intArrayOf(
        com.ikko.aura.R.drawable.mix_bg_01, com.ikko.aura.R.drawable.mix_bg_02, com.ikko.aura.R.drawable.mix_bg_03,
        com.ikko.aura.R.drawable.mix_bg_04, com.ikko.aura.R.drawable.mix_bg_05, com.ikko.aura.R.drawable.mix_bg_06,
        com.ikko.aura.R.drawable.mix_bg_07, com.ikko.aura.R.drawable.mix_bg_08, com.ikko.aura.R.drawable.mix_bg_09,
        com.ikko.aura.R.drawable.mix_bg_10, com.ikko.aura.R.drawable.mix_bg_11, com.ikko.aura.R.drawable.mix_bg_12,
        com.ikko.aura.R.drawable.mix_bg_13, com.ikko.aura.R.drawable.mix_bg_14, com.ikko.aura.R.drawable.mix_bg_15,
        com.ikko.aura.R.drawable.mix_bg_16, com.ikko.aura.R.drawable.mix_bg_17, com.ikko.aura.R.drawable.mix_bg_18,
    )

    fun generate(context: Context, seed: Long, covers: List<Bitmap>, sizePx: Int): Bitmap? {
        val palette = paletteOf(covers)
        if (palette.size < 2) return null
        val rnd = Random(seed)
        return when (rnd.nextInt(10)) {
            in 0..4 -> pools(rnd, palette, sizePx)
            in 5..7 -> repainted(context, rnd, palette, sizePx)
            else -> sweep(rnd, palette, sizePx)
        }
    }

    // --- colours ---

    private fun hsl(c: Int) = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }
    private fun fromHsl(h: Float, s: Float, l: Float) =
        ColorUtils.HSLToColor(floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))

    private fun close(a: Int, b: Int): Boolean {
        val x = hsl(a)
        val y = hsl(b)
        val dh = abs(x[0] - y[0]).let { minOf(it, 360f - it) }
        return dh < 18f && abs(x[2] - y[2]) < 0.18f
    }

    /** The colours the covers are made of: the vivid, the muted, the light and the dark of each, without near-twins. */
    private fun paletteOf(covers: List<Bitmap>): List<Int> {
        val out = ArrayList<Int>()
        for (b in covers) {
            val p = Palette.from(b).maximumColorCount(10).resizeBitmapArea(64 * 64).generate()
            listOf(p.vibrantSwatch, p.mutedSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.dominantSwatch)
                .forEach { sw -> sw?.rgb?.let { c -> if (out.none { close(it, c) }) out += c } }
        }
        if (out.isEmpty()) return out
        // Too few: kin of the first, a little round the colour wheel, so a mix of one cover still has a range.
        val h = hsl(out[0])
        var turn = 1
        while (out.size < 4) {
            val shift = if (turn % 2 == 1) 28f * ((turn + 1) / 2) else -28f * (turn / 2)
            out += fromHsl(h[0] + shift, h[1], (h[2] + if (turn % 3 == 0) 0.18f else -0.12f))
            turn++
        }
        return out.take(8)
    }

    private fun jitter(c: Int, rnd: Random): Int {
        val h = hsl(c)
        return fromHsl(
            h[0] + rnd.nextFloat() * 28f - 14f,
            (h[1] * (0.85f + rnd.nextFloat() * 0.4f)).coerceIn(0.18f, 1f),
            (h[2] * (0.8f + rnd.nextFloat() * 0.45f)).coerceIn(0.12f, 0.88f),
        )
    }

    private fun withAlpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (a.coerceIn(0f, 1f) * 255f).toInt())

    private fun darker(c: Int, to: Float): Int {
        val h = hsl(c)
        return fromHsl(h[0], h[1], minOf(h[2], to))
    }

    // --- the three families ---

    /** A dark ground of one of the colours, soft pools of the others over it, a slanting wash on top. */
    private fun pools(rnd: Random, pal: List<Int>, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(darker(pal[rnd.nextInt(pal.size)], 0.16f + rnd.nextFloat() * 0.2f))
        val s = size.toFloat()
        repeat(4 + rnd.nextInt(4)) {
            val c = jitter(pal[rnd.nextInt(pal.size)], rnd)
            val r = s * (0.35f + rnd.nextFloat() * 0.6f)
            val cx = s * (-0.15f + rnd.nextFloat() * 1.3f)
            val cy = s * (-0.15f + rnd.nextFloat() * 1.3f)
            val a = 0.65f + rnd.nextFloat() * 0.35f
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(c, a), withAlpha(c, a * 0.45f), withAlpha(c, 0f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                },
            )
        }
        wash(canvas, rnd, pal, size, 0.1f + rnd.nextFloat() * 0.22f)
        return out
    }

    /** One of the 18 gradients, turned, mirrored, zoomed and repainted: its light and shade run through the covers' colours. */
    private fun repainted(context: Context, rnd: Random, pal: List<Int>, size: Int): Bitmap {
        val work = 256
        val src = BitmapFactory.decodeResource(context.resources, Backgrounds[rnd.nextInt(Backgrounds.size)])
        val shaped = Bitmap.createBitmap(work, work, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(shaped)
        val zoom = 1f + rnd.nextFloat() * 0.55f
        val room = (zoom - 1f) * work / 2f
        canvas.save()
        canvas.translate(work / 2f + (rnd.nextFloat() * 2f - 1f) * room, work / 2f + (rnd.nextFloat() * 2f - 1f) * room)
        canvas.rotate(90f * rnd.nextInt(4))
        canvas.scale(if (rnd.nextBoolean()) zoom else -zoom, zoom)
        canvas.translate(-work / 2f, -work / 2f)
        canvas.drawBitmap(src, null, Rect(0, 0, work, work), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        src.recycle()

        // The ramp, dark to light, from the palette's own spread; sometimes the other way about.
        val byLight = pal.sortedBy { ColorUtils.calculateLuminance(it) }
        var ramp = intArrayOf(
            darker(byLight.first(), 0.2f), byLight[byLight.size / 3], byLight[byLight.size * 2 / 3], byLight.last(),
        )
        if (rnd.nextInt(3) == 0) ramp = ramp.reversedArray()
        val px = IntArray(work * work)
        shaped.getPixels(px, 0, work, 0, 0, work, work)
        val lum = FloatArray(px.size) { i ->
            val p = px[i]
            (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f
        }
        val lo = lum.min()
        val hi = lum.max().coerceAtLeast(lo + 0.01f)
        for (i in px.indices) {
            val t = ((lum[i] - lo) / (hi - lo)).coerceIn(0f, 1f) * 3f
            val k = t.toInt().coerceAtMost(2)
            px[i] = ColorUtils.blendARGB(ramp[k], ramp[k + 1], t - k)
        }
        shaped.setPixels(px, 0, work, 0, 0, work, work)
        val out = Bitmap.createScaledBitmap(shaped, size, size, true)
        shaped.recycle()
        wash(Canvas(out), rnd, pal, size, 0.06f + rnd.nextFloat() * 0.16f)
        return out
    }

    /** The colours turning round a point, with a pool or two on it. */
    private fun sweep(rnd: Random, pal: List<Int>, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val s = size.toFloat()
        canvas.drawColor(darker(pal[rnd.nextInt(pal.size)], 0.22f))
        val picks = List(3 + rnd.nextInt(2)) { jitter(pal[rnd.nextInt(pal.size)], rnd) }
        val cx = s * (0.1f + rnd.nextFloat() * 0.8f)
        val cy = s * (0.1f + rnd.nextFloat() * 0.8f)
        canvas.drawRect(
            0f, 0f, s, s,
            Paint().apply {
                shader = SweepGradient(cx, cy, (picks + picks.first()).map { withAlpha(it, 0.9f) }.toIntArray(), null)
                // a soft edge: the sweep seen through a wide pool, so its rays do not cut
                alpha = 235
            },
        )
        repeat(1 + rnd.nextInt(2)) {
            val c = jitter(pal[rnd.nextInt(pal.size)], rnd)
            val r = s * (0.5f + rnd.nextFloat() * 0.5f)
            val x = s * rnd.nextFloat()
            val y = s * rnd.nextFloat()
            canvas.drawCircle(
                x, y, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(x, y, r, intArrayOf(withAlpha(c, 0.8f), withAlpha(c, 0f)), null, Shader.TileMode.CLAMP)
                },
            )
        }
        wash(canvas, rnd, pal, size, 0.08f + rnd.nextFloat() * 0.18f)
        return out
    }

    /** A slanting wash of two of the colours over everything, for depth. */
    private fun wash(canvas: Canvas, rnd: Random, pal: List<Int>, size: Int, alpha: Float) {
        val s = size.toFloat()
        val a = Math.toRadians(rnd.nextInt(180).toDouble())
        val half = s * (abs(kotlin.math.sin(a)) + abs(kotlin.math.cos(a))).toFloat() / 2f
        val dx = kotlin.math.sin(a).toFloat() * half
        val dy = -kotlin.math.cos(a).toFloat() * half
        canvas.drawRect(
            0f, 0f, s, s,
            Paint().apply {
                shader = LinearGradient(
                    s / 2 - dx, s / 2 - dy, s / 2 + dx, s / 2 + dy,
                    pal[rnd.nextInt(pal.size)], pal[rnd.nextInt(pal.size)], Shader.TileMode.CLAMP,
                )
                this.alpha = (alpha * 255f).toInt()
            },
        )
    }
}

private val MixArtCache = LruCache<String, Bitmap>(12)

/**
 * The picture of a mix: the colours of its songs' covers set out in one of many ways (see [MixGradients]), different for each
 * mix and each day. It is the same wherever it is drawn: the banner on Home and the mix's page ask with the same songs.
 * It is null while it is being made (a few tens of milliseconds, the first time; then it is kept), and for a mix with no
 * songs: whoever draws it fades it in.
 */
@Composable
fun rememberMixArt(ambience: CardAmbience, tracks: List<Track>, sizePx: Int = 512): Bitmap? {
    val context = LocalContext.current
    val day = remember { java.time.LocalDate.now().toEpochDay() }
    val albums = remember(tracks) { tracks.distinctBy { it.albumId }.take(MixGradients.ALBUMS) }
    if (albums.isEmpty() && tracks.isEmpty()) return null
    // Built once per set of albums, not on every recomposition of the card that shows it.
    val key = remember(ambience, day, albums, sizePx) {
        "${ambience.ordinal}:$day:${albums.joinToString(",") { it.albumId.toString() }}:$sizePx"
    }
    var art by remember(key) { mutableStateOf(MixArtCache.get(key)) }
    LaunchedEffect(key) {
        if (art != null) return@LaunchedEffect
        val made = withContext(Dispatchers.Default) {
            val covers = albums.mapNotNull { t ->
                AlbumArtCache.peek(t.albumId.toString(), 96, derive = true)
                    ?: AlbumArtCache.load(context, t.artworkUri, 96, t.albumId.toString(), shrink = true)
            }
            val seed = day * 104729L + ambience.ordinal * 31L + albums.fold(7L) { h, t -> h * 31L + t.albumId }
            // Covers with no colour to speak of: the plain gradients. Made here too, off the main thread: it decodes a
            // drawable and paints a full-size bitmap, which on the main thread cost the card a frame or two.
            MixGradients.generate(context, seed, covers, sizePx) ?: mixGradientBitmap(context, ambience, day, sizePx)
        }
        MixArtCache.put(key, made)
        art = made
    }
    return art
}
