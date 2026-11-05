package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagCleaningTest {

    @Test
    fun `a byte-order mark, the NUL that ends an ID3 string and stray controls are dropped`() {
        assertEquals("GBUM71029604", TagMap.parse(listOf("TSRC" to "﻿GBUM71029604\u0000")).isrc)
        assertEquals(128.0, TagMap.parse(listOf("TBPM" to "128\u0000")).bpm, 0.0)
        assertEquals(-7.5, TagMap.parse(listOf("REPLAYGAIN_TRACK_GAIN" to "﻿-7.50 dB\r\n")).replayGainDb!!, 1e-9)
        assertEquals("Hip Hop", TagMap.clean("Hip\tHop"))
    }

    @Test
    fun `the values of one frame, separated by NULs, are all read`() {
        val genres = TagMap.parse(listOf("TCON" to "Rock\u0000Pop\u0000")).genres
        assertEquals(listOf("Rock", "Pop"), genres)
    }

    @Test
    fun `a value that is only padding is no value`() {
        val parsed = TagMap.parse(listOf("TSRC" to "\u0000\u0000", "ISRC" to "USRC17607839"))
        assertEquals("USRC17607839", parsed.isrc)
    }

    @Test
    fun `a UFID frame gives its owner and identifier, padding removed`() {
        val id = "f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f"
        val data = "http://musicbrainz.org".toByteArray(Charsets.ISO_8859_1) + 0 + id.toByteArray(Charsets.US_ASCII) + 0 + 0
        val pair = TrackTagScanner.ufidPair(data)!!
        assertEquals("UFID:http://musicbrainz.org", pair.first)
        assertEquals(id, pair.second)
        // As Picard writes it: the owner with its scheme.
        assertEquals(id, TagMap.parse(listOf(pair)).mbid)
    }

    @Test
    fun `a UFID frame with no owner or no identifier is ignored`() {
        assertNull(TrackTagScanner.ufidPair(byteArrayOf(0, 65, 66)))
        assertNull(TrackTagScanner.ufidPair("owner".toByteArray() + 0))
        assertNull(TrackTagScanner.ufidPair(ByteArray(0)))
    }
}
