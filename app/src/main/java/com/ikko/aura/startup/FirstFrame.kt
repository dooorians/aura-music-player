package com.ikko.aura.startup

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewTreeObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens once the first frame of the UI is on screen. Work the first screen does not need — warming the sources'
 * connections, binding to the playback service, the recommendations' renewal, resuming downloads — waits for it, so it
 * takes neither the main thread nor the CPU from the frame the user is waiting for.
 *
 * A process started without the UI (the playback service woken by a media button or a car) has no frame to wait for:
 * every wait gives up after its fallback and the work runs anyway.
 */
object FirstFrame {
    private val drawn = CompletableDeferred<Unit>()

    /** How long work waits for a frame that may never come (no activity in this process). */
    const val FALLBACK_MS = 3_000L

    /**
     * Watches [activity]'s window for its first draw. The listener only notes it: the gate opens from a message posted
     * at the front of the queue, i.e. right after that frame, never inside it.
     */
    fun watch(activity: Activity) {
        if (drawn.isCompleted) return
        val decor = activity.window.decorView
        val main = Handler(Looper.getMainLooper())
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var seen = false
            override fun onDraw() {
                if (seen) return
                seen = true
                main.postAtFrontOfQueue {
                    drawn.complete(Unit)
                    // Not from onDraw itself: a listener may not be removed while the observer dispatches.
                    decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                }
            }
        })
    }

    /** Suspends until the first frame is drawn, or [fallbackMs] has passed. */
    suspend fun await(fallbackMs: Long = FALLBACK_MS) {
        if (drawn.isCompleted) return
        withTimeoutOrNull(fallbackMs) { drawn.await() }
    }

    /** Runs [block] on the main thread once the first frame is drawn (or the fallback has passed). */
    fun runOnMainAfter(fallbackMs: Long = FALLBACK_MS, block: () -> Unit) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            await(fallbackMs)
            block()
        }
    }
}

/**
 * Runs blocking [block] at background priority on the calling thread, then puts the thread back as it was: for
 * warm-up work that must not compete with the UI's threads, run on a pooled thread that other work reuses after.
 */
inline fun <T> atBackgroundPriority(block: () -> T): T {
    val tid = Process.myTid()
    val before = Process.getThreadPriority(tid)
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    try {
        return block()
    } finally {
        Process.setThreadPriority(before)
    }
}
