package com.ikko.aura.player.silence

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Media3's stock processor chain, with [EdgeSilenceTrimProcessor]'s dropped frames added to the
 * ones it already reports as skipped.
 *
 * That count is how the sink keeps the playback clock honest when audio is removed: it adds the
 * skipped frames to the position it reads off the output. Without it, trimming two seconds of
 * leading silence would leave the position two seconds behind the music for the rest of the track.
 * Both counts reset on a flush, so the sum does too.
 *
 * The trimmer sits before Sonic, so its frames are input frames; at normal speed (the only speed
 * this app plays at) that is the same thing as output frames.
 */
@UnstableApi
class TrimmingAudioProcessorChain(
    private val delegate: DefaultAudioSink.DefaultAudioProcessorChain,
    private val trimmer: EdgeSilenceTrimProcessor,
) : DefaultAudioSink.AudioProcessorChain by delegate {

    override fun getSkippedOutputFrameCount(): Long =
        delegate.skippedOutputFrameCount + trimmer.droppedFrames
}
