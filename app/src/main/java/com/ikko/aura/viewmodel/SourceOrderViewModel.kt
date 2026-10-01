package com.ikko.aura.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.source.SourceOrder
import com.ikko.aura.source.remote.RemoteSources
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class OrderEntry(val id: String, val name: String, val detail: String, val enabled: Boolean)

/** The sources in the order the user gave them, for the page where they drag them about. */
@HiltViewModel
class SourceOrderViewModel @Inject constructor(
    private val order: SourceOrder,
    remote: RemoteSources,
    settings: SettingsStore,
) : ViewModel() {

    val entries: StateFlow<List<OrderEntry>> = combine(order.order, remote.configs, settings.ytmEnabled) { ids, configs, ytmOn ->
        ids.mapNotNull { id ->
            when (id) {
                SourceOrder.LOCAL_ENTRY -> OrderEntry(id, "Fichiers de l'appareil", "Ta bibliothèque", true)
                SourceOrder.YTM_ENTRY -> OrderEntry(id, "YouTube Music", "Non officiel", ytmOn)
                SourceOrder.JIOSAAVN_ENTRY -> OrderEntry(id, "JioSaavn", "Natif (Haute Qualité)", true)
                else -> configs.firstOrNull { it.id == id }
                    ?.let { OrderEntry(id, it.name, it.baseUrl.removePrefix("http://").removePrefix("https://"), it.enabled) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun move(from: Int, to: Int) {
        viewModelScope.launch(Dispatchers.IO) { order.move(from, to) }
    }
}
