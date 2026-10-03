package com.ikko.aura.ui.components

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ikko.aura.ui.theme.AuraColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// The wave of the reference (Cobalt), measured frame by frame: a wavelength a tenth of the bar, a thick stroke, one
// cycle a second, and an amplitude that swells on every beat of the music.
private val TRACK_HEIGHT = 34.dp
private val WAVE_STROKE = 4.5.dp
private val LINE_STROKE = 4.dp
private val WAVE_AMPLITUDE = 4.5.dp
private val WAVELENGTH = 40.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 28.dp
private val END_DOT_RADIUS = 2.5.dp

/** How much taller the wave stands on a beat: the reference goes from 9 px to 13 px, a little under half again. */
private const val BEAT_GAIN = 0.45f

/** The swell on a beat: up in this long, back to rest in that long (read off the reference's 60 fps frames). */
private const val BEAT_ATTACK_S = 0.12f
private const val BEAT_DECAY_S = 0.22f

/** The wave is stepped once every this many frames of the display. */
private const val WAVE_FRAME_STRIDE = 2
private const val WAVE_CYCLE_MS = 1000
private const val AMPLITUDE_SETTLE_MS = 450

/**
 * The seek bar from the mockup: the elapsed side of the track is a travelling sine wave, the
 * remaining side a flat line ending in a dot, and a vertical bar marks the join.
 *
 * Material 3's own `LinearWavyProgressIndicator` is a progress *indicator* — it has no thumb and
 * cannot be dragged — and this project is pinned to material3 1.3.1, which predates it entirely.
 * Drawn here instead, which also buys the two behaviours the mockup implies: the wave only
 * travels while audio is actually playing, and it flattens out while you are scrubbing so the
 * thumb has a straight line to sit on.
 *
 * Everything animated is read inside the draw lambda, so a frame of wave motion invalidates the
 * draw pass alone — no recomposition, no relayout. The same rule the rest of the app's hot paths
 * follow.
 */
@Composable
fun WavyProgressBar(
    /** Read here, not by the caller, so a playback tick recomposes this bar and not the screen around it. */
    positionMs: () -> Long,
    durationMs: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Seconds since the last beat of what is being heard, or null while the tempo is not known: the wave then holds steady. */
    sinceBeat: () -> Double? = { null },
    /**
     * How far in from its own edges the bar is drawn. Given the whole screen's width with this as its margin (in
     * the player), it is drawn where it always was while its touch area runs to the screen's edges — where the
     * page's margin had left a strip that belonged to Android's back gesture: grabbing the bar from the side went
     * back instead of seeking. The whole width is kept out of the back gesture too.
     */
    edgeInset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val safeDuration = durationMs().coerceAtLeast(1L)
    // What the player last reported (every half second, and on play, pause and seek): the labels show it, and so
    // does the thumb while nothing plays. While a track plays the thumb moves between two reports (see below).
    val playedFraction = dragFraction
        ?: (positionMs().toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayedMs = (playedFraction * safeDuration).toLong()

    val reading = remember { object { var reported = Long.MIN_VALUE; var at = 0L; var pending = Long.MIN_VALUE } }
    val waving = isPlaying && dragFraction == null
    // Paused with no new report since the thumb last moved: it stays where the music stopped (the report can be up to
    // half a second behind, as the player only reports while it plays), instead of stepping back to it. A seek or a new
    // track gives a new report and the thumb goes to it.
    val frozen = !isPlaying && dragFraction == null && positionMs() == reading.reported
    // Flattening rather than freezing: a wave that simply stops mid-crest reads as a glitch,
    // whereas easing the amplitude to zero reads as the track settling.
    val amplitudeScale = animateFloatAsState(
        targetValue = if (waving) 1f else 0f,
        animationSpec = tween(AMPLITUDE_SETTLE_MS),
        label = "wavy-progress-amplitude",
    )
    // The wave's travel — one wavelength every WAVE_CYCLE_MS, as before — advanced by a frame loop that
    // only runs while there is a wave to move: while playing, and on through the settle so it flattens
    // while still travelling. This was an infinite transition, which asks for every frame for as long as
    // the player is composed: a paused player drew nothing, but its main thread still woke ~90 times a
    // second (17% of a core, measured) to step a value nobody read.
    val phase = remember { mutableFloatStateOf(0f) }
    // 0 at rest, 1 at the top of a beat's swell.
    val pulse = remember { mutableFloatStateOf(0f) }
    // Where the thumb is while a track plays, in ms: the last report plus the time since it came, stepped by the same
    // loop as the wave. The player reports twice a second and the thumb used to jump to each report; between two it now
    // keeps moving at the pace of the music, a fraction of a pixel a step.
    val smoothMs = remember { mutableFloatStateOf(0f) }
    val latestPosition by rememberUpdatedState(positionMs)
    LaunchedEffect(waving) {
        if (!waving && amplitudeScale.value == 0f) return@LaunchedEffect
        if (waving) {
            // Resuming: where the thumb stands is kept, unless the report is somewhere else altogether.
            val reported = latestPosition().toFloat()
            if (kotlin.math.abs(reported - smoothMs.floatValue) > 700f) smoothMs.floatValue = reported
            reading.reported = Long.MIN_VALUE
            reading.pending = Long.MIN_VALUE
        }
        val runForMs = if (waving) Long.MAX_VALUE else AMPLITUDE_SETTLE_MS.toLong()
        var last = -1L
        var elapsed = 0L
        var owed = 0L
        var frame = 0
        while (elapsed < runForMs) {
            withFrameMillis { now ->
                if (last >= 0L) {
                    val dt = now - last
                    elapsed += dt
                    owed += dt
                    // Moved on every other frame: the wave travels about a pixel a frame, and each step redraws the
                    // whole player (its glass is re-rendered whenever anything above it changes), so at ninety-odd
                    // a second the screen was being rebuilt continuously for a progress bar. Every other frame keeps
                    // the same pace and the same even rhythm — the skipped frames owe their time to the next.
                    if (frame++ % WAVE_FRAME_STRIDE == 0) {
                        phase.floatValue = (phase.floatValue + owed.toFloat() / WAVE_CYCLE_MS) % 1f
                        owed = 0L
                        val since = if (waving) sinceBeat() else null
                        pulse.floatValue = if (since == null) 0f else beatSwell(since.toFloat())
                        if (waving) {
                            val reported = latestPosition()
                            if (reported != reading.reported) { reading.reported = reported; reading.at = now }
                            val target = (reported + (now - reading.at)).toFloat()
                            val current = smoothMs.floatValue
                            // Never backwards for a report that merely arrived a little late. A real jump (a seek, a new
                            // track) is followed once the report has held for a second step: right after a seek the player
                            // can still answer with the old position for a moment, and following that too made the thumb
                            // go to the new place, back, and to the new place again.
                            val jump = kotlin.math.abs(target - current) > 250f
                            if (!jump) {
                                reading.pending = Long.MIN_VALUE
                                smoothMs.floatValue = if (target < current) current else target
                            } else if (reading.pending == reported) {
                                reading.pending = Long.MIN_VALUE
                                smoothMs.floatValue = target
                            } else {
                                reading.pending = reported
                            }
                        }
                    }
                }
                last = now
            }
        }
    }

    // One Path for the wave, emptied and refilled each frame rather than allocated each frame: the wave is
    // redrawn ninety-odd times a second while a track plays, and a new native Path per frame was garbage for
    // the collector to chase for nothing.
    val wavePath = remember { Path() }

    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .systemGestureExclusion()
                .pointerInput(safeDuration, edgeInset) {
                    val over = edgeInset.toPx()
                    val track = (size.width - 2 * over).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - over) / track).coerceIn(0f, 1f)
                    // Both detectors start undispatched. Compose starts this block lazily, on the first touch; a plain
                    // launch would begin listening only after that touch had been delivered, so the first gesture after
                    // the player opened reached the page instead — which read a drag to the right as "previous track".
                    coroutineScope {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            detectTapGestures { offset ->
                                onSeek((fractionAt(offset.x) * safeDuration).toLong())
                            }
                        }
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = fractionAt(offset.x)
                                },
                                onDragEnd = {
                                    dragFraction?.let { onSeek((it * safeDuration).toLong()) }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null },
                            ) { change, dragAmount ->
                                val current = dragFraction ?: fractionAt(change.position.x)
                                dragFraction = (current + dragAmount / track).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
        Canvas(
            modifier = Modifier
                .padding(horizontal = edgeInset)
                .fillMaxSize()
                // Its own layer, so a frame of wave motion re-records this canvas and nothing else.
                // Without one the redraw lands on the nearest enclosing layer — the whole player's
                // content — and the page, its glass and its text were re-recorded on every frame while
                // a track played (~2 ms of main thread per frame, traced on the device).
                .graphicsLayer(),
        ) {
            val centerY = size.height / 2f
            val strokePx = WAVE_STROKE.toPx()
            val linePx = LINE_STROKE.toPx()
            val thumbW = THUMB_WIDTH.toPx()
            val wavelength = WAVELENGTH.toPx()
            val amplitude = WAVE_AMPLITUDE.toPx() * (1f + BEAT_GAIN * pulse.floatValue) * amplitudeScale.value

            // Half the thumb is kept clear at either end so it never hangs off the edge, and the
            // wave and the line are laid out inside what is left.
            val start = thumbW / 2f
            val end = size.width - thumbW / 2f
            val usable = (end - start).coerceAtLeast(1f)
            // Read only while a track plays: a paused bar is not redrawn for it.
            // A stepped value that is far from the report is stale (a seek, a pause, a new track, in the frame before the
            // loop catches up): the report is believed then, so the thumb never shows an old place for a frame.
            val shownFraction = if (waving || frozen) {
                val stepped = smoothMs.floatValue
                val reportedMs = playedFraction * safeDuration
                if (kotlin.math.abs(stepped - reportedMs) > 700f) playedFraction else (stepped / safeDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                playedFraction
            }
            val thumbX = start + usable * shownFraction

            // As in the reference: what has been played is a short flat dash at first, and the wave rises out of it as the
            // played part grows — a third of a wavelength is still flat, a wavelength and a half is the full wave. No dot,
            // no hook at the left end: it starts on the line's own level and only ever gets taller.
            val played = thumbX - start
            if (played > strokePx) {
                val path = wavePath.apply { reset() }
                // Read only while there is a wave to move. Not reading it is what unsubscribes
                // this draw from the animation, so a paused track stops invalidating the canvas
                // ninety times a second to redraw the same straight line.
                val shift = if (amplitude > 0f) phase.floatValue * wavelength else 0f
                val grow = ((played - wavelength * 0.3f) / (wavelength * 1.2f)).coerceIn(0f, 1f)
                val height = amplitude * grow * grow * (3f - 2f * grow)
                // The wave's end stops a little short of the thumb, as the reference's does.
                val tip = (thumbX - thumbW * 0.5f - strokePx * 0.25f).coerceAtLeast(start + 0.5f)
                var x = start
                path.moveTo(x, centerY + height * sin(shift / wavelength * 2f * PI.toFloat()))
                while (x < tip) {
                    val next = (x + WAVE_STEP_PX).coerceAtMost(tip)
                    val y = centerY + height * sin((next - start + shift) / wavelength * 2f * PI.toFloat())
                    path.lineTo(next, y)
                    x = next
                }
                drawPath(
                    path = path,
                    color = AuraColors.TextPrimary,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            if (thumbX < end - 0.5f) {
                drawLine(
                    color = RemainingTrackColor,
                    start = Offset(thumbX + thumbW, centerY),
                    end = Offset(end, centerY),
                    strokeWidth = linePx,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = AuraColors.TextPrimary,
                    radius = END_DOT_RADIUS.toPx(),
                    center = Offset(end, centerY),
                )
            }

            drawRoundRect(
                color = AuraColors.TextPrimary,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - THUMB_HEIGHT.toPx() / 2f),
                size = androidx.compose.ui.geometry.Size(thumbW, THUMB_HEIGHT.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f),
            )
        }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeInset + 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayedMs),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
                color = AuraColors.TextSecondary,
            )
            Text(
                text = "-" + formatDuration((safeDuration - displayedMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = com.ikko.aura.ui.theme.TABULAR_NUMBERS),
                color = AuraColors.TextSecondary,
            )
        }
    }
}

/**
 * Sampling step for the wave path. At this density a 2px step is a little under the stroke width,
 * which is fine enough that the polyline is indistinguishable from a curve and coarse enough that
 * a full-width wave is a couple of hundred segments rather than a couple of thousand.
 */
private const val WAVE_STEP_PX = 2f

private val RemainingTrackColor = Color(0x99FFFFFF)

/** The swell [sinceBeat] seconds after a beat: a quick rise that eases off, then a slower, softer return to rest. */
private fun beatSwell(sinceBeat: Float): Float = when {
    sinceBeat < BEAT_ATTACK_S -> sin(sinceBeat / BEAT_ATTACK_S * (PI.toFloat() / 2f))
    sinceBeat < BEAT_ATTACK_S + BEAT_DECAY_S -> {
        val u = (sinceBeat - BEAT_ATTACK_S) / BEAT_DECAY_S
        1f - u * u * (3f - 2f * u)
    }
    else -> 0f
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
