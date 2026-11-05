package com.ikko.aura.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureTest {

    private fun classify(code: Int) = PlaybackFailure.classify(code)

    @Test
    fun `a file that is not there, or cannot be read, is missing`() {
        assertEquals(PlaybackFailure.MISSING, classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlaybackFailure.MISSING, classify(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    @Test
    fun `network trouble is unreachable`() {
        assertEquals(PlaybackFailure.UNREACHABLE, classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackFailure.UNREACHABLE, classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlaybackFailure.UNREACHABLE, classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test
    fun `a container or a codec the device cannot handle is unsupported`() {
        assertEquals(PlaybackFailure.UNSUPPORTED, classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackFailure.UNSUPPORTED, classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    @Test
    fun `a truncated file is skipped like a corrupt one`() {
        assertEquals(PlaybackFailure.UNSUPPORTED, classify(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
    }

    @Test
    fun `the audio output and anything unclassified stay unknown`() {
        assertEquals(PlaybackFailure.UNKNOWN, classify(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(PlaybackFailure.UNKNOWN, classify(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertEquals(PlaybackFailure.UNKNOWN, classify(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(PlaybackFailure.UNKNOWN, classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }
}
