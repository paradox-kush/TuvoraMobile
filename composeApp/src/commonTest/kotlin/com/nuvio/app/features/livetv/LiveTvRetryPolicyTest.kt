package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTvRetryPolicyTest {
    @Test fun liveRetryReResolvesTheChannel() {
        val plan = LiveTvRetryPolicy.onRetry(replayActive = false)
        assertEquals(true, plan.tearDownSurface, "a live retry starts a fresh surface")
        assertEquals(true, plan.reResolveLive, "a live retry re-mints the live link")
        assertEquals(false, plan.reopenReplay, "there is no replay to reopen")
    }

    @Test fun replayRetryReopensTheReplayAndNeverTheLiveLink() {
        val plan = LiveTvRetryPolicy.onRetry(replayActive = true)
        assertEquals(true, plan.reopenReplay, "the replay itself must be tried again")
        assertEquals(false, plan.reResolveLive, "a live re-mint would swap the recording for the live channel")
    }

    @Test fun aTornDownSurfaceIsAlwaysRefilled() {
        for (replayActive in listOf(false, true)) {
            val plan = LiveTvRetryPolicy.onRetry(replayActive)
            if (plan.tearDownSurface) {
                val refillPath = if (replayActive) plan.reopenReplay else plan.reResolveLive
                assertTrue(refillPath, "replayActive=$replayActive tears the player down with nothing to refill it")
            }
        }
    }
}
