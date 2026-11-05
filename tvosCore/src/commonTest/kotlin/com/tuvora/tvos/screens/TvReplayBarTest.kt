package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvReplayBarTest {
    private val min = 60_000L

    @Test
    fun `a replay that has not moved on starts the bar at zero`() {
        val origin = TvReplayBar.originMs(programmeStartMs = 1_000L, linkStartMs = 1_000L)
        assertEquals(0L, origin)
        assertEquals(5 * min, TvReplayBar.position(5 * min, origin, 60 * min))
    }

    @Test
    fun `after the playhead enters the next programme the bar restarts at its start`() {
        // First show 60 min, the next starts at +60: the link still started with the first show.
        val origin = TvReplayBar.originMs(programmeStartMs = 60 * min, linkStartMs = 0L)
        assertEquals(60 * min, origin)
        assertEquals(0L, TvReplayBar.position(60 * min, origin, 30 * min))
        assertEquals(10 * min, TvReplayBar.position(70 * min, origin, 30 * min))
    }

    @Test
    fun `a restarted link that begins part-way into a programme has a negative origin`() {
        val origin = TvReplayBar.originMs(programmeStartMs = 0L, linkStartMs = 20 * min)
        assertEquals(-20 * min, origin)
        assertEquals(20 * min, TvReplayBar.position(0L, origin, 60 * min))
    }

    @Test
    fun `the bar never leaves the programme`() {
        assertEquals(0L, TvReplayBar.position(5 * min, 10 * min, 30 * min))
        assertEquals(30 * min, TvReplayBar.position(90 * min, 10 * min, 30 * min))
    }

    @Test
    fun `a seek on the bar is sent as a player position`() {
        assertEquals(70 * min, TvReplayBar.raw(10 * min, 60 * min))
        assertEquals(0L, TvReplayBar.raw(5 * min, -20 * min), "never before the link start")
    }

    @Test
    fun `a programme still airing cannot be sought past what has aired`() {
        val now = 1_800_000_000_000L
        val start = now - 20 * min
        val end = start + 60 * min
        val guard = com.nuvio.app.features.iptv.CatchUpPlayback.LIVE_EDGE_GUARD_MS
        assertEquals(20 * min - guard, TvReplayBar.maxSeekMs(start, end, now))
        assertEquals(20 * min - guard, TvReplayBar.clampSeek(45 * min, start, end, now))
        assertEquals(5 * min, TvReplayBar.clampSeek(5 * min, start, end, now))
    }

    @Test
    fun `a finished programme is seekable to its end and a just-started one has nothing to seek`() {
        val now = 1_800_000_000_000L
        assertEquals(60 * min, TvReplayBar.clampSeek(90 * min, now - 3 * 60 * min, now - 2 * 60 * min, now))
        assertEquals(0L, TvReplayBar.clampSeek(10 * min, now - 10_000L, now + 59 * min, now))
    }
}
