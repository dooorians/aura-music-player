package com.ikko.aura.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikko.aura.data.lyrics.LyricLine

/**
 * A playback clock fine enough to sweep a word.
 *
 * [PlayerViewModel][com.ikko.aura.viewmodel.PlayerViewModel] publishes its position
 * twice a second, which is plenty for a progress bar and useless for a highlight that
 * has to cross a syllable: the sweep would advance in half-second steps. This anchors
 * on each published value and runs forward from it frame by frame, re-anchoring every
 * time a real one arrives — so it is smooth between updates and can never drift away
 * from the actual position for longer than one update.
 *
 * The value is meant to be read inside a draw lambda. Read during composition it would
 * recompose whatever read it ninety times a second, which is the opposite of the point.
 *
 * [active] is what stops it running for nothing. The capsule and the lyrics screen both ask for
 * a clock before they know whether the track has any words to sweep, and on a track that has
 * none the loop below was still waking on every frame to write a value nobody reads — a
 * choreographer callback and a snapshot write at 90Hz for the entire song.
 */
@Composable
fun rememberLyricClock(
    positionMs: Long,
    isPlaying: Boolean,
    active: Boolean = true,
): MutableLongState {
    val clock = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, active) {
        clock.longValue = positionMs
        if (!isPlaying || !active) return@LaunchedEffect
        val anchor = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame -> clock.longValue = positionMs + (frame - anchor) }
        }
    }
    return clock
}

/**
 * The same clock, read off the player on every frame instead of extrapolated from a published value.
 *
 * [livePosition] is the player's own position. Anchored on a value published twice a second, the clock was up to half
 * a second wrong after a seek, a pause or a track change, and the lines (which change on it) came late by as much. Read
 * straight, it is as right as the player is, always: nothing to re-anchor, nothing to drift.
 *
 * [leadMs] is how far ahead of the audio the clock runs while playing. A frame drawn now reaches the eye one or two
 * frames and a display's latency later, and the voice is already there: the lead puts the light where the sound is
 * when it is seen. Not applied while paused, where the page must show exactly the place the music stopped at.
 *
 * Idle when [active] is false; paused, it looks only a few times a second (enough to follow a seek).
 */
@Composable
fun rememberLiveLyricClock(
    livePosition: () -> Long,
    isPlaying: Boolean,
    active: Boolean = true,
    leadMs: Long = LYRIC_LEAD_MS,
): MutableLongState {
    val clock = remember { mutableLongStateOf(livePosition()) }
    val read = androidx.compose.runtime.rememberUpdatedState(livePosition)
    LaunchedEffect(isPlaying, active) {
        clock.longValue = read.value() + if (isPlaying) leadMs else 0L
        if (!active) return@LaunchedEffect
        if (isPlaying) {
            while (true) withFrameMillis { clock.longValue = read.value() + leadMs }
        } else {
            while (true) {
                kotlinx.coroutines.delay(PAUSED_POLL_MS)
                clock.longValue = read.value()
            }
        }
    }
    return clock
}

/** The index of the line being sung at [positionMs] (-1 before the first), for lines in time order. */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

internal const val LYRIC_LEAD_MS = 80L
private const val PAUSED_POLL_MS = 120L

/**
 * One lyric line with the sung part lit and the rest left dim, the boundary moving
 * through the line as it is sung.
 *
 * Drawn as two copies of the same text — dim underneath, lit on top clipped to what has
 * been sung. The clip is built per *visual* line off the layout, so a line that wraps
 * sweeps along each row in turn instead of the whole block filling at once.
 *
 * On a word-synced source the boundary tracks each word's own span, so a held note fills
 * slowly and a rattled-off one snaps; on a line-synced source the whole line lights at
 * its stamp, because that is all the source knows.
 *
 * [positionProvider] is called in the draw phase on purpose: the head moves every frame,
 * and reading it here means a frame of movement costs a redraw of one text and nothing
 * else — no recomposition, no relayout.
 */
@Composable
fun SweepLyricLine(
    line: LyricLine,
    positionProvider: () -> Long,
    style: TextStyle,
    litColor: Color,
    dimColor: Color,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    /** For a single-line strip: the line is cut with an ellipsis rather than wrapped. */
    maxLines: Int = Int.MAX_VALUE,
    /**
     * The one-line strip's look: the sung part just fills in, at a steady pace across each timed word, with no bloom
     * on the head. The full lyrics keep the glow.
     */
    plain: Boolean = false,
) {
    if (!line.isWordSynced) {
        Text(
            text = line.text,
            style = style,
            color = litColor,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Where each timed word sits in the text, walked in order so a repeated word still lines up.
    val spans = remember(line, plain) {
        if (!plain) {
            emptyList()
        } else {
            var offset = 0
            line.words.map { word ->
                val start = line.text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
                offset = start + word.text.length
                start until offset
            }
        }
    }

    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = dimColor,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = line.text,
            style = style,
            color = litColor,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                // Its own layer, so each frame of the sweep re-records this text alone. Without one the
                // redraw falls to the nearest enclosing layer, which in the player is its whole content.
                // Not clipped, so the glow below can still spill past the text's bounds.
                .graphicsLayer()
                .drawWithContent {
                    val result = layout ?: return@drawWithContent
                    val revealed = line.revealedChars(positionProvider())
                    if (revealed <= 0f) return@drawWithContent

                    var head = Offset.Unspecified
                    for (row in 0 until result.lineCount) {
                        val start = result.getLineStart(row)
                        val end = result.getLineEnd(row, visibleEnd = true)
                        if (revealed <= start) break
                        val top = result.getLineTop(row)
                        val bottom = result.getLineBottom(row)
                        val left = result.getLineLeft(row)
                        val right = if (revealed >= end) {
                            result.getLineRight(row)
                        } else {
                            if (plain) {
                                horizontalAtSpans(result, revealed, row, spans)
                            } else {
                                head = Offset(horizontalAt(result, revealed, start, end), (top + bottom) / 2f)
                                horizontalAt(result, revealed, start, end)
                            }
                        }
                        clipRect(left, top, right, bottom) { this@drawWithContent.drawContent() }
                    }

                    // A soft bloom riding on the head of the sweep. Modulated by how long
                    // the word being sung is held — a note carried for a second swells, a
                    // word rattled off in a tenth of one barely registers, which is the
                    // difference between light that belongs to the singing and a lamp
                    // dragged along under the text.
                    val glow = if (plain) 0f else line.glowIntensity(positionProvider())
                    if (glow > 0.01f && head != Offset.Unspecified) {
                        val radius = GLOW_RADIUS.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(litColor.copy(alpha = 0.30f * glow), Color.Transparent),
                                center = head,
                                radius = radius,
                            ),
                            radius = radius,
                            center = head,
                        )
                    }
                },
        )
    }
}

/**
 * Where a fractional character offset falls across the row, interpolated between the two
 * character boundaries it sits between — which is what makes the head crawl through a
 * held word rather than jumping from letter to letter.
 */
private fun horizontalAt(result: TextLayoutResult, revealed: Float, start: Int, end: Int): Float {
    val index = revealed.toInt().coerceIn(start, end)
    val fraction = (revealed - index).coerceIn(0f, 1f)
    val here = result.getHorizontalPosition(index, usePrimaryDirection = true)
    if (index >= end) return here
    val next = result.getHorizontalPosition(index + 1, usePrimaryDirection = true)
    return here + (next - here) * fraction
}

/** Where a row's text ends at [offset], held inside the row's own edges. */
private fun TextLayoutResult.xOn(offset: Int, row: Int): Float {
    val left = getLineLeft(row)
    val right = getLineRight(row)
    return when {
        offset <= getLineStart(row) -> left
        offset >= getLineEnd(row, visibleEnd = true) -> right
        else -> getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(left, right)
    }
}

/**
 * Where the lit part of a row ends, [chars] characters in. Inside a timed word the edge crosses it at a constant rate
 * — read letter by letter it sped up over an "i" and slowed over a "w" — and between words, or across a wrap, it goes
 * letter by letter.
 */
private fun horizontalAtSpans(result: TextLayoutResult, chars: Float, row: Int, spans: List<IntRange>): Float {
    val rowStart = result.getLineStart(row)
    val rowEnd = result.getLineEnd(row, visibleEnd = true)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= rowStart && word.last + 1 <= rowEnd) {
        val from = result.xOn(word.first, row)
        val to = result.xOn(word.last + 1, row)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    val index = chars.toInt().coerceIn(rowStart, rowEnd)
    val here = result.xOn(index, row)
    val next = result.xOn((index + 1).coerceAtMost(rowEnd), row)
    return here + (next - here) * (chars - index)
}

private val GLOW_RADIUS = 22.dp
