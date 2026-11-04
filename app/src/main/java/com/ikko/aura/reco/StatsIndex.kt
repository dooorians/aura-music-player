package com.ikko.aura.reco

/**
 * The stats of every song, looked up by the song's id *or* by what it is: a file and its stream, one recording under two ids, share
 * what the listener did with either. Pure, so it is checked without a device.
 *
 * [canonOf] gives a canonical key to ids whose row predates the key being stored.
 */
class StatsIndex(rows: Collection<TrackStatsEntity>, private val canonOf: Map<String, String> = emptyMap()) {
    private val byId: Map<String, TrackStatsEntity> = rows.associateBy { it.trackId }
    private val byCanon: Map<String, List<TrackStatsEntity>> =
        rows.groupBy { it.canonicalKey.ifEmpty { canonOf[it.trackId].orEmpty() } }.filterKeys { it.isNotEmpty() }

    /** The merged stats of [trackId] and of every other id that has [canon] (the id's own canonical key by default); null when nothing is known. */
    fun of(trackId: String, canon: String? = null): TrackStatsEntity? {
        val own = byId[trackId]
        // The key given, else the one the id's own row carries, else the library's.
        val key = canon ?: own?.canonicalKey?.takeIf { it.isNotEmpty() } ?: canonOf[trackId]
        val twins = key?.takeIf { it.isNotEmpty() }?.let { byCanon[it] }.orEmpty()
        val all = (listOfNotNull(own) + twins).distinctBy { it.trackId }
        return when (all.size) {
            0 -> null
            1 -> all[0]
            else -> merge(own ?: all[0], all)
        }
    }

    companion object {
        /** One row from several: counts add up, a like or a dislike on any counts, scores are brought to the latest listen and added. */
        fun merge(primary: TrackStatsEntity, rows: List<TrackStatsEntity>): TrackStatsEntity {
            val latest = rows.maxByOrNull { it.lastPlayedTimestamp } ?: primary
            val last = latest.lastPlayedTimestamp
            return primary.copy(
                playCount = rows.sumOf { it.playCount },
                completedCount = rows.sumOf { it.completedCount },
                earlySkipCount = rows.sumOf { it.earlySkipCount },
                manualStartCount = rows.sumOf { it.manualStartCount },
                replayCount = rows.sumOf { it.replayCount },
                playsMorning = rows.sumOf { it.playsMorning },
                playsAfternoon = rows.sumOf { it.playsAfternoon },
                playsEvening = rows.sumOf { it.playsEvening },
                playsNight = rows.sumOf { it.playsNight },
                playsWeekday = rows.sumOf { it.playsWeekday },
                playsWeekend = rows.sumOf { it.playsWeekend },
                consecutiveSkips = latest.consecutiveSkips,
                isLiked = rows.any { it.isLiked },
                // An explicit "no" on any version is a "no" to the song.
                isDisliked = rows.any { it.isDisliked },
                affinityScore = rows.sumOf { AffinityDecay.of(it.affinityScore, it.lastPlayedTimestamp, last) },
                lastPlayedTimestamp = last,
                addedTimestamp = rows.map { it.addedTimestamp }.filter { it > 0 }.minOrNull() ?: 0L,
            )
        }
    }
}
