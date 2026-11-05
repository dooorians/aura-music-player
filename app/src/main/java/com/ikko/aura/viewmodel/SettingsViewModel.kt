package com.ikko.aura.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.player.SleepTimer
import com.ikko.aura.source.ytm.StreamCache
import com.ikko.aura.data.AlbumLayoutMode
import com.ikko.aura.data.TrackLayoutMode
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
    val isScanning: Boolean = false,
    val onlineLyricsEnabled: Boolean = true,
    val automixEnabled: Boolean = false,
    val ytmEnabled: Boolean = false,
    /** Minutes left on the sleep timer, or null when none is running. */
    val sleepMinutesLeft: Int? = null,
    val albumLayoutMode: AlbumLayoutMode = AlbumLayoutMode.GRID_3,
    val trackLayoutMode: TrackLayoutMode = TrackLayoutMode.GRID_2,
    /** Silence at the edges of local tracks is skipped. */
    val trimSilenceEnabled: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val musicRepository: MusicRepository,
    private val sleepTimer: SleepTimer,
    private val streamCache: StreamCache,
    private val ytmDownloads: com.ikko.aura.source.ytm.YtmDownloads,
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
        musicRepository.isScanning,
        settingsStore.onlineLyricsEnabled,
        settingsStore.automixEnabled,
        sleepMinutes,
        settingsStore.ytmEnabled,
        settingsStore.albumLayoutMode,
        settingsStore.trackLayoutMode,
        settingsStore.trimSilenceEnabled,
    ) { values ->
        SettingsUiState(
            isScanning = values[0] as Boolean,
            onlineLyricsEnabled = values[1] as Boolean,
            automixEnabled = values[2] as Boolean,
            sleepMinutesLeft = values[3] as Int?,
            ytmEnabled = values[4] as Boolean,
            albumLayoutMode = values[5] as AlbumLayoutMode,
            trackLayoutMode = values[6] as TrackLayoutMode,
            trimSilenceEnabled = values[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val profileImageUri: StateFlow<String?> = settingsStore.profileImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profileFirstName: StateFlow<String> = settingsStore.profileFirstName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val amoledBackgroundEnabled: StateFlow<Boolean> = settingsStore.amoledBackgroundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val pageSwitchButton: StateFlow<Boolean> = settingsStore.pageSwitchButton
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val miniPlayerGlass: StateFlow<Boolean> = settingsStore.miniPlayerGlass
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private companion object {
        const val SLEEP_TICK_MS = 20_000L
    }

    fun setOnlineLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOnlineLyricsEnabled(enabled) }
    }

    fun setYtmEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setYtmEnabled(enabled) }
    }

    fun setAutomixEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutomixEnabled(enabled) }
    }

    fun setTrimSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setTrimSilenceEnabled(enabled) }
    }

    fun setAlbumLayoutMode(mode: AlbumLayoutMode) {
        viewModelScope.launch { settingsStore.setAlbumLayoutMode(mode) }
    }

    fun setTrackLayoutMode(mode: TrackLayoutMode) {
        viewModelScope.launch { settingsStore.setTrackLayoutMode(mode) }
    }

    fun setProfileImageUri(uri: String?) {
        viewModelScope.launch { settingsStore.setProfileImageUri(uri) }
    }

    fun setProfileFirstName(name: String) {
        viewModelScope.launch { settingsStore.setProfileFirstName(name) }
    }

    fun setMiniPlayerGlass(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMiniPlayerGlass(enabled) }
    }

    fun setPageSwitchButton(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPageSwitchButton(enabled) }
    }

    fun setAmoledBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAmoledBackgroundEnabled(enabled) }
    }

    /**
     * Starts the sleep timer. With [finishCurrentTrack] (the default) the music stops at the end of
     * the track playing when the time is up, never in the middle of a song; either way it fades out
     * over the last 30 seconds.
     */
    fun startSleepTimer(minutes: Int, finishCurrentTrack: Boolean = true) =
        sleepTimer.start(minutes * 60_000L, finishCurrentTrack)

    fun cancelSleepTimer() = sleepTimer.cancel()

    private val _streamCacheBytes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val streamCacheBytes: StateFlow<Long> = _streamCacheBytes

    /** What is downloaded: how many songs and how many bytes. */
    val downloadedSongs: StateFlow<Int> = ytmDownloads.doneCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val downloadedBytes: StateFlow<Long> = ytmDownloads.doneBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun deleteAllDownloads() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { ytmDownloads.removeAll() } }
    }

    fun refreshStreamCacheSize() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _streamCacheBytes.value = streamCache.sizeBytes }
    }

    fun clearStreamCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            streamCache.clear()
            _streamCacheBytes.value = streamCache.sizeBytes
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch { musicRepository.refresh(forceRescan = true) }
    }
}
