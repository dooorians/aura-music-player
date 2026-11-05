package com.ikko.aura.player.silence

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceLevelsTest {

    @Test
    fun `-50 dBFS is about 104 on the 16-bit scale`() {
        val linear = SilenceLevels.dbfsToLinear(-50.0)
        assertEquals(0.0031623, linear, 1e-6)
        assertEquals(104, (linear * 32768).roundToInt())
    }

    @Test
    fun `full scale is 0 dBFS and every 20 dB is a factor of ten`() {
        assertEquals(1.0, SilenceLevels.dbfsToLinear(0.0), 1e-12)
        assertEquals(0.1, SilenceLevels.dbfsToLinear(-20.0), 1e-12)
        assertEquals(-40.0, SilenceLevels.linearToDbfs(0.01), 1e-9)
    }

    @Test
    fun `conversions are each other's inverse`() {
        for (db in listOf(-90.0, -60.0, -50.0, -35.5, -6.0, 0.0)) {
            assertEquals(db, SilenceLevels.linearToDbfs(SilenceLevels.dbfsToLinear(db)), 1e-9)
        }
    }

    @Test
    fun `digital silence is minus infinity`() {
        assertEquals(Double.NEGATIVE_INFINITY, SilenceLevels.linearToDbfs(0.0), 0.0)
    }

    @Test
    fun `a level under the line is silent, on or above it is not`() {
        val line = SilenceLevels.dbfsToLinear(-50.0)
        assertTrue(SilenceLevels.isSilent(line * 0.99, -50.0))
        assertFalse(SilenceLevels.isSilent(line, -50.0))
        assertFalse(SilenceLevels.isSilent(line * 1.01, -50.0))
        assertTrue(SilenceLevels.isSilent(0.0, -50.0))
    }

    @Test
    fun `the RMS of a full-scale sine is one over root two`() {
        val samples = FloatArray(4800) { sin(2 * PI * 440 * it / 48_000).toFloat() }
        assertEquals(1 / sqrt(2.0), SilenceLevels.rmsFloat(samples), 1e-3)
        assertEquals(-3.01, SilenceLevels.linearToDbfs(SilenceLevels.rmsFloat(samples)), 0.01)
    }

    @Test
    fun `16-bit RMS reads a constant at its own level`() {
        val samples = ShortArray(100) { 3277 } // about -20 dBFS
        assertEquals(-20.0, SilenceLevels.linearToDbfs(SilenceLevels.rmsPcm16(samples)), 0.01)
    }

    @Test
    fun `RMS of nothing is zero`() {
        assertEquals(0.0, SilenceLevels.rmsPcm16(ShortArray(0)), 0.0)
        assertEquals(0.0, SilenceLevels.rmsFloat(FloatArray(0)), 0.0)
    }

    @Test
    fun `buffer RMS reads only its slice and leaves the position alone`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        buffer.putShort(0, 32767).putShort(2, 32767).putShort(4, 0).putShort(6, 0)
        assertEquals(0.0, SilenceLevels.rmsPcm16(buffer, 4, 4), 0.0)
        assertEquals(1.0, SilenceLevels.rmsPcm16(buffer, 0, 4), 1e-4)
        assertEquals(0, buffer.position())

        val floats = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        floats.putFloat(0, 0.5f).putFloat(4, -0.5f)
        assertEquals(0.5, SilenceLevels.rmsFloat(floats, 0, 8), 1e-9)
    }
}
