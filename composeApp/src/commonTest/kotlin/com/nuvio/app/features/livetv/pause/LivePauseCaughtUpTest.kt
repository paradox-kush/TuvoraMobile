package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.player.LiveSeekWindow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * "Caught up with live" on mpv. The window's end is the demuxer's cache end (`seekable-ranges`), which on a
 * segmented stream sits a read-ahead lead in front of the playhead even when the viewer is exactly live. A fixed
 * 2 s slack then never matches and the session never ends on its own; the lead seen while live is part of "live".
 */
class LivePauseCaughtUpTest {
    private val mpv = LivePauseCapabilities(diskBuffer = false, engineBackBuffer = true)
    private val config = LivePauseConfig(
        engineMemoryBudgetBytes = 400L * 1024 * 1024,
        engineNormalPlan = LivePausePolicy.EngineCachePlan(96L * 1024 * 1024, 32L * 1024 * 1024, 60),
    )

    @Test
    fun `slack is the edge slack plus the lead seen while live capped`() {
        assertEquals(2_000L, LiveCaughtUp.slackMs(edgeSlackMs = 2_000L, liveLeadMs = 0L), "no lead known: the plain edge slack")
        assertEquals(8_000L, LiveCaughtUp.slackMs(2_000L, 6_000L))
        assertEquals(2_000L + LiveCaughtUp.MAX_LEAD_MS, LiveCaughtUp.slackMs(2_000L, 600_000L), "a runaway cache is not a lead")
        assertEquals(2_000L, LiveCaughtUp.slackMs(2_000L, -5L))
    }

    @Test
    fun `the lead is the largest gap seen while live so a burst of segments does not shrink it`() {
        assertEquals(6_000L, LiveCaughtUp.noteLead(current = 0L, behindLiveMs = 6_000L))
        assertEquals(6_000L, LiveCaughtUp.noteLead(current = 6_000L, behindLiveMs = 1_000L))
        assertEquals(LiveCaughtUp.MAX_LEAD_MS, LiveCaughtUp.noteLead(0L, 10 * 60_000L))
    }

    @Test
    fun `a viewer back at the cache lead they had while live is live again`() = runTest {
        val r = LivePauseTestRig(backgroundScope, config = config)
        r.open("c1", mpv)
        // Watching live: the cache end is 6 s in front of the playhead.
        r.engine.window = LiveSeekWindow(0, 60_000, 54_000)
        r.controller.onSnapshot(54_000, isPlaying = true)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(0, 100_000, 93_000)   // 7 s behind the cache end: the old live lead + 1 s
        r.controller.onSnapshot(93_000, isPlaying = true)
        assertEquals(LivePauseState.Off, r.controller.state.value, "caught up: the session ends on its own")
    }

    @Test
    fun `still clearly behind the lead stays shifted`() = runTest {
        val r = LivePauseTestRig(backgroundScope, config = config)
        r.open("c1", mpv)
        r.engine.window = LiveSeekWindow(0, 60_000, 54_000)
        r.controller.onSnapshot(54_000, isPlaying = true)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(0, 100_000, 91_000)   // 9 s: more than lead + slack
        r.controller.onSnapshot(91_000, isPlaying = true)
        assertEquals(9_000L, assertIs<LivePauseState.Shifted>(r.controller.state.value).behindLiveMs)
    }

    @Test
    fun `without a lead the plain edge slack applies`() = runTest {
        val r = LivePauseTestRig(backgroundScope, config = config)
        r.open("c1", mpv)
        r.engine.window = LiveSeekWindow(0, 60_000, 30_000)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(0, 100_000, 98_100)
        r.controller.onSnapshot(98_100, isPlaying = true)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        r.open("c2", mpv)
        r.engine.window = LiveSeekWindow(0, 60_000, 30_000)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(0, 100_000, 97_000)   // 3 s
        r.controller.onSnapshot(97_000, isPlaying = true)
        assertIs<LivePauseState.Shifted>(r.controller.state.value)
    }

    @Test
    fun `the lead of the previous channel does not carry over a zap`() = runTest {
        val r = LivePauseTestRig(backgroundScope, config = config)
        r.open("c1", mpv)
        r.engine.window = LiveSeekWindow(0, 60_000, 54_000)
        r.controller.onSnapshot(54_000, isPlaying = true)   // lead 6 s on c1
        r.open("c2", mpv)
        r.engine.window = LiveSeekWindow(0, 60_000, 30_000)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(0, 100_000, 93_000)   // 7 s behind: caught up only with c1's lead
        r.controller.onSnapshot(93_000, isPlaying = true)
        assertIs<LivePauseState.Shifted>(r.controller.state.value)
    }
}
