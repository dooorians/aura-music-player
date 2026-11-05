package com.ikko.aura.source.ytm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumRefResolverTest {

    @Test
    fun rejectsTributeAndMismatchedArtists() = runBlocking {
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

        // Title TiK ToK with Kesha:
        // tribute and karaoke should be rejected
        // remix matches startsWith and artist matches Ke$ha
        val ref = resolver.find("TiK ToK", "Kesha")
        assertEquals("remix_id", ref)
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
}
