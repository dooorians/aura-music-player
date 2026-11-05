package com.ikko.aura.data.home

import com.ikko.aura.reco.ArtistNames
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNewRowsTest {
    private val day = 86_400_000L
    private val now = 1_000L * day
    private val zone = ZoneId.of("UTC")

    private fun album(title: String, artist: String) = SectionAlbum(title, artist, ref = "r:$title:$artist", artworkUrl = "https://a/$title")

    // --- En boucle: what is looped now ---

    private fun listen(title: String, daysAgo: Double, outcome: String = "COMPLETED") =
        ListenRow("t:$title:$daysAgo", title, "Artist $title", title, 0, outcome, now - (daysAgo * day).toLong())

    private fun place(r: ListenRow) = album(r.album, r.artist)

    @Test fun `an album taken up this week passes one played more a month ago`() {
        val rows = (1..5).map { listen("Old", 19.0 + it * 0.1) } + (1..3).map { listen("New", it * 0.5) }
        val order = ListeningAlbums.looped(rows, now, zone, ::place).map { it.album.title }
        assertEquals(listOf("New", "Old"), order)
    }

    @Test fun `what was played once, long ago or skipped is not looped`() {
        val rows = listOf(listen("Once", 1.0)) + (1..4).map { listen("Ancient", 40.0 + it) } + (1..4).map { listen("Skipped", it.toDouble(), "SKIPPED") }
        assertTrue(ListeningAlbums.looped(rows, now, zone, ::place).isEmpty())
    }

    @Test fun `the plain count still ranks when the listens are as recent as each other`() {
        val rows = (1..4).map { listen("More", 2.0) } + (1..2).map { listen("Less", 2.0) }
        assertEquals(listOf("More", "Less"), ListeningAlbums.looped(rows, now, zone, ::place).map { it.album.title })
    }

    private fun row(id: String, vararg a: SectionAlbum, spare: List<SectionAlbum> = emptyList()) = HomeSection(id, null, id, a.toList(), spare = spare)

    @Test fun `the held loop is brought up to date without repeating an album shown in another row`() {
        val a = (1..9).map { album("A$it", "x$it") }
        val held = listOf(row("new", a[0], a[1], a[2]), row("loop", a[3], a[4], a[5]))
        val live = row("loop", a[0], a[6], a[7], spare = listOf(a[8]))
        val out = SectionHold.withLoop(held, live)
        assertEquals(listOf("new", "loop"), out.map { it.id })
        // a[0] is shown in "new": it is left out, and the reserve completes the line.
        assertEquals(listOf("A7", "A8", "A9"), out.last().albums.map { it.title })
        assertEquals(held[0], out[0])
    }

    @Test fun `a loop that cannot make a full line leaves the held one alone`() {
        val a = (1..6).map { album("A$it", "x$it") }
        val held = listOf(row("new", a[0], a[1], a[2]), row("loop", a[3], a[4], a[5]))
        assertEquals(held, SectionHold.withLoop(held, row("loop", a[0], a[1], a[6 - 1])))
        assertEquals(held, SectionHold.withLoop(held, null))
    }

    // --- the new rows ---

    private fun ctx(seed: Long, external: List<SectionAlbum>, tags: Map<String, Set<String>> = emptyMap(), known: Set<String> = emptySet(), slow: List<SectionAlbum> = emptyList()) =
        RotationContext(
            base = emptyList(), loop = emptyList(), listens = emptyList(), external = external, artistTags = tags, recoItems = emptyMap(),
            nowMs = now, seed = seed, zone = zone, knownArtists = known, slowAlbums = slow,
        )

    private fun seen(build: (Long) -> RotationContext) = (1L..60L).flatMap { HomeRotation.arrange(build(it)) }

    @Test fun `a scene shows the artists whose tags say where they come from, wherever in their tags it is said`() {
        val belgians = (1..4).map { "Be$it" }
        val others = (1..4).map { "Us$it" }
        val albums = (belgians + others).mapIndexed { i, a -> album("Disc $i", a) }
        val tags = belgians.associate { ArtistNames.key(it) to setOf("indie", "pop", "dream pop", "alternative", "belgian") } +
            others.associate { ArtistNames.key(it) to setOf("indie", "pop") }
        val rows = seen { ctx(it, albums, tags) }.filter { it.id == "scene:be" }
        assertTrue("no Belgian scene in 60 draws", rows.isNotEmpty())
        rows.forEach { r ->
            assertEquals("Scène belge", r.title)
            assertTrue(r.albums.all { it.artist in belgians })
        }
    }

    @Test fun `no scene without enough artists from that country`() {
        val albums = (1..4).map { album("Disc $it", "Be$it") }
        val tags = mapOf(ArtistNames.key("Be1") to setOf("belgian"), ArtistNames.key("Be2") to setOf("belgian"))
        assertTrue(seen { ctx(it, albums, tags) }.none { it.id.startsWith("scene:") })
    }

    @Test fun `the Japanese scene never takes K-pop`() {
        val albums = (1..3).map { album("J$it", "Jp$it") } + (1..3).map { album("K$it", "Kr$it") }
        val tags = (1..3).associate { ArtistNames.key("Jp$it") to setOf("japanese") } + (1..3).associate { ArtistNames.key("Kr$it") to setOf("japanese", "k-pop") }
        seen { ctx(it, albums, tags) }.filter { it.id == "scene:jp" }.forEach { r -> assertTrue(r.albums.all { it.artist.startsWith("Jp") }) }
    }

    @Test fun `concerts are told by their titles, and a song called Live Forever is not one`() {
        listOf("Live at Wembley", "MTV Unplugged in New York", "Nirvana (Live)", "Alive 2007 - Live", "Olympia en concert", "Show Live", "Live!").forEach { assertTrue(it, HomeRotation.isLiveAlbum(it)) }
        listOf("Live Forever", "Alive", "Olive Tree", "Delivery", "Liverpool Nights").forEach { assertFalse(it, HomeRotation.isLiveAlbum(it)) }
    }

    @Test fun `En concert holds the live albums of the artists the listener knows`() {
        val known = setOf(ArtistNames.key("Known One"), ArtistNames.key("Known Two"), ArtistNames.key("Known Three"))
        val albums = listOf(
            album("Live at Wembley", "Known One"), album("Unplugged", "Known Two"), album("Show (Live)", "Known Three"),
            album("Live at Stranger", "Stranger"), album("Studio", "Known One"),
        )
        val rows = seen { ctx(it, albums, known = known) }.filter { it.id == "live" }
        assertTrue(rows.isNotEmpty())
        rows.forEach { r ->
            assertEquals("En concert", r.title)
            assertEquals(setOf("Live at Wembley", "Unplugged", "Show (Live)"), r.albums.map { it.title }.toSet())
        }
    }

    @Test fun `Au ralenti is made from the slow albums of the library, and not drawn with fewer than a line`() {
        val slow = (1..4).map { album("Slow $it", "Library $it") }
        val rows = seen { ctx(it, emptyList(), slow = slow) }.filter { it.id == "tempo:slow" }
        assertTrue(rows.isNotEmpty())
        rows.forEach { r -> assertEquals("Au ralenti", r.title); assertTrue(r.albums.all { it.title.startsWith("Slow") }) }
        assertTrue(seen { ctx(it, emptyList(), slow = slow.take(2)) }.none { it.id == "tempo:slow" })
    }

    // --- a row holds what its title says ---

    private fun ctxWith(seed: Long, external: List<SectionAlbum>, tags: Map<String, Set<String>>, charts: Map<Int, List<SectionAlbum>> = emptyMap()) =
        RotationContext(
            base = emptyList(), loop = emptyList(), listens = emptyList(), external = external, artistTags = tags, recoItems = emptyMap(),
            nowMs = now, seed = seed, zone = zone, chartAlbums = charts,
        )

    private fun rockRows(external: List<SectionAlbum>, tags: Map<String, Set<String>>, charts: Map<Int, List<SectionAlbum>> = emptyMap()) =
        (1L..80L).flatMap { HomeRotation.arrange(ctxWith(it, external, tags, charts)) }.filter { it.id == "tag:rock" }

    private val rockers = (1..5).map { "Rocker$it" }
    private fun rockTags() = rockers.associate { ArtistNames.key(it) to linkedSetOf("rock", "alternative rock", "indie") }

    @Test fun `a pop star whose tags only end in rock is not in the rock row`() {
        val katy = "Katy Perry"
        val external = (rockers + katy).mapIndexed { i, a -> album("Disc $i", a) }
        // Last.fm lists her pop first; "rock" comes fifth.
        val tags = rockTags() + (ArtistNames.key(katy) to linkedSetOf("pop", "female vocalists", "dance", "pop rock", "rock"))
        val rows = rockRows(external, tags)
        assertTrue("no rock row in 80 draws", rows.isNotEmpty())
        rows.forEach { r -> assertTrue(r.albums.none { it.artist == katy }) }
    }

    @Test fun `an artist with no tags known is left out of a row of a style rather than put in it`() {
        val external = (rockers + "Mystery").mapIndexed { i, a -> album("Disc $i", a) }
        val rows = rockRows(external, rockTags())
        assertTrue(rows.isNotEmpty())
        rows.forEach { r -> assertTrue(r.albums.none { it.artist == "Mystery" }) }
    }

    @Test fun `the genre's chart only completes a row with artists whose own tags agree`() {
        // Too few tagged rock artists for a row: the chart makes up the rest, and only the ones that are rock.
        val tags = (1..2).associate { ArtistNames.key("Rocker$it") to linkedSetOf("rock") } +
            (ArtistNames.key("ChartRock") to linkedSetOf("hard rock", "rock")) +
            (ArtistNames.key("ChartPop") to linkedSetOf("pop", "dance", "female vocalists", "rock"))
        val external = (1..2).map { album("Disc $it", "Rocker$it") }
        val chart = mapOf(152 to listOf(album("Chart A", "ChartPop"), album("Chart B", "ChartRock"), album("Chart C", "NoTags")))
        val rows = rockRows(external, tags, chart)
        assertTrue("the chart's rock artist completes the row", rows.any { r -> r.albums.any { it.artist == "ChartRock" } })
        rows.forEach { r -> assertTrue(r.albums.none { it.artist == "ChartPop" || it.artist == "NoTags" }) }
    }

    @Test fun `the family of a name is the one its words say, and a pop rock record is pop`() {
        assertEquals("pop", com.ikko.aura.reco.GenreNormalizer.family("pop rock"))
        assertEquals("rock", com.ikko.aura.reco.GenreNormalizer.family("alternative rock"))
        assertEquals("rock", com.ikko.aura.reco.GenreNormalizer.family("indie"))
        assertEquals("hip-hop", com.ikko.aura.reco.GenreNormalizer.family("french rap").let { if (it == "chanson") "hip-hop" else it })
    }

    @Test fun `an artist is in one scene, the country their tags name first, never two`() {
        val brits = (1..3).map { "Uk$it" }
        val blood = "Blood Orange"
        val albums = (brits + blood).mapIndexed { i, a -> album("Disc $i", a) } + (1..3).map { album("J$it", "Jp$it") }
        // Blood Orange: British by Last.fm, and a Japanese namesake's country mixed in last.
        val tags = brits.associate { ArtistNames.key(it) to linkedSetOf("indie", "british") } +
            (ArtistNames.key(blood) to linkedSetOf("chillwave", "indie", "rnb", "british", "japanese")) +
            (1..3).associate { ArtistNames.key("Jp$it") to linkedSetOf("indie", "japanese") }
        val rows = seen { ctx(it, albums, tags) }
        assertTrue(rows.filter { it.id == "scene:jp" }.all { r -> r.albums.none { it.artist == blood } })
        assertTrue("Blood Orange is in the British scene", rows.filter { it.id == "scene:uk" }.any { r -> r.albums.any { it.artist == blood } })
    }

    @Test fun `a country named far down the tags does not make a scene`() {
        val albums = (1..4).map { album("Disc $it", "Odd$it") }
        val tags = (1..4).associate { ArtistNames.key("Odd$it") to linkedSetOf("a", "b", "c", "d", "e", "f", "g", "h", "japanese") }
        assertTrue(seen { ctx(it, albums, tags) }.none { it.id == "scene:jp" })
    }
}
