package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.CatchUpContinuationPolicy
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayContinuation.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An Xtream replay carries on past its programme exactly as an M3U one does (through ReplayLadder). */
class XtreamReplaySessionTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 300 * minute
    private val end = start + 60 * minute

    private class Memory : CatchUpDialectWalk.WinnerMemory {
        override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? = null
        override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) = Unit
    }

    private fun request(startMs: Long = start, endMs: Long = end) = CatchUpDialectWalk.Request(
        accountId = "acct", baseUrl = "https://example.com", username = "user", password = "pass",
        streamId = 777, startMs = startMs, endMs = endMs,
    )

    private fun session(walk: CatchUpDialectWalk = CatchUpDialectWalk(Memory()), req: CatchUpDialectWalk.Request = request(), nowMs: Long = now) =
        beginLaunchReplay(walk, req, "xtream:acct:live:777", "News", req.startMs, req.endMs, nowMs)!!

    @Test
    fun `an xtream replay is a continuing session carrying the window it asked for`() {
        val s = session()
        assertTrue(s.continues, "the screen follows the playhead on past the programme")
        assertEquals(
            CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(start, end), now),
            s.windowEndMs,
        )
        assertTrue(s.windowEndMs > end, "the link asks for more than the programme")
        assertNotNull(s.ladder)
        assertNotNull(s.release)
    }

    @Test
    fun `the playhead entering the next show retitles it`() {
        val s = session()
        val next = M3uReplayContinuation.Row("Weather", end, end + 30 * minute)
        assertEquals(
            Action.Retitle(next),
            M3uReplayContinuation.react(s.continuationState(), next, 65 * minute, ended = false, nowMs = now),
        )
    }

    @Test
    fun `a window that runs out mid-guide restarts through the session's ladder`() {
        val s = session()
        val row = M3uReplayContinuation.Row("Late", s.windowEndMs - 10 * minute, s.windowEndMs + 50 * minute)
        val action = M3uReplayContinuation.react(s.continuationState(), row, s.windowEndMs - start, ended = true, nowMs = now)
        val restart = action as Action.Restart
        val begun = assertNotNull(s.ladder!!.restartAt(s.attempt.token, restart.atMs, now))
        assertTrue(begun.windowEndMs > restart.atMs)
        assertTrue(begun.attempt.token != s.attempt.token)
    }

    @Test
    fun `a window that ends at the live edge goes live`() {
        val recentEnd = now - 10 * minute
        val s = session(req = request(recentEnd - 60 * minute, recentEnd))
        assertEquals(now - CatchUpContinuationPolicy.AIRED_MARGIN_MS, s.windowEndMs, "capped at what has aired")
        assertEquals(
            Action.GoLive,
            M3uReplayContinuation.react(s.continuationState(), null, s.windowEndMs - s.streamStartMs, ended = true, nowMs = now),
        )
    }

    @Test
    fun `a window that ends with nothing to replay ends the replay`() {
        val s = session()
        assertEquals(
            Action.End,
            M3uReplayContinuation.react(s.continuationState(), null, s.windowEndMs - start + 5 * minute, ended = true, nowMs = now + 10 * 60 * minute),
        )
    }

    @Test
    fun `releasing the session drops the walk's ask`() {
        val walk = CatchUpDialectWalk(Memory())
        val s = session(walk)
        s.release!!.dispose()
        assertNull(s.ladder!!.restartAt(s.attempt.token, start + minute, now))
    }
}
