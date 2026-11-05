package com.ikko.aura.player.silence

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Trims silence at the edges of local tracks, inside the audio sink.
 *
 * **Leading silence** is dropped before it ever reaches the output: no seek, no rebuffer, so no
 * latency. The frames dropped are reported to the sink through [TrimmingAudioProcessorChain], the
 * same way Media3's own silence skipping reports its own, so the playback position jumps over
 * them and stays in step with the file (lyrics, the scrubber and the queue snapshot see the true
 * position).
 *
 * **Trailing silence** cannot be dropped the same way: the track still has to end. So it is only
 * detected here, and published through [trailing] and [onTrailingSilence]; the service moves on to
 * the next track when the playhead reaches it (see [TrailingSilenceSkipper]).
 *
 * The processor decides on its own which streams to touch: every flush carries the stream's
 * timeline and period (Media3 1.11), which name the [MediaItem] being played and its duration.
 * Deciding here rather than from a player listener avoids a race — a listener on the main thread
 * hears about the next track after its first buffers have already gone through. Only when the
 * metadata names nothing does it fall back to [fallbackIsLocal], set by the service.
 *
 * 16-bit and float PCM; anything else (passthrough, offload) leaves the processor inactive.
 */
@UnstableApi
class EdgeSilenceTrimProcessor : BaseAudioProcessor() {

    /** A confirmed trailing silence: the track [mediaId] is over from [silenceStartMs]. */
    data class TrailingSilence(val mediaId: String, val silenceStartMs: Long)

    /** Used only when a flush does not say which item is playing. Set from the main thread. */
    @Volatile
    var fallbackIsLocal: Boolean = false

    /** Called on the audio thread when a trailing silence is confirmed. Keep it short: post and return. */
    @Volatile
    var onTrailingSilence: ((TrailingSilence) -> Unit)? = null

    /** The current stream's confirmed trailing silence, or null. Readable from any thread. */
    @Volatile
    var trailing: TrailingSilence? = null
        private set

    /** Frames dropped since the last flush, for the sink's position (see [TrimmingAudioProcessorChain]). */
    @Volatile
    var droppedFrames: Long = 0L
        private set

    private var detector = EdgeSilenceDetector(SilenceTrim.config)
    private var bytesPerFrame = 0
    private var isFloat = false
    private var mediaId: String? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1) {
            // Inactive processors are not flushed: clear what the last stream left, or the chain
            // would keep reporting its dropped frames against this one.
            droppedFrames = 0L
            trailing = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val config = SilenceTrim.config
        if (detector.config != config) detector = EdgeSilenceDetector(config)
        val (item, durationUs) = describe(streamMetadata)
        val local = if (item != null) SilenceTrim.isLocal(item.localConfiguration?.uri) else fallbackIsLocal
        val active = SilenceTrim.enabled && local
        val startUs = streamMetadata.positionOffsetUs
        detector.begin(
            sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1),
            startPositionUs = startUs,
            durationUs = if (active) durationUs else EdgeSilenceDetector.UNKNOWN,
            // From the very start only: a seek into the track lands where the listener asked.
            trimLead = active && startUs <= LEAD_ARM_US,
        )
        mediaId = item?.mediaId
        droppedFrames = 0L
        trailing = null
    }

    override fun onReset() {
        detector.begin(1, 0L, EdgeSilenceDetector.UNKNOWN, trimLead = false)
        droppedFrames = 0L
        trailing = null
        mediaId = null
        bytesPerFrame = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - start
        if (size <= 0 || bytesPerFrame == 0) return
        val output = replaceOutputBuffer(size)

        // The body of a track: one bookkeeping call and a bulk copy.
        if (!detector.needsLevel()) {
            detector.onWindow(size / bytesPerFrame, 0.0)
            output.put(inputBuffer)
            output.flip()
            publishTrailing()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        val windowBytes = detector.windowFrames() * bytesPerFrame
        var position = start
        while (position < limit) {
            if (!detector.needsLevel()) {
                // Out of the leading silence and not yet in the tail: the rest goes through whole.
                detector.onWindow((limit - position) / bytesPerFrame, 0.0)
                copy(inputBuffer, position, limit, output)
                position = limit
                break
            }
            val chunk = min(windowBytes, limit - position)
            val rms = if (isFloat) SilenceLevels.rmsFloat(inputBuffer, position, chunk)
            else SilenceLevels.rmsPcm16(inputBuffer, position, chunk)
            val drop = detector.onWindow(chunk / bytesPerFrame, rms)
            if (!drop) copy(inputBuffer, position, position + chunk, output)
            position += chunk
        }
        inputBuffer.limit(limit)
        inputBuffer.position(limit)
        output.flip()
        droppedFrames = detector.droppedFrames
        publishTrailing()
    }

    private fun publishTrailing() {
        val startUs = detector.trailingSilenceStartUs()
        val id = mediaId
        if (startUs == null || id == null) {
            if (trailing != null) trailing = null
            return
        }
        if (trailing == null) {
            val found = TrailingSilence(id, startUs / 1000L)
            trailing = found
            onTrailingSilence?.invoke(found)
        }
    }

    private fun copy(source: ByteBuffer, from: Int, to: Int, output: ByteBuffer) {
        source.limit(to)
        source.position(from)
        output.put(source)
    }

    /** The item a flush is for, and its duration in microseconds (or [EdgeSilenceDetector.UNKNOWN]). */
    private fun describe(metadata: AudioProcessor.StreamMetadata): Pair<MediaItem?, Long> = try {
        val timeline = metadata.timeline
        val uid = metadata.periodUid
        val index = if (uid == null || timeline.isEmpty) C.INDEX_UNSET else timeline.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) {
            null to EdgeSilenceDetector.UNKNOWN
        } else {
            val period = timeline.getPeriod(index, Timeline.Period())
            val window = timeline.getWindow(period.windowIndex, Timeline.Window())
            val duration = if (window.durationUs == C.TIME_UNSET) EdgeSilenceDetector.UNKNOWN else window.durationUs
            window.mediaItem to duration
        }
    } catch (_: RuntimeException) {
        // A timeline that does not hold the period it was given: treat the stream as unknown.
        null to EdgeSilenceDetector.UNKNOWN
    }

    private companion object {
        /** A flush this close to zero is the start of the track (the decoder's first timestamp can be a hair late). */
        const val LEAD_ARM_US = 50_000L
    }
}
