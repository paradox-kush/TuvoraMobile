package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingServiceLaunch
import com.nuvio.app.features.recordings.internal.RecordingStartOrigin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B286: one phone crash-looped at every launch (ForegroundServiceDidNotStartInTimeException): app start handed an
 * overdue recording to the service with startForegroundService and the slow cold start missed its deadline.
 */
class RecordingServiceLaunchTest {

    @Test
    fun `app start with an Activity visible uses a plain start with no foreground deadline`() {
        assertFalse(RecordingServiceLaunch.needsForegroundStart(RecordingStartOrigin.VISIBLE_APP))
    }

    @Test
    fun `an alarm from the background still needs the foreground start`() {
        assertTrue(RecordingServiceLaunch.needsForegroundStart(RecordingStartOrigin.ALARM))
    }
}
