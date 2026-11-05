package com.ikko.aura.playback

import androidx.media3.common.PlaybackException

/** Why a track did not play, reduced to what changes what is done about it. */
enum class PlaybackFailure {
    /** The item is not there any more: deleted, moved, or the permission to read it withdrawn. */
    MISSING,

    /** The source could not be reached: network, timeout, a server error. */
    UNREACHABLE,

    /** The bytes are there but cannot be played: a container or a codec the device does not handle. */
    UNSUPPORTED,

    /**
     * Anything else, the audio output included — which moving on to another track would not cure, so it is left as the
     * player leaves it, as it always has been.
     */
    UNKNOWN,
    ;

    companion object {
        fun classify(errorCode: Int): PlaybackFailure = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            -> MISSING

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> UNREACHABLE

            // Parsing (3001..3004) and decoding (4001..4005): the file, not the route to it, is the problem.
            in PARSING_CODES, in DECODING_CODES -> UNSUPPORTED

            // A read past the end of the data: a truncated file (an interrupted copy or download). Skipped like a corrupt one.
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> UNSUPPORTED

            else -> UNKNOWN
        }

        private val PARSING_CODES = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        private val DECODING_CODES = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    }
}
