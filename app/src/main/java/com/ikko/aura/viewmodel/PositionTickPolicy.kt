package com.ikko.aura.viewmodel

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How often the player's position is read into the screens' state.
 *
 * The seek bar and the lyrics need it twice a second, but only while one of them is on screen *and* the track moves. With the
 * app in the background (screen off, another app in front) nothing draws it, yet the ticker used to go on waking the main thread
 * twice a second for the whole of the listening session. Now:
 *
 * - **watched and playing**: every [WATCHED_PLAYING_MS], unconditionally;
 * - **playing, nothing watching**: every [UNWATCHED_PLAYING_MS] — the position is still wanted, coarsely, for the listening
 *   history when a song is left (and refined from the player's own report at that moment), but ten times less often;
 * - **paused**: it waits for the player to say something changed, looking in every [PAUSED_MS] while watched and only every
 *   [UNWATCHED_PAUSED_MS] when not.
 *
 * Any wait but the first is cut short by an event (play, pause, new item, a screen starting to watch).
 */
object PositionTickPolicy {
    const val WATCHED_PLAYING_MS = 500L
    const val UNWATCHED_PLAYING_MS = 5_000L
    const val PAUSED_MS = 5_000L
    const val UNWATCHED_PAUSED_MS = 60_000L

    /** [waitMs] to sleep; [wakeable] when an event may end it early. */
    data class Tick(val waitMs: Long, val wakeable: Boolean)

    fun next(isPlaying: Boolean, watched: Boolean): Tick = when {
        isPlaying && watched -> Tick(WATCHED_PLAYING_MS, wakeable = false)
        isPlaying -> Tick(UNWATCHED_PLAYING_MS, wakeable = true)
        watched -> Tick(PAUSED_MS, wakeable = true)
        else -> Tick(UNWATCHED_PAUSED_MS, wakeable = true)
    }

    /**
     * Calls [onTick], then sleeps as [next] says, for as long as the calling coroutine lives: cancelling it (the ViewModel
     * cleared) stops the loop at its next suspension, with no tick after.
     */
    suspend fun run(isPlaying: () -> Boolean, watched: () -> Boolean, wake: ReceiveChannel<Unit>, onTick: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            onTick()
            val tick = next(isPlaying(), watched())
            if (tick.wakeable) withTimeoutOrNull(tick.waitMs) { wake.receive() } else delay(tick.waitMs)
        }
    }
}
