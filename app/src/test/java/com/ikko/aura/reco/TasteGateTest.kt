package com.ikko.aura.reco

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteGateTest {
    private class Fake(val tags: Map<String, List<String>>, val similar: Map<String, List<String>> = emptyMap()) : ArtistInsight {
        override suspend fun tags(name: String) = tags[name]
        override suspend fun similar(name: String) = similar[name]
        override suspend fun listenerTopArtists() = emptyList<String>()
    }

    private fun profile() = TasteProfile(
        artists = mapOf("angele" to 0.9, "stromae" to 0.8), names = mapOf("angele" to "Angèle", "stromae" to "Stromae"),
        penalty = emptyMap(), contextPenalty = emptyMap(), genres = emptyMap(), decades = emptyMap(), seeds = emptyList(),
        knownArtists = setOf("angele", "stromae"), knownTracks = emptySet(), knownBases = emptySet(), evidence = 50.0,
    )

    private fun cand(artist: String, tags: List<String> = emptyList()) = Candidate(
        id = "ytm:$artist", sourceId = "ytm", sourceName = "YouTube Music", title = "t", artist = artist, album = "", durationMs = 200_000, artworkUrl = null, tags = tags,
    )

    private val insight = Fake(
        tags = mapOf(
            "Angèle" to listOf("pop", "french", "belgian"), "Stromae" to listOf("electronic", "french", "belgian"),
            "Angele" to listOf("pop", "belgian", "french"), "Zaz" to listOf("french", "chanson"),
            "Diljit Dosanjh" to listOf("punjabi", "bhangra", "indian"), "Nobody" to emptyList(),
        ),
    )

    private fun run(vararg artists: String) = runBlocking { TasteGate(insight, profile()).apply(artists.map { cand(it) }).map { it.artist } }

    @Test fun `an artist that only differs by accents from a loved one is another artist`() {
        assertEquals(listOf("Angèle"), run("Angèle", "Angele"))
    }

    @Test fun `an artist that shares tags with the taste passes, a regional one that is never played does not`() {
        assertEquals(listOf("Zaz"), run("Zaz", "Diljit Dosanjh"))
    }

    @Test fun `an artist the catalogue does not know is not removed`() {
        assertEquals(listOf("Nobody"), run("Nobody"))
    }

    @Test fun `nothing is removed when the catalogue cannot be asked`() {
        val silent = object : ArtistInsight {
            override suspend fun tags(name: String): List<String>? = null
            override suspend fun similar(name: String): List<String>? = null
            override suspend fun listenerTopArtists() = emptyList<String>()
        }
        val out = runBlocking { TasteGate(silent, profile()).apply(listOf(cand("Zaz"), cand("Diljit Dosanjh"))) }
        assertEquals(2, out.size)
    }

    @Test fun `a song listed as close to a seed stays only if Last_fm finds its artist close too`() {
        val close = Fake(
            tags = mapOf("Reol" to listOf("japanese", "electropop", "j-pop"), "Ado" to listOf("japanese", "j-pop"), "Dua Lipa" to listOf("pop", "dance")),
            similar = mapOf("Reol" to listOf("Ado"), "Stromae" to listOf("Ado"), "Angèle" to emptyList()),
        )
        val p = profile().let {
            TasteProfile(it.artists + ("reol" to 0.9), it.names + ("reol" to "Reol"), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), setOf("reol"), emptySet(), emptySet(), 50.0)
        }
        fun rel(artist: String) = cand(artist).copy(provenance = listOf(Provenance(ProvKind.RELATED_TRACK, "Reol", 0.9)))
        val out = runBlocking { TasteGate(close, p).apply(listOf(rel("Ado"), rel("Dua Lipa"))) }.map { it.artist }
        assertEquals(listOf("Ado"), out)
    }

    @Test fun `a stranger close to a single loved artist is not offered, one close to several is`() {
        val fit = Fake(
            tags = mapOf("Angèle" to listOf("pop"), "Rina Sawayama" to listOf("electropop"), "Clara Luciani" to listOf("synthpop", "electropop"), "Charli XCX" to listOf("synthpop", "electropop")),
            similar = mapOf("Angèle" to listOf("Clara Luciani", "Charli XCX"), "Rina Sawayama" to listOf("Charli XCX")),
        )
        val p = TasteProfile(
            artists = mapOf("angele" to 0.9, "rina sawayama" to 0.9), names = mapOf("angele" to "Angèle", "rina sawayama" to "Rina Sawayama"),
            penalty = emptyMap(), contextPenalty = emptyMap(), genres = emptyMap(), decades = emptyMap(), seeds = emptyList(),
            knownArtists = setOf("angele", "rina sawayama"), knownTracks = emptySet(), knownBases = emptySet(), evidence = 50.0,
        )
        fun rel(artist: String) = cand(artist).copy(provenance = listOf(Provenance(ProvKind.RELATED_TRACK, "Angèle", 0.9)))
        val out = runBlocking { TasteGate(fit, p).apply(listOf(rel("Clara Luciani"), rel("Charli XCX"))) }.map { it.artist }
        assertEquals(listOf("Charli XCX"), out)
    }

    @Test fun `an artist close to a blocked one is not offered, unless it is closer to what the listener loves`() {
        val fit = Fake(
            tags = mapOf("Angèle" to listOf("pop"), "Rina Sawayama" to listOf("electropop"), "Jenifer" to listOf("pop"), "Zaz" to listOf("pop"), "Charli XCX" to listOf("electropop"), "Patricia Kaas" to listOf("pop")),
            similar = mapOf(
                "Angèle" to listOf("Charli XCX"), "Rina Sawayama" to listOf("Charli XCX"),
                "Jenifer" to listOf("Patricia Kaas", "Zaz"), "Zaz" to emptyList(),
            ),
        )
        val p = TasteProfile(
            artists = mapOf("angele" to 0.9, "rina sawayama" to 0.9), names = mapOf("angele" to "Angèle", "rina sawayama" to "Rina Sawayama"),
            penalty = emptyMap(), contextPenalty = emptyMap(), genres = emptyMap(), decades = emptyMap(), seeds = emptyList(),
            knownArtists = setOf("angele", "rina sawayama"), knownTracks = emptySet(), knownBases = emptySet(), evidence = 50.0,
        )
        val out = runBlocking { TasteGate(fit, p, blockedNames = listOf("Jenifer")).apply(listOf(cand("Patricia Kaas"), cand("Charli XCX"), cand("Zaz"))) }.map { it.artist }
        assertEquals(listOf("Charli XCX"), out)
    }
}
