package com.ikko.aura.reco

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The tempo and energy of one analysed song, as the session remembers them. */
data class Acoustic(val bpm: Double, val energy: Double)

/** What the last analysed songs of the session sound like: their mean, and whether they stayed close enough to count as a mood. */
class AcousticProfile(val bpm: Double, val bpmSettled: Boolean, val energy: Double, val energySettled: Boolean)

/**
 * What the current listening session remembers, in memory only: the last songs and artists played, the genres the listener
 * keeps skipping, what was left within seconds, and what the last songs sounded like. Gone with the app's process, which is
 * what "the end of the session" is.
 */
class SessionContext {
    private val lock = Any()
    private val recentTracks = ArrayDeque<String>()
    private val recentArtists = ArrayDeque<String>()
    /** Every play in order, an artist as often as it came: what the cooldown counts (unlike [recentArtists], which folds repeats). */
    private val artistLog = ArrayDeque<String>()
    private val quickSkips = ArrayList<Set<String>>()
    private val penalized = HashSet<String>()
    private val fastSkippedTracks = HashMap<String, Long>()
    private val fastSkippedArtists = HashMap<String, Long>()
    private val acoustics = ArrayDeque<Acoustic>()

    fun recordPlay(trackId: String, artistKey: String) = synchronized(lock) {
        recentTracks.remove(trackId)
        recentTracks.addLast(trackId)
        while (recentTracks.size > RecommendationConfig.SESSION_TRACKS) recentTracks.removeFirst()
        if (artistKey.isNotEmpty() && recentArtists.lastOrNull() != artistKey) recentArtists.addLast(artistKey)
        while (recentArtists.size > RecommendationConfig.SESSION_ARTISTS) recentArtists.removeFirst()
        if (artistKey.isNotEmpty()) artistLog.addLast(artistKey)
        while (artistLog.size > RecommendationConfig.ARTIST_COOLDOWN_WINDOW) artistLog.removeFirst()
    }

    /**
     * A song of [genres] was left within [RecommendationConfig.QUICK_SKIP_MS]. When two quick skips of the session share a genre,
     * that genre is set aside until the session ends.
     */
    fun recordQuickSkip(genres: Set<String>) = synchronized(lock) {
        val specific = genres.filterNot { GenreNormalizer.isGeneric(it) }.map { it.lowercase() }.toSet()
        if (specific.isEmpty()) return@synchronized
        for (genre in specific) {
            if (quickSkips.count { genre in it } + 1 >= RecommendationConfig.MOMENTUM_SKIPS) penalized += genre
        }
        quickSkips += specific
    }

    /** [trackId] (by [artistKey]) was left within seconds: it, and a little of its artist, rest for [RecommendationConfig.FAST_SKIP_COOLDOWN_MS]. */
    fun recordFastSkip(trackId: String, artistKey: String, nowMs: Long) = synchronized(lock) {
        val until = nowMs + RecommendationConfig.FAST_SKIP_COOLDOWN_MS
        fastSkippedTracks[trackId] = until
        if (artistKey.isNotEmpty()) fastSkippedArtists[artistKey] = until
    }

    fun isCoolingDown(trackId: String, nowMs: Long): Boolean = synchronized(lock) { (fastSkippedTracks[trackId] ?: 0L) > nowMs }

    /** What an artist costs while it rests after a quick skip of one of its songs. */
    fun fastSkipPenalty(artistKey: String, nowMs: Long): Double =
        synchronized(lock) { if ((fastSkippedArtists[artistKey] ?: 0L) > nowMs) RecommendationConfig.FAST_SKIP_ARTIST_PENALTY else 0.0 }

    /**
     * What an artist costs for having been played within the last [RecommendationConfig.ARTIST_COOLDOWN_WINDOW] songs. Each play weighs
     * [RecommendationConfig.ARTIST_COOLDOWN_DECAY] ^ its age, and the cost grows exponentially with the sum: one play just now is
     * a nudge, three in a row is a wall.
     */
    fun artistCooldownPenalty(artistKey: String): Double = synchronized(lock) {
        if (artistKey.isEmpty()) return@synchronized 0.0
        var weight = 0.0
        val last = artistLog.size - 1
        for (i in artistLog.indices) if (artistLog[i] == artistKey) weight += RecommendationConfig.ARTIST_COOLDOWN_DECAY.pow(last - i)
        if (weight == 0.0) 0.0 else min(RecommendationConfig.ARTIST_COOLDOWN_MAX, RecommendationConfig.ARTIST_COOLDOWN_BASE * (2.0.pow(weight) - 1.0))
    }

    /** What the song that just ended sounded like, when it has been analysed. */
    fun recordAcoustics(bpm: Double, energy: Double) = synchronized(lock) {
        if (bpm <= 0.0 && energy <= 0.0) return@synchronized
        acoustics.addLast(Acoustic(bpm, energy))
        while (acoustics.size > RecommendationConfig.SESSION_ACOUSTICS) acoustics.removeFirst()
    }

    /** Null until enough songs have been analysed to say anything. */
    fun acousticProfile(): AcousticProfile? = synchronized(lock) {
        val c = RecommendationConfig
        val bpms = acoustics.map { it.bpm }.filter { it > 0.0 }
        val energies = acoustics.map { it.energy }.filter { it > 0.0 }
        if (bpms.size < c.COHERENCE_MIN_SAMPLES && energies.size < c.COHERENCE_MIN_SAMPLES) return@synchronized null
        val bpmMean = bpms.takeIf { it.size >= c.COHERENCE_MIN_SAMPLES }?.average() ?: 0.0
        val energyMean = energies.takeIf { it.size >= c.COHERENCE_MIN_SAMPLES }?.average() ?: 0.0
        AcousticProfile(
            bpm = bpmMean,
            bpmSettled = bpmMean > 0.0 && (bpms.max() - bpms.min()) <= c.COHERENCE_SETTLED_BPM_SPREAD,
            energy = energyMean,
            energySettled = energyMean > 0.0 && sqrt(energies.map { (it - energyMean).pow(2) }.average()) / energyMean <= c.COHERENCE_SETTLED_ENERGY_SPREAD,
        )
    }

    /**
     * What a song costs for breaking the rhythm of the session: only when the last songs agree on a tempo (or an energy) and this
     * one is far from it. Half and double time are the same pulse, so they are not a break.
     */
    fun coherencePenalty(bpm: Double?, energy: Double?): Double {
        val c = RecommendationConfig
        val p = acousticProfile() ?: return 0.0
        var penalty = 0.0
        if (p.bpmSettled && bpm != null && bpm > 0.0) {
            val gap = listOf(bpm, bpm * 2, bpm / 2).minOf { abs(it - p.bpm) }
            penalty += max(0.0, gap - c.COHERENCE_BPM_TOLERANCE) * c.COHERENCE_BPM_PENALTY_PER
        }
        if (p.energySettled && energy != null && energy > 0.0) {
            val gap = abs(energy - p.energy) / p.energy
            penalty += (gap - 0.35).coerceIn(0.0, 1.0) * c.COHERENCE_ENERGY_PENALTY
        }
        return min(c.COHERENCE_MAX, penalty)
    }

    fun recentTrackIds(): Set<String> = synchronized(lock) { recentTracks.toHashSet() }
    fun lastArtists(): Set<String> = synchronized(lock) { recentArtists.toHashSet() }
    fun penalizedGenres(): Set<String> = synchronized(lock) { penalized.toHashSet() }
}
