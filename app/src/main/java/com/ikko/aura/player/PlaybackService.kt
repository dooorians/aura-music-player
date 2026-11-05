package com.ikko.aura.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ikko.aura.source.ytm.StreamCache
import com.ikko.aura.source.ytm.YtmClient
import com.ikko.aura.source.ytm.YtmDataSource
import com.ikko.aura.source.ytm.YtmGate
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.ForwardingPlayer
import com.ikko.aura.MainActivity
import com.ikko.aura.R
import com.ikko.aura.data.SettingsStore
import com.ikko.aura.playback.PlaybackRecoveryPolicy
import com.ikko.aura.playback.TrackResolver
import com.ikko.aura.player.smart.AutomixSettings
import com.ikko.aura.player.smart.TrackAnalyzer
import com.ikko.aura.player.smart.TransitionFilterProcessor
import com.ikko.aura.player.smart.TransitionFilters
import com.ikko.aura.player.silence.EdgeSilenceTrimProcessor
import com.ikko.aura.player.silence.SilenceTrim
import com.ikko.aura.player.silence.TrailingSilenceSkipper
import com.ikko.aura.player.silence.TrimmingAudioProcessorChain
import com.ikko.aura.player.sleep.SleepFadeProcessor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Owns the app's players for its lifetime, and the [MediaSession] everything outside this
 * process talks to them through. [PlayerController] never touches an `ExoPlayer` directly.
 *
 * **Two players, not one.** A transition that overlaps two tracks needs both of them decoding
 * at once, which one `ExoPlayer` cannot do: its own gapless handover is a hard cut at the
 * sample boundary with no control over timing, gain or filtering. So there is a session player
 * and an idle spare, and a transition consists of starting the incoming track on the spare,
 * riding both gains, and then moving the session onto it — see [adoptPlayer]. Off a transition,
 * exactly one of them is doing anything at all, and the arrangement is invisible: the spare is
 * silent, holds no audio focus, and is not what the session or the notification is bound to.
 *
 * This is BitChord's arrangement, because the Automix engine ported alongside it
 * ([CrossfadeController], [com.ikko.aura.player.smart]) is written against it.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var audioSessionIdProvider: AudioSessionIdProvider
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var audioOutputMonitor: AudioOutputMonitor
    @Inject lateinit var ytmClient: YtmClient
    @Inject lateinit var jioSaavnClient: com.ikko.aura.source.jiosaavn.JioSaavnClient
    @Inject lateinit var remoteSources: com.ikko.aura.source.remote.RemoteSources
    @Inject lateinit var streamCache: StreamCache
    @Inject lateinit var trackResolver: TrackResolver
    @Inject lateinit var queueSnapshotStore: QueueSnapshotStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var spare: ExoPlayer? = null

    /**
     * One filter per player, swapped along with the players at the handoff. "Incoming" and
     * "outgoing" are roles rather than instances: the incoming track is on the session player
     * once the lap has happened, and the outgoing one is on the spare.
     */
    private var activeFilter = TransitionFilterProcessor()
    private var spareFilter = TransitionFilterProcessor()

    /** One tempo detector per player, swapped with them like the filters: the screens follow the one that is audible. */
    private var activeBeat = com.ikko.aura.player.beat.BeatDetector()
    private var spareBeat = com.ikko.aura.player.beat.BeatDetector()

    /** One edge-silence trimmer per player, swapped with them like the filters. */
    private var activeTrimmer = EdgeSilenceTrimProcessor()
    private var spareTrimmer = EdgeSilenceTrimProcessor()

    /** Moves on from a local track's trailing silence; follows the session's player like [recovery]. */
    private val trailingSkipper by lazy {
        TrailingSilenceSkipper(scope) {
            // A crossfade that can be armed owns the end of the track.
            !canArm(AutomixSettings.smartFadeEnabled.value, AutomixSettings.crossfadeSeconds.value) &&
                crossfade?.isTransitioning() != true
        }
    }

    /** Automix's DSP analyzer. Constructed lazily — see [TrackAnalyzer]'s note about contexts. */
    private val trackAnalyzer by lazy { TrackAnalyzer(this) }

    private var crossfade: CrossfadeController? = null
    private var mediaSession: MediaSession? = null

    /** Moves past a track that cannot be played; follows the session's player — see [adoptPlayer]. */
    private val recovery by lazy { PlaybackRecovery(trackResolver, PlaybackRecoveryPolicy(), scope) }

    /** Writes the queue down as it changes, so it can come back after the process is killed. */
    private val snapshotWriter by lazy { QueueSnapshotWriter(queueSnapshotStore, settingsStore, scope) }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.notification_channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_cu_headphones) },
        )

        val active = buildPlayer(activeFilter, activeBeat, activeTrimmer, ownsSession = true)
        val standby = buildPlayer(spareFilter, spareBeat, spareTrimmer, ownsSession = false)
        player = active
        spare = standby
        com.ikko.aura.player.beat.BeatGrids.active = activeBeat
        recovery.attach(active)
        snapshotWriter.attach(active)
        trailingSkipper.attach(active, activeTrimmer)

        val controller = CrossfadeController(
            scope = scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.takeIf { it.scheme != "ytm" }?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            // Read fresh on every call rather than closing over an instance: the two filters
            // change hands halfway through a transition, along with the players they sit on.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
        )
        crossfade = controller

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, SkipAwarePlayer(active, controller))
            .setSessionActivity(sessionActivity)
            .setCallback(AuraSessionCallback(trackResolver, queueSnapshotStore, scope))
            // Media3's default loader decodes the embedded cover at its full resolution for a
            // notification icon Android then draws at about 128dp. On a 3000x3000 front cover
            // that is a 36MB allocation and a decode long enough to be felt: an atrace of three
            // track changes showed `MediaNotificationManager`'s callback and the future it waits
            // on holding the main thread for 18 and 24ms respectively, on top of each other.
            // Capped, the same picture arrives in about a millisecond and the notification is
            // pixel-for-pixel what it was.
            .setBitmapLoader(
                CacheBitmapLoader(
                    DataSourceBitmapLoader.Builder(this)
                        .setMaximumOutputDimension(NOTIFICATION_ART_MAX_PX)
                        .build(),
                ),
            )
            .build()

        // The engine reads plain state flows from whatever thread it is on, so the user's
        // stored preference is mirrored onto them rather than collected at each read.
        settingsStore.automixEnabled
            .onEach { AutomixSettings.smartFadeEnabled.value = it }
            .launchIn(scope)
        // Read by both trimmers on the audio thread, buffer by buffer: a change applies at once, with
        // no restart of the players.
        settingsStore.trimSilenceEnabled
            .onEach { SilenceTrim.enabled = it }
            .launchIn(scope)

        // Applied to both players, the idle one included: it is the one the next transition will
        // start a track on, and a route set only on the audible player would be lost at the lap.
        audioOutputMonitor.state
            .map { it.selectedDeviceId }
            .distinctUntilChanged()
            .onEach { deviceId ->
                val device = deviceId?.let { id ->
                    (getSystemService(AUDIO_SERVICE) as AudioManager)
                        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.id == id }
                }
                player?.setPreferredAudioDevice(device)
                spare?.setPreferredAudioDevice(device)
            }
            .launchIn(scope)

        controller.start()
    }

    /**
     * Moves the session onto the player the crossfade has just started the incoming track on.
     * This is the whole of the handoff: no seek, no re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player *before* the
     * incoming one asks for it, so the app never holds two focus requests at once and never
     * briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        player = incoming
        spare = outgoing
        recovery.attach(incoming)
        snapshotWriter.attach(incoming)
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        val heldBeat = activeBeat
        activeBeat = spareBeat
        spareBeat = heldBeat
        com.ikko.aura.player.beat.BeatGrids.active = activeBeat
        val heldTrimmer = activeTrimmer
        activeTrimmer = spareTrimmer
        spareTrimmer = heldTrimmer
        trailingSkipper.attach(incoming, activeTrimmer)

        crossfade?.let { mediaSession?.player = SkipAwarePlayer(incoming, it) }
    }

    /**
     * Only the session's player takes audio focus and reacts to the headphones being pulled.
     * Two focus-handling players in one process fight each other: the standby would duck or
     * pause the very transition it is there to perform.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    private fun buildPlayer(
        filter: TransitionFilterProcessor,
        beat: com.ikko.aura.player.beat.BeatDetector,
        trimmer: EdgeSilenceTrimProcessor,
        ownsSession: Boolean,
    ): ExoPlayer =
        ExoPlayer.Builder(this, filteredRenderers(filter, beat, trimmer))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        DataSource.Factory {
                            val upstream = com.ikko.aura.source.jiosaavn.JioSaavnDataSource(
                                jioSaavnClient,
                                YtmGate(ytmClient, streamCache.factory { YtmDataSource(ytmClient) }.createDataSource()),
                            )
                            object : DataSource by upstream {
                                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                                    val uri = dataSpec.uri
                                    if (uri.scheme == "remote") {
                                        val sourceId = uri.authority ?: uri.host.orEmpty()
                                        val itemId = uri.path?.removePrefix("/").orEmpty()
                                        val resolved = remoteSources.streamFor("$sourceId:$itemId")
                                            ?: throw androidx.media3.datasource.HttpDataSource.HttpDataSourceException(
                                                "Remote source $sourceId unavailable",
                                                dataSpec,
                                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                                                androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                                            )
                                        return upstream.open(dataSpec.buildUpon().setUri(android.net.Uri.parse(resolved)).build())
                                    }
                                    return upstream.open(dataSpec)
                                }
                            }
                        },
                    ),
                    // A raw AAC (ADTS) or AMR file has no seek table, nor does an MP3 whose Xing header carries none:
                    // without this they play but cannot be seeked, and the progress bar snaps back. Seeking by the
                    // average bitrate is approximate on variable-bitrate files, which is still far better than nothing.
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                ),
            )
            .setAudioAttributes(AUDIO_ATTRIBUTES, ownsSession)
            .setHandleAudioBecomingNoisy(ownsSession)
            // Wake and Wi-Fi locks held only while this player is actually playing or buffering, and
            // let go of the moment it pauses — the sleep timer's pause included. Without them a
            // screen-off phone may suspend the CPU mid-stream.
            // Held only while this player is playing or buffering, so the idle spare holds nothing. Without it the CPU
            // and the Wi-Fi could sleep with the screen off while a streamed track was still buffering, and playback
            // stalled until the phone woke up.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            // A new track starts the tempo over; a seek within one does not (see BeatDetector).
            .also {
                it.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        beat.reset()
                        // Only a net under the trimmer, which reads the item off the stream itself.
                        trimmer.fallbackIsLocal = SilenceTrim.isLocal(mediaItem?.localConfiguration?.uri)
                    }
                })
            }
            // Both players share one session id so the equaliser applies to whichever is
            // audible, including through the overlap where both are.
            .apply { setAudioSessionId(audioSessionIdProvider.id) }

    /**
     * The stock renderer chain with Aura's processors spliced into the audio sink:
     * [EdgeSilenceTrimProcessor] (silence at the edges of local tracks), [TransitionFilterProcessor]
     * (what makes a transition's low-pass and high-pass audible rather than advisory) and
     * [SleepFadeProcessor] (the sleep timer's fade-out).
     */
    private fun filteredRenderers(
        transition: TransitionFilterProcessor,
        beat: com.ikko.aura.player.beat.BeatDetector,
        trimmer: EdgeSilenceTrimProcessor,
    ) =
        object : io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(this@PlaybackService) {
            // The phone's own codecs first; FFmpeg only for the formats they refuse, so a file that plays
            // silently in the gallery still plays here.
            init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = com.ikko.aura.player.beat.BeatAudioSink(DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    // Edge trimming first, so nothing after it spends time on silence that is about to
                    // be dropped; the sleep fade last, on exactly what is about to be heard.
                    TrimmingAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            arrayOf(trimmer, transition, SleepFadeProcessor()),
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor(),
                        ),
                        trimmer,
                    ),
                )
                .build(), beat)
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        recovery.release()
        trailingSkipper.release()
        snapshotWriter.release()
        crossfade?.release()
        crossfade = null
        scope.cancel()
        trackAnalyzer.release()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        spare?.release()
        player = null
        spare = null
        super.onDestroy()
    }

    /**
     * **A skip cancels the crossfade.** Blending is for a track running out, not for one being
     * changed: told to move on, the listener wants the song they were on to stop, not to keep
     * playing over the one they asked for. So every skip tells [CrossfadeController] to drop
     * whatever is in flight, and then moves the queue plainly.
     *
     * Command availability is deliberately untouched — mutating it through a [ForwardingPlayer]
     * would mean intercepting listener callbacks too.
     */
    private class SkipAwarePlayer(
        player: Player,
        private val crossfade: CrossfadeController,
    ) : ForwardingPlayer(player) {

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            crossfade.onSkipRequested()
            super.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekToPreviousMediaItem() {
            crossfade.onSkipRequested()
            super.seekToPreviousMediaItem()
        }

        override fun seekToNextMediaItem() {
            crossfade.onSkipRequested()
            super.seekToNextMediaItem()
        }

        override fun seekToNext() {
            crossfade.onSkipRequested()
            super.seekToNext()
        }

        override fun seekToPrevious() {
            crossfade.onSkipRequested()
            super.seekToPrevious()
        }
    }

    companion object {
        private const val CHANNEL_ID = "aura_playback"

        /**
         * Longest side the notification's cover is decoded at. The system draws it at roughly
         * 128dp, which is 320px on this screen, so this is already more than it can show — and
         * on the lock screen, where the same bitmap is blurred across the wallpaper, it is what
         * keeps that from looking soft.
         */
        private const val NOTIFICATION_ART_MAX_PX = 1024

        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
