package com.ikko.aura.reco

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal val ZONE: ZoneId = ZoneOffset.UTC
internal const val DAY = 86_400_000L

/** 2026-10-10 12:00 UTC. */
internal val NOW: Long = LocalDate.of(2026, 10, 10).atStartOfDay(ZONE).toInstant().toEpochMilli() + 12 * 3_600_000L

internal fun candidate(
    title: String,
    artist: String,
    id: String = "ytm:" + (title + artist).hashCode(),
    source: String = "ytm",
    durationMs: Long = 200_000,
    album: String = "",
    release: Release = Release.Unknown,
    tags: List<String> = emptyList(),
    extra: List<String> = emptyList(),
    provenance: List<Provenance> = listOf(Provenance(ProvKind.ARTIST, artist, 0.8)),
    playability: Playability = Playability.UNVERIFIED,
) = Candidate(
    id = id, sourceId = source, sourceName = source, title = title, artist = artist, album = album, durationMs = durationMs,
    artworkUrl = null, extraCredits = extra, release = release, tags = tags, provenance = provenance, playability = playability,
)

internal fun listen(
    title: String,
    artist: String,
    outcome: Outcome = Outcome.COMPLETED,
    ageDays: Int = 1,
    context: String? = null,
    durationMs: Long = 200_000,
    playedMs: Long = if (outcome == Outcome.COMPLETED) 200_000 else 20_000,
    id: String = "$artist:$title",
) = ListenRecord(id, title, artist, "", 0, durationMs, playedMs, outcome, NOW - ageDays * DAY, context)

internal fun profileOf(vararg listens: ListenRecord, library: List<LibraryEntry> = emptyList(), seeds: List<String> = emptyList()): TasteProfile =
    TasteProfileBuilder.build(ProfileInputs(listens = listens.toList(), library = library, seedArtists = seeds, nowMs = NOW))

internal fun ctx(
    profile: TasteProfile,
    local: List<TrackIdentity.Entry> = emptyList(),
    blocked: Set<String> = emptySet(),
    hidden: Set<String> = emptySet(),
    history: Map<String, Long> = emptyMap(),
    variation: Long = 1,
) = RankContext(profile, TrackIdentity.Index(local), blocked, hidden, history, NOW, ZONE, variation)
