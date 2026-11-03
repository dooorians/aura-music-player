package com.ikko.aura.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.model.Album
import com.ikko.aura.data.model.Artist
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.reco.authority.ArtistAuthority
import com.ikko.aura.reco.authority.DiscographyVerifier
import com.ikko.aura.source.ytm.YtmClient
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtistImagesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var photosDir: File

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        photosDir = tempFolder.newFolder("artist_photos")
    }

    private val testUri: Uri = android.net.TestUri.INSTANCE

    private fun createFakeMusicRepository(tracks: List<Track>, albums: List<Album>): MusicRepository {
        return object : MusicRepository {
            override val tracks: StateFlow<List<Track>> = MutableStateFlow(tracks)
            override val albums: StateFlow<List<Album>> = MutableStateFlow(albums)
            override val artists: StateFlow<List<Artist>> = MutableStateFlow(emptyList())
            override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)
            override suspend fun refresh(forceRescan: Boolean) {}
            override fun forget(trackIds: Set<String>) {}
        }
    }

    @Test
    fun testResolveArtistImageWithLocalDiscography_selectsCorrectHomonym() = runBlocking {
        // Local user has tracks and album by the Belgian pop star Angèle
        val localTrack = Track(
            id = "t1",
            title = "Tout oublier",
            artist = "Angèle",
            album = "Brol",
            artistId = 1L,
            albumId = 1L,
            artworkUri = null,
            contentUri = testUri,
            durationMs = 200_000L,
        )
        val localAlbum = Album(
            id = 1L,
            title = "Brol",
            artist = "Angèle",
            artistId = 1L,
            artworkUri = null,
            trackCount = 12,
            year = 2018,
        )
        val repository = createFakeMusicRepository(listOf(localTrack), listOf(localAlbum))

        // Deezer mock with 3 homonym candidates for "Angèle"
        val deezerClient = DeezerClient(httpGet = { url ->
            when {
                url.contains("search/artist") -> """
                {
                  "data": [
                    { "id": 58827562, "name": "Angèle", "nb_fan": 86, "picture_xl": "https://api.deezer.com/artist/58827562/image" },
                    { "id": 76309762, "name": "Angele", "nb_fan": 22, "picture_xl": "https://api.deezer.com/artist/76309762/image" },
                    { "id": 1042268, "name": "Angèle", "nb_fan": 1754855, "picture_xl": "https://api.deezer.com/artist/1042268/image" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/58827562/albums") -> """
                {
                  "data": [
                    { "id": 359766087, "title": "Stronger Than B4", "record_type": "album" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/58827562/top") -> """
                {
                  "data": [
                    { "id": 1, "title": "Si Mwen Vivan", "title_short": "Si Mwen Vivan", "artist": { "id": 58827562, "name": "Angèle" } }
                  ]
                }
                """.trimIndent()
                url.contains("artist/76309762/albums") -> """
                {
                  "data": [
                    { "id": 2, "title": "Something Else", "record_type": "album" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/76309762/top") -> """
                {
                  "data": [
                    { "id": 20, "title": "Unrelated Track", "title_short": "Unrelated Track", "artist": { "id": 76309762, "name": "Angele" } }
                  ]
                }
                """.trimIndent()
                url.contains("artist/1042268/albums") -> """
                {
                  "data": [
                    { "id": 71730882, "title": "Brol", "record_type": "album" },
                    { "id": 277015082, "title": "Nonante-Cinq", "record_type": "album" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/1042268/top") -> """
                {
                  "data": [
                    { "id": 30, "title": "Tout oublier", "title_short": "Tout oublier", "artist": { "id": 1042268, "name": "Angèle" } },
                    { "id": 31, "title": "Balance ton quoi", "title_short": "Balance ton quoi", "artist": { "id": 1042268, "name": "Angèle" } }
                  ]
                }
                """.trimIndent()
                url.contains("artist/1042268") -> """
                {
                  "id": 1042268,
                  "name": "Angèle",
                  "nb_fan": 1754855,
                  "picture_xl": "https://api.deezer.com/artist/1042268/image"
                }
                """.trimIndent()
                else -> """{ "data": [] }"""
            }
        })

        val mbClient = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        val authority = ArtistAuthority(mbClient, deezerClient)
        val verifier = DiscographyVerifier(deezerClient, mbClient)

        val artistImages = ArtistImages(
            context = null,
            ytm = null,
            repository = repository,
            authority = authority,
            verifier = verifier,
            deezerClient = deezerClient,
            prefsOverride = fakePrefs,
            dirOverride = photosDir,
        )

        val resolvedUrl = artistImages.resolveValidArtistImageUrl("Angèle")

        // Must resolve candidate 1042268 whose discography matches "Brol" and "Tout oublier"
        assertNotNull(resolvedUrl)
        assertEquals("https://api.deezer.com/artist/1042268/image", resolvedUrl)
    }

    @Test
    fun testResolveArtistImage_withLocalMusic_rejectsWhenNoConcordance() = runBlocking {
        // Local user has an obscure local indie artist called "Angèle" with demo tracks
        val localTrack = Track(
            id = "t_demo",
            title = "Private Garage Demo",
            artist = "Angèle",
            album = "Cassette 2021",
            artistId = 99L,
            albumId = 99L,
            artworkUri = null,
            contentUri = testUri,
            durationMs = 150_000L,
        )
        val localAlbum = Album(
            id = 99L,
            title = "Cassette 2021",
            artist = "Angèle",
            artistId = 99L,
            artworkUri = null,
            trackCount = 3,
            year = 2021,
        )
        val repository = createFakeMusicRepository(listOf(localTrack), listOf(localAlbum))

        // Deezer mock only knows the famous pop star Angèle (Brol) and another Angèle (Stronger Than B4)
        val deezerClient = DeezerClient(httpGet = { url ->
            when {
                url.contains("search/artist") -> """
                {
                  "data": [
                    { "id": 58827562, "name": "Angèle", "nb_fan": 86, "picture_xl": "https://api.deezer.com/artist/58827562/image" },
                    { "id": 1042268, "name": "Angèle", "nb_fan": 1754855, "picture_xl": "https://api.deezer.com/artist/1042268/image" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/1042268/albums") -> """
                {
                  "data": [
                    { "id": 71730882, "title": "Brol", "record_type": "album" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/1042268/top") -> """
                {
                  "data": [
                    { "id": 30, "title": "Tout oublier", "title_short": "Tout oublier", "artist": { "id": 1042268, "name": "Angèle" } }
                  ]
                }
                """.trimIndent()
                url.contains("artist/58827562/albums") -> """
                {
                  "data": [
                    { "id": 359766087, "title": "Stronger Than B4", "record_type": "album" }
                  ]
                }
                """.trimIndent()
                url.contains("artist/58827562/top") -> """
                {
                  "data": [
                    { "id": 1, "title": "Si Mwen Vivan", "title_short": "Si Mwen Vivan", "artist": { "id": 58827562, "name": "Angèle" } }
                  ]
                }
                """.trimIndent()
                else -> """{ "data": [] }"""
            }
        })

        val mbClient = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        val authority = ArtistAuthority(mbClient, deezerClient)
        val verifier = DiscographyVerifier(deezerClient, mbClient)

        val artistImages = ArtistImages(
            context = null,
            ytm = null,
            repository = repository,
            authority = authority,
            verifier = verifier,
            deezerClient = deezerClient,
            prefsOverride = fakePrefs,
            dirOverride = photosDir,
        )

        val resolvedUrl = artistImages.resolveValidArtistImageUrl("Angèle")

        // Requirement: If no discography concordance is confirmed with local library, return null!
        assertNull(resolvedUrl)
    }

    @Test
    fun testAccentMismatchDiscrimination() {
        // Query has accent "Angèle"
        assertTrue(ArtistAuthority.hasAccentMismatch("Angèle", "Angele"))
        assertTrue(ArtistAuthority.hasAccentMismatch("Angele", "Angèle"))
        assertTrue(ArtistAuthority.hasAccentMismatch("Beyoncé", "Beyonce"))

        // Exact accents match
        assertTrue(!ArtistAuthority.hasAccentMismatch("Angèle", "Angèle"))
        assertTrue(!ArtistAuthority.hasAccentMismatch("Angèle", "angèle"))
        assertTrue(!ArtistAuthority.hasAccentMismatch("Dua Lipa", "Dua Lipa"))
    }

    @Test
    fun testPreservesExistingValidatedImageWhenFetchReturnsNull() = runBlocking {
        // Create an existing photo file in the photos directory
        val existingPhoto = File(photosDir, "existing_valid_photo.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val key = com.ikko.aura.reco.ArtistNames.key("Angèle")
        val timestamp = System.currentTimeMillis()
        fakePrefs.edit().putInt(ArtistImages.RULES_KEY, ArtistImages.PHOTO_RULES).apply()
        fakePrefs.edit().putString(key, "$timestamp\thttps://valid.url/photo.jpg\t${existingPhoto.name}").apply()

        val repository = createFakeMusicRepository(emptyList(), emptyList())
        // Deezer returns nothing (network down or artist not found)
        val deezerClient = DeezerClient(httpGet = { """{ "data": [] }""" })
        val mbClient = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        val authority = ArtistAuthority(mbClient, deezerClient)
        val verifier = DiscographyVerifier(deezerClient, mbClient)

        val artistImages = ArtistImages(
            context = null,
            ytm = null,
            repository = repository,
            authority = authority,
            verifier = verifier,
            deezerClient = deezerClient,
            prefsOverride = fakePrefs,
            dirOverride = photosDir,
        )

        // Requesting urlFor when resolution returns null
        val resultUri = artistImages.urlFor("Angèle")

        // MUST preserve existing valid photo, NOT clear it or return null
        assertNotNull(resultUri)
        assertTrue(resultUri!!.contains("existing_valid_photo.jpg"))
        assertTrue(existingPhoto.exists())
    }



    private fun images(repository: MusicRepository, deezer: DeezerClient): ArtistImages {
        val mb = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        return ArtistImages(
            context = null, ytm = null, repository = repository, authority = ArtistAuthority(mb, deezer),
            verifier = DiscographyVerifier(deezer, mb), deezerClient = deezer, prefsOverride = fakePrefs, dirOverride = photosDir,
        )
    }

    @Test
    fun aPhotoChosenByAnOlderRuleIsDroppedAndNotKept() = runBlocking {
        val old = File(photosDir, "wrong_namesake.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val key = com.ikko.aura.reco.ArtistNames.key("Angèle")
        // No rules key: the photo was chosen before the discography rule existed.
        fakePrefs.edit().putString(key, "${System.currentTimeMillis()}\thttps://wrong/photo.jpg\t${old.name}").apply()
        val deezer = DeezerClient(httpGet = { """{ "data": [] }""" }, minIntervalMs = 0, retryDelayMs = 1)
        val artistImages = images(createFakeMusicRepository(emptyList(), emptyList()), deezer)
        assertNull(artistImages.urlFor("Angèle"))
        assertTrue(!old.exists())
    }

    @Test
    fun anArtistWithNoPhotoIsNotLookedForAgainAtOnce() = runBlocking {
        var calls = 0
        val deezer = DeezerClient(httpGet = { calls++; """{ "data": [] }""" }, minIntervalMs = 0, retryDelayMs = 1)
        val artistImages = images(createFakeMusicRepository(emptyList(), emptyList()), deezer)
        assertNull(artistImages.urlFor("Nobody Atall"))
        val after = calls
        assertNull(artistImages.urlFor("Nobody Atall"))
        assertEquals(after, calls)
    }

    /** Two namesakes called "Brel": the one with fewer fans is the one Last.fm's best-known songs belong to. */
    private fun namesakes(): DeezerClient = DeezerClient(
        httpGet = { url ->
            when {
                url.contains("search/artist") -> """{ "data": [
                    { "id": 1, "name": "Brel", "nb_fan": 900000, "picture_xl": "https://img/popular-namesake" },
                    { "id": 2, "name": "Brel", "nb_fan": 4000, "picture_xl": "https://img/the-artist" },
                    { "id": 3, "name": "Brel Dubois", "nb_fan": 5000000, "picture_xl": "https://img/contains-the-name" }
                ] }"""
                url.contains("artist/1/top") -> """{ "data": [ { "id": 10, "title": "Other Song", "title_short": "Other Song", "artist": { "id": 1, "name": "Brel" } } ] }"""
                url.contains("artist/2/top") -> """{ "data": [ { "id": 20, "title": "Ma Chanson", "title_short": "Ma Chanson", "artist": { "id": 2, "name": "Brel" } } ] }"""
                else -> """{ "data": [] }"""
            }
        },
        minIntervalMs = 0, retryDelayMs = 1,
    )

    @Test
    fun withoutAnyLocalSongTheNamesakeWhoOwnsLastFmsTopSongsWins() = runBlocking {
        val deezer = namesakes()
        val mb = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        val artistImages = ArtistImages(
            context = null, ytm = null, repository = createFakeMusicRepository(emptyList(), emptyList()),
            authority = ArtistAuthority(mb, deezer), verifier = DiscographyVerifier(deezer, mb), deezerClient = deezer,
            prefsOverride = fakePrefs, dirOverride = photosDir,
            lastFmTopTracks = { listOf("Ma Chanson", "Autre") },
        )
        assertEquals("https://img/the-artist", artistImages.resolveValidArtistImageUrl("Brel"))
    }

    @Test
    fun aNameThatOnlyContainsTheArtistsIsNeverTheirPhoto() = runBlocking {
        val deezer = DeezerClient(
            httpGet = { url ->
                if (url.contains("search/artist")) """{ "data": [ { "id": 3, "name": "Angèle Dubeau", "nb_fan": 20000, "picture_xl": "https://img/violinist" } ] }"""
                else """{ "data": [] }"""
            },
            minIntervalMs = 0, retryDelayMs = 1,
        )
        val mb = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        val artistImages = ArtistImages(
            context = null, ytm = null, repository = createFakeMusicRepository(emptyList(), emptyList()),
            authority = ArtistAuthority(mb, deezer), verifier = DiscographyVerifier(deezer, mb), deezerClient = deezer,
            prefsOverride = fakePrefs, dirOverride = photosDir,
        )
        // No photo rather than the violinist's.
        assertNull(artistImages.resolveValidArtistImageUrl("Angèle"))
    }

    @Test
    fun aLibraryWithOnlyOtherSongsStillGetsThePhotoLastFmProves() = runBlocking {
        val deezer = namesakes()
        val mb = MusicBrainzClient(httpGet = { """{ "artists": [] }""" })
        // The library has a song of "Brel" whose title Deezer does not list among the top ones.
        val track = Track(id = "t", title = "Obscure B-side", artist = "Brel", album = "Rarities", artistId = 1, albumId = 1, artworkUri = null, contentUri = testUri, durationMs = 1)
        val artistImages = ArtistImages(
            context = null, ytm = null, repository = createFakeMusicRepository(listOf(track), emptyList()),
            authority = ArtistAuthority(mb, deezer), verifier = DiscographyVerifier(deezer, mb), deezerClient = deezer,
            prefsOverride = fakePrefs, dirOverride = photosDir,
            lastFmTopTracks = { listOf("Ma Chanson") },
        )
        assertEquals("https://img/the-artist", artistImages.resolveValidArtistImageUrl("Brel"))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val target: HashMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { pending[key!!] = this; return this }
            override fun clear(): SharedPreferences.Editor { clearPending = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearPending) target.clear()
                pending.forEach { (k, v) ->
                    if (v === this) target.remove(k) else target[k] = v
                }
            }
        }
    }
}
