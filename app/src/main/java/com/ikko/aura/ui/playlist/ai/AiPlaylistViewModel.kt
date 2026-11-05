package com.ikko.aura.ui.playlist.ai

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikko.aura.ai.playlist.AiPlaylistAssistant
import com.ikko.aura.ai.playlist.AiPlaylistResult
import com.ikko.aura.ai.playlist.AiStage
import com.ikko.aura.ai.playlist.EnergyLevel
import com.ikko.aura.ai.playlist.IntentOrigin
import com.ikko.aura.ai.playlist.PlaylistIntent
import com.ikko.aura.ai.playlist.Relaxation
import com.ikko.aura.ai.slm.LlamaBridge
import com.ikko.aura.ai.slm.SlmModelState
import com.ikko.aura.ai.slm.SlmModelStore
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class AiPhase { Idle, Thinking, Ready, NoMatch, LibraryEmpty }

@Immutable
data class AiPlaylistUiState(
    val prompt: String = "",
    val phase: AiPhase = AiPhase.Idle,
    val stage: AiStage = AiStage.READING,
    /** The request the shown result answers (the field may have been edited since). */
    val request: String = "",
    val tracks: List<Track> = emptyList(),
    val totalDurationMs: Long = 0L,
    /** What was understood, as short labels ("≈ 150 BPM", "Énergie haute", "Sans trap"). */
    val tags: List<String> = emptyList(),
    val origin: IntentOrigin? = null,
    val relaxation: Relaxation = Relaxation.NONE,
    val saving: Boolean = false,
    val savedPlaylistId: Long? = null,
)

@HiltViewModel
class AiPlaylistViewModel @Inject constructor(
    private val assistant: AiPlaylistAssistant,
    private val models: SlmModelStore,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AiPlaylistUiState())
    val state: StateFlow<AiPlaylistUiState> = _state.asStateFlow()

    val modelState: StateFlow<SlmModelState> = models.state
    val modelName: String = models.spec.displayName
    val modelSizeMb: Int = (models.spec.sizeBytes / 1_000_000L).toInt()

    /** Null until known: loading the library happens off the main thread, once, when the sheet opens. */
    private val _engineAvailable = MutableStateFlow<Boolean?>(null)
    val engineAvailable: StateFlow<Boolean?> = _engineAvailable.asStateFlow()

    private var job: Job? = null
    private var download: Job? = null

    init {
        viewModelScope.launch { _engineAvailable.value = withContext(Dispatchers.Default) { LlamaBridge.available } }
    }

    fun onPromptChange(text: String) {
        _state.update { it.copy(prompt = text.take(MAX_PROMPT)) }
    }

    /** Composes a playlist for [request] (the field's text by default), replacing whatever was being composed. */
    fun compose(request: String = _state.value.prompt) {
        val text = request.trim()
        if (text.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(prompt = text, phase = AiPhase.Thinking, stage = AiStage.READING, request = text, savedPlaylistId = null) }
        job = viewModelScope.launch {
            val result = assistant.compose(text) { stage -> _state.update { it.copy(stage = stage) } }
            _state.update { s ->
                when (result) {
                    AiPlaylistResult.EmptyRequest -> s.copy(phase = AiPhase.Idle)
                    AiPlaylistResult.LibraryEmpty -> s.copy(phase = AiPhase.LibraryEmpty, tracks = emptyList(), totalDurationMs = 0)
                    is AiPlaylistResult.NoMatch -> s.copy(
                        phase = AiPhase.NoMatch, tracks = emptyList(), totalDurationMs = 0,
                        tags = IntentSummary.describe(result.interpretation.intent), origin = result.interpretation.origin,
                    )
                    is AiPlaylistResult.Ready -> s.copy(
                        phase = AiPhase.Ready, tracks = result.tracks, totalDurationMs = result.totalDurationMs,
                        tags = IntentSummary.describe(result.interpretation.intent), origin = result.interpretation.origin,
                        relaxation = result.relaxation,
                    )
                }
            }
        }
    }

    /** Stops a composition in progress (the native engine stops at its next step). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { if (it.phase == AiPhase.Thinking) it.copy(phase = AiPhase.Idle) else it }
    }

    fun downloadModel() {
        if (download?.isActive == true) return
        download = viewModelScope.launch { models.download() }
    }

    fun cancelDownload() {
        download?.cancel()
        download = null
    }

    /** Saves the playlist shown under a name drawn from its request; [onSaved] gets the new playlist's id. */
    fun save(onSaved: (Long) -> Unit = {}) {
        val s = _state.value
        if (s.phase != AiPhase.Ready || s.tracks.isEmpty() || s.saving) return
        s.savedPlaylistId?.let { onSaved(it); return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = runCatching {
                val id = playlists.createPlaylist(IntentSummary.playlistName(s.request))
                for (t in s.tracks) playlists.addTrack(id, t.id)
                id
            }.getOrNull()
            _state.update { it.copy(saving = false, savedPlaylistId = id) }
            if (id != null) onSaved(id)
        }
    }

    override fun onCleared() {
        job?.cancel()
        // A download outlives nothing but the sheet: it resumes from its ".part" next time.
        download?.cancel()
    }

    private companion object {
        const val MAX_PROMPT = 280
    }
}

/** What the sheet writes about an intent; pure, so it is tested with the rest. */
object IntentSummary {

    fun describe(intent: PlaylistIntent): List<String> = buildList {
        intent.mood?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
        intent.targetBpm?.let { bpm ->
            add(if (intent.bpmTolerance != null) "$bpm ± ${intent.bpmTolerance} BPM" else "≈ $bpm BPM")
        }
        when (intent.energy) {
            EnergyLevel.LOW -> add("Énergie douce")
            EnergyLevel.MEDIUM -> add("Énergie modérée")
            EnergyLevel.HIGH -> add("Énergie haute")
            null -> Unit
        }
        if (intent.genresIncluded.isNotEmpty()) add(intent.genresIncluded.joinToString(" · ") { title(it) })
        intent.genresExcluded.forEach { add("Sans ${it}") }
        intent.targetDurationMinutes?.let { add(duration(it * 60_000L)) }
    }

    /** "47 min", "1 h 02". */
    fun duration(ms: Long): String {
        val minutes = ((ms + 30_000L) / 60_000L).coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d".format(minutes % 60)
    }

    /** "18 titres · 1 h 02". */
    fun count(tracks: Int, ms: Long): String = "$tracks titre${if (tracks > 1) "s" else ""} · ${duration(ms)}"

    /** The request as a playlist's name: its first letter up, cut at a word under 40 characters. */
    fun playlistName(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return "Playlist IA"
        val cut = if (clean.length <= 40) clean else clean.take(40).substringBeforeLast(' ').ifBlank { clean.take(40) } + "…"
        return cut.replaceFirstChar { it.uppercase() }
    }

    private fun title(genre: String): String =
        if (genre == "r&b") "R&B" else genre.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
