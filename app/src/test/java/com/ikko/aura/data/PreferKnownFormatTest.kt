package com.ikko.aura.data

import com.ikko.aura.data.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferKnownFormatTest {
    @Test
    fun `a probe that could not name the codec keeps the MediaStore format`() {
        // MP3, WAV, Vorbis, WMA and AIFF come back UNKNOWN from the probe
        assertEquals(AudioFormat.MP3, preferKnownFormat(AudioFormat.UNKNOWN, AudioFormat.MP3))
        assertEquals(AudioFormat.WAV, preferKnownFormat(AudioFormat.UNKNOWN, AudioFormat.WAV))
    }

    @Test
    fun `a probe that named the codec wins`() {
        assertEquals(AudioFormat.FLAC, preferKnownFormat(AudioFormat.FLAC, AudioFormat.UNKNOWN))
        assertEquals(AudioFormat.ALAC, preferKnownFormat(AudioFormat.ALAC, AudioFormat.AAC))
    }

    @Test
    fun `both unknown stays unknown`() {
        assertEquals(AudioFormat.UNKNOWN, preferKnownFormat(AudioFormat.UNKNOWN, AudioFormat.UNKNOWN))
    }
}
