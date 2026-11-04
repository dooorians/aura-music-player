package com.ikko.aura.reco

import java.time.Instant
import java.time.ZoneId

/** What happened to a song when it was left. */
data class ListenEvent(
    val playedMs: Long,
    val durationMs: Long,
    val transition: Transition,
    /** The listener started it (a tap), as opposed to the queue reaching it. */
    val manualStart: Boolean,
    val atMs: Long,
)

/** The bands of the day plays are counted in. */
enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT;
    companion object {
        /** 05:00-11:59 morning, 12:00-17:59 afternoon, 18:00-22:59 evening, 23:00-04:59 night. */
        fun of(hour: Int) = when (hour) { in 5..11 -> MORNING; in 12..17 -> AFTERNOON; in 18..22 -> EVENING; else -> NIGHT }
        fun at(ms: Long, zone: ZoneId = ZoneId.systemDefault()) = of(Instant.ofEpochMilli(ms).atZone(zone).hour)
    }
}

/** The scale of [RecommendationConfig] applied to one listen. Pure. */
object AffinityScorer {

    class Result(val stats: TrackStatsEntity, val delta: Double)

    fun apply(before: TrackStatsEntity, e: ListenEvent, zone: ZoneId = ZoneId.systemDefault()): Result {
        val fraction = if (e.durationMs > 0) (e.playedMs.toDouble() / e.durationMs).coerceIn(0.0, 1.0) else 0.0
        val skipped = e.transition == Transition.USER_SKIP || e.transition == Transition.UNKNOWN
        var s = before
        var delta = 0.0
        var counted = true
        when {
            // Heard to the end, whatever the way out (or repeated).
            e.transition == Transition.REPEAT -> {
                delta += RecommendationConfig.REPLAY
                s = s.copy(replayCount = s.replayCount + 1, completedCount = s.completedCount + 1, consecutiveSkips = 0)
            }
            fraction >= RecommendationConfig.COMPLETED_FRACTION -> {
                delta += RecommendationConfig.COMPLETED
                s = s.copy(completedCount = s.completedCount + 1, consecutiveSkips = 0)
            }
            e.transition == Transition.QUEUE_REPLACED -> {
                // Put aside for another queue: only what was really heard counts, nothing counts against it.
                if (fraction >= RecommendationConfig.PARTIAL_FRACTION) delta += RecommendationConfig.PARTIAL else counted = e.playedMs >= 1_500L
            }
            fraction >= RecommendationConfig.PARTIAL_FRACTION -> delta += RecommendationConfig.PARTIAL
            skipped && e.playedMs < RecommendationConfig.INSTANT_SKIP_MS -> {
                delta += RecommendationConfig.INSTANT_SKIP
                s = s.copy(earlySkipCount = s.earlySkipCount + 1, consecutiveSkips = s.consecutiveSkips + 1)
            }
            skipped && e.playedMs < RecommendationConfig.QUICK_SKIP_MS -> {
                delta += RecommendationConfig.QUICK_SKIP
                s = s.copy(earlySkipCount = s.earlySkipCount + 1, consecutiveSkips = s.consecutiveSkips + 1)
            }
            else -> Unit
        }
        if (e.manualStart && delta >= 0.0 || e.manualStart && fraction >= RecommendationConfig.PARTIAL_FRACTION) {
            delta += RecommendationConfig.MANUAL_START
            s = s.copy(manualStartCount = s.manualStartCount + 1)
        }
        if (counted) {
            s = s.copy(playCount = s.playCount + 1, lastPlayedTimestamp = e.atMs)
            s = when (DayPart.at(e.atMs, zone)) {
                DayPart.MORNING -> s.copy(playsMorning = s.playsMorning + 1)
                DayPart.AFTERNOON -> s.copy(playsAfternoon = s.playsAfternoon + 1)
                DayPart.EVENING -> s.copy(playsEvening = s.playsEvening + 1)
                DayPart.NIGHT -> s.copy(playsNight = s.playsNight + 1)
            }
            val day = java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).dayOfWeek
            val weekend = day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY
            s = if (weekend) s.copy(playsWeekend = s.playsWeekend + 1) else s.copy(playsWeekday = s.playsWeekday + 1)
        }
        return Result(s.copy(affinityScore = s.affinityScore + delta), delta)
    }

    /** A like: a fixed bonus, and the song is no longer a dislike. */
    fun liked(before: TrackStatsEntity) = Result(
        before.copy(isLiked = true, isDisliked = false, affinityScore = before.affinityScore + RecommendationConfig.LIKE, consecutiveSkips = 0),
        RecommendationConfig.LIKE,
    )

    fun unliked(before: TrackStatsEntity) = Result(
        before.copy(isLiked = false, affinityScore = before.affinityScore - RecommendationConfig.LIKE),
        -RecommendationConfig.LIKE,
    )

    /** "Je n'aime pas": out of the proposals, and what the song had earned is taken back. */
    fun disliked(before: TrackStatsEntity) = Result(
        before.copy(isDisliked = true, isLiked = false, affinityScore = minOf(before.affinityScore, 0.0) + RecommendationConfig.INSTANT_SKIP),
        RecommendationConfig.INSTANT_SKIP,
    )
}
