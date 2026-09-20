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
    onLetterPicked: (Char) -> Unit,
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

    // Drawn rather than composed. Twenty-seven Text nodes laid out with SpaceEvenly cannot be
    // made smaller than their line height, so on a short list area the strip simply overflowed
    // and the last letters fell off the bottom — the one thing an index must never do. Measured
    // once and drawn into slots computed from the height actually available, it always fits,
    // and it costs one node instead of twenty-seven.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Sized from the height the strip actually got, not from a constant. The list area shrinks
    // when the player pill appears, and letters measured for the taller version then overlapped
    // each other — an index you cannot read is not an index.
    val letterSize = remember(stripHeightPx, density) {
        val slotDp = with(density) { (stripHeightPx.toFloat() / INDEX_LETTERS.size).toDp() }
        (slotDp.value * LETTER_HEIGHT_FRACTION).coerceIn(MIN_LETTER_SP, MAX_LETTER_SP).sp
    }
    val glyphs = remember(measurer, letterSize) {
        INDEX_LETTERS.map {
            measurer.measure(
                AnnotatedString(it.toString()),
                TextStyle(fontSize = letterSize, fontWeight = FontWeight.Medium),
            )
        }
    }

    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
        Canvas(
            modifier = Modifier
                .width(STRIP_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(AuraColors.GlassFill)
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
            val slot = size.height / INDEX_LETTERS.size
            glyphs.forEachIndexed { index, glyph ->
                val letter = INDEX_LETTERS[index]
                drawText(
                    textLayoutResult = glyph,
                    color = when {
                        index == activeIndex -> AuraColors.TextPrimary
                        letter in available -> AuraColors.TextSecondary
                        else -> AuraColors.TextTertiary.copy(alpha = 0.35f)
                    },
                    topLeft = Offset(
                        x = (size.width - glyph.size.width) / 2f,
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
                            x = -(STRIP_WIDTH + BUBBLE_GAP).roundToPx(),
                            y = (slotHeight * (shown + 0.5f) - stripHeightPx / 2f).roundToInt(),
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
                    color = AuraColors.TextPrimary,
                )
            }
        }
    }
}

private val STRIP_WIDTH = 16.dp
private val BUBBLE_SIZE = 62.dp
private val BUBBLE_GAP = 10.dp
/** Share of a slot a letter fills, leaving enough air that neighbours never touch. */
private const val LETTER_HEIGHT_FRACTION = 0.72f
private const val MIN_LETTER_SP = 5f
private const val MAX_LETTER_SP = 9f

/**
 * Appears once a list is more than half scrolled and takes it back to the top.
 *
 * Past halfway is the point where flicking back is a chore rather than a gesture — before it,
 * a button would be in the way of the content it is meant to help with.
 */
@Composable
fun ScrollToTopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(AuraColors.PillScrim)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "↑  Haut de la liste",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = AuraColors.TextPrimary,
        )
    }
}
