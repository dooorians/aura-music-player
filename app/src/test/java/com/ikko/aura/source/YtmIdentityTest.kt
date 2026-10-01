package com.ikko.aura.source

import com.ikko.aura.source.ytm.YTM
import com.ikko.aura.source.ytm.YtmLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtmIdentityTest {
    @Test fun keyIsTheEncodedReference() {
        val key = YtmLibrary.keyOf("VZ_-pd9d03c")
        assertEquals("ytm:VZ_-pd9d03c", key)
        assertEquals(SourceTrackRef(YTM, "VZ_-pd9d03c"), SourceTrackRef.decode(key))
        assertEquals(key, SourceTrackRef(YTM, "VZ_-pd9d03c").encode())
    }

    @Test fun localIdsAreNeverStreamed() {
        assertTrue(YtmLibrary.isStreamedId("ytm:abc"))
        assertFalse(YtmLibrary.isStreamedId("1234"))
        assertFalse(YtmLibrary.isStreamedId("l_1234"))
    }

    @Test fun pseudoAlbumIdIsNegativeAndPerSong() {
        val a = YtmLibrary.pseudoAlbumId("aaaaaaaaaaa")
        val b = YtmLibrary.pseudoAlbumId("bbbbbbbbbbb")
        assertTrue(a < 0 && b < 0)
        assertTrue(a != b)
        assertEquals(a, YtmLibrary.pseudoAlbumId("aaaaaaaaaaa"))
    }
}
