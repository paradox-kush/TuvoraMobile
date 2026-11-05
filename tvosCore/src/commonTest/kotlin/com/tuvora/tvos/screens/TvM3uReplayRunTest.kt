package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uCatchUpType
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.tuvora.tvos.screens.TvM3uReplayRun.Move
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Apple TV M3U replay machine: proof, link walk, never-ending waits, continuation, rejoin live. */
class TvM3uReplayRunTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 300 * minute
    private val end = start + 60 * minute
    private val info = M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3)

    private class Memory : M3uReplayWalk.FormMemory {
        var remembered: String? = null
        override fun recallM3uForm(accountId: String): String? = null
        override fun rememberM3uForm(accountId: String, form: String) { remembered = form }
    }

    private val memory = Memory()
    private val walk = M3uReplayWalk(memory)

    private fun run(): TvM3uReplayRun {
        val begun = walk.begin(M3uReplayWalk.Request("pl", info, "http://h:80/live/u/p/9.ts", start, end, now))!!
        return TvM3uReplayRun(walk, "News 1", "Morning", start, end, begun)
    }

    private fun TvM3uReplayRun.tick(
        playing: Boolean = false, pos: Long = 0L, ended: Boolean = false, error: String? = null,
        elapsed: Long = 0L, next: M3uReplayContinuation.Row? = null,
    ) = observe(playing, pos, ended, error, elapsed, next, now)

    @Test
    fun `the player title is channel and programme`() {
        assertEquals("News 1 · Morning", run().initialTitle())
    }

    @Test
    fun `a link still opening waits`() {
        assertEquals(Move.Wait, run().tick())
    }

    @Test
    fun `a link that plays is remembered and then left alone`() {
        val r = run()
        assertEquals(Move.Wait, r.tick(playing = true, pos = 1_000L))
        assertTrue(memory.remembered != null, "the form that played is pinned for the playlist")
        assertEquals(Move.Wait, r.tick(playing = true, pos = 10 * minute))
    }

    @Test
    fun `a transport failure walks to the next link and the first link was not the last`() {
        val r = run()
        val first = r.url
        val move = r.tick(error = "Failed to open")
        assertIs<Move.Reopen>(move)
        assertFalse(move.attempt.url == first)
        assertEquals(move.attempt.url, r.url)
    }

    @Test
    fun `a link that never answers ends in a failure with Retry not a spinner`() {
        val r = run()
        var move: Move = r.tick(elapsed = TvM3uReplayRun.STARTUP_DEADLINE_MS)
        var guard = 0
        while (move is Move.Reopen && guard++ < 20) move = r.tick(elapsed = TvM3uReplayRun.STARTUP_DEADLINE_MS)
        val failed = assertIs<Move.Failed>(move)
        assertTrue(failed.retry, "a hang is retryable")
        assertEquals(TvReplayPolicy.REPLAY_FAILED_TEXT, failed.notice)
    }

    @Test
    fun `a link source that does not resolve walks on and ends in a failure with Retry`() {
        val r = run()
        var move: Move = r.sourceFailed()
        var guard = 0
        while (move is Move.Reopen && guard++ < 20) move = r.sourceFailed()
        assertTrue((move as Move.Failed).retry)
    }

    @Test
    fun `a decode failure is not retried and says no recording`() {
        val r = run()
        val move = r.tick(error = "Unsupported codec: decoder failed")
        val failed = assertIs<Move.Failed>(move)
        assertFalse(failed.retry)
        assertEquals(TvReplayPolicy.noRecordingText("Morning"), failed.notice)
    }

    @Test
    fun `a session limit says so and is not retried`() {
        val move = run().tick(error = "{'error':'limit'}")
        val failed = assertIs<Move.Failed>(move)
        assertEquals(TvReplayPolicy.SESSION_LIMIT_TEXT, failed.notice)
        assertFalse(failed.retry)
    }

    @Test
    fun `the playhead entering the next programme retitles it`() {
        val r = run()
        r.tick(playing = true, pos = 1_000L)
        val next = M3uReplayContinuation.Row("Midday", end, end + 30 * minute)
        val move = r.tick(playing = true, pos = 61 * minute, next = next)
        assertEquals(Move.Retitle("News 1 · Midday"), move)
        assertEquals(end + 30 * minute, r.programmeEndMs, "the next lookup now follows the new programme")
        assertEquals(Move.Wait, r.tick(playing = true, pos = 62 * minute, next = next))
    }

    @Test
    fun `a blank next title keeps the current one`() {
        val r = run()
        r.tick(playing = true, pos = 1_000L)
        val move = r.tick(playing = true, pos = 61 * minute, next = M3uReplayContinuation.Row("", end, end + 30 * minute))
        assertEquals(Move.Retitle("News 1 · Morning"), move)
    }

    private val windowEnd = now - 60 * minute   // end + 3 h of continuation, well before "now"

    @Test
    fun `a window that ran out mid-guide reopens a fresh link at the playhead`() {
        val r = run()
        r.tick(playing = true, pos = 1_000L)
        val before = r.url
        val move = r.tick(ended = true, next = M3uReplayContinuation.Row("Later", windowEnd - 30 * minute, windowEnd + 30 * minute))
        val reopen = assertIs<Move.Reopen>(move)
        assertFalse(reopen.attempt.url == before, "the link names the new start")
        assertEquals(reopen.attempt.url, r.url)
        // The new link is a fresh attempt: it must prove itself before the replay is left alone again.
        assertEquals(Move.Wait, r.tick())
    }

    @Test
    fun `a replay that ends with nothing to replay says it ended`() {
        val r = run()
        r.tick(playing = true, pos = 1_000L)
        assertEquals(Move.Ended, r.tick(ended = true, next = null))
    }

    @Test
    fun `a window that ran out at the live edge rejoins live`() {
        val recentStart = now - 20 * minute
        val begun = walk.begin(M3uReplayWalk.Request("pl", info, "http://h:80/live/u/p/9.ts", recentStart, now - 10 * minute, now))!!
        val r = TvM3uReplayRun(walk, "News 1", "Morning", recentStart, now - 10 * minute, begun)
        r.tick(playing = true, pos = 1_000L)
        assertEquals(Move.GoLive, r.tick(ended = true))
    }

    @Test
    fun `a stale answer after the walk moved on abandons`() {
        val r = run()
        // Another replay on the same playlist supersedes this one.
        walk.begin(M3uReplayWalk.Request("pl", info, "http://h:80/live/u/p/9.ts", start, end, now))
        assertEquals(Move.Abandon, r.tick(playing = true, pos = 1_000L))
    }

    @Test
    fun `the ended copy is the shared string and names going back to live`() {
        assertEquals("This replay has ended · Back on live", TvReplayPolicy.REPLAY_ENDED_TEXT)
    }
}
