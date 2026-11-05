package com.ikko.aura.reco

/**
 * The order of a generated playlist. [Ranker.select] decides which songs go in, best first; this decides how they follow each other: the
 * [ArcShape] of the playlist ("Ça bouge !" builds, "Nuit" winds down, the mixes breathe), tempo steps a mixer can ride (half and double time
 * included), no artist twice within three songs, and discoveries anchored by songs of artists the listener knows.
 *
 * Every measure is optional: a song with no tempo or energy known is placed as an average song would be, never pushed to the end.
 */
object PlaylistFlow {

    /** Below this, the analyser's own tempo is a guess: the catalogue's is preferred, else none. */
    private const val MIN_BEAT_CONFIDENCE = 0.3

    /** The tempo a song is known by: what was heard of it with a clear beat, else what a catalogue or a model states. Null when none is usable. */
    fun bpmOf(c: Candidate, f: SongFeatures?): Double? =
        f?.probe?.takeIf { it.beatConfidence >= MIN_BEAT_CONFIDENCE }?.bpm?.let(Tempo::sanitize)
            ?: Tempo.sanitize(f?.bpm)
            ?: Tempo.sanitize(f?.feel?.tempo)
            ?: Tempo.sanitize(c.bpm)

    /**
     * 0..1, or null: how intense a song is. A model's energy is the best measure; else the loudness the app measured of the preview (-30 dBFS quiet,
     * -8 loud); else the master's loudness a catalogue states (-18 dB quiet, -5 loud). The sequencer ranks these within the playlist, so only their
     * order matters.
     */
    fun energyOf(f: SongFeatures?): Double? {
        if (f == null) return null
        f.feel?.energy?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { return it }
        f.probe?.rmsDb?.takeIf { it.isFinite() && it < 0.0 }?.let { return ((it + 30.0) / 22.0).coerceIn(0.0, 1.0) }
        f.gainDb?.takeIf { it.isFinite() }?.let { return ((it + 18.0) / 13.0).coerceIn(0.0, 1.0) }
        return null
    }

    fun weightsOf(kind: RecoKind): PlaylistSequencer.Weights = when (kind) {
        // One song per artist already: the gap matters less than not dropping the day's best to the end.
        RecoKind.DAILY -> PlaylistSequencer.Weights(artistGap = 4, relevance = 1.0)
        // What is newest and closest first: the order of a release list is part of what it says.
        RecoKind.NEW -> PlaylistSequencer.Weights(relevance = 1.4, energyArc = 0.6)
        // An ambience is its arc and its tempo.
        RecoKind.ENERGY, RecoKind.NIGHT, RecoKind.CHILL -> PlaylistSequencer.Weights(tempo = 1.3, energyArc = 1.5, relevance = 0.6)
        RecoKind.DISCOVERY -> PlaylistSequencer.Weights(maxNovelRun = 3)
        RecoKind.STYLE -> PlaylistSequencer.Weights()
    }

    /** [picks] (as [Ranker.select] gave them) in the order they are best heard. The same songs, each once. */
    fun sequence(kind: RecoKind, picks: List<Pick>, ctx: RankContext, cancel: () -> Unit = {}): List<Pick> {
        if (picks.size <= 2) return picks
        val profile = ctx.profile
        val items = picks.map { p ->
            val c = p.candidate
            val f = ctx.songFeatures[c.identity]
            val primary = c.credits.firstOrNull().orEmpty()
            SeqItem(
                item = p,
                key = c.id,
                artist = primary,
                album = if (c.album.isBlank()) "" else ArtistNames.key(c.album) + "|" + primary,
                bpm = bpmOf(c, f),
                energy = energyOf(f),
                relevance = p.score,
                familiar = c.credits.any { it in profile.knownArtists || ctx.local.hasArtist(it) },
            )
        }
        val ordered = PlaylistSequencer.order(items, ArcShape.of(kind), weightsOf(kind), cancel)
        // The sequencer keeps one of each key; a pick it dropped as a duplicate is a duplicate, anything else would be a bug: kept at the end.
        if (ordered.size == picks.size) return ordered
        val placed = ordered.mapTo(HashSet()) { it.candidate.id }
        return ordered + picks.filter { it.candidate.id !in placed }.distinctBy { it.candidate.id }
    }
}
