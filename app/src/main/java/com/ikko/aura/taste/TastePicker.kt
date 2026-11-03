package com.ikko.aura.taste

import androidx.compose.runtime.Immutable
import com.ikko.aura.reco.ArtistNames

/** An artist offered as a bubble. [key] is what makes two spellings of one artist the same bubble (see [ArtistNames.key]). */
@Immutable
data class PickerArtist(val name: String) {
    val key: String = ArtistNames.key(name)

    /** A credit line ("A feat. B") and not an artist: never a bubble. */
    val isCredit: Boolean get() = CREDIT.containsMatchIn(name)

    private companion object { val CREDIT = Regex("\\b(?:feat\\.?|ft\\.?|featuring)\\b", RegexOption.IGNORE_CASE) }
}

/** What the screen draws. */
@Immutable
data class TastePickerUiState(
    val artists: List<PickerArtist> = emptyList(),
    /** Keys of the artists picked. */
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    /** How many lookups of similar artists are running: the grid is growing. */
    val expanding: Int = 0,
    val saving: Boolean = false,
    /** True once the choice is kept: the screen goes back. */
    val saved: Boolean = false,
    /** What is typed in the search field; blank shows the grid itself. */
    val query: String = "",
    val results: List<PickerArtist> = emptyList(),
    val searching: Boolean = false,
    /** Keys of the artists blocked here (a long press): they stay on the grid, marked, so that the block can be taken back. */
    val blocked: Set<String> = emptySet(),
    /**
     * How many artists are asked for before the choice can be confirmed: [MIN_SELECTION] to build a first base, then one (a later
     * test only adds to a base that exists).
     */
    val minSelection: Int = MIN_SELECTION,
) {
    /** What the grid shows: the search's answer while one is typed, else the grown grid. */
    val shown: List<PickerArtist> get() = if (query.isBlank()) artists else results

    val remaining: Int get() = (minSelection - selected.size).coerceAtLeast(0)
    val canConfirm: Boolean get() = remaining == 0 && !saving

    companion object {
        const val MIN_SELECTION = 5
        /** What a refining test asks for once a base exists. */
        const val MIN_REFINE = 1
    }
}

/** The pure part of the grid's growth, so that uniqueness and order are tested on their own. */
object TastePickerGrid {

    /**
     * [added] go right after the artist they were found for, in the order given, without any artist the grid already has (under
     * any spelling) and without twice the same one. An anchor that is not in the grid sends them to the end.
     */
    fun insertAfter(artists: List<PickerArtist>, anchorKey: String, added: List<PickerArtist>): List<PickerArtist> {
        val have = artists.mapTo(HashSet()) { it.key }
        val fresh = added.filter { it.key.isNotEmpty() && !it.isCredit && have.add(it.key) }
        if (fresh.isEmpty()) return artists
        val at = artists.indexOfFirst { it.key == anchorKey }
        return if (at < 0) artists + fresh else artists.take(at + 1) + fresh + artists.drop(at + 1)
    }

    /** A first grid: names without duplicates, in order. */
    fun starters(names: List<String>): List<PickerArtist> =
        names.map(::PickerArtist).filter { it.key.isNotEmpty() && !it.isCredit }.distinctBy { it.key }

    /** A picked artist that is not in the grid yet (found by search) joins it at the top. */
    fun withArtist(artists: List<PickerArtist>, artist: PickerArtist): List<PickerArtist> =
        if (artists.any { it.key == artist.key }) artists else listOf(artist) + artists

    /**
     * What a search shows: the artists close to the already chosen ones it landed on come first, then what matched; never an artist
     * already chosen in an earlier test, nor a blocked one, nor twice the same.
     */
    fun searchResults(found: List<PickerArtist>, picked: Set<String>, blocked: Set<String>, closeToPicked: List<PickerArtist>): List<PickerArtist> =
        (closeToPicked + found).filter { it.key.isNotEmpty() && !it.isCredit && it.key !in picked && it.key !in blocked }.distinctBy { it.key }

    fun toggle(selected: Set<String>, key: String): Set<String> = if (key in selected) selected - key else selected + key
}
