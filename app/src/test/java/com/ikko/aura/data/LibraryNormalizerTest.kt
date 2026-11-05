package com.ikko.aura.data

import com.ikko.aura.reco.GenreNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryNormalizerTest {
    private fun t(id: String, artist: String, album: String, albumId: Long, artistId: Long) =
        LibraryNormalizer.Row(id, artist, album, albumId, artistId)

    private fun ids(rows: List<LibraryNormalizer.Row>, extras: Map<String, FileExtras> = emptyMap()) = LibraryNormalizer.group(rows, extras)

    @Test fun `an album spread over several media index albums by a featured artist becomes one`() {
        val tracks = listOf(
            t("1", "Angèle", "Nonante-Cinq", 10, 1), t("2", "Angèle feat. Roméo Elvis", "Nonante-Cinq", 11, 2),
            t("3", "Angèle", "Nonante-Cinq", 10, 1), t("4", "Angèle & Damso", "Nonante-Cinq", 12, 3),
        )
        val out = ids(tracks).values
        assertEquals(1, out.map { it.albumId }.distinct().size)
        assertEquals(1, out.map { it.artistId }.distinct().size)
    }

    @Test fun `disc folders and markers do not split an album and give the disc`() {
        val tracks = listOf(
            t("1", "A", "Epic (Disc 1)", 5, 1), t("2", "A", "Epic [CD2]", 6, 1), t("3", "A", "Epic", 7, 1),
        )
        val out = ids(tracks)
        assertEquals(1, out.values.map { it.albumId }.distinct().size)
        assertEquals(listOf(1, 2, 0), listOf("1", "2", "3").map { out.getValue(it).disc })
        assertEquals("Epic", LibraryNormalizer.displayTitle("Epic (Disc 1)"))
    }

    @Test fun `a duo is kept whole unless its first name is an artist alone in the library`() {
        val alone = setOf("simon")
        assertEquals("Simon & Garfunkel", LibraryNormalizer.parseArtist("Simon & Garfunkel", emptySet()).primary)
        assertEquals("Simon", LibraryNormalizer.parseArtist("Simon & Garfunkel", alone).primary)
        assertEquals("AC/DC", LibraryNormalizer.parseArtist("AC/DC", emptySet()).primary)
        assertEquals(listOf("B", "C"), LibraryNormalizer.parseArtist("A (feat. B & C)", emptySet()).guests)
        assertEquals("A", LibraryNormalizer.parseArtist("A ft. B", emptySet()).primary)
    }

    @Test fun `two albums with the same name by unrelated artists stay two`() {
        val out = ids(listOf(t("1", "A", "Greatest Hits", 1, 1), t("2", "B", "Greatest Hits", 2, 2))).values
        assertEquals(2, out.map { it.albumId }.distinct().size)
    }

    @Test fun `a compilation of unrelated artists in one folder is one album`() {
        val dir = FileExtras(dir = "Music/Now/")
        val tracks = listOf(t("1", "A", "Now 99", 1, 1), t("2", "B", "Now 99", 2, 2), t("3", "C", "Now 99", 3, 3), t("4", "D", "Now 99", 4, 4))
        val out = ids(tracks, tracks.associate { it.id to dir }).values
        assertEquals(1, out.map { it.albumId }.distinct().size)
        assertEquals("Artistes divers", LibraryNormalizer.albumArtistOf(tracks.map { it.artist }))
    }

    @Test fun `genres variants fold, ID3 numbers are names, junk is none`() {
        assertEquals("Hip-Hop", GenreNormalizer.normalize("Hip Hop"))
        assertEquals("Hip-Hop", GenreNormalizer.normalize("Rap/Hip-Hop"))
        assertEquals("Rock", GenreNormalizer.normalize("(17)"))
        assertEquals("K-Pop", GenreNormalizer.normalize("kpop"))
        assertNull(GenreNormalizer.normalize("Other"))
        assertNull(GenreNormalizer.normalize("<unknown>"))
        assertNull(GenreNormalizer.normalize("2019"))
    }
}
