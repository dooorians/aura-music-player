package com.ikko.aura.reco

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenewalTest {
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDate.of(y, m, d).atStartOfDay(ZONE).toInstant().toEpochMilli() + h * 3_600_000L

    @Test fun `a playlist never made is due`() {
        assertTrue(Renewal.isDue(Cadence.DAILY, 0, NOW, ZONE))
        assertTrue(Renewal.isDue(Cadence.WEEKLY, 0, NOW, ZONE))
    }

    @Test fun `the daily mix changes with the calendar day, not after 24 hours`() {
        assertFalse(Renewal.isDue(Cadence.DAILY, at(2026, 10, 10, 1), at(2026, 10, 10, 23), ZONE))
        assertTrue(Renewal.isDue(Cadence.DAILY, at(2026, 10, 10, 23), at(2026, 10, 11, 0), ZONE))
    }

    @Test fun `weekly playlists are due after seven days`() {
        assertFalse(Renewal.isDue(Cadence.WEEKLY, at(2026, 10, 3, 12), at(2026, 10, 10, 11), ZONE))
        assertTrue(Renewal.isDue(Cadence.WEEKLY, at(2026, 10, 3, 12), at(2026, 10, 10, 12), ZONE))
    }

    @Test fun `a clock that went back does not renew`() {
        assertFalse(Renewal.isDue(Cadence.DAILY, at(2026, 10, 12, 1), at(2026, 10, 10, 1), ZONE))
    }

    @Test fun `cadences are as specified`() {
        assertEquals(Cadence.DAILY, RecoKind.DAILY.cadence)
        RecoKind.entries.filter { it != RecoKind.DAILY }.forEach { assertEquals(Cadence.WEEKLY, it.cadence) }
    }
}
