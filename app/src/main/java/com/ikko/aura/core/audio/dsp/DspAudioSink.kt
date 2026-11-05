package com.ikko.aura.core.audio.dsp

import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * A sink's processor chain with [DspAudioProcessor] at its very end, after the silence skipper and Sonic.
 *
 * [DefaultAudioSink.DefaultAudioProcessorChain] only lets processors in *before* those two, and both of them only
 * take 16-bit PCM: placed there, a float-out processor would switch them off. At the end, everything upstream keeps
 * its 16-bit path, and the float buffer goes straight to the AudioTrack. Speed, pitch, silence skipping and durations
 * are still [upstream]'s, whatever chain that is — the default one, or another wrapping it.
 */
@UnstableApi
class DspAudioProcessorChain(
    private val upstream: AudioProcessorChain,
    val dsp: DspAudioProcessor,
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> = upstream.audioProcessors + dsp

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        upstream.applyPlaybackParameters(playbackParameters)

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        upstream.applySkipSilenceEnabled(skipSilenceEnabled)

    // The DSP gives one frame out per frame in: durations are Sonic's and the skipper's alone.
    override fun getMediaDuration(playoutDuration: Long): Long = upstream.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = upstream.skippedOutputFrameCount
}

/**
 * A pass-through sink that reads each new format's loudness tags on the way to the real sink, and tells the
 * processor that a new track is coming. Every call goes on to [sink] unchanged.
 */
@UnstableApi
class DspAudioSink(private val sink: AudioSink, private val dsp: DspAudioProcessor) : AudioSink by sink {

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        dsp.onNextTrack(ReplayGainReader.read(audioSinkConfig.format.metadata))
        sink.configure(audioSinkConfig)
    }

    @Deprecated("Media3 calls configure(AudioSinkConfig); kept for callers of the old signature.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        dsp.onNextTrack(ReplayGainReader.read(inputFormat.metadata))
        @Suppress("DEPRECATION")
        sink.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
}
