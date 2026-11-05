package com.ikko.aura.ai.slm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaBridgeCpuTest {

    // As /proc/cpuinfo lists them on a MediaTek MT6789 (Cortex-A55 + A76) and on a Cortex-A53.
    private val a55 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val a76 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp ssbs"
    private val a53 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    @Test fun `ARMv8_2 cores with dot product and half precision run the engine`() {
        assertTrue(LlamaBridge.cpuSupported(List(6) { a55 } + List(2) { a76 }))
    }

    @Test fun `one core without them is enough to refuse, and so is an unreadable cpuinfo`() {
        assertFalse(LlamaBridge.cpuSupported(listOf(a55, a53)))
        assertFalse(LlamaBridge.cpuSupported(listOf(a53)))
        assertFalse(LlamaBridge.cpuSupported(emptyList()))
    }
}
