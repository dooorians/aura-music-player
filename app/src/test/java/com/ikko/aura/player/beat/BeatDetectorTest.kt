package com.ikko.aura.player.beat

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {
    private val sr = 44100
    private val latency = 0.2 // seconds between what is decoded and what is heard

    /** A kick (a decaying 60 Hz sine) every [period] seconds from [from] to [until] and digital silence elsewhere. */
    private fun track(seconds: Double, kicks: List<Double>): ShortArray {
        val pcm = ShortArray((seconds * sr).toInt() * 2)
        for (k in kicks) {
            val start = (k * sr).toInt()
            for (i in 0 until (0.12 * sr).toInt()) {
                val f = start + i
                if (f * 2 + 1 >= pcm.size) break
                val v = (sin(2 * PI * 60.0 * i / sr) * exp(-i / (0.035 * sr)) * 0.8 * 32767).toInt().toShort()
                pcm[f * 2] = v; pcm[f * 2 + 1] = v
            }
        }
        return pcm
    }

    /** Feeds [pcm] as the player would and returns, every 10 ms of audible time, how long ago the last hit was (or null). */
    private fun play(pcm: ShortArray): List<Pair<Double, Double?>> {
        val d = BeatDetector()
        d.configure(sr, 2, C.ENCODING_PCM_16BIT)
        val frames = pcm.size / 2
        val out = ArrayList<Pair<Double, Double?>>()
        var f = 0
        var next = 0.0
        while (f < frames) {
            val n = minOf(4096, frames - f)
            val buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * 2) buf.putShort(pcm[f * 2 + i])
            buf.flip()
            d.onBuffer(buf, f.toLong() * 1_000_000L / sr)
            f += n
            val audible = f.toDouble() / sr - latency
            d.noteAudible(audible, audible)
            while (next <= audible) { out += next to d.sinceBeat(next); next += 0.01 }
        }
        return out
    }

    /** The times at which the answer restarts from a small value: a hit has just reached the ear. */
    private fun hits(samples: List<Pair<Double, Double?>>): List<Double> {
        val h = ArrayList<Double>(); var prev: Double? = null
        for ((t, since) in samples) {
            if (since != null && prev != null && since < prev - 0.05) h += t - since
            prev = since
        }
        return h
    }

    @Test
    fun `a steady kick is followed on every beat from the first seconds`() {
        val kicks = (0 until 24).map { 0.5 + it * 0.5 } // 120 BPM
        val found = hits(play(track(13.0, kicks)))
        // Each kick is found within 40 ms of where it sounds, from the second beat on.
        val onTime = kicks.drop(2).count { k -> found.any { kotlin.math.abs(it - k) < 0.04 } }
        assertTrue("found $onTime of ${kicks.size - 2}", onTime >= (kicks.size - 2) * 9 / 10)
        // And it does not invent hits between them.
        val extra = found.count { t -> kicks.none { kotlin.math.abs(it - t) < 0.08 } }
        assertTrue("$extra hits that are not kicks", extra <= 2)
    }

    @Test
    fun `a silence gives no hit and no answer`() {
        // Kicks for 6 s, then 10 s of digital silence, then kicks again.
        val kicks = (0 until 12).map { 0.5 + it * 0.5 } + (0 until 6).map { 16.5 + it * 0.5 }
        val samples = play(track(20.0, kicks))
        val silent = samples.filter { (t, _) -> t in 8.0..15.5 }
        assertTrue(silent.isNotEmpty())
        assertTrue("answers during the silence: ${silent.count { it.second != null }}", silent.all { it.second == null })
        val back = hits(samples).filter { it in 16.0..19.5 }
        assertTrue("found ${back.size} hits after the silence", back.size >= 4)
    }

    @Test
    fun `before anything has been heard there is nothing to follow`() {
        assertNull(BeatDetector().sinceBeat(0.0))
        val d = BeatDetector()
        d.noteAudible(1.0, 1.0)
        assertNull(d.sinceBeat(1.0))
        assertNotNull(d) // nothing thrown for a detector never configured
        assertEquals(null, d.sinceBeat(5.0))
    }
}
