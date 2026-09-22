package com.ikko.aura.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * One motion vocabulary for the whole app, so a transition's shape tells you what kind of move it
 * was rather than which screen happened to implement it.
 *
 * Two categories, and only two:
 *
 * - **Peers** — anything you switch *between* without a hierarchy: the five top tabs, and the
 *   library's Playlists/Albums/Artistes/Titres filters. New content fades in while rising a
 *   little; the old one only fades. It looks identical whichever peer you pick, because there is
 *   no ordering between them to convey. A plain crossfade was tried first and read as *no*
 *   animation at all, since consecutive screens share the same blurred backdrop and only the
 *   foreground text swapped.
 *
 * - **Push** — opening something *inside* the current context (an album, the queue, the player).
 *   Horizontal slide, forward from the right and back from the left, so the gesture matches where
 *   the screen sits relative to what it covers.
 */
object AuraMotion {
    const val PEER_MS = 220
    const val PUSH_MS = 260

    /** Fraction of the container height the entering peer rises from — deliberately small. */
    private const val PEER_RISE_DIVISOR = 14

    val peerEnter: EnterTransition =
        fadeIn(tween(PEER_MS)) +
            slideInVertically(tween(PEER_MS, easing = FastOutSlowInEasing)) { it / PEER_RISE_DIVISOR }

    val peerExit: ExitTransition = fadeOut(tween(PEER_MS / 2))

    const val SHEET_MS = 320

    /**
     * The player as Apple's Music opens and closes it, measured off a recording of it frame by frame.
     *
     * Opening: the card is there at once — dark grey, opaque, top corners rounded, a shade narrower than
     * the screen — with its top edge a quarter of the way down (26.4%), and rises to the top on a
     * critically damped spring: 60% of the way in the first four frames (67 ms), all of it in about 370 ms,
     * with no overshoot. Behind it the page stays where it is and goes dim.
     *
     * Closing: the card sinks the whole way to the mini player's place — its top edge at 85.5% — on an
     * ease-in-out over 320 ms (slow off the line, fastest at the middle, slow into the end), narrowing to
     * the pill's width as it goes, and clears there as the pill takes its place.
     */
    /** Where the card's top edge starts, as a fraction of the screen's height below the top. */
    const val SHEET_START_FRACTION = 0.264f

    /** Where it ends when it closes: the mini player's top edge. */
    const val SHEET_PILL_TOP_FRACTION = 0.847f

    /** How much narrower than the screen the card is, per unit of [SHEET_START_FRACTION]: 2.4% at the start, the pill's 9.7% at the end. */
    const val SHEET_NARROWING = 0.115f

    /** The spring the card rises on, critically damped. */
    const val SHEET_STIFFNESS = 650f
    const val SHEET_EXIT_MS = 320

    /** How long the cover backdrop takes to come in behind the card (and, going, to leave). */
    const val SHEET_ARTWORK_IN_MS = 110

    /** Where the card's dark face has cleared completely, and from where the card itself starts to clear on the way down. */
    const val SHEET_FACE_CLEARS_BELOW = 0.12f
    const val SHEET_CLEAR_FROM = 0.70f

    val EaseInOut = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** How long the navigation transition holds the page under the card (and the card itself, on the way out) in place. */
    const val SHEET_HOLD_MS = 800
    const val SHEET_EXIT_HOLD_MS = 380

    /** How dim the page behind goes, and how quickly. */
    const val SHEET_DIM = 0.45f
    const val SHEET_DIM_IN_MS = 130
    const val SHEET_DIM_OUT_MS = 320

    /** The card's top corners while it is away from full screen. */
    val SheetCorner = 44.dp
    private val PillCorner = 24.dp

    /** The card's corner radius when its top edge is [offset] (a fraction of the screen's height) down. */
    fun cornerAt(offset: Float): androidx.compose.ui.unit.Dp = when {
        offset <= 0f -> 0.dp
        offset < 0.10f -> SheetCorner * (offset / 0.10f)
        offset < 0.60f -> SheetCorner
        else -> SheetCorner + (PillCorner - SheetCorner) * ((offset - 0.60f) / (SHEET_PILL_TOP_FRACTION - 0.60f)).coerceIn(0f, 1f)
    }

    // Placeholders for the navigation transition: they change nothing that can be seen, and only keep the
    // transition alive — and so the page under the card drawn — for as long as the card takes.
    //
    // A scale of 0.9999, not a fade: a fade puts the screen in a layer of its own with an alpha, and on the
    // player — six glass panels — that is a re-render of all of them when the alpha reaches 1 and the layer
    // goes away, which showed as a flicker. And a placeholder whose two ends are equal is not animated at
    // all, so a transition with nothing in it ends at once and takes the page under the card with it.
    // The player itself, coming in, has none: the page under it holds the transition open.
    val sheetEnter: EnterTransition = EnterTransition.None
    val sheetUnderExit: ExitTransition = scaleOut(tween(SHEET_HOLD_MS), targetScale = 0.9999f)
    val sheetUnderEnter: EnterTransition = EnterTransition.None
    val sheetExit: ExitTransition = scaleOut(tween(SHEET_EXIT_HOLD_MS), targetScale = 0.9999f)

    const val QUEUE_SLIDE_MS = 280

    /** Low bounciness: enough overshoot to feel alive, not enough to look like a toy. */
    private val queueSlideSpring = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Moving through the queue, shared by the pill and the full player so one swipe reads the same
     * wherever you made it. The incoming side springs — overshooting its resting position by a
     * hair and settling back — while the outgoing side leaves on a plain curve, since bouncing
     * something on its way off screen is motion nobody sees.
     */
    fun queueSlide(forward: Boolean): ContentTransform {
        val sign = if (forward) 1 else -1
        return (
            slideInHorizontally(queueSlideSpring) { width -> sign * width } +
                fadeIn(tween(QUEUE_SLIDE_MS))
            ) togetherWith (
            slideOutHorizontally(tween(QUEUE_SLIDE_MS, easing = FastOutSlowInEasing)) { width ->
                -sign * width
            } + fadeOut(tween(QUEUE_SLIDE_MS))
            )
    }
}
