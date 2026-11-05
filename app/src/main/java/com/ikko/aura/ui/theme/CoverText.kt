package com.ikko.aura.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Type set in the cover's [CoverColors.tint], at [alpha] of its strength.
 *
 * The colour is handed over as a [ColorProducer], so the fade's state is read while the text is drawn: for the
 * length of a fade (400 ms after the record changes) only the text's draw is invalidated. Reading it in the call
 * (`Text(color = cover.tint)`) made the whole enclosing composable — every row of a list — recompose for each frame
 * of the fade. Same colours, same fade, same text; only the phase that reads them changes.
 */
@Composable
fun CoverText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // White, whatever the record: the page titles used to take a trace of its hue, which read as a pale pink.
    val color = remember(alpha) {
        ColorProducer { if (alpha == 1f) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = alpha) }
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        color = color,
    )
}
