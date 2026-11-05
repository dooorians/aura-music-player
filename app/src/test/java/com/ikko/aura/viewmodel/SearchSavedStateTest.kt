package com.ikko.aura.viewmodel

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSavedStateTest {

    @Test
    fun `a fresh screen starts with no query and every result`() {
        val state = SearchSavedState(SavedStateHandle())
        assertEquals("", state.query)
        assertEquals(SearchFilter.ALL, state.filter)
    }

    @Test
    fun `the query and filter come back after process death`() {
        val before = SearchSavedState(SavedStateHandle())
        before.query = "daft punk"
        before.filter = SearchFilter.ALBUMS

        // What the system hands back to the rebuilt view model: the same keys and values.
        val restored = SearchSavedState(
            SavedStateHandle(
                mapOf(
                    SearchSavedState.KEY_QUERY to before.query,
                    SearchSavedState.KEY_FILTER to SearchFilter.ALBUMS.name,
                ),
            ),
        )
        assertEquals("daft punk", restored.query)
        assertEquals(SearchFilter.ALBUMS, restored.filter)
    }

    @Test
    fun `a filter saved by another version of the app falls back to all`() {
        val state = SearchSavedState(SavedStateHandle(mapOf(SearchSavedState.KEY_FILTER to "PODCASTS")))
        assertEquals(SearchFilter.ALL, state.filter)
    }

    @Test
    fun `a huge pasted query is capped before it goes into the saved state`() {
        val handle = SavedStateHandle()
        SearchSavedState(handle).query = "x".repeat(100_000)
        assertEquals(SearchSavedState.MAX_SAVED_QUERY, handle.get<String>(SearchSavedState.KEY_QUERY)?.length)
    }

    @Test
    fun `clearing the search clears what is saved`() {
        val handle = SavedStateHandle()
        val state = SearchSavedState(handle)
        state.query = "air"
        state.query = ""
        assertEquals("", SearchSavedState(handle).query)
    }
}
