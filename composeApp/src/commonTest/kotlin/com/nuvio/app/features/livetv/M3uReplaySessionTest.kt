package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.CatchUpContinuationPolicy
import com.nuvio.app.features.iptv.CatchUpPlayback
import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uCatchUpType
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayContinuation.Action
import com.nuvio.app.features.iptv.M3uReplayWalk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An M3U replay enters Live TV as a CONTINUING catch-up session (the same flag the gates read), asking the
 * provider for more than its programme, and the screen's session follows the continuation policy's actions.
 */
class M3uReplaySessionTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 300 * minute
    private val end = start + 60 * minute
    private val info = M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3)

    private class Memory : M3uReplayWalk.FormMemory {
        override fun recallM3uForm(accountId: String): String? = null
        override fun rememberM3uForm(accountId: String, form: String) = Unit
    }

    private fun request(live: String = "http://h:80/live/u/p/9.ts") =
        M3uReplayWalk.Request("pl", info, live, start, end, now)

    private fun session() = beginM3uReplay(M3uReplayWalk(Memory()), request(), "pl:live:9", "News", start, end)!!

    @Test
    fun `an m3u replay is a catch-up session that continues and carries the window it asked for`() {
        val s = session()
        assertTrue(s.continues, "the session is flagged so the screen follows the playhead on")
        assertEquals("pl:live:9", s.contentId)
        assertEquals(start, s.startMs)
        assertEquals(end, s.endMs)
        assertEquals(start, s.streamStartMs)
        assertEquals(
            CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(start, end), now),
            s.windowEndMs,
        )
        assertTrue(s.windowEndMs > end)
        // Same gates as any replay: isCatchUpPlayback is exactly "this returned non-null".
        assertFalse(CatchUpPlayback.allowsChannelChange(isCatchUpPlayback = true))
    }

    @Test
    fun `a link that cannot be built gives no session`() {
        val none = beginM3uReplay(M3uReplayWalk(Memory()), request(live = ""), "pl:live:9", "News", start, end)
        assertNull(none)
    }

    @Test
    fun `retitling moves the programme and offsets the scrub bar into the stream`() {
        val s = session()
        val next = M3uReplayContinuation.Row("Weather", end, end + 30 * minute)
        val action = M3uReplayContinuation.react(s.continuationState(), next, 65 * minute, ended = false, nowMs = now)
        assertEquals(Action.Retitle(next), action)
        val moved = s.copy(programmeTitle = next.title, startMs = next.startMs, endMs = next.endMs)
        assertEquals(60 * minute, moved.programmeOffsetMs, "the new programme starts an hour into the stream")
        assertEquals(0L, s.programmeOffsetMs, "a replay that has not moved on scrubs from zero")
    }

    @Test
    fun `a restart moves the stream origin to the playhead and keeps the programme timeline true`() {
        val w = M3uReplayWalk(Memory())
        val s = beginM3uReplay(w, request(), "pl:live:9", "News", start, end)!!
        val at = s.windowEndMs
        val begun = assertNotNull(w.restartAt(s.attempt.token, at, now))
        val restarted = s.copy(attempt = begun.attempt, streamStartMs = at, windowEndMs = begun.windowEndMs, proven = false)
        assertNotNull(restarted.continuationState())
        // The playhead is the new link's origin plus the player position.
        assertEquals(at + 30_000L, M3uReplayContinuation.playheadMs(restarted.continuationState().copy(url = "http://h/live/9.ts"), 30_000L))
        assertTrue(restarted.programmeOffsetMs < 0, "the link now starts after the programme did")
    }
}
