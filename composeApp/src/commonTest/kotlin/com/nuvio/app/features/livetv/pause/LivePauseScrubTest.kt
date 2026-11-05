package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.player.LiveSeekWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class LivePauseScrubTest {
    private val window = LiveSeekWindow(startMs = 100_000, endMs = 160_000, positionMs = 160_000)

    private fun shifted(position: Long, paused: Boolean = true, start: Long = 100_000, end: Long = 160_000) =
        LivePauseState.Shifted(paused, (end - position).coerceAtLeast(0), start, end, position, false)

    @Test
    fun `at live back opens the shift and lands one step behind`() {
        assertEquals(
            LivePauseScrub.Step.Seek(150_000, pauseFirst = true, resume = true),
            LivePauseScrub.step(LivePauseState.Off, window, -10_000, wasPlaying = true),
            "playing viewer keeps watching from the new spot",
        )
        assertEquals(
            LivePauseScrub.Step.Seek(150_000, pauseFirst = true, resume = false),
            LivePauseScrub.step(LivePauseState.Off, window, -10_000, wasPlaying = false),
            "a paused viewer stays paused",
        )
    }

    @Test
    fun `at live forward does nothing`() {
        assertEquals(LivePauseScrub.Step.Ignore, LivePauseScrub.step(LivePauseState.Off, window, 10_000, wasPlaying = true))
    }

    @Test
    fun `at live back with no past held does nothing`() {
        assertEquals(LivePauseScrub.Step.Ignore, LivePauseScrub.step(LivePauseState.Off, null, -10_000, wasPlaying = true))
        val atStart = LiveSeekWindow(100_000, 160_000, 100_500)
        assertEquals(LivePauseScrub.Step.Ignore, LivePauseScrub.step(LivePauseState.Off, atStart, -10_000, wasPlaying = true))
    }

    @Test
    fun `at live back never lands before the window start`() {
        val near = LiveSeekWindow(100_000, 160_000, 105_000)
        assertEquals(
            LivePauseScrub.Step.Seek(100_000, pauseFirst = true, resume = true),
            LivePauseScrub.step(LivePauseState.Off, near, -10_000, wasPlaying = true),
        )
    }

    @Test
    fun `shifted scrubs both ways inside the window and keeps its pause`() {
        assertEquals(
            LivePauseScrub.Step.Seek(120_000, pauseFirst = false, resume = false),
            LivePauseScrub.step(shifted(130_000), null, -10_000, wasPlaying = false),
        )
        assertEquals(
            LivePauseScrub.Step.Seek(140_000, pauseFirst = false, resume = false),
            LivePauseScrub.step(shifted(130_000), null, 10_000, wasPlaying = false),
        )
    }

    @Test
    fun `shifted forward into the last seconds goes live`() {
        assertEquals(LivePauseScrub.Step.GoLive, LivePauseScrub.step(shifted(149_000), null, 10_000, wasPlaying = true))
        assertEquals(LivePauseScrub.Step.GoLive, LivePauseScrub.step(shifted(158_500), null, 10_000, wasPlaying = true))
    }

    @Test
    fun `shifted forward stopping well short of the edge stays shifted`() {
        assertEquals(
            LivePauseScrub.Step.Seek(145_000, pauseFirst = false, resume = false),
            LivePauseScrub.step(shifted(135_000), null, 10_000, wasPlaying = true),
        )
    }

    @Test
    fun `shifted back clamps to the window start and stops there`() {
        assertEquals(
            LivePauseScrub.Step.Seek(100_000, pauseFirst = false, resume = false),
            LivePauseScrub.step(shifted(105_000), null, -10_000, wasPlaying = false),
        )
        assertEquals(LivePauseScrub.Step.Ignore, LivePauseScrub.step(shifted(100_000), null, -10_000, wasPlaying = false))
    }

    @Test
    fun `a shift with no window yet cannot scrub`() {
        // Provider-archive pauses report an empty window.
        assertEquals(
            LivePauseScrub.Step.Ignore,
            LivePauseScrub.step(LivePauseState.Shifted(true, 0, 0, 0, 0, false), null, -10_000, wasPlaying = false),
        )
    }

    @Test
    fun `starting and failed states do not scrub`() {
        val preparing = LivePauseState.Preparing(BoundedLoad.begin(LoadSurface.LIVE_PAUSE))
        assertEquals(LivePauseScrub.Step.Ignore, LivePauseScrub.step(preparing, window, -10_000, wasPlaying = true))
        assertEquals(
            LivePauseScrub.Step.Ignore,
            LivePauseScrub.step(LivePauseState.Failed(LivePauseFailure.STALLED), window, -10_000, wasPlaying = true),
        )
    }
}
