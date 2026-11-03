package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import com.ikko.aura.source.ytm.AlbumRefResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The page of an album on the sources, found from its title and artist: where the "En ligne" side of an album's source switch goes. */
@HiltViewModel
class AlbumLinksViewModel @Inject constructor(private val resolver: AlbumRefResolver) : ViewModel() {
    suspend fun onlineRefOf(title: String, artist: String): String? = resolver.find(title, artist)
}
