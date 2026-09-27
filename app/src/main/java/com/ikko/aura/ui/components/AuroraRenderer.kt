package com.ikko.aura.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The background Apple Music puts behind a playing record, rebuilt from the same recipe: four copies
 * of the cover, at 25%, 50%, 80% and 125% of the screen, the small two set on circular tracks and all
 * four turned at their own angles; the whole thing swirled by a twist that grows towards a point;
 * then blurred until only the colours are left, and saturated well past the cover's own.
 *
 * It is worked out on a small bitmap — 80 pixels wide, because the result is all soft colour and
 * gains nothing from more — and the screen scales it up. [AuroraRenderer.render] takes the moment in
 * the animation as a parameter, which is what the surface view in AuroraBackground steps.
 */

/** A stand-in for a cover, for when there is no cover: the theme's own colours as soft pools. */
internal fun neutralArt(theme: AlbumTheme): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(theme.solid.toArgbInt())
    val spots = arrayOf(0.25f to 0.25f, 0.8f to 0.4f, 0.4f to 0.85f, 0.85f to 0.9f)
    theme.glows.take(spots.size).forEachIndexed { i, colour ->
        val (fx, fy) = spots[i]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                fx * size, fy * size, size * 0.6f,
                colour.copy(alpha = 0.9f).toArgbInt(), colour.copy(alpha = 0f).toArgbInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    }
    return bitmap
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = AndroidColor.argb(
    (alpha * 255f + 0.5f).toInt(), (red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt(),
)

internal const val AURORA_W = 80
internal const val AURORA_H = 92

/**
 * The colour and the light of the background are the cover's own — measured from it, not pushed past
 * it. A fixed boost turned a cover that was already vivid (a blue-to-red gradient, say) into neon that
 * looked nothing like the record, so the saturation and the brightness the background is brought to
 * follow the cover's: a vivid sleeve stays as vivid as it is, a dull one is lifted to a floor so it
 * still has colour, and no pixel goes further past the cover's mean than the headroom below.
 */
private const val MIN_TARGET_S = 0.30f
private const val MAX_TARGET_S = 0.56f
private const val MIN_TARGET_V = 0.30f
private const val MAX_TARGET_V = 0.56f
private const val S_HEADROOM = 0.04f
private const val S_CEILING = 0.60f
private const val V_HEADROOM = 0.10f

/** Where the brightness ceiling begins (of 255), and how much of what is above it is kept. */
private const val KNEE = 78f
private const val KNEE_SLOPE = 0.22f

/**
 * Deep enough that nothing of the cover can be read — no faces, no lettering, and above all no edges
 * of the four squares: three passes of a 14-pixel window is a Gaussian of about a ninth of the
 * screen's width. Apple's is the same: a field of soft colour, with nothing in it to look at.
 */
private const val BLUR_RADIUS = 9
private const val BLUR_PASSES = 3

private const val MESH_W = 14
private const val MESH_H = 16

internal const val AURORA_PIXELS = AURORA_W * AURORA_H

internal class AuroraRenderer(originalArt: Bitmap) {
    private val art = android.graphics.Bitmap.createScaledBitmap(originalArt, 64, 64, true)
    private val scene = Bitmap.createBitmap(AURORA_W, AURORA_H, Bitmap.Config.ARGB_8888)
    private val sceneCanvas = AndroidCanvas(scene)
    private val meshed = Bitmap.createBitmap(AURORA_W, AURORA_H, Bitmap.Config.ARGB_8888)
    private val meshedCanvas = AndroidCanvas(meshed)

    /**
     * The latest frame, one plane per colour, each value in 8.8 fixed point (255 is 65280). Kept that
     * precise on purpose: this picture is nothing but slow gradients, and cut to eight bits here it would
     * carry a visible band at every step of them all the way to the screen. The screen's own copy is made
     * from these, dithered — see [AuroraSurfaceView].
     */
    val outR = IntArray(AURORA_PIXELS)
    val outG = IntArray(AURORA_PIXELS)
    val outB = IntArray(AURORA_PIXELS)

    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val mesh = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    private val pixels = IntArray(AURORA_PIXELS)
    private val scratch = IntArray(AURORA_PIXELS)
    private val dst = RectF()
    private val clipPath = android.graphics.Path()

    /** The cover's average, dark: what shows through wherever the four copies leave a gap. */
    private val ground: Int

    // What the background is graded to, from the cover's own colour and light.
    private val targetS: Float
    private val targetV: Float
    private val sCap: Float
    private val vCap: Float

    // Worked out from the first frame, once, so the grading does not drift as the picture moves.
    private var calibrated = false
    private var boost = 1f
    private var vGain = 1f
    private val hsv = FloatArray(3)

    init {
        val one = Bitmap.createScaledBitmap(art, 1, 1, true)
        val c = one.getPixel(0, 0)
        one.recycle()
        ground = AndroidColor.rgb(AndroidColor.red(c) * 6 / 10, AndroidColor.green(c) * 6 / 10, AndroidColor.blue(c) * 6 / 10)

        val small = Bitmap.createScaledBitmap(art, 24, 24, true)
        val sample = IntArray(24 * 24)
        small.getPixels(sample, 0, 24, 0, 0, 24, 24)
        small.recycle()
        var vSum = 0f
        var sWeighted = 0f
        val h = FloatArray(3)
        for (px in sample) {
            AndroidColor.RGBToHSV(AndroidColor.red(px), AndroidColor.green(px), AndroidColor.blue(px), h)
            vSum += h[2]
            sWeighted += h[1] * h[2]
        }
        val coverV = vSum / sample.size
        val coverS = if (vSum > 0f) sWeighted / vSum else 0f
        targetS = coverS.coerceIn(MIN_TARGET_S, MAX_TARGET_S)
        targetV = coverV.coerceIn(MIN_TARGET_V, MAX_TARGET_V)
        sCap = (targetS + S_HEADROOM).coerceAtMost(S_CEILING)
        vCap = (targetV + V_HEADROOM).coerceAtMost(0.80f)
    }

    fun render(t: Float) {
        val w = AURORA_W.toFloat()
        val h = AURORA_H.toFloat()

        sceneCanvas.drawColor(ground)
        // Apple's four sizes, as fractions of the width — nudged up a little because a phone is taller
        // than a browser window. Largest first, so the small ones travel over the big ones.
        layer(1.40f * w, 0.50f * w, 0.50f * h, t * 360f / 150f)
        layer(0.90f * w, 0.36f * w, 0.64f * h, -t * 360f / 105f)
        val a2 = t * 2f * PI.toFloat() / 52f
        layer(0.55f * w, w * (0.5f + 0.34f * cos(a2)), h * (0.46f + 0.28f * sin(a2)), t * 360f / 36f)
        val a1 = -t * 2f * PI.toFloat() / 39f + 1.3f
        layer(0.28f * w, w * (0.5f + 0.38f * cos(a1)), h * (0.5f + 0.32f * sin(a1)), -t * 360f / 25f)

        twistMesh(t, w, h)
        meshedCanvas.drawColor(ground)
        meshedCanvas.drawBitmapMesh(scene, MESH_W, MESH_H, mesh, 0, null, 0, meshPaint)
        meshed.getPixels(pixels, 0, AURORA_W, 0, 0, AURORA_W, AURORA_H)
        for (i in 0 until AURORA_PIXELS) {
            val c = pixels[i]
            outR[i] = ((c shr 16) and 0xFF) shl 8
            outG[i] = ((c shr 8) and 0xFF) shl 8
            outB[i] = (c and 0xFF) shl 8
        }
        blur()
        grade()
    }

    private fun layer(size: Float, cx: Float, cy: Float, degrees: Float) {
        sceneCanvas.save()
        sceneCanvas.translate(cx, cy)
        sceneCanvas.rotate(degrees)
        clipPath.reset()
        clipPath.addCircle(0f, 0f, size / 2f, android.graphics.Path.Direction.CW)
        sceneCanvas.clipPath(clipPath)
        dst.set(-size / 2f, -size / 2f, size / 2f, size / 2f)
        sceneCanvas.drawBitmap(art, null, dst, layerPaint)
        sceneCanvas.restore()
    }

    /**
     * Apple's twist, applied to a grid: inside [radius] of a wandering point each vertex is turned about
     * it by an angle that grows with the square of how close it is, so the picture is wound up into a
     * swirl around that point and left alone outside.
     */
    private fun twistMesh(t: Float, w: Float, h: Float) {
        val ox = w * (0.50f + 0.20f * cos(t * 0.11f))
        val oy = h * (0.42f + 0.16f * sin(t * 0.09f + 0.7f))
        val radius = 0.80f * h
        val angle = 0.5f * sin(t * 0.13f) + 0.25f
        var i = 0
        for (row in 0..MESH_H) {
            val y = h * row / MESH_H
            for (col in 0..MESH_W) {
                val x = w * col / MESH_W
                var dx = x - ox
                var dy = y - oy
                val d = hypot(dx, dy)
                if (d < radius) {
                    val ratio = (radius - d) / radius
                    val a = ratio * ratio * angle
                    val s = sin(a)
                    val c = cos(a)
                    val nx = dx * c - dy * s
                    dy = dx * s + dy * c
                    dx = nx
                }
                mesh[i++] = ox + dx
                mesh[i++] = oy + dy
            }
        }
    }

    /** Separable box blur, three times over, which is close enough to a Gaussian at this size. */
    private fun blur() {
        for (plane in arrayOf(outR, outG, outB)) {
            repeat(BLUR_PASSES) {
                boxAxis(plane, scratch, AURORA_W, AURORA_H, horizontal = true)
                boxAxis(scratch, plane, AURORA_W, AURORA_H, horizontal = false)
            }
        }
    }

    /**
     * Saturation and brightness, applied after the blur rather than before it: blurring mixes
     * neighbouring hues towards grey, so the saturation has to be put back afterwards to keep the
     * colours as vivid as the cover's — and no more.
     *
     * Done in plain arithmetic on the planes rather than through android.graphics.Color, whose two
     * conversions are native calls that copy a float array in and out: 7,360 pixels twice over, fifteen
     * times a second, was the largest single cost of the whole background.
     */
    private fun grade() {
        if (!calibrated) {
            var vSum = 0f
            var sWeighted = 0f
            for (i in 0 until AURORA_PIXELS) {
                rgbToHsv(outR[i] / 256f, outG[i] / 256f, outB[i] / 256f, hsv)
                vSum += hsv[2]
                sWeighted += hsv[1] * hsv[2]
            }
            val meanV = vSum / AURORA_PIXELS
            val meanS = if (vSum > 0f) sWeighted / vSum else 0f
            boost = (targetS / meanS.coerceAtLeast(0.02f)).coerceIn(0.85f, 2.2f)
            vGain = (targetV / meanV.coerceAtLeast(0.05f)).coerceIn(0.55f, 1.5f)
            calibrated = true
        }
        for (i in 0 until AURORA_PIXELS) {
            var nr = outR[i] / 256f
            var ng = outG[i] / 256f
            var nb = outB[i] / 256f
            val lum = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
            nr = maxOf(0f, lum + (nr - lum) * boost) * vGain
            ng = maxOf(0f, lum + (ng - lum) * boost) * vGain
            nb = maxOf(0f, lum + (nb - lum) * boost) * vGain
            // A soft ceiling on brightness. Every screen has white type on it, and a cover with white in
            // it (a pale sleeve, a sky, a highlight) otherwise made a patch of background as light as the
            // text: above the knee the light is compressed, keeping the hue, so the brightest part of any
            // background is a light colour and never white.
            val ny = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
            if (ny > KNEE) {
                val k = (KNEE + (ny - KNEE) * KNEE_SLOPE) / ny
                nr *= k; ng *= k; nb *= k
            }
            outR[i] = (nr * 256f + 0.5f).toInt().coerceIn(0, 65280)
            outG[i] = (ng * 256f + 0.5f).toInt().coerceIn(0, 65280)
            outB[i] = (nb * 256f + 0.5f).toInt().coerceIn(0, 65280)
        }
    }

    /** r, g, b in 0..255; writes hue in degrees, saturation and value (0..1) into [out]. */
    private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        out[2] = max / 255f
        if (delta <= 0f || max <= 0f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        out[1] = delta / max
        var h = when (max) {
            r -> (g - b) / delta
            g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f
        if (h < 0f) h += 360f
        out[0] = h
    }

    /** Writes red, green, blue in 0..255 into [out]. */
    private fun hsvToRgb(h: Float, s: Float, v: Float, out: FloatArray) {
        val vv = v * 255f
        if (s <= 0f) {
            out[0] = vv; out[1] = vv; out[2] = vv
            return
        }
        val hx = (if (h < 0f || h >= 360f) 0f else h) / 60f
        val sector = hx.toInt()
        val f = hx - sector
        val p = vv * (1f - s)
        val q = vv * (1f - s * f)
        val t = vv * (1f - s * (1f - f))
        when (sector) {
            0 -> { out[0] = vv; out[1] = t; out[2] = p }
            1 -> { out[0] = q; out[1] = vv; out[2] = p }
            2 -> { out[0] = p; out[1] = vv; out[2] = t }
            3 -> { out[0] = p; out[1] = q; out[2] = vv }
            4 -> { out[0] = t; out[1] = p; out[2] = vv }
            else -> { out[0] = vv; out[1] = p; out[2] = q }
        }
    }

    private fun boxAxis(src: IntArray, out: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val r = BLUR_RADIUS
        val window = 2 * r + 1
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val stride = if (horizontal) 1 else width
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            var sum = 0
            for (k in -r..r) sum += src[base + k.coerceIn(0, length - 1) * stride]
            for (pos in 0 until length) {
                out[base + pos * stride] = sum / window
                val add = src[base + (pos + r + 1).coerceAtMost(length - 1) * stride]
                val sub = src[base + (pos - r).coerceAtLeast(0) * stride]
                sum += add - sub
            }
        }
    }
}
