package com.ikko.aura.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityTest {

    @Test
    fun `a bare id is a local track`() {
        assertEquals(SourceTrackRef(SourceId.LOCAL, "12345"), SourceTrackRef.decode("12345"))
    }

    @Test
    fun `a local track encodes to the bare id it always had`() {
        // The compatibility rule: every key already stored (playlists, lyrics cache, recents, analyses) is a bare
        // MediaStore id, and must come out of a round trip unchanged.
        listOf("1", "98765", "0").forEach { id ->
            assertEquals(id, SourceTrackRef.decode(id).encode())
        }
    }

    @Test
    fun `another source is written source colon item`() {
        val ref = SourceTrackRef(SourceId("ytm"), "dQw4w9WgXcQ")
        assertEquals("ytm:dQw4w9WgXcQ", ref.encode())
        assertEquals(ref, SourceTrackRef.decode("ytm:dQw4w9WgXcQ"))
    }

    @Test
    fun `an item id may itself contain a colon`() {
        val ref = SourceTrackRef.decode("ytm:a:b:c")
        assertEquals(SourceId("ytm"), ref.source)
        assertEquals("a:b:c", ref.itemId)
        assertEquals("ytm:a:b:c", ref.encode())
    }

    @Test
    fun `a source id cannot contain the separator or be blank`() {
        assertThrows(IllegalArgumentException::class.java) { SourceId("a:b") }
        assertThrows(IllegalArgumentException::class.java) { SourceId("") }
        assertThrows(IllegalArgumentException::class.java) { SourceId("  ") }
    }

    @Test
    fun `a local id containing a colon would read back as another source - the known edge`() {
        // encode() of a LOCAL ref is the bare item id; an id with a colon would decode as another source. Local ids
        // are MediaStore numbers, so the case does not occur — this pins down what it would do if it ever did.
        assertEquals(SourceId("x"), SourceTrackRef.decode(SourceTrackRef(SourceId.LOCAL, "x:1").encode()).source)
    }
}
