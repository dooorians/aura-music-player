package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.reco.ArtistNames
import com.ikko.aura.reco.authority.ArtistDiscographies
import com.ikko.aura.reco.authority.Discography
import com.ikko.aura.source.ytm.YtmClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The other side of an artist's page: the source switch (En ligne / Local) changes the songs and the albums of the page that is on screen,
 * in place. A page of the library gets its online side from here, a page of the sources gets its library side.
 */
@HiltViewModel
class ArtistLinksViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val client: YtmClient,
    private val discographies: ArtistDiscographies,
    private val engine: com.ikko.aura.reco.RecommendationEngine,
) : ViewModel() {
    /** The keys of the artists the listener likes (the taste picker's choice, the ones typed in) and of the ones they blocked. */
    val likedKeys: StateFlow<Set<String>> = engine.seedArtists
        .map { names -> names.mapTo(HashSet()) { ArtistNames.primaryKey(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val blockedKeys: StateFlow<Set<String>> = engine.blockedArtists
        .map { list -> list.mapTo(HashSet()) { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Likes an artist (everything that follows weighs it like a deliberate choice), or takes the like back. A liked artist is let back if blocked. */
    fun toggleLike(name: String) {
        val key = ArtistNames.primaryKey(name)
        viewModelScope.launch {
            if (key in likedKeys.value) engine.setSeedArtists(engine.seedArtists.first().filterNot { ArtistNames.primaryKey(it) == key })
            else {
                if (key in blockedKeys.value) engine.unblockArtist(key)
                engine.artistsPicked(listOf(name))
            }
        }
    }

    /** Blocks an artist (no song, album or radio of theirs is proposed), or lets them back. Blocking takes the like back. */
    fun toggleBlock(name: String) {
        val key = ArtistNames.primaryKey(name)
        viewModelScope.launch {
            if (key in blockedKeys.value) engine.unblockArtist(key)
            else {
                if (key in likedKeys.value) engine.setSeedArtists(engine.seedArtists.first().filterNot { ArtistNames.primaryKey(it) == key })
                engine.blockArtist(name)
            }
        }
    }

    private var onlinePage: OnlineArtistPage? = null
    private var onlineName: String? = null

    fun localIdOf(name: String): Long? {
        val key = ArtistNames.primaryKey(ArtistNames.askName(name))
        if (key.isEmpty()) return null
        return repository.artists.value.firstOrNull { ArtistNames.primaryKey(it.name) == key }?.id
    }

    /** The artist's page on the sources, loaded once when first asked for. */
    fun online(name: String): StateFlow<YtmArtistState> {
        val page = onlinePage?.takeIf { onlineName == name } ?: OnlineArtistPage(name, client, discographies, viewModelScope).also {
            onlinePage = it
            onlineName = name
        }
        page.start()
        return page.state
    }

    private val discography = MutableStateFlow<Discography?>(null)
    private var localFlow: StateFlow<ArtistUiState?>? = null
    private var localName: String? = null

    /** The artist's page in the library, null while the library has no artist of that name. */
    fun local(name: String): StateFlow<ArtistUiState?> {
        localFlow?.takeIf { localName == name }?.let { return it }
        localName = name
        viewModelScope.launch(Dispatchers.IO) { discography.value = runCatching { discographies.of(name) }.getOrNull() }
        return combine(repository.tracks, repository.albums, repository.artists, discography) { tracks, albums, _, d ->
            localIdOf(name)?.let { id -> localArtistUiState(id, tracks, albums, d, emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null).also { localFlow = it }
    }
}
