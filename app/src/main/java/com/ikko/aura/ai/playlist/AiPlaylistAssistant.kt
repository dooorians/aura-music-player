package com.ikko.aura.ai.playlist

import android.content.Context
import com.ikko.aura.ai.slm.LlamaBridge
import com.ikko.aura.ai.slm.SlmModelStore
import com.ikko.aura.data.MusicRepository
import com.ikko.aura.data.model.Track
import com.ikko.aura.data.playlist.AuraDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** What the assistant is doing, for the sheet's indicator. */
enum class AiStage { READING, SELECTING }

sealed interface AiPlaylistResult {
    data object EmptyRequest : AiPlaylistResult
    data object LibraryEmpty : AiPlaylistResult
    data class NoMatch(val interpretation: Interpretation) : AiPlaylistResult
    data class Ready(
        val tracks: List<Track>,
        val totalDurationMs: Long,
        val interpretation: Interpretation,
        val relaxation: Relaxation,
    ) : AiPlaylistResult
}

/**
 * The whole feature in one call: a request in, a playlist of the listener's songs out. Reads the request (the local
 * model, or the rules), plans, queries and sequences ([AiPlaylistGenerator]). Stateless between calls: the model is
 * loaded and freed within [compose].
 */
class AiPlaylistAssistant(
    private val interpreter: PlaylistIntentInterpreter,
    private val generator: AiPlaylistGenerator,
) {
    suspend fun compose(request: String, onStage: (AiStage) -> Unit = {}): AiPlaylistResult {
        val text = request.trim()
        if (text.isEmpty()) return AiPlaylistResult.EmptyRequest
        onStage(AiStage.READING)
        val interpretation = interpreter.interpret(text)
        onStage(AiStage.SELECTING)
        return when (val outcome = generator.generate(interpretation.intent, seed = text.lowercase())) {
            AiPlaylistOutcome.LibraryEmpty -> AiPlaylistResult.LibraryEmpty
            is AiPlaylistOutcome.NoMatch -> AiPlaylistResult.NoMatch(interpretation)
            is AiPlaylistOutcome.Ready -> AiPlaylistResult.Ready(outcome.tracks, outcome.totalDurationMs, interpretation, outcome.plan.relaxation)
        }
    }
}

/** The library as Room and the media index know it. */
class RoomAiTrackSource @Inject constructor(
    private val dao: AiPlaylistDao,
    private val music: MusicRepository,
) : AiTrackSource {
    override suspend fun library(): List<Track> = music.tracks.value
    override suspend fun rows(query: TrackQuery): List<AiTrackRow> =
        dao.candidates(query.anyTempo, query.lo, query.hi, query.halfLo, query.halfHi, query.doubleLo, query.doubleHi, query.limit)
    override suspend fun refused(): Set<String> = dao.refusedIds().toSet()
    override suspend fun tagged(): Set<String> = dao.taggedIds().toSet()
}

/** The prompt files, read once from the APK's assets. */
@Singleton
class PromptAssetsLoader @Inject constructor(@ApplicationContext private val context: Context) {
    val assets: PromptAssets? by lazy {
        runCatching {
            val grammar = context.assets.open("slm/playlist_intent.gbnf").bufferedReader().use { it.readText() }
            val prompt = context.assets.open("slm/playlist_intent_prompt.txt").bufferedReader().use { it.readText() }
            PromptAssets.of(grammar, prompt)
        }.getOrNull()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AiPlaylistModule {

    @Provides
    fun provideAiPlaylistDao(database: AuraDatabase): AiPlaylistDao = database.aiPlaylistDao()

    @Provides
    @Singleton
    fun provideAiPlaylistAssistant(
        source: RoomAiTrackSource,
        models: SlmModelStore,
        prompts: PromptAssetsLoader,
    ): AiPlaylistAssistant = AiPlaylistAssistant(
        interpreter = PlaylistIntentInterpreter(
            engine = LlamaBridge,
            modelPath = { models.modelFile.absolutePath.takeIf { models.isInstalled() && LlamaBridge.available } },
            assets = { prompts.assets },
            prefixCachePath = { key -> models.prefixCacheFile(key).also { it.parentFile?.mkdirs() }.absolutePath },
        ),
        generator = AiPlaylistGenerator(source),
    )
}
