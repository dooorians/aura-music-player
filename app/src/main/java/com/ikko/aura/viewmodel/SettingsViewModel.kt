package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.player.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val batterySaverEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val onlineLyricsEnabled: Boolean = true,
    val automixEnabled: Boolean = false,
    /** Minutes left on the sleep timer, or null when none is running. */
    val sleepMinutesLeft: Int? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val musicRepository: MusicRepository,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    /**
     * Ticked once a minute rather than derived from the deadline on every recomposition: the row
     * shows whole minutes, so anything finer is a redraw nobody can see the result of.
     */
    private val sleepMinutes = flow {
        while (true) {
            emit(sleepTimer.remainingMs()?.let { ((it + 59_999) / 60_000).toInt() })
            delay(SLEEP_TICK_MS)
        }
    }

    // Explicit type arguments: the vararg `combine` would otherwise infer its element type as the
    // intersection of everything passed in, which Kotlin warns about and will reject outright in a
    // future release. `Any?` is what the array is actually read as two lines below anyway.
    val uiState: StateFlow<SettingsUiState> = combine<Any?, SettingsUiState>(
        settingsStore.batterySaverEnabled,
        musicRepository.isScanning,
        settingsStore.onlineLyricsEnabled,
        settingsStore.automixEnabled,
        sleepMinutes,
    ) { values ->
        SettingsUiState(
            batterySaverEnabled = values[0] as Boolean,
            isScanning = values[1] as Boolean,
            onlineLyricsEnabled = values[2] as Boolean,
            automixEnabled = values[3] as Boolean,
            sleepMinutesLeft = values[4] as Int?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private companion object {
        const val SLEEP_TICK_MS = 20_000L
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBatterySaverEnabled(enabled) }
    }

    fun setOnlineLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOnlineLyricsEnabled(enabled) }
    }

    fun setAutomixEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutomixEnabled(enabled) }
    }

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun rescanLibrary() {
        viewModelScope.launch { musicRepository.refresh(forceRescan = true) }
    }
}
