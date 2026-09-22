package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which of Playlists / Albums / Artistes / Titres the library shows: chosen from the capsule, read by the library. */
@Singleton
class LibrarySelection @Inject constructor() {
    private val _filter = MutableStateFlow(LibraryFilter.TRACKS)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()
    fun select(filter: LibraryFilter) { _filter.value = filter }
}

@HiltViewModel
class TabCapsuleViewModel @Inject constructor(val selection: LibrarySelection) : ViewModel()
