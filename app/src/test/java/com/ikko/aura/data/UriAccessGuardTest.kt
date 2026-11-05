package com.ikko.aura.data

import org.junit.Assert.assertEquals
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

    @Test
    fun `a grant no picture refers to any more is given back`() {
        val replaced = "content://com.android.providers.media.documents/document/image%3A7"
        assertEquals(setOf(replaced), UriAccessGuard.orphanedGrants(setOf(photo, replaced), setOf(photo)))
    }

    @Test
    fun `every grant in use is kept`() {
        val cover = "content://com.android.externalstorage.documents/document/primary%3Acover.png"
        assertEquals(emptySet<String>(), UriAccessGuard.orphanedGrants(setOf(photo, cover), setOf(photo, cover)))
    }

    @Test
    fun `a directory grant is never given back`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        assertEquals(emptySet<String>(), UriAccessGuard.orphanedGrants(setOf(tree), emptySet()))
    }

    @Test
    fun `nothing referenced and nothing held is nothing to release`() {
        assertEquals(emptySet<String>(), UriAccessGuard.orphanedGrants(emptySet(), setOf(photo)))
    }
}
