package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.livetv.pause.LivePausePolicy.EngineCachePlan
import com.nuvio.app.features.livetv.pause.LivePausePolicy.GoLiveAction
import com.nuvio.app.features.livetv.pause.LivePausePolicy.HlsVariant
import com.nuvio.app.features.livetv.pause.LivePausePolicy.RefuseReason
import com.nuvio.app.features.livetv.pause.LivePausePolicy.SpaceVerdict
import com.nuvio.app.features.livetv.pause.LivePausePolicy.StartDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class LivePausePolicyTest {
    private val live = LivePauseChannel(isLive = true, isCatchUpPlayback = false, hasProviderArchive = false)
    private val bothCaps = LivePauseCapabilities(diskBuffer = true, engineBackBuffer = true)
    private val noCaps = LivePauseCapabilities(diskBuffer = false, engineBackBuffer = false)
    private val reserve = LivePausePolicy.FREE_SPACE_RESERVE_BYTES

    // ---- mechanism

    @Test
    fun `a channel that is not live has no live pause`() {
        assertEquals(LivePauseMechanism.UNAVAILABLE, LivePausePolicy.mechanism(live.copy(isLive = false), bothCaps))
    }

    @Test
    fun `a catch-up replay has no live pause because it already seeks`() {
        assertEquals(
            LivePauseMechanism.UNAVAILABLE,
            LivePausePolicy.mechanism(live.copy(isCatchUpPlayback = true, hasProviderArchive = true), bothCaps),
        )
    }

    @Test
    fun `a provider archive wins over local buffers`() {
        assertEquals(LivePauseMechanism.PROVIDER_CATCHUP, LivePausePolicy.mechanism(live.copy(hasProviderArchive = true), bothCaps))
    }

    @Test
    fun `disk buffer wins over engine back buffer`() {
        assertEquals(LivePauseMechanism.DISK_BUFFER, LivePausePolicy.mechanism(live, bothCaps))
    }

    @Test
    fun `engine back buffer is used when there is no disk buffer`() {
        assertEquals(
            LivePauseMechanism.ENGINE_BACK_BUFFER,
            LivePausePolicy.mechanism(live, LivePauseCapabilities(diskBuffer = false, engineBackBuffer = true)),
        )
    }

    @Test
    fun `no archive and no capability is unavailable`() {
        assertEquals(LivePauseMechanism.UNAVAILABLE, LivePausePolicy.mechanism(live, noCaps))
    }

    // ---- sizing

    @Test
    fun `bytesFor includes the container overhead`() {
        // 8 Mbps = 1 MB/s; 60 s = 60 MB; plus 10 percent.
        assertEquals(66_000_000L, LivePausePolicy.bytesFor(60_000L, 8_000_000L))
    }

    @Test
    fun `bytesFor falls back to the HD assumption when the bitrate is unknown`() {
        assertEquals(66_000_000L, LivePausePolicy.bytesFor(60_000L, null))
        assertEquals(66_000_000L, LivePausePolicy.bytesFor(60_000L, 0L))
    }

    @Test
    fun `bytesFor never goes negative`() {
        assertEquals(0L, LivePausePolicy.bytesFor(-5_000L, 8_000_000L))
    }

    @Test
    fun `durationFor is the inverse of bytesFor`() {
        assertEquals(60_000L, LivePausePolicy.durationFor(66_000_000L, 8_000_000L))
        assertEquals(0L, LivePausePolicy.durationFor(-1L, 8_000_000L))
    }

    // ---- diskStart

    @Test
    fun `plenty of space starts the full default window`() {
        val free = reserve + 660_000_000L
        assertEquals(
            StartDecision.Start(keepMs = LivePausePolicy.DEFAULT_KEEP_MS, budgetBytes = 660_000_000L),
            LivePausePolicy.diskStart(free, 8_000_000L),
        )
    }

    @Test
    fun `less space shrinks the window to what fits`() {
        val free = reserve + 330_000_000L
        assertEquals(
            StartDecision.Start(keepMs = 300_000L, budgetBytes = 330_000_000L),
            LivePausePolicy.diskStart(free, 8_000_000L),
        )
    }

    @Test
    fun `a window under the minimum is refused for lack of space`() {
        val free = reserve + 60_000_000L // fits only about 54 s
        assertEquals(StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(free, 8_000_000L))
    }

    @Test
    fun `free space at or under the reserve is refused`() {
        assertEquals(StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(reserve, 8_000_000L))
        assertEquals(StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(0L, 8_000_000L))
    }

    @Test
    fun `a smaller requested window is honoured`() {
        val free = reserve + 5_000_000_000L
        assertEquals(
            StartDecision.Start(keepMs = 120_000L, budgetBytes = 132_000_000L),
            LivePausePolicy.diskStart(free, 8_000_000L, requestedKeepMs = 120_000L),
        )
    }

    @Test
    fun `an unknown bitrate sizes with the fallback`() {
        val free = reserve + 660_000_000L
        assertEquals(LivePausePolicy.diskStart(free, 8_000_000L), LivePausePolicy.diskStart(free, null))
    }

    @Test
    fun `a higher bitrate fits a shorter window in the same space`() {
        val free = reserve + 660_000_000L
        val d = LivePausePolicy.diskStart(free, 16_000_000L) as StartDecision.Start
        assertEquals(300_000L, d.keepMs)
    }

    // ---- beforeWrite

    @Test
    fun `a write with room everywhere goes ahead`() {
        assertEquals(SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(reserve + 10_000_000L, 1_000_000L, 5_000_000L, 100_000_000L))
    }

    @Test
    fun `low disk with buffered pieces trims the oldest`() {
        assertEquals(SpaceVerdict.TRIM_OLDEST, LivePausePolicy.beforeWrite(reserve + 500_000L, 1_000_000L, 5_000_000L, 100_000_000L))
    }

    @Test
    fun `low disk with nothing buffered stops for lack of space`() {
        assertEquals(SpaceVerdict.STOP_NO_SPACE, LivePausePolicy.beforeWrite(reserve + 500_000L, 1_000_000L, 0L, 100_000_000L))
    }

    @Test
    fun `exactly the reserve left after the write is still allowed`() {
        assertEquals(SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(reserve + 1_000_000L, 1_000_000L, 0L, 100_000_000L))
    }

    @Test
    fun `an over budget buffer trims the oldest even with free space`() {
        assertEquals(SpaceVerdict.TRIM_OLDEST, LivePausePolicy.beforeWrite(reserve + 9_000_000_000L, 1_000_000L, 99_500_000L, 100_000_000L))
    }

    @Test
    fun `an oversize piece with nothing buffered is written not trimmed`() {
        assertEquals(SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(reserve + 9_000_000_000L, 200_000_000L, 0L, 100_000_000L))
    }

    @Test
    fun `exactly at budget is allowed`() {
        assertEquals(SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(reserve + 9_000_000_000L, 1_000_000L, 99_000_000L, 100_000_000L))
    }

    // ---- piecesToDrop

    @Test
    fun `nothing is dropped while within the window`() {
        assertEquals(0, LivePausePolicy.piecesToDrop(List(5) { 6_000L }, keepMs = 30_000L))
    }

    @Test
    fun `old pieces are dropped until the rest fits`() {
        assertEquals(5, LivePausePolicy.piecesToDrop(List(10) { 6_000L }, keepMs = 30_000L))
        assertEquals(6, LivePausePolicy.piecesToDrop(List(10) { 6_000L }, keepMs = 29_999L))
    }

    @Test
    fun `at least three pieces are always kept`() {
        assertEquals(7, LivePausePolicy.piecesToDrop(List(10) { 6_000L }, keepMs = 0L))
        assertEquals(0, LivePausePolicy.piecesToDrop(List(3) { 60_000L }, keepMs = 1_000L))
        assertEquals(0, LivePausePolicy.piecesToDrop(emptyList(), keepMs = 1_000L))
    }

    @Test
    fun `uneven pieces are summed not counted`() {
        assertEquals(2, LivePausePolicy.piecesToDrop(listOf(30_000L, 30_000L, 2_000L, 2_000L, 2_000L), keepMs = 6_000L))
    }

    // ---- engine plans

    private val normal = EngineCachePlan(maxBytes = 150_000_000L, maxBackBytes = 50_000_000L, cacheSecs = 60L)

    @Test
    fun `paused plan gives the back buffer up to half the budget and the rest to the forward cache`() {
        val plan = LivePausePolicy.enginePausedPlan(1_000_000_000L, normal, 8_000_000L)
        assertEquals(EngineCachePlan(maxBytes = 500_000_000L, maxBackBytes = 500_000_000L, cacheSecs = 454L), plan)
    }

    @Test
    fun `paused plan sizes the back buffer to the keep window when that is smaller`() {
        val plan = LivePausePolicy.enginePausedPlan(1_000_000_000L, normal, 8_000_000L, keepMs = 60_000L)
        assertEquals(66_000_000L, plan.maxBackBytes)
        assertEquals(934_000_000L, plan.maxBytes)
    }

    @Test
    fun `paused forward cache is never below the normal one`() {
        val plan = LivePausePolicy.enginePausedPlan(100_000_000L, normal, 8_000_000L)
        assertEquals(50_000_000L, plan.maxBackBytes)
        assertEquals(150_000_000L, plan.maxBytes)
        assertEquals(136L, plan.cacheSecs, "150 MB at 8.8 Mbps effective")
    }

    @Test
    fun `live plan keeps the normal forward cache and adds the back buffer`() {
        val plan = LivePausePolicy.engineLivePlan(1_000_000_000L, normal, 8_000_000L)
        assertEquals(EngineCachePlan(maxBytes = 150_000_000L, maxBackBytes = 500_000_000L, cacheSecs = 60L), plan)
    }

    // ---- go live

    @Test
    fun `go live retunes for disk buffer and provider catch-up`() {
        for (healthy in listOf(true, false)) {
            assertEquals(GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.DISK_BUFFER, healthy))
            assertEquals(GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.PROVIDER_CATCHUP, healthy))
        }
    }

    @Test
    fun `go live seeks to the edge on a healthy engine buffer and retunes otherwise`() {
        assertEquals(GoLiveAction.SEEK_TO_EDGE, LivePausePolicy.goLive(LivePauseMechanism.ENGINE_BACK_BUFFER, true))
        assertEquals(GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.ENGINE_BACK_BUFFER, false))
    }

    // ---- variants

    private val v1 = HlsVariant("low.m3u8", 1_000_000L, 360)
    private val v5 = HlsVariant("mid.m3u8", 5_000_000L, 720)
    private val v20 = HlsVariant("high.m3u8", 20_000_000L, 2160)

    @Test
    fun `capture cap is lower on the low memory tier`() {
        assertEquals(6_000_000L, LivePausePolicy.maxCaptureBitrateBps(lowMemoryTier = true))
        assertEquals(15_000_000L, LivePausePolicy.maxCaptureBitrateBps(lowMemoryTier = false))
    }

    @Test
    fun `pickVariant takes the highest under the cap and not the highest bandwidth`() {
        assertEquals(v5, LivePausePolicy.pickVariant(listOf(v1, v20, v5), 15_000_000L))
    }

    @Test
    fun `pickVariant accepts a variant exactly at the cap`() {
        assertEquals(v5, LivePausePolicy.pickVariant(listOf(v1, v5, v20), 5_000_000L))
    }

    @Test
    fun `pickVariant falls back to the lowest when none fits`() {
        assertEquals(v5, LivePausePolicy.pickVariant(listOf(v20, v5), 2_000_000L))
    }

    @Test
    fun `pickVariant of nothing is null`() {
        assertEquals(null, LivePausePolicy.pickVariant(emptyList(), 15_000_000L))
    }
}
