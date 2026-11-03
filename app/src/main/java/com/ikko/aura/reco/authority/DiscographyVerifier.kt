package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.reco.TrackIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Singleton
class DiscographyVerifier @Inject constructor(
    private val deezerClient: DeezerClient,
    private val mbClient: MusicBrainzClient,
) {
    private val verifiedTracksCache = ConcurrentHashMap<String, Set<String>>()
    private val verifiedAlbumsCache = ConcurrentHashMap<String, Set<String>>()

    suspend fun getVerifiedTrackTitles(artist: DisambiguatedArtist): Set<String> {
        val key = artist.canonicalKey
        if (key.isEmpty()) return emptySet()
        verifiedTracksCache[key]?.let { return it }

        val titles = LinkedHashSet<String>()

        // 1. Fetch from Deezer (top tracks + official albums & tracks)
        val dId = artist.deezerId
        if (dId != null && dId > 0) {
            val topTracks = runCatching { deezerClient.getArtistTopTracks(dId, limit = 100) }.getOrDefault(emptyList())
            for (t in topTracks) {
                addTitle(titles, t.title)
                addTitle(titles, t.titleShort)
            }

            val albums = runCatching { deezerClient.getArtistAlbums(dId, limit = 60) }.getOrDefault(emptyList())
            val officialReleases = albums.filter {
                it.recordType in OFFICIAL_RECORD_TYPES
            }

            for (alb in officialReleases) {
                addTitle(titles, alb.title)
            }

            // Fetch tracks for the most significant releases in parallel (max 20 releases)
            coroutineScope {
                officialReleases.take(MAX_ALBUMS_FETCHED).map { album ->
                    async {
                        runCatching { deezerClient.getAlbumTracks(album.id) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten().forEach { t ->
                    addTitle(titles, t.title)
                    addTitle(titles, t.titleShort)
                }
            }
        }

        // 2. Fetch from MusicBrainz (release groups)
        val mbid = artist.mbid
        if (!mbid.isNullOrBlank()) {
            val rgs = runCatching { mbClient.getArtistReleaseGroups(mbid) }.getOrDefault(emptyList())
            for (rg in rgs) {
                if (rg.primaryType in OFFICIAL_MB_TYPES || rg.primaryType == null) {
                    addTitle(titles, rg.title)
                }
            }
        }

        return titles.also { verifiedTracksCache[key] = it }
    }

    suspend fun getVerifiedAlbumTitles(artist: DisambiguatedArtist): Set<String> {
        val key = artist.canonicalKey
        if (key.isEmpty()) return emptySet()
        verifiedAlbumsCache[key]?.let { return it }

        val albumTitles = LinkedHashSet<String>()

        // 1. Fetch from Deezer (artist albums)
        val dId = artist.deezerId
        if (dId != null && dId > 0) {
            val albums = runCatching { deezerClient.getArtistAlbums(dId, limit = 100) }.getOrDefault(emptyList())
            for (alb in albums) {
                if (alb.recordType in OFFICIAL_RECORD_TYPES) {
                    addTitle(albumTitles, alb.title)
                }
            }
        }

        // 2. Fetch from MusicBrainz (release groups)
        val mbid = artist.mbid
        if (!mbid.isNullOrBlank()) {
            val rgs = runCatching { mbClient.getArtistReleaseGroups(mbid) }.getOrDefault(emptyList())
            for (rg in rgs) {
                if (rg.primaryType in OFFICIAL_MB_TYPES || rg.primaryType == null) {
                    addTitle(albumTitles, rg.title)
                }
            }
        }

        return albumTitles.also { verifiedAlbumsCache[key] = it }
    }

    suspend fun isOfficialTrack(artist: DisambiguatedArtist, trackTitle: String): Boolean {
        val clean = trackTitle.trim()
        if (clean.isEmpty()) return false
        val verified = getVerifiedTrackTitles(artist)
        if (verified.isEmpty()) return false

        val titleKey = TrackIdentity.titleKey(clean)
        val baseKey = TrackIdentity.baseKey(clean)

        return titleKey in verified || baseKey in verified
    }

    suspend fun isOfficialAlbum(artist: DisambiguatedArtist, albumTitle: String): Boolean {
        val clean = albumTitle.trim()
        if (clean.isEmpty()) return false
        if (isKnownCompilationOrPlaylist(clean)) return false
        val verified = getVerifiedAlbumTitles(artist)
        if (verified.isEmpty()) return false

        val titleKey = TrackIdentity.titleKey(clean)
        val baseKey = TrackIdentity.baseKey(clean)

        return titleKey in verified || baseKey in verified
    }

    /**
     * Absolute discography integrity:
     * - The track MUST be an official track of the artist.
     * - If an album title is specified (and not a generic label or single name), it MUST be an official release of the artist.
     *   Any tracks scraped from third-party compilations, bootlegs or editorial playlists are strictly rejected.
     */
    suspend fun isOfficialTrackAndRelease(artist: DisambiguatedArtist, trackTitle: String, albumTitle: String?): Boolean {
        if (isKnownCompilationOrPlaylist(trackTitle)) return false
        val cleanAlbum = albumTitle?.trim().orEmpty()
        if (cleanAlbum.isNotEmpty() && isKnownCompilationOrPlaylist(cleanAlbum)) return false
        if (!isOfficialTrack(artist, trackTitle)) return false
        if (cleanAlbum.isEmpty() || isGenericAlbum(cleanAlbum)) return true
        if (TrackIdentity.baseKey(cleanAlbum) == TrackIdentity.baseKey(trackTitle)) return true
        return isOfficialAlbum(artist, cleanAlbum)
    }

    suspend fun filterOfficialTracks(artist: DisambiguatedArtist, candidates: List<String>): List<String> {
        val verified = getVerifiedTrackTitles(artist)
        if (verified.isEmpty()) return emptyList()

        return candidates.filter { title ->
            val titleKey = TrackIdentity.titleKey(title)
            val baseKey = TrackIdentity.baseKey(title)
            titleKey in verified || baseKey in verified
        }
    }

    fun isKnownCompilationOrPlaylist(title: String): Boolean {
        return CompilationTitles.matches(title)
    }

    fun isGenericAlbum(title: String): Boolean {
        val t = title.lowercase().trim()
        return t in GENERIC_ALBUM_NAMES
    }

    private fun addTitle(into: MutableSet<String>, rawTitle: String) {
        val clean = rawTitle.trim()
        if (clean.isEmpty()) return
        val tKey = TrackIdentity.titleKey(clean)
        if (tKey.isNotEmpty()) into += tKey
        val bKey = TrackIdentity.baseKey(clean)
        if (bKey.isNotEmpty()) into += bKey
    }

    private companion object {
        val OFFICIAL_RECORD_TYPES = setOf("album", "single", "ep", "compile")
        val OFFICIAL_MB_TYPES = setOf("Album", "Single", "EP")
        const val MAX_ALBUMS_FETCHED = 20

        val GENERIC_ALBUM_NAMES = setOf(
            "single",
            "singles",
            "ep",
            "unknown",
            "unknown album",
            "track",
            "tracks",
            "audio",
        )
    }
}
