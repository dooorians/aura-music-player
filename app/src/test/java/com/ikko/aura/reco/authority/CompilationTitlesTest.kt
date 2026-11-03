package com.ikko.aura.reco.authority

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationTitlesTest {

    @Test fun `playlists and compilations are not an artist's record`() {
        assertTrue(CompilationTitles.matches("Summer Hits 2024"))
        assertTrue(CompilationTitles.matches("  LO-FI BEATS to study to "))
        assertTrue(CompilationTitles.matches("Bichotas"))
        assertTrue(CompilationTitles.matches("The Best Compilation"))
        assertTrue(CompilationTitles.matches("Out Of This World (ICC Men's T20 World Cup 2024 Official Anthem) (DIVINE Remix)"))
    }

    @Test fun `a real album and a blank title are not`() {
        assertFalse(CompilationTitles.matches("Hyper-Ego"))
        assertFalse(CompilationTitles.matches(""))
        assertFalse(CompilationTitles.matches("   "))
    }
}
