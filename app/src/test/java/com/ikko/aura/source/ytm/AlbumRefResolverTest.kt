package com.ikko.aura.source.ytm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumRefResolverTest {

    @Test
    fun rejectsEditionsTributesAndMismatchedArtists() = runBlocking {
        val resolver = AlbumRefResolver { query ->
            if (query == "tik tok kesha") {
                listOf(
                    YtmAlbumRef("tribute_id", "Tik Tok (a Tribute To Kesha)", "Katchya", null),
                    YtmAlbumRef("karaoke_id", "The Karaoke Channel - Sing Tik Tok Like Kesha", "The Karaoke Channel", null),
                    YtmAlbumRef("animal_id", "Animal (Expanded Edition)", "Ke\$ha", null),
                    YtmAlbumRef("remix_id", "TiK ToK - Remixes", "Ke\$ha", null),
                )
            } else emptyList()
        }

        // Nothing here is the exact album: a tribute, a karaoke channel, an expanded edition and a
        // remix EP are all different records. The exact title "TiK ToK" matches none of them.
        val ref = resolver.find("TiK ToK", "Kesha")
        assertNull(ref)
    }

    @Test
    fun rejectsCompletelyIfOnlyTributeAvailable() = runBlocking {
        val resolver = AlbumRefResolver { query ->
            if (query == "tik tok kesha") {
                listOf(
                    YtmAlbumRef("tribute_id", "Tik Tok (a Tribute To Kesha)", "Katchya", null),
                    YtmAlbumRef("cover_id", "Acoustic Guitar Tribute to Kesha", "Acoustic Sessions", null),
                )
            } else emptyList()
        }

        val ref = resolver.find("TiK ToK", "Kesha")
        assertNull(ref)
    }

    @Test
    fun matchesExactTitle() = runBlocking {
        val resolver = AlbumRefResolver { query ->
            listOf(
                YtmAlbumRef("other_id", "Other Album", "Troye Sivan", null),
                YtmAlbumRef("exact_id", "Angel Baby", "Troye Sivan", null),
            )
        }

        val ref = resolver.find("Angel Baby", "Troye Sivan")
        assertEquals("exact_id", ref)
    }

    @Test
    fun doesNotResolveEditionSuffixes() = runBlocking {
        val resolver = AlbumRefResolver { query ->
            listOf(
                YtmAlbumRef("deluxe_id", "Thriller (Deluxe Edition)", "Michael Jackson", null),
                YtmAlbumRef("exact_id", "Thriller", "Michael Jackson", null),
            )
        }
        // "Thriller" ne doit pas ouvrir l'édition Deluxe : l'exact seul compte.
        assertEquals("exact_id", resolver.find("Thriller", "Michael Jackson"))
    }

    @Test
    fun rejectsWhenOnlyAnEditionIsAvailable() = runBlocking {
        val resolver = AlbumRefResolver { query ->
            listOf(YtmAlbumRef("remix_id", "TiK ToK - Remixes", "Ke\$ha", null))
        }
        // Une édition différente n'est pas l'album : pas de résolution.
        assertNull(resolver.find("TiK ToK", "Kesha"))
    }

    @Test
    fun aSearchAnsweredByKaraokeOnlyDoesNotStopTheNextOne() = runBlocking {
        val asked = ArrayList<String>()
        val resolver = AlbumRefResolver { q ->
            asked += q
            if (q.contains("lady gaga")) listOf(
                YtmAlbumRef("k1", "Cheek to Cheek (Karaoke Version Originally Performed by Tony Bennett & Lady Gaga)", "Karaoke Star", null),
                YtmAlbumRef("k2", "Cheek To Cheek (Tribute)", "Some Band", null),
            ) else listOf(YtmAlbumRef("real_id", "Cheek To Cheek (Deluxe)", "Tony Bennett & Lady Gaga", null))
        }
        assertEquals("real_id", resolver.find("Cheek To Cheek (Deluxe)", "Lady Gaga"))
    }

    @Test
    fun theSameAlbumCreditedToTheOtherHalfOfTheDuoIsFound() = runBlocking {
        val resolver = AlbumRefResolver { _ -> listOf(YtmAlbumRef("duo_id", "Cheek To Cheek (Deluxe)", "Tony Bennett", null)) }
        assertEquals("duo_id", resolver.find("Cheek To Cheek (Deluxe)", "Tony Bennett & Lady Gaga"))
    }

    @Test
    fun aSharedTitleBySomeoneElseStaysOut() = runBlocking {
        val resolver = AlbumRefResolver { _ -> listOf(YtmAlbumRef("other_id", "Greatest Hits", "Queen", null)) }
        assertNull(resolver.find("Greatest Hits", "Abba"))
    }

    @Test
    fun htmlEntitiesAreDecodedBeforeSearching() = runBlocking {
        val asked = ArrayList<String>()
        val resolver = AlbumRefResolver { q ->
            asked += q
            listOf(YtmAlbumRef("only_id", "Only You", "Kaskade & Tiësto", null))
        }
        assertEquals("only_id", resolver.find("Only You", "Kaskade &amp;amp; Tiësto"))
        assertEquals(true, asked.none { it.contains("amp") })
    }

    @Test
    fun theKindOfReleaseWrittenAfterTheNameDoesNotHideTheAlbum() = runBlocking {
        val resolver = AlbumRefResolver { _ ->
            listOf(
                YtmAlbumRef("mura_id", "Honey (Mura Masa Wonder Remix)", "Troye Sivan", null),
                YtmAlbumRef("remix_id", "Honey (Remix)", "Troye Sivan", null),
            )
        }
        // Home calls it "Honey (Remix EP)"; the source lists "Honey (Remix)". Same artist, same name, "EP" is a label.
        assertEquals("remix_id", resolver.find("Honey (Remix EP)", "Troye Sivan"))
    }

    @Test
    fun theKindOfReleaseDoesNotLetAnotherArtistsAlbumIn() = runBlocking {
        val resolver = AlbumRefResolver { _ -> listOf(YtmAlbumRef("other_id", "Honey", "Mariah Carey", null)) }
        assertNull(resolver.find("Honey EP", "Troye Sivan"))
    }

    @Test
    fun anEditionIsStillAnotherRecordThanTheEp() = runBlocking {
        val resolver = AlbumRefResolver { _ -> listOf(YtmAlbumRef("deluxe_id", "Honey (Deluxe)", "Troye Sivan", null)) }
        assertNull(resolver.find("Honey EP", "Troye Sivan"))
    }
}
