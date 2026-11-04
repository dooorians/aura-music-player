package com.ikko.aura.reco

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedDiscographyDaoTest {
    @Test fun migrationIsFrom11To12() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)
    }
}
