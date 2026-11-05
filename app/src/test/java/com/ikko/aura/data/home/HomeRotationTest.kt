package com.ikko.aura.data.home

import com.ikko.aura.data.model.Album
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.Reason
import com.ikko.aura.reco.ReasonCode
import com.ikko.aura.reco.RecoItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRotationTest {
    private val now = 2_000L * 86_400_000L
    private val styles = listOf(
        "k-pop", "hyperpop", "french pop", "indie pop", "latin", "ballroom", "bedroom pop", "french hip hop", "synthpop", "reggaeton",
    )

    private fun local(i: Int) = SectionAlbum("Local $i", "Mine $i", album = Album(i.toLong(), "Local $i", "Mine $i", 0, null, 10, 2010 + i % 10))

    /** 40 albums of the sources, by 20 artists each tagged with a style. */
    private fun external() = (1..40).map { SectionAlbum("Online $it", "Artist ${it - 1}", ref = "ref$it", artworkUrl = "https://x/$it") }

    /** The listening: ten albums of the device and ten of the sources, with plays at every hour and old and recent ones. */
    private fun listens(): List<AlbumListen> = (1..20).map { i ->
        val album = if (i % 2 == 0) local(i) else SectionAlbum("Stream $i", "Singer $i", ref = "s$i", artworkUrl = "https://s/$i")
        AlbumListen(album, count = 30 - i, lastAtMs = now - (i % 4) * 50L * 86_400_000L, parts = intArrayOf(1, 1, 1, 1), year = 2010 + (i % 2) * 10)
    }

    private fun recoItem(id: String, artist: String, code: ReasonCode = ReasonCode.LISTENED_ARTIST, subject: String = artist) = RecoItem(
        trackId = "ytm:$id", title = "Song $id", artist = artist, album = "Album $id", durationMs = 180_000L,
        artworkUrl = "https://img/$id", albumRef = "alb_$id", sourceId = "ytm", sourceName = "YTM",
        reason = Reason(code, subject), verified = true,
    )

    private fun recoItems(): Map<String, List<RecoItem>> = mapOf(
        "chill" to (1..4).map { recoItem("c$it", "SeedA", ReasonCode.CALM, "SeedA") },
        "night" to (1..4).map { recoItem("n$it", "SeedB", ReasonCode.NIGHT, "SeedB") },
        "energy" to (1..4).map { recoItem("e$it", "SeedC", ReasonCode.ENERGETIC, "SeedC") },
    )

    private fun ctx(
        seed: Long,
        hourMs: Long = 20L * 3_600_000L,
        tags: Boolean = true,
        withListens: Boolean = true,
        withReco: Boolean = false,
        base: List<HomeSection> = emptyList(),
        externalAlbums: List<SectionAlbum> = external(),
        tagsByArtist: Map<String, Set<String>>? = null,
        taste: Map<String, Double> = emptyMap(),
        charts: Map<Int, List<SectionAlbum>> = emptyMap(),
    ) = RotationContext(
        base = base,
        loop = if (withListens) listens().map { it.album }.take(9) else emptyList(),
        listens = if (withListens) listens() else emptyList(),
        external = externalAlbums,
        artistTags = tagsByArtist ?: if (!tags) emptyMap() else (0 until 40).associate { ArtistNames.key("Artist $it") to setOf(styles[it % styles.size], "pop") },
        tasteTags = taste,
        chartAlbums = charts,
        recoItems = if (withReco) recoItems() else emptyMap(),
        nowMs = now + hourMs,
        seed = seed,
        zone = ZoneId.of("UTC"),
    )

    /** Eighteen genres of the catalogue, four artists each, one online album per artist: enough for a Home with all its rows. */
    private val genreTags = listOf(
        "rock", "hip-hop", "electronic", "house", "jazz", "funk", "metal", "punk", "reggae", "country",
        "classical", "blues", "j-pop", "afrobeats", "ambient", "world", "k-pop", "latin",
    )
    private fun richArtists() = (0 until genreTags.size * 4).map { "G$it" }
    private fun richAlbums() = richArtists().mapIndexed { i, a -> SectionAlbum("Disc $i", a, ref = "g$i", artworkUrl = "https://g/$i") }
    private fun richTags() = richArtists().mapIndexed { i, a -> ArtistNames.key(a) to setOf(genreTags[i % genreTags.size]) }.toMap()
    private fun rich(seed: Long, taste: Map<String, Double> = emptyMap()) =
        ctx(seed, externalAlbums = richAlbums(), tagsByArtist = richTags(), taste = taste)

    @Test fun `the head and nine to eleven drawn rows, each on one line, without replay`() {
        for (seed in 1L..30L) {
            val out = HomeRotation.arrange(rich(seed))
            assertEquals("loop", out.last().id)
            assertFalse(out.any { it.id == "replay" })
            val drawn = out.filterNot { it.id in setOf("new", "loop") }
            assertTrue("seed $seed got ${drawn.size}: ${drawn.map { it.id }}", drawn.size in HomeRotation.MIN_DYNAMIC..HomeRotation.MAX_DYNAMIC)
            assertTrue(out.all { it.albums.size == 3 })
        }
    }

    @Test fun `the genres of the catalogue make rows of their own, from the sources' albums by that style`() {
        val rock = (1L..40L).flatMap { HomeRotation.arrange(rich(it)) }.filter { it.id == "tag:rock" }
        assertTrue(rock.isNotEmpty())
        rock.forEach { s ->
            assertEquals("Rock", s.title)
            assertTrue(s.albums.all { a -> richTags()[ArtistNames.key(a.artist)]?.contains("rock") == true })
        }
    }

    @Test fun `the styles the listener's artists carry become rows too, and what says nothing of a sound does not`() {
        val artists = (0 until 12).map { "V$it" }
        val albums = artists.mapIndexed { i, a -> SectionAlbum("Vapor $i", a, ref = "v$i", artworkUrl = "https://v/$i") }
        val tags = artists.associate { ArtistNames.key(it) to setOf("vaporwave") }
        val taste = mapOf("vaporwave" to 40.0, "pop" to 99.0, "seen live" to 50.0, "1990s" to 5.0, "french" to 30.0, "rock" to 20.0)
        val seen = (1L..40L).flatMap { HomeRotation.arrange(ctx(it, externalAlbums = albums, tagsByArtist = tags, taste = taste)) }.map { it.id }.toSet()
        assertTrue("vaporwave is a row of its own: $seen", "tag:auto:vaporwave" in seen)
        assertTrue(seen.none { it == "tag:auto:pop" || it == "tag:auto:seen live" || it == "tag:auto:1990s" || it == "tag:auto:french" || it == "tag:auto:rock" })
    }

    @Test fun `a radio of one artist can be blocked, a radio of several cannot`() {
        val radios = (1L..30L).flatMap { HomeRotation.arrange(ctx(it, withReco = true)) }.filter { it.id == "radios" }.flatMap { it.albums }
        assertTrue(radios.isNotEmpty())
        radios.forEach { t ->
            if (t.title.endsWith(" et autres artistes")) assertEquals(null, t.blockableArtist) else assertEquals(t.title, t.blockableArtist)
        }
    }

    @Test fun `no album appears twice across all rows`() {
        val out = HomeRotation.arrange(ctx(7))
        val keys = out.flatMap { s -> s.albums.map { it.key } }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `the themes are made of the sources' albums only, never of the library's own`() {
        for (seed in 1L..40L) {
            val out = HomeRotation.arrange(ctx(seed))
            out.filter { it.id.startsWith("family:") }.forEach { s -> assertTrue("seed $seed ${s.id}", s.albums.all { it.album == null && it.ref != null }) }
        }
    }

    @Test fun `the device's own albums only appear in the loop, never in any other row`() {
        val baseWithLocalArtist = listOf(
            HomeSection("artist:Mine 2", "Plus de", "Mine 2", listOf(local(2), local(4), local(6))),
            HomeSection("similar:Local 2", "Plus de contenu similaire à", "Local 2", (41..43).map { SectionAlbum("Sim $it", "Art $it", ref = "sim$it", artworkUrl = "https://x/$it") }),
        )
        for (seed in 1L..40L) {
            HomeRotation.arrange(ctx(seed, withReco = true, base = baseWithLocalArtist)).forEach { s ->
                if (s.id != "loop") {
                    assertTrue("seed $seed row ${s.id} had local album", s.albums.all { it.album == null })
                }
            }
        }
    }

    @Test fun `the loop counts the sources' albums as much as the device's`() {
        val out = HomeRotation.arrange(ctx(2))
        val loop = out.first { it.id == "loop" }
        assertTrue(loop.albums.any { it.album == null })
        assertTrue(loop.albums.any { it.album != null })
    }

    @Test fun `the drawn rows are diversified across categories`() {
        val base = listOf(
            HomeSection("similar:Prima", "Plus de contenu similaire à", "Prima", (50..52).map { SectionAlbum("S$it", "A$it", ref = "r$it", artworkUrl = "https://x/$it") }),
        )
        for (seed in 1L..20L) {
            val drawn = HomeRotation.arrange(ctx(seed, withReco = true, base = base)).filterNot { it.id in setOf("new", "loop") }
            val hasStyle = drawn.any { it.id.startsWith("family:") || it.id.startsWith("genre:") || it.id.startsWith("tag:") }
            val hasTime = drawn.any { it.id.startsWith("decade:") || it.id == "tastemix" || it.id == "routine" }
            val hasReco = drawn.any { it.id == "radios" || it.id == "tastemix" }
            val hasArtist = drawn.any { it.id.startsWith("similar:") || it.id.startsWith("artist:") || it.id == "fav_artists" }
            val distinctCategories = listOf(hasStyle, hasTime, hasReco, hasArtist).count { it }
            assertTrue("seed $seed had only $distinctCategories categories: ${drawn.map { it.id }}", distinctCategories >= 3)
        }
    }

    @Test fun `the draw does not move for a given seed, and changes with another`() {
        val a = HomeRotation.arrange(ctx(3)).map { it.id }
        assertEquals(a, HomeRotation.arrange(ctx(3)).map { it.id })
        assertTrue((4L..30L).any { HomeRotation.arrange(ctx(it)).map { s -> s.id } != a })
    }

    @Test fun `two variants of one theme are never drawn together`() {
        for (seed in 1L..60L) {
            val ids = HomeRotation.arrange(ctx(seed)).map { it.id }
            assertTrue("kpop twice, seed $seed", ids.count { it == "family:kpop" } <= 1)
            assertTrue("hyperpop twice, seed $seed", ids.count { it == "family:hyperpop" } <= 1)
            assertTrue("a decade twice, seed $seed", ids.count { it.startsWith("decade:") } <= 1)
        }
    }

    @Test fun `a row with too little to show is never drawn`() {
        assertTrue(HomeRotation.arrange(ctx(5, tags = false, withListens = false).let { RotationContext(it.base, it.loop, it.listens, emptyList(), emptyMap(), emptyMap(), it.nowMs, it.seed, it.zone) }).isEmpty())
        val untagged = HomeRotation.arrange(ctx(5, tags = false))
        assertTrue(untagged.none { it.id.startsWith("family:") })
        assertTrue(untagged.all { it.albums.size == 3 })
    }

    @Test fun `the routine row follows the hour`() {
        val morning = (1L..40L).mapNotNull { HomeRotation.arrange(ctx(it, hourMs = 8L * 3_600_000L)).firstOrNull { s -> s.id == "routine" }?.title }.toSet()
        assertTrue(morning.isEmpty() || morning == setOf("Pour commencer la journée"))
        val night = (1L..40L).mapNotNull { HomeRotation.arrange(ctx(it, hourMs = 23L * 3_600_000L)).firstOrNull { s -> s.id == "routine" }?.title }.toSet()
        assertTrue(night.isEmpty() || night == setOf("Session nocturne"))
        assertFalse(morning.contains("Session nocturne"))
    }

    @Test fun `khalid and troye sivan never appear in indie while genuine indie artists do`() {
        val albums = listOf(
            SectionAlbum("Free Spirit", "Khalid", ref = "k1", artworkUrl = "https://x/k1"),
            SectionAlbum("American Teen", "Khalid", ref = "k2", artworkUrl = "https://x/k2"),
            SectionAlbum("Blue Neighbourhood", "Troye Sivan", ref = "t1", artworkUrl = "https://x/t1"),
            SectionAlbum("Bloom", "Troye Sivan", ref = "t2", artworkUrl = "https://x/t2"),
            SectionAlbum("Untourable Album", "Men I Trust", ref = "m1", artworkUrl = "https://x/m1"),
            SectionAlbum("Salad Days", "Mac DeMarco", ref = "m2", artworkUrl = "https://x/m2"),
            SectionAlbum("Immunity", "Clairo", ref = "c1", artworkUrl = "https://x/c1"),
            SectionAlbum("Fake It Flowers", "Beabadoobee", ref = "b1", artworkUrl = "https://x/b1"),
        )
        val tags = mapOf(
            ArtistNames.key("Khalid") to setOf("soul", "pop", "rnb", "alternative rnb"),
            ArtistNames.key("Troye Sivan") to setOf("pop", "indie pop", "indie", "synthpop"),
            ArtistNames.key("Men I Trust") to setOf("dream pop", "indie pop", "indie", "downtempo"),
            ArtistNames.key("Mac DeMarco") to setOf("lo-fi", "indie", "post-punk", "experimental"),
            ArtistNames.key("Clairo") to setOf("bedroom pop", "indie pop", "dream pop", "lo-fi"),
            ArtistNames.key("Beabadoobee") to setOf("indie pop", "indie rock", "bedroom pop", "indie"),
        )
        for (seed in 1L..30L) {
            val out = HomeRotation.arrange(
                RotationContext(
                    base = emptyList(), loop = emptyList(), listens = emptyList(),
                    external = albums, artistTags = tags, recoItems = emptyMap(),
                    nowMs = now, seed = seed, zone = ZoneId.of("UTC"),
                ),
            )
            val indie = out.firstOrNull { it.id == "family:indie" } ?: continue
            val artists = indie.albums.map { it.artist }.toSet()
            assertFalse("Khalid must never be in Indé: $artists", "Khalid" in artists)
            assertFalse("Troye Sivan must never be in Indé: $artists", "Troye Sivan" in artists)
            assertTrue(artists.all { it in setOf("Men I Trust", "Mac DeMarco", "Clairo", "Beabadoobee") })
        }
    }

    @Test fun `an artist is never twice in a row and never in two rows`() {
        for (seed in 1L..60L) {
            val out = HomeRotation.arrange(ctx(seed, withReco = true))
            val rowsOf = HashMap<String, Int>()
            out.forEach { s ->
                val keys = s.albums.filter { !it.playsItems }.map { ArtistNames.key(it.artist) }
                if (!s.id.startsWith("artist:")) assertEquals("seed $seed row ${s.id}", keys.size, keys.toSet().size)
                keys.toSet().forEach { rowsOf.merge(it, 1, Int::plus) }
            }
            rowsOf.forEach { (k, n) -> assertTrue("seed $seed: $k in $n rows", n <= 1) }
        }
    }

    /** Three albums by three artists for each genre's chart, ids as Deezer's. */
    private fun charts(): Map<Int, List<SectionAlbum>> = listOf(132, 152, 116, 106, 113, 129, 169, 464, 144, 466, 98, 153, 2).associateWith { id ->
        (1..4).map { SectionAlbum("Chart $id-$it", "Chartist $id-$it", artworkUrl = "https://chart/$id/$it") }
    }

    @Test fun `a Home with no style tag at all still has nine rows or more, the genres' charts making up the rows`() {
        for (seed in 1L..30L) {
            val out = HomeRotation.arrange(ctx(seed, tags = false, charts = charts()))
            val drawn = out.filterNot { it.id in setOf("new", "loop") }
            assertTrue("seed $seed got ${drawn.size}: ${drawn.map { it.id }}", drawn.size >= HomeRotation.MIN_DYNAMIC)
            assertTrue(out.all { it.albums.size == 3 })
            val keys = out.flatMap { s -> s.albums.map { it.key } }
            assertEquals(keys.size, keys.toSet().size)
        }
    }

    @Test fun `the albums of the listener's own artists in a style come before the chart's`() {
        val rockArtists = (0 until 3).map { "R$it" }
        val own = rockArtists.mapIndexed { i, a -> SectionAlbum("Own $i", a, ref = "o$i", artworkUrl = "https://o/$i") }
        val tags = rockArtists.associate { ArtistNames.key(it) to setOf("rock") }
        val rows = (1L..40L).flatMap { HomeRotation.arrange(ctx(it, externalAlbums = own, tagsByArtist = tags, charts = charts())) }.filter { it.id == "tag:rock" }
        assertTrue(rows.isNotEmpty())
        rows.forEach { r -> assertTrue(r.albums.all { it.title.startsWith("Own") || it.title.startsWith("Chart 152") }) }
        assertTrue(rows.any { r -> r.albums.all { it.title.startsWith("Own") } })
    }

    private fun popContext(seed: Long, tags: Map<String, Set<String>>, albums: List<SectionAlbum>) =
        ctx(seed, externalAlbums = albums, tagsByArtist = tags)

    @Test fun `a style that an artist only carries far down its tags does not put the artist in that style's row`() {
        // Ten pop artists (pop first or second) and ten that only have "pop" among their last tags.
        val central = (0 until 10).map { "Pop $it" }
        val side = (0 until 10).map { "Side $it" }
        val tags = central.associate { ArtistNames.key(it) to setOf("pop", "dance pop", "synthpop", "teen pop") } +
            side.associate { ArtistNames.key(it) to setOf("indie", "dream pop", "shoegaze", "lo-fi", "pop") }
        val albums = (central + side).mapIndexed { i, a -> SectionAlbum("Disc $i", a, ref = "p$i", artworkUrl = "https://p/$i") }
        val rows = (1L..40L).flatMap { HomeRotation.arrange(popContext(it, tags, albums)) }.filter { it.id == "tag:pop" }
        assertTrue(rows.isNotEmpty())
        rows.forEach { s -> assertTrue(s.albums.joinToString { it.artist }, s.albums.all { it.artist.startsWith("Pop") }) }
    }

    @Test fun `in a row of a style, what the engine found for artists the listener plays comes before a loose exploration`() {
        val artists = (0 until 9).map { "Rocker $it" }
        val tags = artists.associate { ArtistNames.key(it) to setOf("rock", "classic rock") }
        val played = setOf("Rocker 7", "Rocker 8", "Rocker 3")
        val albums = artists.mapIndexed { i, a ->
            val code = if (a in played) ReasonCode.LISTENED_ARTIST else ReasonCode.EXPLORATION
            SectionAlbum("Disc $i", a, ref = "r$i", artworkUrl = "https://r/$i", items = listOf(recoItem("r$i", a, code)))
        }
        val rows = (1L..30L).flatMap { seed -> HomeRotation.arrange(popContext(seed, tags, albums)) }.filter { it.id == "tag:rock" }
        assertTrue(rows.isNotEmpty())
        rows.forEach { s -> assertEquals(s.albums.joinToString { it.artist }, played, s.albums.map { it.artist }.toSet()) }
    }

    @Test fun `the loop is always the last row, and what it shows is shown nowhere else`() {
        for (seed in 1L..30L) {
            val out = HomeRotation.arrange(rich(seed))
            assertEquals("seed $seed", "loop", out.last().id)
            assertEquals(1, out.count { it.id == "loop" })
            val loopKeys = out.last().albums.map { it.key }.toSet()
            assertTrue(out.dropLast(1).none { s -> s.albums.any { it.key in loopKeys } })
        }
    }

    @Test fun `an album refused in a row of Home is replaced from the row's own reserve, the row is not dropped`() {
        for (seed in 1L..30L) {
            val held = HomeRotation.arrange(rich(seed))
            val rows = held.filter { it.id.startsWith("tag:") }
            assertTrue(rows.isNotEmpty())
            val victim = rows.first().albums.first()
            val out = SectionHold.reconcile(held, held, emptySet(), setOf(com.ikko.aura.reco.AlbumKeys.ofName(victim.title, victim.artist)))
            val row = out.firstOrNull { it.id == rows.first().id }
            assertTrue("seed $seed lost the row ${rows.first().id}", row != null)
            assertEquals(3, row!!.albums.size)
            assertTrue(row.albums.none { it.key == victim.key })
        }
    }

    @Test fun `Home has at least twelve different rows, never twice the same`() {
        for (seed in 1L..40L) {
            val out = HomeRotation.arrange(rich(seed))
            assertTrue("seed $seed got ${out.size}: ${out.map { it.id }}", out.size >= HomeRotation.MIN_ROWS)
            assertEquals(out.size, out.map { it.id }.toSet().size)
        }
    }

    @Test fun `no row of jazz, nor any jazz album, whoever the artist is`() {
        for (seed in 1L..40L) {
            val out = HomeRotation.arrange(rich(seed))
            assertTrue("seed $seed", out.none { it.id.contains("jazz") || it.title.contains("jazz", ignoreCase = true) })
            val jazzArtists = richTags().filterValues { "jazz" in it }.keys
            assertTrue("seed $seed", out.filter { it.id != "loop" }.flatMap { it.albums }.none { ArtistNames.primaryKey(it.artist) in jazzArtists })
        }
        assertTrue(HomeRotation.isUnwantedStyle(listOf("vocal jazz")))
        assertFalse(HomeRotation.isUnwantedStyle(listOf("rock", "pop")))
    }
}
