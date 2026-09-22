package com.ikko.aura.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * there — behind one name, so [AlphabetIndex] and [ScrollToTopButton] work over either without
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
 * PowerAmp's alphabet strip: a thin column of letters down the right edge that scrubs the list
 * as a finger runs over it.
 *
 * Dragging rather than tapping is the point — the whole alphabet is a few hundred pixels tall,
 * so every letter is a target far under the touch minimum and tapping one accurately is not
 * realistic. Held as a scrub, the size stops mattering: the finger lands roughly and slides to
 * the letter, with the magnified bubble saying where it is. Each new letter ticks the haptics,
 * which is what makes it usable without looking.
 *
 * [available] dims the letters nothing files under, so the strip tells the truth about a library
 * with no Q in it rather than jumping to the nearest thing.
 */
@Composable
fun AlphabetIndex(
    available: Set<Char>,
    current: Char?,
    onLetterPicked: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var stripHeightPx by remember { mutableIntStateOf(0) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    val cover = LocalCoverColors.current

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

    // Drawn rather than composed. Twenty-seven Text nodes laid out with SpaceEvenly cannot be
    // made smaller than their line height, so on a short list area the strip simply overflowed
    // and the last letters fell off the bottom — the one thing an index must never do. Measured
    // once and drawn into slots computed from the height actually available, it always fits,
    // and it costs one node instead of twenty-seven.
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer) {
        INDEX_LETTERS.map { measurer.measure(AnnotatedString(it.toString()), LibraryTypography.IndexLetter) }
    }
    val currentGlyphs = remember(measurer) {
        INDEX_LETTERS.map { measurer.measure(AnnotatedString(it.toString()), LibraryTypography.IndexLetterCurrent) }
    }

    // The touch zone is the strip's own height and 40 dp wide: the strip drawn inside it, a hand's breadth
    // narrower than the finger that scrubs it.
    Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
        Canvas(
            modifier = Modifier
                .width(TOUCH_WIDTH)
                .height(STRIP_HEIGHT)
                .onSizeChanged { stripHeightPx = it.height }
                .pointerInput(available) {
                    detectTapGestures(
                        onPress = { offset ->
                            pick(offset.y)
                            tryAwaitRelease()
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
        ) {
            val stripWidth = STRIP_WIDTH.toPx()
            val stripLeft = size.width - STRIP_MARGIN.toPx() - stripWidth
            drawRoundRect(
                color = Color.White.copy(alpha = 0.07f),
                topLeft = Offset(stripLeft, 0f),
                size = androidx.compose.ui.geometry.Size(stripWidth, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(stripWidth / 2f),
            )
            val slot = size.height / INDEX_LETTERS.size
            val tint = cover.tint
            INDEX_LETTERS.forEachIndexed { index, letter ->
                val isCurrent = index == activeIndex || (activeIndex == null && letter == current)
                val glyph = if (isCurrent) currentGlyphs[index] else glyphs[index]
                drawText(
                    textLayoutResult = glyph,
                    color = when {
                        isCurrent -> cover.accent
                        letter in available -> tint.copy(alpha = 0.90f)
                        else -> tint.copy(alpha = 0.36f)
                    },
                    topLeft = Offset(
                        x = stripLeft + (stripWidth - glyph.size.width) / 2f,
                        y = slot * index + (slot - glyph.size.height) / 2f,
                    ),
                )
            }
        }

        // The magnified letter, parked beside whichever row the finger is on rather than centred
        // on the screen: it belongs to the strip, and level with the finger it can be read
        // without the hand being in the way.
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

/** 13 dp wide and 253 tall, 10 dp in from the screen's edge. */
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

/**
 * Appears once a list is more than half scrolled and takes it back to the top.
 *
 * Past halfway is the point where flicking back is a chore rather than a gesture — before it,
 * a button would be in the way of the content it is meant to help with.
 */
@Composable
fun ScrollToTopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AuraButton(
        onClick = onClick,
        modifier = modifier,
        minHeight = 36.dp,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(
            text = "↑  Haut de la liste",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}
