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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = dimColor,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = line.text,
            style = style,
            color = litColor,
            textAlign = textAlign,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
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
                            head = Offset(horizontalAt(result, revealed, start, end), (top + bottom) / 2f)
                            horizontalAt(result, revealed, start, end)
                        }
                        clipRect(left, top, right, bottom) { this@drawWithContent.drawContent() }
                    }

                    // A soft bloom riding on the head of the sweep. Modulated by how long
                    // the word being sung is held — a note carried for a second swells, a
                    // word rattled off in a tenth of one barely registers, which is the
                    // difference between light that belongs to the singing and a lamp
                    // dragged along under the text.
                    val glow = line.glowIntensity(positionProvider())
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

private val GLOW_RADIUS = 22.dp
