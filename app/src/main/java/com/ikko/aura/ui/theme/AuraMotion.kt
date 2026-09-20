package com.ikko.aura.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

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
     * The full player is presented as a sheet: it rises from the bottom edge and leaves the same
     * way, so the direction of travel says what it is — something that covers the current screen
     * rather than a step further into it. Whatever is underneath deliberately doesn't move
     * ([EnterTransition.None] / [ExitTransition.None] on the other side), which is what sells the
     * player as sliding *over* it.
     */
    val sheetEnter: EnterTransition =
        slideInVertically(
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) { height -> height } + fadeIn(tween(SHEET_MS / 2))

    /**
     * What the screen *underneath* does while the player covers it. It has to actually leave:
     * screens are transparent over one shared backdrop, so a screen that merely stops animating
     * stays fully painted and shows through the player. Quick, so the two barely overlap.
     */
    val sheetUnderExit: ExitTransition = fadeOut(tween(SHEET_MS / 2))
    val sheetUnderEnter: EnterTransition = fadeIn(tween(SHEET_MS / 2))

    val sheetExit: ExitTransition =
        slideOutVertically(tween(SHEET_MS, easing = FastOutSlowInEasing)) { height -> height } +
            fadeOut(tween(SHEET_MS))

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
