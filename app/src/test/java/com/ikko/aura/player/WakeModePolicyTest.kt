package com.ikko.aura.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeModePolicyTest {
    @Test fun `a local file holds the CPU only`() {
        assertEquals(C.WAKE_MODE_LOCAL, WakeModePolicy.forScheme("content"))
        assertEquals(C.WAKE_MODE_LOCAL, WakeModePolicy.forScheme("file"))
    }

    @Test fun `a stream also holds the Wi-Fi lock`() {
        listOf("https", "http", "ytm", "remote", "jiosaavn").forEach { assertEquals(it, C.WAKE_MODE_NETWORK, WakeModePolicy.forScheme(it)) }
    }

    @Test fun `no track holds nothing`() {
        assertEquals(C.WAKE_MODE_NONE, WakeModePolicy.forScheme(null))
    }
}
