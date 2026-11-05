package com.ikko.aura.ui.playlist.ai

import com.ikko.aura.ai.playlist.EnergyLevel
import com.ikko.aura.ai.playlist.PlaylistIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentSummaryTest {

    @Test fun `what was understood, in the sheet's words`() {
        val intent = PlaylistIntent(150, 15, EnergyLevel.HIGH, listOf("french rap", "r&b"), listOf("trap"), 60, "workout")
        assertEquals(listOf("Workout", "150 ± 15 BPM", "Énergie haute", "French Rap · R&B", "Sans trap", "1 h 00"), IntentSummary.describe(intent))
        assertEquals(listOf("≈ 90 BPM"), IntentSummary.describe(PlaylistIntent(targetBpm = 90)))
        assertEquals(emptyList<String>(), IntentSummary.describe(PlaylistIntent.EMPTY))
    }

    @Test fun `lengths and counts`() {
        assertEquals("47 min", IntentSummary.duration(47 * 60_000L))
        assertEquals("1 h 02", IntentSummary.duration(62 * 60_000L + 10_000))
        assertEquals("0 min", IntentSummary.duration(0))
        assertEquals("1 titre · 3 min", IntentSummary.count(1, 180_000))
        assertEquals("18 titres · 1 h 02", IntentSummary.count(18, 62 * 60_000L))
    }

    @Test fun `a playlist is named after its request`() {
        assertEquals("Night Drive", IntentSummary.playlistName("Night Drive"))
        assertEquals("Du jazz pour lire", IntentSummary.playlistName("  du jazz   pour lire "))
        assertEquals("Playlist IA", IntentSummary.playlistName(" "))
        val long = IntentSummary.playlistName("une très longue demande qui décrit une ambiance avec beaucoup trop de mots")
        assertEquals("Une très longue demande qui décrit une…", long)
    }
}
