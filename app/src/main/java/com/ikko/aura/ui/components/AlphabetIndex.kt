package com.ikko.aura.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikko.aura.ui.theme.AuraColors
import kotlin.math.roundToInt
import com.ikko.aura.ui.theme.LocalCoverColors
import com.ikko.aura.ui.theme.LibraryTypography
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues

/**
 * The one thing a long list and a long grid are asked for identically — where am I, and take me
 * there — behind one name, so [AlphabetIndex] (and its double tap to the top) works over either without
 * caring which they were handed.
 */
@Stable
interface ScrollTarget {
    val firstVisibleIndex: Int
    val itemCount: Int
    suspend fun jumpTo(index: Int)
    suspend fun animateToTop()
}

@Composable
fun rememberScrollTarget(state: LazyListState): ScrollTarget = remember(state) {
    object : ScrollTarget {
        override val firstVisibleIndex: Int get() = state.firstVisibleItemIndex
        override val itemCount: Int get() = state.layoutInfo.totalItemsCount
        override suspend fun jumpTo(index: Int) = state.scrollToItem(index)
        override suspend fun animateToTop() = state.animateScrollToItem(0)
    }
}

@Composable
fun rememberScrollTarget(state: LazyGridState): ScrollTarget = remember(state) {
    object : ScrollTarget {
        override val firstVisibleIndex: Int get() = state.firstVisibleItemIndex
        override val itemCount: Int get() = state.layoutInfo.totalItemsCount
        override suspend fun jumpTo(index: Int) = state.scrollToItem(index)
        override suspend fun animateToTop() = state.animateScrollToItem(0)
    }
}

/**
 * The letter a name files under. Everything that does not begin with a letter — a number, a
 * bracket, a symbol — files under `#`, which is where a listener looks for it.
 *
 * Accents are folded first: "Ángel" belongs with the As, and a strip where it did not would have
 * a letter that jumps to nothing and a track reachable from no letter at all.
 */
fun indexLetterOf(name: String): Char {
    val first = name.trim().firstOrNull() ?: return INDEX_OTHER
    val folded = when (first.uppercaseChar()) {
        'À', 'Á', 'Â', 'Ã', 'Ä', 'Å' -> 'A'
        'Ç' -> 'C'
        'È', 'É', 'Ê', 'Ë' -> 'E'
        'Ì', 'Í', 'Î', 'Ï' -> 'I'
        'Ñ' -> 'N'
        'Ò', 'Ó', 'Ô', 'Õ', 'Ö' -> 'O'
        'Ù', 'Ú', 'Û', 'Ü' -> 'U'
        'Ý' -> 'Y'
        else -> first.uppercaseChar()
    }
    return if (folded in 'A'..'Z') folded else INDEX_OTHER
}

const val INDEX_OTHER = '#'

/** `#` first, then A–Z: the order a name-sorted list actually comes out in. */
val INDEX_LETTERS: List<Char> = listOf(INDEX_OTHER) + ('A'..'Z')

/**
 * PowerAmp's alphabet strip, made invisible: a column down the right edge that scrubs the list as a finger runs
 * over it, with nothing drawn — no letters, no pill behind them (the user's choice, cleaner). Only the magnified
 * letter shows, beside the finger, while it scrubs.
 *
 * Dragging rather than tapping is the point: the finger lands roughly and slides, with the bubble saying where it
 * is and each new letter ticking the haptics, so it works without looking. A double tap in the same zone goes
 * back to the top of the list — it replaced the back-to-top button. A single tap still jumps to the letter under
 * it. Letters nothing files under are skipped, so a library with no Q never jumps to the nearest thing.
 */
@Composable
fun AlphabetIndex(
    available: Set<Char>,
    onLetterPicked: (Char) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var stripHeightPx by remember { mutableIntStateOf(0) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }

    fun pick(y: Float) {
        if (stripHeightPx <= 0) return
        val slot = (y / stripHeightPx * INDEX_LETTERS.size).toInt()
            .coerceIn(0, INDEX_LETTERS.lastIndex)
        if (slot == activeIndex) return
        activeIndex = slot
        val letter = INDEX_LETTERS[slot]
        if (letter in available) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLetterPicked(letter)
        }
    }

    // The touch zone is the strip's old place: its height, and 40 dp wide at the screen's edge.
    Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .width(TOUCH_WIDTH)
                .height(STRIP_HEIGHT)
                .onSizeChanged { stripHeightPx = it.height }
                .pointerInput(available) {
                    detectTapGestures(
                        onDoubleTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDoubleTap()
                        },
                        onTap = { offset ->
                            pick(offset.y)
                            activeIndex = null
                        },
                    )
                }
                .pointerInput(available) {
                    detectVerticalDragGestures(
                        onDragEnd = { activeIndex = null },
                        onDragCancel = { activeIndex = null },
                    ) { change, _ ->
                        pick(change.position.y)
                        change.consume()
                    }
                },
        )

        // The magnified letter, parked beside whichever row the finger is on rather than centred
        // on the screen: level with the finger it can be read without the hand being in the way.
        val shown = activeIndex
        if (shown != null && stripHeightPx > 0) {
            val slotHeight = stripHeightPx.toFloat() / INDEX_LETTERS.size
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = -(STRIP_WIDTH + STRIP_MARGIN + BUBBLE_GAP).roundToPx(),
                            y = (slotHeight * (shown + 0.5f) - BUBBLE_SIZE.toPx() / 2f).roundToInt(),
                        )
                    }
                    .size(BUBBLE_SIZE)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AuraColors.PillScrim),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = INDEX_LETTERS[shown].toString(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/** Where the strip was drawn — 13 dp wide, 10 dp in from the edge — still places the bubble; 253 dp tall. */
private val STRIP_WIDTH = 13.dp
private val STRIP_HEIGHT = 253.dp
private val STRIP_MARGIN = 10.dp
private val TOUCH_WIDTH = 40.dp
private val BUBBLE_SIZE = 62.dp
private val BUBBLE_GAP = 10.dp

/**
 * How far to move the strip down from the top of the list it is laid over, in px: read where it is laid
 * out, so the strip can hold still on the screen while the header above the list folds away.
 */
val LocalIndexStripShift = androidx.compose.runtime.compositionLocalOf<() -> Float> { { 0f } }
