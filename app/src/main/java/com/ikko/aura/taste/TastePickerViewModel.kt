package com.ikko.aura.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The taste picker: a grid of artist bubbles that grows as artists are picked. Each pick looks up the artists close to it and
 * slips them into the grid beside it; picking again takes the choice back (what was added stays, so the grid never jumps).
 */
@HiltViewModel
class TastePickerViewModel @Inject constructor(private val catalog: TasteCatalog) : ViewModel() {

    /** Whether the personalised recommendations are on: the playlists are then made again after a choice. */
    val consent: StateFlow<Boolean> = catalog.consent.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(TastePickerUiState())
    val state: StateFlow<TastePickerUiState> = _state.asStateFlow()

    /** Artists already expanded: asking again for the same one changes nothing. */
    private val expanded = HashSet<String>()
    private var blocked: Set<String> = emptySet()
    /** What an earlier test already offered or chose: never offered again. */
    private var seen: Set<String> = emptySet()
    /** Keys of the artists chosen in earlier tests (and in Réglages): a search never offers them again. */
    private var picked: Set<String> = emptySet()
    private val query = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun watchQuery() {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) { _state.update { it.copy(results = emptyList(), searching = false) }; return@collectLatest }
                _state.update { it.copy(searching = true) }
                val matched = TastePickerGrid.starters(catalog.search(q))
                // The search landed on an artist already chosen: it is not shown again, its neighbours are.
                val close = matched.filter { it.key in picked }.take(2)
                    .flatMap { catalog.similar(it.name).map(::PickerArtist) }.take(12)
                val found = TastePickerGrid.searchResults(matched, picked, blocked, close)
                _state.update { if (it.query == q) it.copy(results = found, searching = false) else it }
            }
        }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text, searching = text.isNotBlank()) }
        query.value = text.trim()
    }

    init {
        watchQuery()
        viewModelScope.launch {
            blocked = runCatching { catalog.blockedKeys() }.getOrDefault(emptySet())
            picked = runCatching { catalog.alreadyPicked() }.getOrDefault(emptySet())
            seen = catalog.alreadyShown() + picked
            fun fresh(names: List<String>) = TastePickerGrid.starters(names).filter { it.key !in blocked && it.key !in seen }
            val pool = catalog.starters(STARTERS * 8)
            var first = fresh(pool).take(STARTERS)
            // Every name of the usual list has been seen: the grid is made of what is close to what was chosen.
            if (first.size < STARTERS) {
                val names = runCatching { catalog.nextFrom(catalog.pickedNames(), STARTERS) }.getOrDefault(emptyList())
                first = TastePickerGrid.insertAfter(first, "", fresh(names)).take(STARTERS)
            }
            // Still short: what an earlier test showed without anyone picking it comes back, rather than a nearly empty grid.
            if (first.size < STARTERS) {
                val have = first.mapTo(HashSet()) { it.key }
                val again = TastePickerGrid.starters(pool).filter { it.key !in blocked && it.key !in picked && have.add(it.key) }
                first = (first + again).take(STARTERS)
            }
            catalog.markShown(first.map { it.key })
            // The first test builds a base (five artists); once one exists, a test may add as few as one.
            val min = if (catalog.pickedNames().isEmpty()) TastePickerUiState.MIN_SELECTION else TastePickerUiState.MIN_REFINE
            _state.update { it.copy(artists = first, loading = false, minSelection = min) }
        }
    }

    /** A long press: the artist is blocked (never proposed again, in any playlist), or let back if it already was. */
    fun toggleBlock(artist: PickerArtist) {
        val blocking = artist.key !in _state.value.blocked
        _state.update { s ->
            s.copy(blocked = if (blocking) s.blocked + artist.key else s.blocked - artist.key, selected = if (blocking) s.selected - artist.key else s.selected)
        }
        blocked = if (blocking) blocked + artist.key else blocked - artist.key
        viewModelScope.launch { runCatching { if (blocking) catalog.block(artist.name) else catalog.unblock(artist.key) } }
        // Whoever sounds like an artist the listener blocks is likely not wanted either: they come in beside it, to be blocked in turn.
        if (blocking && expanded.add(artist.key)) expand(artist)
    }

    fun toggle(artist: PickerArtist) {
        // A blocked artist cannot be picked: the block is taken back first, with a long press.
        if (artist.key in _state.value.blocked) return
        val picking = artist.key !in _state.value.selected
        _state.update { it.copy(selected = TastePickerGrid.toggle(it.selected, artist.key), artists = if (picking) TastePickerGrid.withArtist(it.artists, artist) else it.artists) }
        if (picking && expanded.add(artist.key)) expand(artist)
    }

    private fun expand(artist: PickerArtist) {
        viewModelScope.launch {
            _state.update { it.copy(expanding = it.expanding + 1) }
            val near = catalog.similar(artist.name).map(::PickerArtist)
            fun usable(a: PickerArtist) = a.key !in blocked && a.key !in seen && a.key != artist.key
            var close = near.filter(::usable).take(PER_PICK)
            // Too few that are new (most were shown in earlier tests): the neighbours of the closest ones are looked up too.
            if (close.size < MIN_NEW) {
                val have = close.mapTo(HashSet()) { it.key }
                val deeper = near.take(3).flatMap { n -> catalog.similar(n.name).map(::PickerArtist) }
                    .filter { usable(it) && have.add(it.key) }
                close = (close + deeper).take(PER_PICK)
            }
            catalog.markShown(close.map { it.key })
            _state.update { s ->
                // While a search is shown, the artists close to the one picked come into its list too, right after it.
                val results = if (s.results.any { it.key == artist.key }) TastePickerGrid.insertAfter(s.results, artist.key, close) else s.results
                s.copy(artists = TastePickerGrid.insertAfter(s.artists, artist.key, close), results = results, expanding = s.expanding - 1)
            }
        }
    }

    fun confirm() {
        val s = _state.value
        if (!s.canConfirm) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val names = (s.artists + s.results).distinctBy { it.key }.filter { it.key in s.selected }.map { it.name }
            runCatching { catalog.save(names) }
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    private companion object {
        const val STARTERS = 15
        const val PER_PICK = 6
        const val MIN_NEW = 3
    }
}
