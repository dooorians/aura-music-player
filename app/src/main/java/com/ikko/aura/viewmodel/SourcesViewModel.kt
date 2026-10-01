package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.source.remote.RemoteKind
import com.ikko.aura.source.remote.RemoteSources
import com.ikko.aura.source.remote.SourceConfigEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The sources the user adds from Réglages: the list, and what can be done to one. */
@HiltViewModel
class SourcesViewModel @Inject constructor(private val sources: RemoteSources) : ViewModel() {

    val configs: StateFlow<List<SourceConfigEntity>> = sources.configs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null if the credentials work, the reason if they do not. */
    suspend fun test(kind: RemoteKind, url: String, username: String, password: String): String? =
        sources.test(kind, url, username, password)

    fun add(kind: RemoteKind, name: String, url: String, username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) { sources.add(kind, name, url, username, password) }
    }

    fun update(id: String, name: String, url: String, username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) { sources.update(id, name, url, username, password) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { sources.setEnabled(id, enabled) }
    }

    fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) { sources.remove(id) }
    }
}
