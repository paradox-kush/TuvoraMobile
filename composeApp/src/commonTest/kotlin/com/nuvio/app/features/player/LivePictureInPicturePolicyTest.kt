package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePictureInPicturePolicyTest {
    @Test
    fun liveStreamIsAlwaysIndefiniteEvenWithAReportedDuration() {
        assertTrue(LivePictureInPicturePolicy.isIndefinitePlaybackRange(isLive = true, durationMs = 7_200_000))
        assertFalse(LivePictureInPicturePolicy.canSkip(isLive = true, durationMs = 7_200_000))
    }

    @Test
    fun vodWithoutADurationIsIndefiniteAndBoundedVodCanSkip() {
        assertTrue(LivePictureInPicturePolicy.isIndefinitePlaybackRange(isLive = false, durationMs = 0))
        assertTrue(LivePictureInPicturePolicy.canSkip(isLive = false, durationMs = 5_400_000))
    }

    @Test
    fun liveStreamPausedForTheBackgroundRejoinsTheLiveEdgeOnReturn() {
        val plan = LivePictureInPicturePolicy.foregroundPlan(isLive = true, pausedForBackground = true, pictureInPictureKeptPlayback = false)
        assertEquals(LivePictureInPicturePolicy.ForegroundPlan(restoreVideo = true, rejoinLiveEdge = true, resumePlayback = true), plan)
    }

    @Test
    fun vodReturningFromTheBackgroundJustResumes() {
        val plan = LivePictureInPicturePolicy.foregroundPlan(isLive = false, pausedForBackground = true, pictureInPictureKeptPlayback = false)
        assertEquals(LivePictureInPicturePolicy.ForegroundPlan(restoreVideo = true, rejoinLiveEdge = false, resumePlayback = true), plan)
    }

    @Test
    fun returningFromAPictureInPictureThatKeptPlayingDoesNothingToALiveStream() {
        val plan = LivePictureInPicturePolicy.foregroundPlan(isLive = true, pausedForBackground = false, pictureInPictureKeptPlayback = true)
        assertEquals(LivePictureInPicturePolicy.ForegroundPlan(restoreVideo = false, rejoinLiveEdge = false, resumePlayback = false), plan)
    }

    @Test
    fun aPictureInPictureClosedInTheBackgroundRejoinsAPausedLiveStream() {
        val plan = LivePictureInPicturePolicy.foregroundPlan(isLive = true, pausedForBackground = true, pictureInPictureKeptPlayback = true)
        assertTrue(plan.rejoinLiveEdge && plan.resumePlayback && !plan.restoreVideo)
    }

    @Test
    fun aPausedVodPictureInPictureKeepsItsPausedState() {
        val plan = LivePictureInPicturePolicy.foregroundPlan(isLive = false, pausedForBackground = true, pictureInPictureKeptPlayback = true)
        assertFalse(plan.resumePlayback)
        assertFalse(plan.rejoinLiveEdge)
    }
}
