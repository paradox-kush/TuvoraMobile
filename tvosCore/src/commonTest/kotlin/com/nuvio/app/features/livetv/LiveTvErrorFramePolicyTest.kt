package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvErrorFramePolicyTest {
    @Test fun errorsCoverRetainedFramesAndLoadingDoesNot() {
        assertTrue(LiveTvErrorFramePolicy.coverVideo(true, false))
        assertTrue(LiveTvErrorFramePolicy.coverVideo(false, true))
        assertTrue(LiveTvErrorFramePolicy.coverVideo(true, true))
        assertFalse(LiveTvErrorFramePolicy.coverVideo(false, false))
    }
}
