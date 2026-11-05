package com.ikko.aura.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeCanArmTest {
    @Test
    fun `with Automix off and no manual fade nothing can be armed, so the ticker sleeps`() {
        assertFalse(canArm(smartFadeEnabled = false, crossfadeSeconds = 0))
    }

    @Test
    fun `Automix on keeps the ticker running whatever the manual fade`() {
        assertTrue(canArm(smartFadeEnabled = true, crossfadeSeconds = 0))
        assertTrue(canArm(smartFadeEnabled = true, crossfadeSeconds = 6))
    }

    @Test
    fun `a manual fade length keeps it running with Automix off`() {
        assertTrue(canArm(smartFadeEnabled = false, crossfadeSeconds = 3))
    }
}
