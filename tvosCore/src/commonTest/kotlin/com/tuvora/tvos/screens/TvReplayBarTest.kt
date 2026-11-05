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
}
