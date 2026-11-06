package com.ikko.aura.viewmodel

import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.authority.ArtistDiscographies
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmSong
import kotlinx.coroutines.awaitAll
import com.ikko.aura.source.ytm.YtmLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An artist's page on the sources, loaded into [state]: the page at once with what the name alone allows, then checked against the
 * artist's real discography (what is not theirs goes, the type and the year of each release come in), then completed with the albums
 * it lacked. Used by the online page itself and by the library's page when its switch goes to "En ligne": the same page, other songs.
 */
class OnlineArtistPage(
    private val name: String,
    private val client: YtmClient,
    private val discographies: ArtistDiscographies,
    private val scope: CoroutineScope,
    /** Where the artists close to this one come from (free, no key); none: the page has no such section. */
    private val deezer: com.ikko.aura.data.deezer.DeezerClient? = null,
) {
    // A page already loaded in this run is there at once, with everything it had: no placeholders for what is already known.
    private val cached = ArtistPageCache.get(name)
    private val _state = MutableStateFlow(cached ?: YtmArtistState(name = name))
    val state: StateFlow<YtmArtistState> = _state.asStateFlow()
    private var started = false

    init {
        scope.launch { _state.collect { if (!it.loading && !it.failed) ArtistPageCache.put(name, it) } }
    }

    /** What a song is called, for comparing it with the same song elsewhere: what is in brackets or after a dash is left out. */
    private fun norm(s: String) = s.lowercase().replace(ASIDE, "").replace(AFTER_DASH, "").filter { it.isLetterOrDigit() }

    /**
     * The line under each of the artist's first songs, complete ("Album - Single · 2026", "Album · 2018"), by the song's name: asked of
     * the catalogue with the page, so the lines are there with the rows and not filled in, in two steps, afterwards. The album comes
     * from the artist's popular songs (or a search for the song when it is not among them), its kind and its year from the artist's
     * list of releases.
     */
    private suspend fun deezerSubtitles(songs: List<YtmSong>): Map<String, String> {
        val source = deezer ?: return emptyMap()
        val key = com.ikko.aura.reco.ArtistNames.primaryKey(name)
        val me = runCatching { source.searchArtist(name, 5) }.getOrNull()
            ?.firstOrNull { com.ikko.aura.reco.ArtistNames.primaryKey(it.name) == key } ?: return emptyMap()
        val releases = runCatching { source.getArtistAlbums(me.id, 100) }.getOrDefault(emptyList())
        fun subtitleOf(albumTitle: String): String {
            val release = releases.firstOrNull { norm(it.title) == norm(albumTitle) }
            val kind = when (release?.recordType) { "single" -> " - Single"; "ep" -> " - EP"; else -> "" }
            val year = release?.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            return albumTitle + kind + (year?.let { " · $it" } ?: "")
        }
        val out = LinkedHashMap<String, String>()
        for (t in runCatching { source.getArtistTopTracks(me.id, 100) }.getOrDefault(emptyList())) {
            val album = t.albumTitle?.takeIf { it.isNotBlank() } ?: continue
            val line = subtitleOf(album)
            out.putIfAbsent(norm(t.titleShort.ifBlank { t.title }), line)
            out.putIfAbsent(norm(t.title), line)
        }
        // The songs shown first that are not among the popular ones: asked one by one, together.
        val missing = songs.filter { norm(it.title) !in out }.take(6)
        if (missing.isNotEmpty()) {
            coroutineScope {
                missing.map { song -> async { song to runCatching { source.songAlbumOf(song.title, name) }.getOrNull() } }.awaitAll()
            }.forEach { (song, album) -> if (!album.isNullOrBlank()) out.putIfAbsent(norm(song.title), subtitleOf(album)) }
        }
        return out
    }

    /** The albums the page lacked of the artist's real discography, found afterwards: they join the page as they come. */
    private fun completeLater(shown: List<YtmAlbumRef>) {
        scope.launch(Dispatchers.IO) {
            val more = runCatching { discographies.completion(name, shown) }.getOrNull() ?: return@launch
            if (more.albums.isEmpty()) return@launch
            _state.update { s ->
                s.copy(albums = (s.albums + more.albums).distinctBy { it.id }, types = s.types + more.types, years = s.years + more.years)
            }
        }
    }

    /** The artists the catalogue lists as close to this one, with their pictures; they join the page when found. */
    private fun findSimilar() {
        val source = deezer ?: return
        scope.launch(Dispatchers.IO) {
            val key = com.ikko.aura.reco.ArtistNames.primaryKey(name)
            val me = runCatching { source.searchArtist(name, 5) }.getOrNull()
                ?.firstOrNull { com.ikko.aura.reco.ArtistNames.primaryKey(it.name) == key } ?: return@launch
            val close = runCatching { source.getRelatedArtists(me.id, 20) }.getOrDefault(emptyList())
                .filter { com.ikko.aura.reco.ArtistNames.primaryKey(it.name) != key }
            if (close.isEmpty()) return@launch
            _state.update { it.copy(similar = close.map { SimilarArtist(it.name, it.pictureXl) }) }
        }
    }

    private fun present(tracks: List<Track>, albums: List<YtmAlbumRef>, subtitleByTitle: Map<String, String>) {
        findSimilar()
        val (mine, theirAlbums) = discographies.byName(name, tracks, albums)
        _state.value = YtmArtistState(
            loading = false,
            name = name,
            coverUrl = mine.firstOrNull { it.artworkUri != null }?.artworkUri?.toString() ?: theirAlbums.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
            tracks = mine,
            albums = theirAlbums,
            // The albums of the songs come with the page itself: their lines are there with the rows.
            subtitleOf = mine.mapNotNull { t -> subtitleByTitle[norm(t.title)]?.let { t.id to it } }.toMap(),
            // Close artists may already have come in (they are asked for as the page begins): the page does not drop them.
            similar = _state.value.similar,
        )
        scope.launch(Dispatchers.IO) {
            val page = runCatching { discographies.verify(name, mine, theirAlbums) }.getOrNull()
            if (page == null) {
                findAlbums(mine, theirAlbums)
                return@launch
            }
            _state.update { it.copy(tracks = page.tracks, albums = page.albums, types = page.types, years = page.years) }
            completeLater(page.albums)
            findAlbums(page.tracks, page.albums)
        }
    }

    /**
     * A song of a search comes without its album. Where a single bears the song's own name it is that; for the others the
     * albums are opened, the newest first, until each song is found on one. They fill in as they are found.
     */
    private fun findAlbums(tracks: List<Track>, albums: List<YtmAlbumRef>) {
        val kind = Regex("(?i)\\s*[-(\\[]\\s*(single|ep)\\s*[)\\]]?$")
        // What the catalogue already said stays; the songs it did not know are looked for here.
        val found = _state.value.albumOf.filterValues { it.isNotEmpty() }.toMutableMap()
        // The songs whose whole line the catalogue gave are done.
        val told = _state.value.subtitleOf.keys
        for (t in tracks) if (t.id !in found && t.id !in told) {
            albums.firstOrNull { norm(it.title.replace(kind, "")) == norm(t.title) }?.let { found[t.id] = it.title }
        }
        fun publish() { _state.update { it.copy(albumOf = found.toMap()) } }
        publish()
        for (ref in albums.take(25)) {
            if (tracks.all { it.id in found || it.id in told }) break
            val songs = runCatching { client.album(ref.id).songs }.getOrNull() ?: continue
            val titles = songs.map { norm(it.title) }.toSet()
            var changed = false
            for (t in tracks) if (t.id !in found && t.id !in told && norm(t.title) in titles) { found[t.id] = ref.title; changed = true }
            if (changed) publish()
        }
        // What no album held is said so (an empty name), so its line falls back to the artist instead of staying blank.
        for (t in tracks) if (t.id !in found && t.id !in told) found[t.id] = ""
        publish()
    }

    /** Loads the page once: asking again while it is there does nothing. */
    fun start() {
        if (started) return
        started = true
        // Already loaded in this run: it is shown as it was, nothing is fetched again.
        if (cached != null) return
        load()
    }

    fun load() {
        started = true
        _state.value = YtmArtistState(name = name)
        scope.launch {
            // Looked up while the source answers, not after it.
            discographies.prefetch(scope, name)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val songs = async { client.artistSongs(name) }
                        val albums = async { runCatching { client.artistAlbums(name) }.getOrDefault(emptyList()) }
                        // Asked with the page, and not waited for longer than a moment: the lines come with the rows or a little after.
                        val byTitle = async { kotlinx.coroutines.withTimeoutOrNull(3500) { deezerSubtitles(songs.await().take(8)) } ?: emptyMap() }
                        Triple(songs.await(), albums.await(), byTitle.await())
                    }
                }
            }.getOrNull()
            if (result == null || (result.first.isEmpty() && result.second.isEmpty())) {
                _state.value = YtmArtistState(loading = false, failed = result == null, name = name)
            } else {
                present(result.first.map(YtmLibrary::trackOf), result.second, result.third)
            }
        }
    }

    private companion object {
        val ASIDE = Regex("\\s*[(\\[].*?[)\\]]")
        val AFTER_DASH = Regex("\\s+-\\s+.*$")
    }
}

/** The artist pages loaded in this run, by name: a page opened again is there at once, without its placeholders. */
internal object ArtistPageCache {
    private val pages = object : LinkedHashMap<String, YtmArtistState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YtmArtistState>) = size > 24
    }

    @Synchronized fun get(name: String): YtmArtistState? = pages[name.lowercase()]

    @Synchronized fun put(name: String, state: YtmArtistState) { pages[name.lowercase()] = state }
}
