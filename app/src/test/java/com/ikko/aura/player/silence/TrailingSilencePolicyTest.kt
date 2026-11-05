package com.ikko.aura.player.silence

import com.ikko.aura.player.silence.TrailingSilencePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingSilencePolicyTest {

    private val found = EdgeSilenceTrimProcessor.TrailingSilence(mediaId = "a", silenceStartMs = 180_000)

    private fun decide(
        position: Long,
        mediaId: String? = "a",
        confirmed: Boolean = true,
        playing: Boolean = true,
        mayIntervene: Boolean = true,
    ) = TrailingSilencePolicy.decide(found, confirmed, mediaId, position, playing, mayIntervene)

    @Test
    fun `skips the moment the playhead reaches the silence`() {
        assertEquals(Decision.Skip, decide(position = 180_000))
        assertEquals(Decision.Skip, decide(position = 180_400))
    }

    @Test
    fun `waits for the playhead, never longer than a second at a time`() {
        assertEquals(Decision.Wait(300), decide(position = 179_700))
        assertEquals(Decision.Wait(1_000), decide(position = 170_000))
        assertEquals(Decision.Wait(15), decide(position = 179_999))
    }

    @Test
    fun `a paused player is not skipped, only watched`() {
        assertEquals(Decision.Wait(500), decide(position = 180_500, playing = false))
    }

    @Test
    fun `gives up on another track, on sound returning, or when the crossfade owns the end`() {
        assertEquals(Decision.Drop, decide(position = 180_000, mediaId = "b"))
        assertEquals(Decision.Drop, decide(position = 180_000, confirmed = false))
        assertEquals(Decision.Drop, decide(position = 180_000, mayIntervene = false))
    }
}
