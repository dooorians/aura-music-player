package com.ikko.aura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagTextTest {

    @Test
    fun `a clean tag is returned as it is`() {
        assertEquals("Daft Punk", TagText.clean("Daft Punk"))
        assertEquals("Beyoncé — Halo", TagText.clean("Beyoncé — Halo"))
    }

    @Test
    fun `null, empty and blank tags are no tag`() {
        assertNull(TagText.clean(null))
        assertNull(TagText.clean(""))
        assertNull(TagText.clean("   \t  "))
    }

    @Test
    fun `an ID3 NUL terminator ends the text and the padding after it is dropped`() {
        assertEquals("Around the World", TagText.clean("Around the World\u0000\u0000\u0000garbage"))
        assertNull(TagText.clean("\u0000\u0000"))
    }

    @Test
    fun `control characters and byte order marks are removed`() {
        assertEquals("Title", TagText.clean("﻿Ti\u0007tle\u001B"))
        assertEquals("Noncharacters", TagText.clean("Non￾characters￿"))
    }

    @Test
    fun `line breaks and tabs become single spaces`() {
        assertEquals("One More Time", TagText.clean("One\r\nMore\t\tTime"))
    }

    @Test
    fun `a lone surrogate from a broken UTF-16 frame is dropped, a real pair is kept`() {
        assertEquals("ab", TagText.clean("a\uD83Db"))
        assertEquals("ab", TagText.clean("a\uDE00b"))
        assertEquals("a😀b", TagText.clean("a😀b"))
    }

    @Test
    fun `a tag made only of replacement characters is no tag, one with some text is kept`() {
        assertNull(TagText.clean("�� �"))
        assertEquals("Caf�", TagText.clean("Caf�"))
    }

    @Test
    fun `cleanOr falls back when nothing readable is left`() {
        assertEquals("Artiste inconnu", TagText.cleanOr("\u0000", "Artiste inconnu"))
        assertEquals("Air", TagText.cleanOr(" Air ", "Artiste inconnu"))
    }

    @Test
    fun `a huge or random input never throws`() {
        val random = java.util.Random(42)
        repeat(2_000) {
            val chars = CharArray(random.nextInt(64)) { random.nextInt(0x10000).toChar() }
            TagText.clean(String(chars))
        }
    }
}
