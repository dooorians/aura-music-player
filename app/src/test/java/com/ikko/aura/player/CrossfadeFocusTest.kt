package com.ikko.aura.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tail of a blend has no audio focus of its own: it follows the session player, suppression included. */
class CrossfadeFocusTest {

    @Test
    fun `the tail plays while the session plays`() {
        assertTrue(tailShouldPlay(true, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
    }

    @Test
    fun `a pause, the headphones pulled for instance, stops the tail`() {
        assertFalse(tailShouldPlay(false, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
    }

    @Test
    fun `a call silences the session without pausing it, and the tail stops too`() {
        // A transient focus loss leaves playWhenReady true: mirroring it alone kept the outgoing track playing.
        assertFalse(tailShouldPlay(true, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
    }

    @Test
    fun `an output the session refuses stops the tail too`() {
        assertFalse(tailShouldPlay(true, Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT))
    }
}
