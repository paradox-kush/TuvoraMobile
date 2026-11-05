package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.M3uReplayContinuation.Action
import com.nuvio.app.features.iptv.M3uReplayContinuation.Row
import com.nuvio.app.features.iptv.M3uReplayContinuation.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The screen acts on the continuation policy's decision; these pin what it is fed and what it is told to do. */
class M3uReplayContinuationTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 5 * 60 * minute
    private val end = start + 60 * minute
    private val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(start, end), now)
    private val next = Row("Next show", end, end + 30 * minute)
    private val tsUrl = "http://h/live/c1.ts"

    private fun state(url: String = tsUrl, streamStart: Long = start) = State(
        CatchUpContinuationPolicy.Programme(start, end), streamStart, windowEnd, url,
    )

    @Test
    fun `inside the programme nothing changes`() {
        assertEquals(Action.Stay, M3uReplayContinuation.react(state(), next, 20 * minute, ended = false, nowMs = now))
    }

    @Test
    fun `crossing into the next programme retitles to it`() {
        assertEquals(Action.Retitle(next), M3uReplayContinuation.react(state(), next, 65 * minute, ended = false, nowMs = now))
    }

    @Test
    fun `the window running out mid-guide restarts at the playhead`() {
        val pos = windowEnd - start
        assertEquals(
            Action.Restart(windowEnd),
            M3uReplayContinuation.react(state(), Row("Late", windowEnd - minute, windowEnd + minute), pos, ended = false, nowMs = now),
        )
    }

    @Test
    fun `the window running out at the live edge rejoins live`() {
        val nowEdge = windowEnd + 10_000L
        assertEquals(Action.GoLive, M3uReplayContinuation.react(state(), null, windowEnd - start, ended = false, nowMs = nowEdge))
    }

    @Test
    fun `the window running out with nothing to replay ends`() {
        // Past the programme and its successor, the guide has no row covering the playhead.
        val gap = state()
        assertEquals(Action.End, M3uReplayContinuation.react(gap, null, windowEnd - start + 30 * minute, ended = false, nowMs = windowEnd + 2 * 60 * minute))
    }

    @Test
    fun `a stream that ended counts as the window running out`() {
        // Position stalls a little short of the window; the stream ending is the signal.
        val action = M3uReplayContinuation.react(state(), Row("Late", windowEnd - minute, windowEnd + minute), 20 * minute, ended = true, nowMs = now)
        assertEquals(Action.Restart(windowEnd), action)
    }

    @Test
    fun `a whole-minute link starts its clock at the minute not the programme's second`() {
        val xt = "http://h/timeshift/u/p/180/2026-11-04:20-30/42.ts"
        val s = state(url = xt, streamStart = start + 45_000L)
        assertEquals(start - (start % minute) + 90_000L, M3uReplayContinuation.playheadMs(s, 90_000L))
        assertEquals(start + 45_000L + 90_000L, M3uReplayContinuation.playheadMs(state(streamStart = start + 45_000L), 90_000L))
    }

    @Test
    fun `next programme is the one covering the playhead else the next to start`() {
        val rows = listOf(Row("A", 100, 200), Row("B", 200, 300), Row("bad", 0, 0), Row("C", 400, 500))
        assertEquals("A", CatchUpNextProgramme.pick(rows, 150)?.title)
        assertEquals("B", CatchUpNextProgramme.pick(rows, 200)?.title)
        assertEquals("C", CatchUpNextProgramme.pick(rows, 350)?.title)
        assertNull(CatchUpNextProgramme.pick(rows, 600))
    }
}
