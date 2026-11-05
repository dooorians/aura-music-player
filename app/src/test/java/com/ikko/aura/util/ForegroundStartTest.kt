package com.ikko.aura.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartTest {

    /** Stands for android.app.ForegroundServiceStartNotAllowedException, which extends IllegalStateException. */
    private class ForegroundServiceStartNotAllowed : IllegalStateException("startForegroundService() not allowed")

    @Test
    fun `a start that goes through is reported as started`() {
        var ran = false
        assertTrue(ForegroundStart.attempt { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `a start refused from the background does not crash and is reported`() {
        assertFalse(ForegroundStart.attempt { throw ForegroundServiceStartNotAllowed() })
    }

    @Test
    fun `a start refused for a missing foreground service permission does not crash`() {
        assertFalse(ForegroundStart.attempt { throw SecurityException("FOREGROUND_SERVICE_DATA_SYNC") })
    }

    @Test
    fun `any other failure is not hidden`() {
        val thrown = runCatching { ForegroundStart.attempt { throw IllegalArgumentException("bug") } }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, thrown?.javaClass)
    }
}
