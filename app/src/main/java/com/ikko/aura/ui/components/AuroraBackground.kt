package com.ikko.aura.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ikko.aura.ui.theme.LocalAuraBatterySaver

/**
 * The app's background: the playing cover's colours as an Apple-Music-style aurora (see
 * [AuroraRenderer]), moving slowly.
 *
 * It lives on a [SurfaceView], behind the window, and that is what makes the movement affordable.
 * Drawn as part of the interface, every step of a moving background redraws everything above it —
 * every list row, the mini player's blur — and on this device that was twenty milliseconds a step
 * at idle clock speeds and more dropped frames whenever a screen changed. On a surface of its own
 * the aurora is drawn by its own thread into its own buffer and put behind the interface by the
 * compositor, so the interface does not redraw for it at all: an idle screen renders nothing, and
 * the screens over it are exactly as smooth as they were over a still one.
 *
 * Fifteen steps a second, which is Apple's own cap. The surface is a fraction of the screen's
 * size, and scaled up by the compositor, because the picture is all soft colour. It stops while
 * [active] is false (the player, which covers it), and holds one still frame under the battery
 * saver.
 */
@Composable
fun AuroraBackground(theme: AlbumTheme, active: Boolean, modifier: Modifier = Modifier) {
    val animate = !LocalAuraBatterySaver.current
    AndroidView(
        factory = { AuroraSurfaceView(it) },
        update = { it.update(theme, active, animate) },
        modifier = modifier,
    )
}

/** How often the aurora itself moves. */
private const val STEP_MS = 66L

/**
 * How long one background takes to give way to the next, and the pace the change is redrawn at. The
 * movement is only computed fifteen times a second, but a change between two backgrounds is redrawn
 * every frame of the screen: stepped at fifteen it advanced in visible jumps, with gaps of up to
 * 120ms between them.
 */
private const val CROSSFADE_MS = 750L
// One step per frame of the display, and no more: every post of this surface makes the compositor put the
// whole screen together again, and at 6ms that was two of them per frame the screen could show — landing
// exactly as the player closes, which is when the interface can least afford it.
private const val FADE_FRAME_MS = 11L

/**
 * The surface is this fraction of the view's size in each direction. A quarter: every pixel of it is
 * four on the screen, which a picture this soft carries without a hint of them, where an eighth showed
 * its grid.
 */
private const val SURFACE_DIVISOR = 4

private class AuroraSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var wanted: AlbumTheme? = null
    @Volatile private var active = true
    @Volatile private var animate = true
    @Volatile private var surfaceReady = false

    // Touched only on the render thread from here down.
    private var theme: AlbumTheme? = null
    private var renderer: AuroraRenderer? = null

    /** What was last put on the surface, in the renderer's own precision: the source for the next change to fade from. */
    private val shownR = IntArray(AURORA_PIXELS)
    private val shownG = IntArray(AURORA_PIXELS)
    private val shownB = IntArray(AURORA_PIXELS)

    /** The picture being faded away from: a copy of [shownR] and its planes taken when the change began, or null. */
    private var outgoing: Array<IntArray>? = null
    private var fadeStart = 0L
    private var lastRenderAt = 0L
    private var haveShown = false
    private var needsDraw = true
    private val tickRunnable = Runnable { tick() }

    // The picture at the surface's own size, and what it is made from.
    private var frameBitmap: Bitmap? = null
    private var frame = IntArray(0)
    private var columnLeft = IntArray(0)
    private var columnRight = IntArray(0)
    private var columnWeight = IntArray(0)
    private val rowR = IntArray(AURORA_W)
    private val rowG = IntArray(AURORA_W)
    private val rowB = IntArray(AURORA_W)
    private var dimHeight = -1
    private var rowDim = IntArray(0)

    init {
        holder.addCallback(this)
        // Eight bits a channel. PixelFormat.OPAQUE means 565 on a software canvas — five bits of red, six of
        // green, five of blue — and a gradient this soft on that shows every one of its 32 steps as a band.
        holder.setFormat(PixelFormat.RGBX_8888)
    }

    fun update(theme: AlbumTheme, active: Boolean, animate: Boolean) {
        this.wanted = theme
        this.active = active
        this.animate = animate
        handler?.let {
            it.removeCallbacks(tickRunnable)
            it.post(tickRunnable)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) holder.setFixedSize((w / SURFACE_DIVISOR).coerceAtLeast(1), (h / SURFACE_DIVISOR).coerceAtLeast(1))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = HandlerThread("aurora", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        handler = Handler(t.looper)
        surfaceReady = true
        needsDraw = true
        handler?.post(tickRunnable)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        needsDraw = true
        handler?.post(tickRunnable)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        handler?.removeCallbacks(tickRunnable)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun tick() {
        if (!surfaceReady || !active) return
        val now = SystemClock.uptimeMillis()
        val next = wanted
        var changed = false
        if (next != null && next !== theme) {
            // Whatever is on screen right now — a settled picture, or a change caught half way — becomes
            // the picture to fade out of, so a change that arrives during another starts from what the
            // eye is looking at rather than jumping back to the earlier one.
            outgoing = if (haveShown) arrayOf(shownR.copyOf(), shownG.copyOf(), shownB.copyOf()) else null
            renderer = AuroraRenderer(next.art ?: neutralArt(next))
            theme = next
            changed = true
        }
        val r = renderer ?: return
        var rendered = false
        if (changed || now - lastRenderAt >= STEP_MS) {
            r.render((now % 1_000_000L) / 1000f)
            lastRenderAt = now
            rendered = true
        }
        // The clock for the fade starts once the new picture exists, not when the change was asked
        // for: building it and drawing its first frame took a few milliseconds that were otherwise
        // spent as a jump at the start of the fade.
        if (changed) fadeStart = SystemClock.uptimeMillis()

        val fading = outgoing != null
        // Only when there is something new to show. This used to draw on every tick, and a tick that landed
        // a millisecond before the step was due drew the same picture again: thirty surface posts a second
        // for fifteen steps.
        if (rendered || fading || needsDraw) {
            compose(r)
            draw()
            needsDraw = false
        }
        when {
            fading -> handler?.postDelayed(tickRunnable, FADE_FRAME_MS)
            animate -> handler?.postDelayed(tickRunnable, (STEP_MS - (SystemClock.uptimeMillis() - lastRenderAt)).coerceIn(8L, STEP_MS))
        }
    }

    /** Puts the current picture — the frame, or the frame blended over the outgoing one — into the shown planes. */
    private fun compose(r: AuroraRenderer) {
        val old = outgoing
        if (old != null) {
            val raw = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
            // Smoothstep: the change eases in and out rather than starting and stopping abruptly.
            val p = raw * raw * (3f - 2f * raw)
            val w = (p * 256f + 0.5f).toInt().coerceIn(0, 256)
            val iw = 256 - w
            for (i in 0 until AURORA_PIXELS) {
                shownR[i] = (old[0][i] * iw + r.outR[i] * w) shr 8
                shownG[i] = (old[1][i] * iw + r.outG[i] * w) shr 8
                shownB[i] = (old[2][i] * iw + r.outB[i] * w) shr 8
            }
            if (raw >= 1f) outgoing = null
        } else {
            System.arraycopy(r.outR, 0, shownR, 0, AURORA_PIXELS)
            System.arraycopy(r.outG, 0, shownG, 0, AURORA_PIXELS)
            System.arraycopy(r.outB, 0, shownB, 0, AURORA_PIXELS)
        }
        haveShown = true
    }

    /**
     * The picture at the surface's size: the small one stretched with a bilinear blend, darkened by the
     * same top-to-bottom veil the interface's white type needs, and rounded to eight bits with a fixed
     * pattern of noise added first. Rounding a slow gradient bare turns it into bands; rounding it with
     * noise of a single step's size turns the bands into a grain no eye can find, which is what a
     * gradient this soft has to look like on an OLED.
     */
    private fun draw() {
        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            val w = canvas.width
            val h = canvas.height
            if (w <= 0 || h <= 0) return
            prepare(w, h)
            val srcW = AURORA_W
            val srcH = AURORA_H
            val noise = NOISE
            val vr = rowR
            val vg = rowG
            val vb = rowB
            var out = 0
            for (y in 0 until h) {
                val sy = ((y + 0.5f) * srcH / h - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
                val y0 = sy.toInt()
                val y1 = minOf(y0 + 1, srcH - 1)
                val fy = ((sy - y0) * 256f).toInt()
                val row0 = y0 * srcW
                val row1 = y1 * srcW
                val dim = rowDim[y]
                // Down the columns first — one blend of two source rows, and the veil, per source pixel — so
                // the pass across is a blend of two numbers per channel rather than of four.
                for (sx in 0 until srcW) {
                    vr[sx] = ((shownR[row0 + sx] * (256 - fy) + shownR[row1 + sx] * fy) shr 8) * dim shr 8
                    vg[sx] = ((shownG[row0 + sx] * (256 - fy) + shownG[row1 + sx] * fy) shr 8) * dim shr 8
                    vb[sx] = ((shownB[row0 + sx] * (256 - fy) + shownB[row1 + sx] * fy) shr 8) * dim shr 8
                }
                val noiseRow = (y and NOISE_MASK) shl NOISE_BITS
                for (x in 0 until w) {
                    val x0 = columnLeft[x]
                    val x1 = columnRight[x]
                    val fx = columnWeight[x]
                    val ifx = 256 - fx
                    val t = noise[noiseRow or (x and NOISE_MASK)].toInt() and 0xFF
                    val r = (vr[x0] * ifx + vr[x1] * fx) shr 8
                    val g = (vg[x0] * ifx + vg[x1] * fx) shr 8
                    val b = (vb[x0] * ifx + vb[x1] * fx) shr 8
                    frame[out++] = (0xFF shl 24) or (((r + t) shr 8) shl 16) or (((g + t) shr 8) shl 8) or ((b + t) shr 8)
                }
            }
            val bmp = frameBitmap ?: return
            bmp.setPixels(frame, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Everything that depends only on the surface's size, made again only when it changes. */
    private fun prepare(w: Int, h: Int) {
        if (frame.size != w * h) {
            frame = IntArray(w * h)
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            columnLeft = IntArray(w)
            columnRight = IntArray(w)
            columnWeight = IntArray(w)
            for (x in 0 until w) {
                val sx = ((x + 0.5f) * AURORA_W / w - 0.5f).coerceIn(0f, (AURORA_W - 1).toFloat())
                columnLeft[x] = sx.toInt()
                columnRight[x] = minOf(sx.toInt() + 1, AURORA_W - 1)
                columnWeight[x] = ((sx - sx.toInt()) * 256f).toInt()
            }
            dimHeight = -1
        }
        if (dimHeight != h) {
            // White type sits on all of this, and it has to read on the brightest corner — more
            // so at the bottom, where the mini player is. A black veil from 10% at the top to 42% at the
            // bottom, through 20% a little over half way down.
            rowDim = IntArray(h) { y ->
                val u = if (h > 1) y.toFloat() / (h - 1) else 0f
                val a = if (u < 0.55f) {
                    DIM_TOP + (DIM_MID - DIM_TOP) * (u / 0.55f)
                } else {
                    DIM_MID + (DIM_BOTTOM - DIM_MID) * ((u - 0.55f) / 0.45f)
                }
                ((1f - a) * 256f + 0.5f).toInt().coerceIn(0, 256)
            }
            dimHeight = h
        }
    }
}

private const val DIM_TOP = 0x1A / 255f
private const val DIM_MID = 0x33 / 255f
private const val DIM_BOTTOM = 0x6B / 255f

private const val NOISE_BITS = 7
private const val NOISE_MASK = (1 shl NOISE_BITS) - 1

/** A fixed square of noise, each value 0..255 — one step of the 8.8 fixed point the picture is kept in. */
private val NOISE: ByteArray = ByteArray(1 shl (2 * NOISE_BITS)).also { java.util.Random(0x41757261L).nextBytes(it) }
