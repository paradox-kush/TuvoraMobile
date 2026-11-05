package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.player.LiveSeekWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseControllerTest {
    private val live = LiveLink("http://p/live/1.ts", mapOf("User-Agent" to "ua"))
    private val liveChannel = LivePauseChannel(isLive = true, isCatchUpPlayback = false, hasProviderArchive = false)
    private val archiveChannel = liveChannel.copy(hasProviderArchive = true)
    private val disk = LivePauseCapabilities(diskBuffer = true, engineBackBuffer = false)
    private val mpv = LivePauseCapabilities(diskBuffer = false, engineBackBuffer = true)

    private class Rig(
        scope: CoroutineScope,
        capture: FakeCapturePort? = FakeCapturePort(),
        config: LivePauseConfig = LivePauseConfig(),
    ) {
        val clock = FakeLiveClock()
        val engine = FakeLiveEngine()
        val host = FakeLiveHost()
        val capture = capture
        val controller = LivePauseController(
            instanceId = "p1",
            scope = scope,
            clock = clock,
            capture = capture,
            minter = LiveLinkMinter { LiveLink("http://p/fresh") },
            host = host,
            config = config,
        )
    }

    private fun TestScope.rig(
        capture: FakeCapturePort? = FakeCapturePort(),
        config: LivePauseConfig = LivePauseConfig(),
    ) = Rig(backgroundScope, capture, config)

    private fun Rig.openLive(channel: LivePauseChannel = liveChannel, caps: LivePauseCapabilities = disk, singleUse: Boolean = false, id: String = "c1") {
        controller.onLiveSourceOpened(id, channel, live, singleUse)
        controller.attachEngine(engine, caps)
    }

    private val mpvConfig = LivePauseConfig(
        engineMemoryBudgetBytes = 400L * 1024 * 1024,
        engineNormalPlan = LivePausePolicy.EngineCachePlan(96L * 1024 * 1024, 32L * 1024 * 1024, 60),
    )

    // --- DISK_BUFFER ---------------------------------------------------------------------------

    @Test
    fun `disk pause releases the live source then opens the capture after the delay`() = runTest {
        val r = rig()
        r.openLive(singleUse = true)
        assertTrue(r.controller.handlesPlayPause)
        r.controller.onPause()
        assertIs<LivePauseState.Preparing>(r.controller.state.value)
        assertEquals(LivePauseSurface.Released, r.controller.surface.value, "the provider connection is released first")
        advanceTimeBy(1_400); runCurrent()
        assertEquals(0, r.capture!!.requests.size, "the capture waits for the connection to be released")
        advanceTimeBy(200); runCurrent()
        assertEquals(1, r.capture.requests.size)
        val req = r.capture.requests.single()
        assertEquals("p1", req.instanceId)
        assertTrue(req.singleUseLink)
        assertEquals(live, req.firstLink)
        assertEquals(LivePausePolicy.DEFAULT_KEEP_MS, req.keepMs)
    }

    @Test
    fun `preparing carries a bounded live pause deadline`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        val preparing = assertIs<LivePauseState.Preparing>(r.controller.state.value)
        assertEquals(LivePausePolicy.BUFFER_START_DEADLINE_MS, preparing.status.deadlineAtMs - preparing.status.startedAtMs)
        assertIs<LoadStatus.Loading>(preparing.status)
    }

    @Test
    fun `first piece moves preparing to shifted on the local playlist`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Ready("file:///c/live-pause/p1/index.m3u8", 2_000))
        runCurrent()
        assertEquals(LivePauseSurface.Local("file:///c/live-pause/p1/index.m3u8"), r.controller.surface.value)
        assertEquals(
            LivePauseState.Shifted(paused = true, behindLiveMs = 2_000, windowStartMs = 0, windowEndMs = 2_000, positionMs = 0, bufferFull = false),
            r.controller.state.value,
        )
    }

    private suspend fun TestScope.shifted(r: Rig) {
        r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Ready("file:///x/index.m3u8", 2_000))
        runCurrent()
    }

    @Test
    fun `buffered events grow the window and report a full buffer`() = runTest {
        val r = rig(); shifted(r)
        r.capture!!.handles.single().emit(LiveCaptureEvent.Buffered(60_000, full = false)); runCurrent()
        var s = assertIs<LivePauseState.Shifted>(r.controller.state.value)
        assertEquals(60_000, s.windowEndMs); assertEquals(60_000, s.behindLiveMs); assertFalse(s.bufferFull)
        r.capture.handles.single().emit(LiveCaptureEvent.Buffered(600_000, full = true)); runCurrent()
        s = assertIs(r.controller.state.value)
        assertTrue(s.bufferFull)
    }

    @Test
    fun `snapshots move the position and behind live`() = runTest {
        val r = rig(); shifted(r)
        r.capture!!.handles.single().emit(LiveCaptureEvent.Buffered(60_000, false)); runCurrent()
        r.controller.onSnapshot(positionMs = 15_000, isPlaying = true)
        val s = assertIs<LivePauseState.Shifted>(r.controller.state.value)
        assertEquals(15_000, s.positionMs); assertEquals(45_000, s.behindLiveMs)
    }

    @Test
    fun `a late snapshot of the released live player is ignored`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        r.controller.onSnapshot(positionMs = 99_000, isPlaying = true)
        assertIs<LivePauseState.Preparing>(r.controller.state.value)
    }

    @Test
    fun `seek in window clamps to the buffered range`() = runTest {
        val r = rig(); shifted(r)
        r.capture!!.handles.single().emit(LiveCaptureEvent.Buffered(60_000, false)); runCurrent()
        r.controller.seekInWindow(500_000)
        r.controller.seekInWindow(-5)
        assertEquals(listOf("seek:60000", "seek:0"), r.engine.calls)
    }

    @Test
    fun `play and pause drive the engine and the paused flag`() = runTest {
        val r = rig(); shifted(r)
        r.controller.onPlay()
        assertEquals("play", r.engine.calls.last())
        assertFalse(assertIs<LivePauseState.Shifted>(r.controller.state.value).paused)
        r.controller.onPause()
        assertEquals("pause", r.engine.calls.last())
        assertTrue(assertIs<LivePauseState.Shifted>(r.controller.state.value).paused)
    }

    @Test
    fun `play pressed while preparing plays the local source once the engine attaches`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        r.controller.onPlay()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Ready("file:///x/index.m3u8", 2_000)); runCurrent()
        assertFalse(assertIs<LivePauseState.Shifted>(r.controller.state.value).paused)
        r.controller.attachEngine(r.engine, disk)   // the surface swapped and its player is ready
        assertEquals(listOf("play"), r.engine.calls)
    }

    @Test
    fun `a pause that stays paused does not play when the engine attaches`() = runTest {
        val r = rig(); shifted(r)
        r.controller.attachEngine(r.engine, disk)
        assertTrue(r.engine.calls.none { it == "play" })
    }

    private suspend fun TestScope.assertFails(reason: LivePauseFailure, emitBeforeReady: Boolean) {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val h = r.capture!!.handles.single()
        if (!emitBeforeReady) { h.emit(LiveCaptureEvent.Ready("file:///x/index.m3u8", 2_000)); runCurrent() }
        h.emit(LiveCaptureEvent.Failed(reason)); runCurrent()
        assertEquals(LivePauseState.Failed(reason), r.controller.state.value, "reason $reason")
        if (emitBeforeReady) {
            assertTrue(h.disposed >= 1, "a capture that failed before a buffer existed is disposed (deletes its directory)")
        } else {
            assertTrue(h.stopped >= 1, "a capture that failed under a viewer is stopped")
            assertEquals(0, h.disposed, "but the buffer being watched is kept until Live")
        }
    }

    @Test
    fun `every failure reason reaches the state before and after the first piece`() = runTest {
        for (reason in LivePauseFailure.entries) {
            assertFails(reason, emitBeforeReady = true)
            assertFails(reason, emitBeforeReady = false)
        }
    }

    @Test
    fun `a capture that cannot even start is a refused provider`() = runTest {
        val port = FakeCapturePort().apply { throwOnStart = IllegalStateException("401") }
        val r = rig(port); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.PROVIDER_REFUSED), r.controller.state.value)
    }

    @Test
    fun `a capture flow that ends without a piece is a stall`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().dispose(); runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.STALLED), r.controller.state.value)
    }

    @Test
    fun `no first piece within the deadline times out and disposes the capture`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        advanceTimeBy(LivePausePolicy.BUFFER_START_DEADLINE_MS + 1_000); runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.TIMED_OUT), r.controller.state.value)
        assertTrue(r.capture!!.handles.single().disposed >= 1)
    }

    @Test
    fun `retry goes live then pauses again once playing`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.NO_SPACE)); runCurrent()
        r.controller.retry()
        assertEquals(1, r.host.retunes)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertEquals(LivePauseSurface.Live, r.controller.surface.value)
        r.openLive()   // the screen re-resolved the same channel
        r.controller.onSnapshot(0, isPlaying = false)
        assertEquals(LivePauseState.Off, r.controller.state.value, "waits for the picture")
        r.controller.onSnapshot(1_000, isPlaying = true)
        assertIs<LivePauseState.Preparing>(r.controller.state.value)
    }

    @Test
    fun `a pending retry does not survive a switch to another channel`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED)); runCurrent()
        r.controller.retry()
        r.openLive(id = "c2")
        r.controller.onSnapshot(1_000, isPlaying = true)
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `retry outside failed does nothing`() = runTest {
        val r = rig(); r.openLive()
        r.controller.retry()
        assertEquals(0, r.host.retunes)
    }

    @Test
    fun `live from a disk buffer disposes the capture and retunes`() = runTest {
        val r = rig(); shifted(r)
        r.controller.goLive()
        assertTrue(r.capture!!.handles.single().disposed >= 1)
        assertEquals(1, r.host.retunes)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertEquals(LivePauseSurface.Live, r.controller.surface.value)
    }

    @Test
    fun `a new live source ends the session and deletes the buffer`() = runTest {
        val r = rig(); shifted(r)
        r.openLive(id = "c2")
        assertTrue(r.capture!!.handles.single().disposed >= 1)
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `dispose deletes the buffer and makes the controller inert`() = runTest {
        val r = rig(); shifted(r)
        r.controller.dispose()
        assertTrue(r.capture!!.handles.single().disposed >= 1)
        r.controller.onPause()
        assertEquals(1, r.capture.requests.size)
        assertFalse(r.controller.handlesPlayPause)
    }

    @Test
    fun `dispose during preparing cancels the pending capture start`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        r.controller.dispose()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, r.capture!!.requests.size)
    }

    // --- mechanism selection ---------------------------------------------------------------------

    @Test
    fun `without a capture port or an engine capability the plain pause stays`() = runTest {
        val r = rig(capture = null); r.openLive()
        assertFalse(r.controller.handlesPlayPause)
        r.controller.onPause()
        assertEquals(listOf("pause"), r.engine.calls)
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `a catch-up replay is never buffered`() = runTest {
        val r = rig(); r.openLive(channel = liveChannel.copy(isCatchUpPlayback = true))
        assertFalse(r.controller.handlesPlayPause)
    }

    // --- PROVIDER_CATCHUP ------------------------------------------------------------------------

    @Test
    fun `a short pause on an archive channel just resumes`() = runTest {
        val r = rig(); r.openLive(channel = archiveChannel)
        r.controller.onPause()
        assertEquals("pause", r.engine.calls.last())
        assertIs<LivePauseState.Shifted>(r.controller.state.value)
        r.clock.wall += 19_000
        r.controller.onPlay()
        assertEquals("play", r.engine.calls.last())
        assertEquals(0, r.host.catchUps.size)
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `a long pause on an archive channel resumes from the archive at the pause instant`() = runTest {
        val r = rig(); r.openLive(channel = archiveChannel)
        val pausedAt = r.clock.wall
        r.controller.onPause()
        r.clock.wall += 20_000
        r.controller.onPlay()
        assertEquals(listOf(pausedAt to pausedAt + LivePausePolicy.DEFAULT_KEEP_MS), r.host.catchUps)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertTrue(r.engine.calls.none { it == "play" }, "the archive opens instead of resuming the live source")
    }

    // --- ENGINE_BACK_BUFFER ----------------------------------------------------------------------

    @Test
    fun `mpv gets the live plan when a live channel opens`() = runTest {
        val r = rig(config = mpvConfig); r.openLive(caps = mpv)
        val expected = LivePausePolicy.engineLivePlan(mpvConfig.engineMemoryBudgetBytes, mpvConfig.engineNormalPlan, null)
        assertEquals(listOf(expected), r.engine.budgets)
    }

    @Test
    fun `mpv pause raises the caches pauses and reports the window and play restores the live plan`() = runTest {
        val r = rig(config = mpvConfig); r.openLive(caps = mpv)
        r.engine.budgets.clear()
        r.engine.window = LiveSeekWindow(startMs = 100_000, endMs = 160_000, positionMs = 160_000)
        r.controller.onPause()
        assertEquals(listOf(LivePausePolicy.enginePausedPlan(mpvConfig.engineMemoryBudgetBytes, mpvConfig.engineNormalPlan, null)), r.engine.budgets)
        assertEquals("pause", r.engine.calls.last())
        assertEquals(
            LivePauseState.Shifted(true, 0, 100_000, 160_000, 160_000, false),
            r.controller.state.value,
        )
        r.engine.budgets.clear()
        r.controller.onPlay()
        assertEquals(listOf(LivePausePolicy.engineLivePlan(mpvConfig.engineMemoryBudgetBytes, mpvConfig.engineNormalPlan, null)), r.engine.budgets)
        assertEquals("play", r.engine.calls.last())
    }

    @Test
    fun `mpv does not raise the caches when the device budget does not cover the normal plan`() = runTest {
        val tight = mpvConfig.copy(engineMemoryBudgetBytes = 100L * 1024 * 1024)
        val r = rig(config = tight); r.openLive(caps = mpv)
        r.engine.window = LiveSeekWindow(0, 30_000, 30_000)
        r.controller.onPause()
        assertEquals(emptyList(), r.engine.budgets)
        assertEquals("pause", r.engine.calls.last(), "pause still pauses")
    }

    @Test
    fun `mpv scrub is bounded by the engine window`() = runTest {
        val r = rig(config = mpvConfig); r.openLive(caps = mpv)
        r.engine.window = LiveSeekWindow(100_000, 160_000, 140_000)
        r.controller.onPause()
        r.controller.seekInWindow(10_000)
        r.controller.seekInWindow(900_000)
        assertEquals(listOf("pause", "seek:100000", "seek:160000"), r.engine.calls)
    }

    @Test
    fun `mpv playing back up to the live edge returns to off`() = runTest {
        val r = rig(config = mpvConfig); r.openLive(caps = mpv)
        r.engine.window = LiveSeekWindow(100_000, 160_000, 120_000)
        r.controller.onPause()
        r.controller.onPlay()
        r.engine.window = LiveSeekWindow(100_000, 162_000, 150_000)
        r.controller.onSnapshot(150_000, isPlaying = true)
        assertEquals(12_000, assertIs<LivePauseState.Shifted>(r.controller.state.value).behindLiveMs)
        r.engine.window = LiveSeekWindow(100_000, 164_000, 163_000)
        r.controller.onSnapshot(163_000, isPlaying = true)
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `mpv go live seeks to the edge while the window is healthy and retunes when it is not`() = runTest {
        val r = rig(config = mpvConfig); r.openLive(caps = mpv)
        r.engine.window = LiveSeekWindow(100_000, 160_000, 120_000)
        r.controller.onPause()
        r.controller.goLive()
        assertEquals(listOf("pause", "seek:160000", "play"), r.engine.calls)
        assertEquals(0, r.host.retunes)
        assertEquals(LivePauseState.Off, r.controller.state.value)

        val r2 = rig(config = mpvConfig); r2.openLive(caps = mpv)
        r2.controller.onPause()          // no window known
        r2.controller.goLive()
        assertEquals(1, r2.host.retunes)
    }

    @Test
    fun `instances are independent`() = runTest {
        val a = rig(); val b = rig()
        a.openLive(); b.openLive()
        a.controller.onPause()
        assertEquals(LivePauseState.Off, b.controller.state.value)
        assertNotNull(a.controller.instanceId)
    }

    // --- play/pause button, reset, archive fact ---------------------------------------------------

    @Test
    fun `the play pause button follows the controller while a session runs`() = runTest {
        val r = rig(); shifted(r)
        r.controller.togglePlayPause(isPlaying = true)   // the stale live snapshot says playing; the session says paused
        assertEquals("play", r.engine.calls.last())
        r.controller.togglePlayPause(isPlaying = false)
        assertEquals("pause", r.engine.calls.last())
    }

    @Test
    fun `the play pause button changes the wish while preparing and goes live once failed`() = runTest {
        val r = rig(); r.openLive()
        r.controller.togglePlayPause(isPlaying = true)   // pause
        assertIs<LivePauseState.Preparing>(r.controller.state.value)
        r.controller.togglePlayPause(isPlaying = false)  // play requested while preparing
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED)); runCurrent()
        r.controller.togglePlayPause(isPlaying = false)
        assertEquals(1, r.host.retunes, "Play on a failed pause goes live instead of doing nothing")
        assertEquals(LivePauseState.Off, r.controller.state.value)
    }

    @Test
    fun `reset on a zap drops the buffer and the channel`() = runTest {
        val r = rig(); shifted(r)
        r.controller.reset()
        assertTrue(r.capture!!.handles.single().disposed >= 1)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertEquals(LivePauseSurface.Live, r.controller.surface.value)
        assertFalse(r.controller.handlesPlayPause, "no channel until the next live source opens")
    }

    @Test
    fun `an archive fact that arrives after the tune switches the mechanism between sessions`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onArchiveKnown(true)
        r.controller.onPause()
        assertEquals("pause", r.engine.calls.last(), "provider catch-up: no buffer, no capture")
        assertEquals(0, r.capture!!.requests.size)
        assertIs<LivePauseState.Shifted>(r.controller.state.value)
    }

    // --- Opus final-review fixes -----------------------------------------------------------------

    @Test
    fun `a capture that fails right behind its first piece never shows a buffer`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val h = r.capture!!.handles.single()
        h.emit(LiveCaptureEvent.Ready("file:///x/index.m3u8", 2_000))
        h.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED))   // both land before the start job resumes
        runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.STALLED), r.controller.state.value)
        assertEquals(LivePauseSurface.Released, r.controller.surface.value, "no Local surface on a dead capture")
    }

    @Test
    fun `play on a failed pause goes live`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture!!.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.NO_SPACE)); runCurrent()
        r.controller.onPlay()
        assertEquals(1, r.host.retunes)
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertEquals(LivePauseSurface.Live, r.controller.surface.value)
    }

    @Test
    fun `a stall while shifted keeps the buffer being watched until live`() = runTest {
        val r = rig(); shifted(r)
        val h = r.capture!!.handles.single()
        h.emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED)); runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.STALLED), r.controller.state.value)
        assertIs<LivePauseSurface.Local>(r.controller.surface.value, "the player keeps its source")
        assertEquals(1, h.stopped)
        assertEquals(0, h.disposed, "the files under the player are not deleted")
        r.controller.goLive()
        assertTrue(h.disposed >= 1, "Live finally deletes them")
    }

    @Test
    fun `dispose mid start deletes the capture that is already open`() = runTest {
        val r = rig(); r.openLive()
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()   // the capture is open, no piece yet
        r.controller.dispose()
        runCurrent()
        assertTrue(r.capture!!.handles.single().disposed >= 1)
    }

    @Test
    fun `retry and the capture share one bounded attempt counter`() = runTest {
        val r = rig(); r.openLive(singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val first = r.capture!!.requests.single().reconnect
        repeat(3) { first.onDrop() }   // the capture spent its reconnects, each minting a single-use link
        r.capture.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED)); runCurrent()
        r.controller.retry()
        r.openLive(singleUse = true)   // the screen re-resolved the same channel
        r.controller.onSnapshot(1_000, isPlaying = true)
        advanceTimeBy(1_500); runCurrent()
        val second = r.capture.requests.last().reconnect
        assertTrue(first === second, "Retry continues the same counter")
        assertIs<LiveCaptureReconnect.Decision.Retry>(second.onDrop(), "one more reconnect is allowed")
        assertEquals(LiveCaptureReconnect.Decision.GiveUp, second.onDrop(), "and then it is over")
    }

    @Test
    fun `another channel starts with a fresh attempt counter`() = runTest {
        val r = rig(); r.openLive(singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val first = r.capture!!.requests.single().reconnect
        r.openLive(singleUse = true, id = "c2")
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        assertTrue(first !== r.capture.requests.last().reconnect)
    }

    @Test
    fun `a held scrub key does not repeat the same seek`() = runTest {
        val r = rig(); shifted(r)
        r.capture!!.handles.single().emit(LiveCaptureEvent.Buffered(60_000, false)); runCurrent()
        r.controller.seekInWindow(0)
        r.controller.seekInWindow(0)
        r.controller.seekInWindow(-1_000)
        r.controller.seekInWindow(30_000)
        r.controller.seekInWindow(30_000)
        assertEquals(listOf("seek:30000"), r.engine.calls)
    }
}
