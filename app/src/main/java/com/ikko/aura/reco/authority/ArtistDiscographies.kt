package com.ikko.aura.reco.authority

import com.ikko.aura.data.deezer.DeezerClient
import com.ikko.aura.data.musicbrainz.MusicBrainzClient
import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.TrackIdentity
import com.ikko.aura.source.ytm.YtmAlbumRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ReleaseType { ALBUM, SINGLE, EP }

/** One release of an artist: its title, what it is, the year it came out and its cover (null when only MusicBrainz knows it). */
class Release(val title: String, val type: ReleaseType, val year: Int, val cover: String?)

/** What an artist really released, by title: Deezer's and MusicBrainz's lists for the artist that Last.fm, Deezer and MusicBrainz agree on. */
class Discography(val artist: DisambiguatedArtist, val releases: List<Release>) {
    private val byKey: Map<String, Release> = LinkedHashMap<String, Release>().also { m ->
        releases.forEach { r ->
            TrackIdentity.titleKey(r.title).takeIf { it.isNotEmpty() }?.let { m.putIfAbsent(it, r) }
            TrackIdentity.baseKey(r.title).takeIf { it.isNotEmpty() }?.let { m.putIfAbsent(it, r) }
        }
    }

    fun find(title: String): Release? = byKey[TrackIdentity.titleKey(title)] ?: byKey[TrackIdentity.baseKey(title)]
    fun typeOf(title: String): ReleaseType? = find(title)?.type
    fun yearOf(title: String): Int = find(title)?.year ?: 0
}

/** A page of an artist once checked: only what is theirs, the type and the year of each release (by the album's id). */
class VerifiedPage(
    val tracks: List<Track>,
    val albums: List<YtmAlbumRef>,
    val types: Map<String, ReleaseType>,
    val years: Map<String, Int> = emptyMap(),
)

/**
 * Before a page shows an artist's songs, singles and albums, they are checked against the discography of the right artist: a search
 * by name answers with namesakes ("Ariana" and "Ariana Grande") and with covers, and none of that is theirs.
 */
@Singleton
class ArtistDiscographies @Inject constructor(
    private val authority: ArtistAuthority,
    private val deezer: DeezerClient,
    private val mb: MusicBrainzClient,
    private val verifier: DiscographyVerifier,
    private val ytm: com.ikko.aura.source.ytm.YtmClient,
) {
    private class Entry(val discography: Discography?)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Discography?>>()

    /** The artist's discography, or null when the artist cannot be told from a namesake (then nothing is verified, and nothing is invented). */
    suspend fun of(name: String): Discography? {
        val asked = ArtistNames.primary(ArtistNames.askName(name), beforeAmpersand = true)
        val key = ArtistNames.key(asked)
        if (key.isEmpty()) return null
        cache[key]?.let { return it.discography }
        // One lookup at a time for one artist: the page asks as it opens and again when it checks what it found.
        val mine = CompletableDeferred<Discography?>()
        inFlight.putIfAbsent(key, mine)?.let { return it.await() }
        try {
            val answer = runCatching { build(asked, key) }
            // A failed answer (no network, a quota) is not kept: the next opening asks again. A namesake, or an unknown artist, is kept.
            val found = answer.getOrNull()
            if (answer.isSuccess && cache.size < MAX_CACHE) cache[key] = Entry(found)
            mine.complete(found)
            return found
        } finally {
            if (!mine.isCompleted) mine.complete(null)
            inFlight.remove(key)
        }
    }

    /** Starts the lookup of an artist's discography at once, in the background: what a page checks its findings against is ready when it needs it. */
    fun prefetch(scope: CoroutineScope, name: String) {
        scope.launch(Dispatchers.IO) { runCatching { of(name) } }
    }

    /** At once, with no network: what the name alone allows (the artist is one of the credits, not a name that merely contains theirs). */
    fun byName(name: String, tracks: List<Track>, albums: List<YtmAlbumRef>): Pair<List<Track>, List<YtmAlbumRef>> {
        val key = ArtistNames.primaryKey(ArtistNames.askName(name))
        return tracks.filter { isOf(key, it.artist, it.title) } to albums.filter { isOf(key, it.artist, "") }
    }

    private suspend fun build(asked: String, key: String): Discography? {
        val artist = authority.resolve(asked) ?: return null
        // The artist found must be the one asked for, by name: "Ariana" is not "Ariana Grande".
        if (artist.canonicalKey != key) return null
        val raw = ArrayList<Release>()
        fun yearOf(date: String?) = date?.take(4)?.toIntOrNull() ?: 0
        artist.deezerId?.takeIf { it > 0 }?.let { id ->
            deezer.artistReleases(id, limit = 100).forEach { a ->
                val type = when (a.type) { "ep" -> ReleaseType.EP; "single" -> ReleaseType.SINGLE; else -> ReleaseType.ALBUM }
                raw += Release(a.title, type, yearOf(a.releaseDate), a.cover.takeIf { it.isNotBlank() })
            }
        }
        artist.mbid?.takeIf { it.isNotBlank() }?.let { mbid ->
            mb.getArtistReleaseGroups(mbid).forEach { rg ->
                val type = when (rg.primaryType) { "Album" -> ReleaseType.ALBUM; "EP" -> ReleaseType.EP; "Single" -> ReleaseType.SINGLE; else -> null }
                if (type != null) raw += Release(rg.title, type, yearOf(rg.firstReleaseDate), null)
            }
        }
        val releases = mergeReleases(raw)
        return if (releases.isEmpty()) null else Discography(artist, releases)
    }

    /**
     * [tracks] and [albums] a search by [name] answered with, kept to what is the artist's: first by name (the artist must be one of the
     * credits, not a name that merely holds theirs), then by the discography when the artist is known. When the discography leaves
     * nothing, what the name allowed stays: a page is never emptied by a catalogue that does not know the artist yet.
     */
    suspend fun verify(name: String, tracks: List<Track>, albums: List<YtmAlbumRef>): VerifiedPage {
        val key = ArtistNames.primaryKey(ArtistNames.askName(name))
        val byName = tracks.filter { isOf(key, it.artist, it.title) }
        val albumsByName = albums.filter { isOf(key, it.artist, "") }
        val d = of(name) ?: return VerifiedPage(byName, albumsByName, emptyMap())
        val keptAlbums = albumsByName.filter { d.typeOf(it.title) != null }
        val keptTracks = byName.filter { t -> verifier.isOfficialTrackAndRelease(d.artist, t.title, realAlbum(t.album)) }
        val finalAlbums = keptAlbums.ifEmpty { albumsByName }
        val types = finalAlbums.mapNotNull { a -> d.typeOf(a.title)?.let { a.id to it } }.toMap()
        val years = finalAlbums.mapNotNull { a -> d.yearOf(a.title).takeIf { it > 0 }?.let { a.id to it } }.toMap()
        return VerifiedPage(keptTracks.ifEmpty { byName }, finalAlbums, types, years)
    }

    /** What a page lacked of the real discography, found on the source afterwards: albums, with their type and their year. */
    class Completion(val albums: List<YtmAlbumRef>, val types: Map<String, ReleaseType>, val years: Map<String, Int>)

    /** The albums an artist's page lacks (by [shown]), found on the source: the page is the artist's whole discography, not what a search gave. */
    suspend fun completion(name: String, shown: Collection<YtmAlbumRef>): Completion {
        val d = of(name) ?: return Completion(emptyList(), emptyMap(), emptyMap())
        val found = missingOnSource(d, shown.map { it.title })
        return Completion(
            found,
            found.mapNotNull { a -> d.typeOf(a.title)?.let { a.id to it } }.toMap(),
            found.mapNotNull { a -> d.yearOf(a.title).takeIf { it > 0 }?.let { a.id to it } }.toMap(),
        )
    }

    /**
     * The releases of [d] that none of [haveTitles] is, with a cover, found on YouTube Music: albums first, then EPs and singles, the most
     * recent first within each. What was found when time runs out is kept: a slow search loses the last ones, not all of them. A release the
     * source does not have is left out: it could not be played.
     */
    suspend fun missingOnSource(d: Discography, haveTitles: Collection<String>): List<YtmAlbumRef> {
        if (!ytm.isEnabled()) return emptyList()
        val have = haveTitles.flatMapTo(HashSet()) { listOf(TrackIdentity.titleKey(it), TrackIdentity.baseKey(it)) }
        val wanted = d.releases.filter { r ->
            r.cover != null && TrackIdentity.titleKey(r.title) !in have && TrackIdentity.baseKey(r.title) !in have &&
                !verifier.isKnownCompilationOrPlaylist(r.title)
        }.sortedWith(compareBy<Release> { it.type.ordinal }.thenByDescending { it.year }).take(MAX_MISSING)
        if (wanted.isEmpty()) return emptyList()
        val gate = Semaphore(4)
        val done = java.util.Collections.synchronizedList(ArrayList<YtmAlbumRef>())
        withTimeoutOrNull(MISSING_WAIT_MS) {
            coroutineScope {
                wanted.map { r -> async { gate.withPermit { findOnSource(r, d.artist.canonicalName)?.let { done += it } } } }.awaitAll()
            }
        }
        return done.toList().distinctBy { it.id }
    }

    private suspend fun findOnSource(r: Release, artistName: String): YtmAlbumRef? = withContext(Dispatchers.IO) {
        val key = ArtistNames.key(artistName)
        val want = TrackIdentity.baseKey(r.title)
        runCatching { ytm.searchAlbums("${r.title} $artistName") }.getOrDefault(emptyList())
            .firstOrNull { a -> TrackIdentity.baseKey(a.title) == want && key in ArtistNames.split(a.artist) }
            ?.let { a -> YtmAlbumRef(a.id, r.title, artistName, r.cover ?: a.thumbnailUrl) }
    }

    private fun realAlbum(album: String): String? =
        album.trim().takeIf { it.isNotEmpty() && !it.equals("YouTube Music", true) && !it.equals("JioSaavn", true) && ':' !in it }

    companion object {
        /**
         * One release for what the catalogues list several times: a single and the album of the same name, the editions of an album
         * ("(Deluxe)", a reissue). The fullest kind is kept (album, then EP, then single), under its plain title, with its own date: the
         * album's, not the single that came before it ("thank u, next" the song is 2018, the album 2019); between two editions of the same
         * kind, the first one's. The most recent first.
         */
        fun mergeReleases(raw: List<Release>): List<Release> {
            val byTitle = LinkedHashMap<String, Release>()
            for (r in raw) {
                val k = TrackIdentity.baseKey(r.title)
                if (k.isEmpty()) continue
                val old = byTitle[k]
                byTitle[k] = if (old == null) r else {
                    val better = r.type.ordinal < old.type.ordinal
                    Release(
                        // The plain title, not the one of an edition.
                        title = if (better || (r.type == old.type && r.title.length < old.title.length)) r.title else old.title,
                        type = if (better) r.type else old.type,
                        year = when {
                            better -> r.year.takeIf { it > 0 } ?: old.year
                            r.type == old.type -> listOf(old.year, r.year).filter { it > 0 }.minOrNull() ?: 0
                            else -> old.year.takeIf { it > 0 } ?: r.year
                        },
                        cover = if (better && r.cover != null) r.cover else old.cover ?: r.cover,
                    )
                }
            }
            return byTitle.values.sortedByDescending { it.year }
        }

        private const val MAX_CACHE = 200
        private const val MAX_MISSING = 40
        private const val MISSING_WAIT_MS = 25_000L

        /** Whether the artist whose key is [key] is among the first credits of a song or an album: not a name that only contains theirs. */
        private fun isOf(key: String, artist: String, title: String): Boolean =
            key.isNotEmpty() && key in ArtistNames.creditsOf(artist, title).take(3)
    }
}
