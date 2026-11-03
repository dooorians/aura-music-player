package com.ikko.aura.viewmodel

import com.ikko.aura.data.model.Track
import com.ikko.aura.reco.authority.ArtistDiscographies
import com.ikko.aura.source.ytm.YtmAlbumRef
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val _state = MutableStateFlow(YtmArtistState(name = name))
    val state: StateFlow<YtmArtistState> = _state.asStateFlow()
    private var started = false

    /** The albums the page lacked of the artist's real discography, found afterwards: they join the page as they come. */
    private fun completeLater(shown: List<YtmAlbumRef>) {
        scope.launch(Dispatchers.IO) {
            val more = runCatching { discographies.completion(name, shown) }.getOrNull() ?: return@launch
            if (more.albums.isEmpty()) return@launch
            _state.value = _state.value.let { s ->
                s.copy(albums = (s.albums + more.albums).distinctBy { it.id }, types = s.types + more.types, years = s.years + more.years)
            }
        }
    }

    private fun present(tracks: List<Track>, albums: List<YtmAlbumRef>) {
        val (mine, theirAlbums) = discographies.byName(name, tracks, albums)
        _state.value = YtmArtistState(
            loading = false,
            name = name,
            coverUrl = mine.firstOrNull { it.artworkUri != null }?.artworkUri?.toString() ?: theirAlbums.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
            tracks = mine,
            albums = theirAlbums,
        )
        scope.launch(Dispatchers.IO) {
            val page = runCatching { discographies.verify(name, mine, theirAlbums) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(tracks = page.tracks, albums = page.albums, types = page.types, years = page.years)
            completeLater(page.albums)
        }
    }

    /** Loads the page once: asking again while it is there does nothing. */
    fun start() {
        if (started) return
        started = true
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
                        songs.await() to albums.await()
                    }
                }
            }.getOrNull()
            if (result == null || (result.first.isEmpty() && result.second.isEmpty())) {
                _state.value = YtmArtistState(loading = false, failed = result == null, name = name)
            } else {
                present(result.first.map(YtmLibrary::trackOf), result.second)
            }
        }
    }
}
