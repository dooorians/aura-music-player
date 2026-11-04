package com.ikko.aura.reco

import com.ikko.aura.player.smart.AnalysisReader
import com.ikko.aura.player.smart.TrackAnalysis
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Automix measured of a song (tempo, mean energy), as the recommendations need it: the link between the analysis files and the
 * session's rhythm ([SessionContext.coherencePenalty]). A song with no usable analysis answers nothing, and is not asked again.
 */
@Singleton
class AcousticBridge @Inject constructor(private val reader: AnalysisReader, private val meta: TrackMetaDao) {
    /** Song id → what it sounds like; [NONE] for a song looked up and found without an analysis. */
    private val known = ConcurrentHashMap<String, Acoustic>()

    suspend fun of(id: String): Acoustic? = of(listOf(id))[id]

    suspend fun of(ids: Collection<String>): Map<String, Acoustic> {
        val missing = ids.filter { !known.containsKey(it) }
        if (missing.isNotEmpty()) {
            val found = reader.analysesFor(missing)
            // A file with no analysis yet may still carry its tempo in its tags.
            val tagged = runCatching { meta.allTags() }.getOrDefault(emptyList()).filter { it.bpm > 0.0 }.associate { it.trackId to it.bpm }
            missing.forEach { id -> known[id] = found[id]?.let(::summarize) ?: tagged[id]?.let { Acoustic(it, 0.0) } ?: NONE }
        }
        return ids.mapNotNull { id -> known[id]?.takeIf { it !== NONE }?.let { id to it } }.toMap()
    }

    companion object {
        private val NONE = Acoustic(0.0, 0.0)

        /** Null when the analysis has no tempo and no energy to give. */
        fun summarize(a: TrackAnalysis): Acoustic? {
            if (a.status.isNotBlank() && a.status != TrackAnalysis.STATUS_READY) return null
            val energy = a.energyCurve.map { it.energy }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            return if (a.bpm <= 0.0 && energy <= 0.0) null else Acoustic(a.bpm, energy)
        }
    }
}
