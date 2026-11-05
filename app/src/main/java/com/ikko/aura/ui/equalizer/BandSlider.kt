package com.ikko.aura.ui.equalizer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.core.audio.dsp.IsoBands
import com.ikko.aura.ui.theme.AuraFontFamily
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every value a band can show, in tenths of a dB from -12.0 to +12.0, written once. The label over a slider is a
 * lookup, not a format call: a drag at the display's rate builds no strings.
 */
internal object GainLabels {
    private const val STEPS = (IsoBands.MAX_GAIN_DB * 10).toInt()
    private val labels = Array(STEPS * 2 + 1) { i ->
        val tenths = i - STEPS
        when {
            tenths == 0 -> "0"
            else -> String.format(Locale.FRANCE, "%+.1f", tenths / 10f).replace('-', '−')
        }
    }

    fun of(db: Float): String = labels[((db * 10f).roundToInt() + STEPS).coerceIn(0, labels.size - 1)]
}

/** Within this of 0 dB, a dragged band holds at 0: the detent the haptic tick marks. */
private const val DETENT_DB = 0.35f

/**
 * One vertical band: its gain above, its frequency below, and a track between whose filled part grows from the
 * 0 dB line. Dragging is relative (the finger moves the value, it does not jump to where it lands) at
 * [dbPerTrack] over the track's height; crossing 0 dB, the band holds there for [DETENT_DB] and ticks; a double tap
 * returns it to 0.
 *
 * The track is drawn in a draw pass that reads the band's float state, so a drag redraws it without recomposing.
 */
@Composable
fun BandSlider(
    label: String,
    gain: MutableFloatState,
    accent: Color,
    active: Boolean,
    onDragStart: () -> Unit,
    onChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 196.dp,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val trackWidth = remember(density) { with(density) { 4.dp.toPx() } }
    val thumbRadius = remember(density) { with(density) { 9.dp.toPx() } }
    val fillColor = if (active) accent else Color.White.copy(alpha = 0.5f)
    val latestChange by rememberUpdatedState(onChange)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestEnd by rememberUpdatedState(onDragEnd)
    val latestReset by rememberUpdatedState(onReset)
    val labelStyle = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Reads the state where it is shown: only this Text recomposes on a drag.
        GainText(gain, labelStyle.copy(color = Color.White))
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .semantics {
                    contentDescription = "Bande $label hertz"
                    stateDescription = "${GainLabels.of(gain.floatValue)} décibels"
                    progressBarRangeInfo = ProgressBarRangeInfo(gain.floatValue, -IsoBands.MAX_GAIN_DB..IsoBands.MAX_GAIN_DB, steps = 0)
                    setProgress { value ->
                        latestStart()
                        latestChange(value)
                        latestEnd()
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestReset()
                    })
                }
                .pointerInput(Unit) {
                    // The unsnapped value the finger has dragged to; what is shown holds at 0 within the detent.
                    var raw = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            raw = gain.floatValue
                            latestStart()
                        },
                        onDragEnd = { latestEnd() },
                        onDragCancel = { latestEnd() },
                    ) { pointer, dragAmount ->
                        pointer.consume()
                        val range = IsoBands.MAX_GAIN_DB * 2f
                        val usable = (size.height - 2 * thumbRadius).coerceAtLeast(1f)
                        val before = gain.floatValue
                        raw = (raw - dragAmount / usable * range).coerceIn(-IsoBands.MAX_GAIN_DB, IsoBands.MAX_GAIN_DB)
                        val shown = if (abs(raw) < DETENT_DB) 0f else raw
                        if (shown == 0f && before != 0f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (shown != before) latestChange(shown)
                    }
                }
                .drawBehind {
                    val g = gain.floatValue
                    val cx = size.width / 2f
                    val top = thumbRadius
                    val usable = size.height - 2 * thumbRadius
                    val zeroY = top + usable / 2f
                    val y = top + usable * (1f - (g + IsoBands.MAX_GAIN_DB) / (2 * IsoBands.MAX_GAIN_DB))
                    val radius = CornerRadius(trackWidth / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(cx - trackWidth / 2f, top), Size(trackWidth, usable), radius)
                    // The 0 dB mark, across the track.
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - trackWidth * 2.2f, zeroY), Offset(cx + trackWidth * 2.2f, zeroY), trackWidth / 3f)
                    val from = minOf(y, zeroY)
                    val to = maxOf(y, zeroY)
                    if (to - from > 0.5f) drawRoundRect(fillColor, Offset(cx - trackWidth / 2f, from), Size(trackWidth, to - from), radius)
                    drawCircle(Color.Black.copy(alpha = 0.25f), thumbRadius + 1.5f, Offset(cx, y + 1.5f))
                    drawCircle(Color.White, thumbRadius, Offset(cx, y))
                },
        )
        Text(label, style = labelStyle.copy(color = Color.White.copy(alpha = 0.55f)), maxLines = 1)
    }
}

@Composable
private fun GainText(gain: MutableFloatState, style: TextStyle) {
    Text(GainLabels.of(gain.floatValue), style = style, maxLines = 1)
}
