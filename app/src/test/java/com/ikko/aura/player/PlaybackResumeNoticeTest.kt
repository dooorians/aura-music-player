package com.ikko.aura.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumeNoticeTest {

    @Test
    fun `the resume notice is posted only with the permission and notifications on`() {
        assertTrue(PlaybackResumeNotice.canNotify(permissionGranted = true, notificationsEnabled = true))
    }

    @Test
    fun `a refused POST_NOTIFICATIONS permission degrades to nothing shown`() {
        assertFalse(PlaybackResumeNotice.canNotify(permissionGranted = false, notificationsEnabled = true))
        assertFalse(PlaybackResumeNotice.canNotify(permissionGranted = true, notificationsEnabled = false))
    }
}
