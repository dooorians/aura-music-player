package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleMatcherTest {
    @Test fun `more plays start higher, with a cap`() {
        assertTrue(ScrobbleMatcher.trackAffinity(100) > ScrobbleMatcher.trackAffinity(2))
        assertEquals(20.0, ScrobbleMatcher.trackAffinity(100_000), 1e-9)
        assertTrue(ScrobbleMatcher.artistAffinity(1000) <= 40.0)
    }
}
