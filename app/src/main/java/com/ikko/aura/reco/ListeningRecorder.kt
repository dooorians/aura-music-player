package com.ikko.aura.reco

import com.ikko.aura.data.model.Track
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Writes what was heard: one row per song left, with how much of it was played and how it was left. Kept on the
 * device; a failed write is dropped, never a reason for the player to stutter.
 */
@Singleton
class ListeningRecorder @Inject constructor(private val dao: RecoDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val written = AtomicInteger()

    /** [context] is the [RecoKind.key] of the playlist the song was played from, if it was. */
    fun songLeft(track: Track, playedMs: Long, durationMs: Long, transition: Transition, context: String?) {
        // A glance of a second or two is not a listen nor much of a refusal.
        if (playedMs < 1_500L && transition != Transition.USER_SKIP) return
        val outcome = ListenClassifier.classify(playedMs, if (durationMs > 0) durationMs else track.durationMs, transition)
        scope.launch {
            runCatching {
                dao.insertListen(
                    ListenEntity(
                        trackId = track.id, title = track.title, artist = track.artist, album = track.album, year = track.year,
                        durationMs = if (durationMs > 0) durationMs else track.durationMs, playedMs = playedMs,
                        outcome = outcome.name, endedAtMs = System.currentTimeMillis(), context = context, extra = "",
                    ),
                )
                if (written.incrementAndGet() % PRUNE_EVERY == 0) dao.pruneListens(KEEP)
            }
        }
    }

    private companion object {
        const val PRUNE_EVERY = 200
        const val KEEP = 6_000
    }
}
