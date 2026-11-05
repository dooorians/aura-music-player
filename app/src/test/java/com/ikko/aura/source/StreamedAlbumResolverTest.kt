package com.ikko.aura.source

import com.ikko.aura.data.model.AudioFormat
import com.ikko.aura.data.model.Track
import com.ikko.aura.navigation.streamedAlbumRoute
import com.ikko.aura.source.ytm.YtmAlbumRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamedAlbumResolverTest {

    private fun makeTrack(
        id: String = "ytm:test1234",
        title: String,
        artist: String,
        album: String = "",
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        artistId = 0L,
        albumId = 0L,
        artworkUri = null,
        contentUri = android.net.TestUri.INSTANCE,
        durationMs = 200_000L,
    )

    @Test
    fun rejectsTributeAlbumsAndResolvesRealAlbumViaDetails() = runBlocking {
        val resolver = StreamedAlbumResolver(
            ytm = null,
            isEnabledFunc = { true },
            searchAlbumsFunc = { q ->
                when {
                    q.contains("TiK ToK", ignoreCase = true) -> listOf(
                        YtmAlbumRef("katchya_tribute", "Tik Tok (a Tribute To Kesha)", "Katchya", null),
                        YtmAlbumRef("karaoke_1", "Tik Tok Karaoke", "Various Artists", null),
                    )
                    q.contains("Animal", ignoreCase = true) -> listOf(
                        YtmAlbumRef("animal_expanded", "Animal (Expanded Edition)", "Kesha", null),
                    )
                    else -> emptyList()
                }
            },
            detailsAlbumFunc = { videoId ->
                if (videoId == "test1234") "Animal (Expanded Edition)" else null
            },
        )

        val track = makeTrack(title = "TiK ToK", artist = "Kesha", album = "")
        val route = resolver.routeOf(track)

        assertEquals(streamedAlbumRoute("animal_expanded", online = true), route)
    }

    @Test
    fun rejectsTributeWhenNoDetailsAvailable() = runBlocking {
        val resolver = StreamedAlbumResolver(
            ytm = null,
            isEnabledFunc = { true },
            searchAlbumsFunc = { q ->
                listOf(
                    YtmAlbumRef("katchya_tribute", "Tik Tok (a Tribute To Kesha)", "Katchya", null),
                )
            },
            detailsAlbumFunc = { null },
        )

        val track = makeTrack(title = "TiK ToK", artist = "Kesha", album = "TiK ToK")
        val route = resolver.routeOf(track)

        assertNull("Should never match a tribute album of a different artist", route)
    }

    @Test
    fun matchesArtistWithStylizedDollarSign() = runBlocking {
        val resolver = StreamedAlbumResolver(
            ytm = null,
            isEnabledFunc = { true },
            searchAlbumsFunc = { q ->
                listOf(
                    YtmAlbumRef("animal_1", "Animal", "Ke\$ha", null),
                )
            },
            detailsAlbumFunc = { "Animal" },
        )

        val track = makeTrack(title = "TiK ToK", artist = "Kesha", album = "")
        val route = resolver.routeOf(track)

        assertEquals(streamedAlbumRoute("animal_1", online = true), route)
    }
}
