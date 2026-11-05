package com.ikko.aura.viewmodel

import androidx.lifecycle.SavedStateHandle

/**
 * The part of the search that has to outlive the process: what was typed, and which filter was on.
 *
 * The search field keeps its text with `rememberSaveable`, so after the system has killed the app in the background
 * the field came back filled in while the view model, rebuilt empty, showed no results for it. Kept in the
 * [SavedStateHandle], the view model starts again from the same query and the two agree.
 */
internal class SearchSavedState(private val handle: SavedStateHandle) {

    var query: String
        get() = handle.get<String>(KEY_QUERY).orEmpty()
        set(value) {
            // A pasted novel is not worth putting in the saved state: the Binder transaction holding it is limited.
            handle[KEY_QUERY] = value.take(MAX_SAVED_QUERY)
        }

    /** Stored by name, so a filter renamed or removed in an update falls back to [SearchFilter.ALL] instead of crashing. */
    var filter: SearchFilter
        get() = handle.get<String>(KEY_FILTER)
            ?.let { name -> SearchFilter.entries.firstOrNull { it.name == name } }
            ?: SearchFilter.ALL
        set(value) {
            handle[KEY_FILTER] = value.name
        }

    companion object {
        const val KEY_QUERY = "search_query"
        const val KEY_FILTER = "search_filter"
        const val MAX_SAVED_QUERY = 512
    }
}
