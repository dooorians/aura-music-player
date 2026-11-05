package com.ikko.aura.reco

import com.ikko.aura.player.smart.AnalysisReader
import com.ikko.aura.player.smart.TrackAnalysis
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Automix measured of a song (tempo, mean energy), as the recommendations need it: the link between the analysis files and the
 * session's rhythm ([SessionContext.coherencePenalty]). A song with no usable analysis answers nothing, and is not asked again for
 * [RETRY_MISSING_MS]: Automix analyses songs as they are played, so what had no analysis this morning may have one tonight.
 */
@Singleton
class AcousticBridge @Inject constructor(private val reader: AnalysisReader, private val meta: TrackMetaDao) {
    /** Song id → what it sounds like; [NONE] for a song looked up and found without an analysis. */
    private val known = ConcurrentHashMap<String, Acoustic>()
    /** When a song was found without anything to give: it is asked again once [RETRY_MISSING_MS] have passed. */
    private val missedAt = ConcurrentHashMap<String, Long>()

    suspend fun of(id: String): Acoustic? = of(listOf(id))[id]

    suspend fun of(ids: Collection<String>, nowMs: Long = System.currentTimeMillis()): Map<String, Acoustic> {
        val missing = ids.filter { id ->
            val k = known[id]
            k == null || (k === NONE && nowMs - (missedAt[id] ?: 0L) >= RETRY_MISSING_MS)
        }
        if (missing.isNotEmpty()) {
            val found = reader.analysesFor(missing)
            // A file with no analysis yet may still carry its tempo in its tags.
            val tagged = runCatching { meta.allTags() }.getOrDefault(emptyList()).mapNotNull { t -> Tempo.sanitize(t.bpm)?.let { t.trackId to it } }.toMap()
            missing.forEach { id ->
                val a = found[id]?.let(::summarize) ?: tagged[id]?.let { Acoustic(it, 0.0) }
                if (a == null) { known[id] = NONE; missedAt[id] = nowMs } else { known[id] = a; missedAt.remove(id) }
            }
        }
        return ids.mapNotNull { id -> known[id]?.takeIf { it !== NONE }?.let { id to it } }.toMap()
    }

    companion object {
        private val NONE = Acoustic(0.0, 0.0)
        const val RETRY_MISSING_MS = 6L * 3_600_000L

        /** Null when the analysis has no tempo and no energy to give. A tempo or an energy that is not a number counts as none. */
        fun summarize(a: TrackAnalysis): Acoustic? {
            if (a.status.isNotBlank() && a.status != TrackAnalysis.STATUS_READY) return null
            val energy = a.energyCurve.map { it.energy }.filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()?.takeIf { it > 0.0 } ?: 0.0
            val bpm = Tempo.sanitize(a.bpm) ?: 0.0
            return if (bpm <= 0.0 && energy <= 0.0) null else Acoustic(bpm, energy)
        }
    }
}
