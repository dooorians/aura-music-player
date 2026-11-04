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
}
