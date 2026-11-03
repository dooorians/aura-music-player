package com.ikko.aura.reco.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistDiscographyTest {
    private val artist = DisambiguatedArtist("ariana grande", "Ariana Grande", null, 1L, null, emptySet())

    @Test fun `a release is typed by its title, whatever the case, the accents and a deluxe suffix`() {
        val d = Discography(
            artist,
            listOf(
                Release("Eternal Sunshine", ReleaseType.ALBUM, 2024, "https://c/1"),
                Release("Hate That I Made You Love Me", ReleaseType.SINGLE, 2025, null),
            ),
        )
        assertEquals(ReleaseType.ALBUM, d.typeOf("eternal sunshine"))
        assertEquals(ReleaseType.SINGLE, d.typeOf("Hate That I Made You Love Me"))
        assertNull(d.typeOf("Something Else"))
        assertEquals(2024, d.yearOf("ETERNAL SUNSHINE"))
        assertEquals(0, d.yearOf("Something Else"))
    }

    @Test fun `a single and the album of the same name are one release, the album, with the album's date`() {
        val merged = ArtistDiscographies.mergeReleases(
            listOf(
                Release("thank u, next", ReleaseType.SINGLE, 2018, "https://c/single"),
                Release("thank u, next", ReleaseType.ALBUM, 2019, "https://c/album"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(ReleaseType.ALBUM, merged.single().type)
        assertEquals(2019, merged.single().year)
        assertEquals("https://c/album", merged.single().cover)
    }

    @Test fun `the editions of one album are one release, under the plain title, from the first edition's year`() {
        val merged = ArtistDiscographies.mergeReleases(
            listOf(
                Release("Dangerous Woman (Deluxe)", ReleaseType.ALBUM, 2016, null),
                Release("Dangerous Woman", ReleaseType.ALBUM, 2026, "https://c/reissue"),
                Release("Dangerous Woman", ReleaseType.ALBUM, 2016, "https://c/original"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("Dangerous Woman", merged.single().title)
        assertEquals(2016, merged.single().year)
    }

    @Test fun `the releases come most recent first`() {
        val merged = ArtistDiscographies.mergeReleases(
            listOf(
                Release("Yours Truly", ReleaseType.ALBUM, 2013, null),
                Release("petal", ReleaseType.ALBUM, 2026, null),
                Release("Sweetener", ReleaseType.ALBUM, 2018, null),
            ),
        )
        assertEquals(listOf("petal", "Sweetener", "Yours Truly"), merged.map { it.title })
    }
}
