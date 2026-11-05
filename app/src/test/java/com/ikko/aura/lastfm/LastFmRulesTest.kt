package com.ikko.aura.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmRulesTest {
    @Test fun `a song counts after half of it`() {
        assertTrue(LastFmRules.counts(playedMs = 100_000, durationMs = 200_000))
        assertFalse(LastFmRules.counts(playedMs = 99_000, durationMs = 200_000))
    }

    @Test fun `a long song counts after four minutes`() {
        assertTrue(LastFmRules.counts(playedMs = 240_000, durationMs = 900_000))
        assertFalse(LastFmRules.counts(playedMs = 239_000, durationMs = 900_000))
    }

    @Test fun `a song of thirty seconds or less never counts`() {
        assertFalse(LastFmRules.counts(playedMs = 29_000, durationMs = 29_000))
        assertTrue(LastFmRules.counts(playedMs = 15_000, durationMs = 30_000))
    }

    @Test fun `the signature is the md5 of the sorted parameters and the secret, without format`() {
        val sig = LastFmRules.sign(mapOf("token" to "tok", "method" to "auth.getSession", "api_key" to "abc", "format" to "json"), "secret")
        assertEquals("4abd3667910a03c7211b110646d89b6a", sig)
        assertEquals(sig, LastFmRules.sign(mapOf("api_key" to "abc", "method" to "auth.getSession", "token" to "tok"), "secret"))
    }
}
