package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.deezer.DeezerTrackInfo
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.reco.TrackIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class DiscographyVerifier @Inject constructor(
    private val deezerClient: DeezerClient,
    private val mbClient: MusicBrainzClient,
    private val store: com.ikko.aura.reco.VerifiedDiscographyDao,
) {
    /** For callers without a Room store (tests): an in-memory store, forgotten when the process ends. */
    constructor(deezerClient: DeezerClient, mbClient: MusicBrainzClient) :
        this(deezerClient, mbClient, com.ikko.aura.reco.InMemoryDiscographyStore())

    /** Fast path within a session; the durable source is [store], these avoid reading Room on every check. */
    private val verifiedTracksCache = ConcurrentHashMap<String, Set<String>>()
    private val verifiedAlbumsCache = ConcurrentHashMap<String, Set<String>>()
    /** Artist key → (track title key → bpm), from Deezer; kept so [bpmOf] answers without the network. */
    private val bpmCache = ConcurrentHashMap<String, Map<String, Double>>()

    /** Loads the discography once per artist: from the store when fresh, else rebuilt and stored. */
    private suspend fun ensureLoaded(artist: DisambiguatedArtist) {
        val key = artist.canonicalKey
        if (key.isEmpty() || verifiedTracksCache.containsKey(key)) return
        val stored = store.get(key)
        if (stored != null && System.currentTimeMillis() - stored.fetchedAtMs < TTL_MS) {
            verifiedTracksCache[key] = decode(stored.tracksJson)
            verifiedAlbumsCache[key] = decode(stored.albumsJson)
            if (stored.bpmJson.isNotBlank()) bpmCache[key] = decodeBpm(stored.bpmJson)
            return
        }
        val (tracks, albums, bpm) = fetch(artist)
        verifiedTracksCache[key] = tracks
        verifiedAlbumsCache[key] = albums
        if (bpm.isNotEmpty()) bpmCache[key] = bpm
        if (tracks.isNotEmpty() || albums.isNotEmpty()) {
            store.put(
                com.ikko.aura.reco.VerifiedDiscographyEntity(key, encode(tracks), encode(albums), encodeBpm(bpm), System.currentTimeMillis()),
            )
        }
    }

    /**
     * The official titles: Deezer's top tracks and the tracks of every official release up to a generous cap (the old
     * cap of 20 albums missed the tail of a long discography), plus MusicBrainz's own recordings and release groups.
     * Bounded but far more complete than before, and written to the store so it survives relaunches. The tempo Deezer
     * states is kept alongside, so a candidate's mood can be judged on its music, not its title.
     */
    private suspend fun fetch(artist: DisambiguatedArtist): Triple<Set<String>, Set<String>, Map<String, Double>> {
        val tracks = LinkedHashSet<String>()
        val albums = LinkedHashSet<String>()
        val bpmByTitle = HashMap<String, Double>()

        val dId = artist.deezerId
        if (dId != null && dId > 0) {
            val topTracks = runCatching { deezerClient.getArtistTopTracks(dId, limit = 100) }.getOrDefault(emptyList())
            for (t in topTracks) {
                addTitle(tracks, t.title)
                addTitle(tracks, t.titleShort)
                collectBpm(bpmByTitle, t)
            }

            val releases = runCatching { deezerClient.getArtistAlbums(dId, limit = 100) }.getOrDefault(emptyList())
            val official = releases.filter { it.recordType in OFFICIAL_RECORD_TYPES }
            for (alb in official) addTitle(albums, alb.title)

            coroutineScope {
                val gate = Semaphore(6)
                official.take(MAX_ALBUM_TRACKS_FETCHED).map { album ->
                    async {
                        gate.withPermit { runCatching { deezerClient.getAlbumTracks(album.id) }.getOrDefault(emptyList()) }
                    }
                }.awaitAll().flatten().forEach { t ->
                    addTitle(tracks, t.title)
                    addTitle(tracks, t.titleShort)
                    collectBpm(bpmByTitle, t)
                }
            }
        }

        val mbid = artist.mbid
        if (!mbid.isNullOrBlank()) {
            val recordings = runCatching { mbClient.getArtistRecordings(mbid) }.getOrDefault(emptyList())
            recordings.forEach { addTitle(tracks, it) }
            val rgs = runCatching { mbClient.getArtistReleaseGroups(mbid) }.getOrDefault(emptyList())
            for (rg in rgs) {
                if (rg.primaryType in OFFICIAL_MB_TYPES || rg.primaryType == null) addTitle(albums, rg.title)
            }
        }
        return Triple(tracks, albums, bpmByTitle)
    }

    suspend fun getVerifiedTrackTitles(artist: DisambiguatedArtist): Set<String> {
        ensureLoaded(artist)
        return verifiedTracksCache[artist.canonicalKey].orEmpty()
    }

    suspend fun getVerifiedAlbumTitles(artist: DisambiguatedArtist): Set<String> {
        ensureLoaded(artist)
        return verifiedAlbumsCache[artist.canonicalKey].orEmpty()
    }

    /** The tempo a catalogue states for [title], when one does; null means none. */
    suspend fun bpmOf(artist: DisambiguatedArtist, title: String): Double? {
        ensureLoaded(artist)
        val map = bpmCache[artist.canonicalKey] ?: return null
        return map[TrackIdentity.titleKey(title)] ?: map[TrackIdentity.baseKey(title)]
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

    fun isKnownCompilationOrPlaylist(title: String): Boolean = CompilationTitles.matches(title)

    fun isGenericAlbum(title: String): Boolean = title.lowercase().trim() in GENERIC_ALBUM_NAMES

    private fun addTitle(into: MutableSet<String>, rawTitle: String) {
        val clean = rawTitle.trim()
        if (clean.isEmpty()) return
        val tKey = TrackIdentity.titleKey(clean)
        if (tKey.isNotEmpty()) into += tKey
        val bKey = TrackIdentity.baseKey(clean)
        if (bKey.isNotEmpty()) into += bKey
    }

    private fun collectBpm(into: MutableMap<String, Double>, t: DeezerTrackInfo) {
        if (t.bpm <= 0) return
        listOf(t.title, t.titleShort).forEach { title ->
            TrackIdentity.titleKey(title).takeIf { it.isNotEmpty() }?.let { into[it] = t.bpm.toDouble() }
            TrackIdentity.baseKey(title).takeIf { it.isNotEmpty() }?.let { into[it] = t.bpm.toDouble() }
        }
    }

    private fun encode(set: Set<String>): String = set.joinToString("\u0000")
    private fun decode(json: String): Set<String> = json.split('\u0000').filter { it.isNotEmpty() }.toSet()

    private fun encodeBpm(map: Map<String, Double>): String =
        map.entries.joinToString("\u0000") { "${it.key}=${it.value}" }

    private fun decodeBpm(json: String): Map<String, Double> =
        json.split('\u0000').mapNotNull { part ->
            val i = part.lastIndexOf('=')
            val key = if (i > 0) part.substring(0, i) else return@mapNotNull null
            val value = part.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null
            if (key.isEmpty()) null else key to value
        }.toMap()

    private companion object {
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        val OFFICIAL_RECORD_TYPES = setOf("album", "single", "ep", "compile")
        val OFFICIAL_MB_TYPES = setOf("Album", "Single", "EP")
        const val MAX_ALBUM_TRACKS_FETCHED = 50
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
