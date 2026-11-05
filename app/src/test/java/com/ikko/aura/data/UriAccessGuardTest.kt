package com.ikko.aura.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriAccessGuardTest {

    private val photo = "content://com.android.providers.media.documents/document/image%3A42"

    @Test
    fun `a document whose grant is still held stays`() {
        assertTrue(UriAccessGuard.isStillReadable(photo, setOf(photo)))
    }

    @Test
    fun `a document whose grant was lost is dropped`() {
        assertFalse(UriAccessGuard.isStillReadable(photo, emptySet()))
        assertFalse(UriAccessGuard.isStillReadable(photo, setOf("content://other/document/1")))
    }

    @Test
    fun `addresses that do not depend on a grant are left alone`() {
        assertTrue(UriAccessGuard.isStillReadable("file:///data/user/0/com.ikko.aura/files/cover.jpg", emptySet()))
        assertTrue(UriAccessGuard.isStillReadable("https://example.com/cover.jpg", emptySet()))
    }

    @Test
    fun `a blank address is nothing to open`() {
        assertFalse(UriAccessGuard.isStillReadable("", emptySet()))
        assertFalse(UriAccessGuard.isStillReadable("   ", emptySet()))
    }

    @Test
    fun `the scheme is matched whatever its case`() {
        assertFalse(UriAccessGuard.isStillReadable("CONTENT://provider/doc/1", emptySet()))
    }
}
