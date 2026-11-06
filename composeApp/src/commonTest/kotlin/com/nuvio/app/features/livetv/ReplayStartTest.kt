package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uCatchUpType
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.stalker.StalkerArchiveAnswer
import com.nuvio.app.features.iptv.stalker.StalkerArchiveLink.LinkError
import com.nuvio.app.features.iptv.stalker.StalkerReplayFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Both entries into a replay (guide cell, Sports launch) pick the walk by the channel's playlist type. */
class ReplayStartTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 300 * minute
    private val end = start + 60 * minute

    private class XMemory : CatchUpDialectWalk.WinnerMemory {
        override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? = null
        override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) = Unit
    }

    private class MMemory : M3uReplayWalk.FormMemory {
        override fun recallM3uForm(accountId: String): String? = null
        override fun rememberM3uForm(accountId: String, form: String) = Unit
    }

    private val m3uRequest = M3uReplayWalk.Request(
        "pl", M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3), "http://h:80/live/u/p/9.ts", start, end, now,
    )
    private val xtreamRequest = CatchUpDialectWalk.Request(
        accountId = "acct", baseUrl = "https://example.com", username = "user", password = "pass",
        streamId = 777, startMs = start, endMs = end,
    )

    private suspend fun runStalker(link: suspend () -> StalkerArchiveAnswer?) = startReplay(
        isM3uPlaylist = false, sourceType = "stalker",
        contentId = "c:1", programmeTitle = "Match", startMs = start, endMs = end, nowMs = now,
        m3uWalk = M3uReplayWalk(MMemory()), dialectWalk = CatchUpDialectWalk(XMemory()),
        m3uRequest = { error("a Stalker replay never builds a playlist request") },
        xtreamRequest = { error("a Stalker replay never builds an Xtream request") },
        isStalker = true, stalkerLink = link,
    )

    @Test
    fun `a stalker replay is one single use session with no walk`() = runTest {
        val result = runStalker { StalkerArchiveAnswer("http://portal/archive.ts", LinkError.NONE) }
        val session = assertIs<ReplayStart.Started>(result).session
        assertEquals("http://portal/archive.ts", session.attempt.url)
        assertTrue(session.singleUse, "no in-engine re-open of a spent link")
        assertNull(session.ladder, "nothing to walk or restart")
        assertFalse(session.continues)
    }

    @Test
    fun `each stalker refusal is told apart and no programme id is no recording`() = runTest {
        suspend fun failure(e: LinkError) = (runStalker { StalkerArchiveAnswer(null, e) } as ReplayStart.Refused).failure
        assertEquals(StalkerReplayFailure.SESSION_LIMIT, failure(LinkError.SESSION_LIMIT))
        assertEquals(StalkerReplayFailure.NOTHING_TO_PLAY, failure(LinkError.NOTHING_TO_PLAY))
        assertEquals(StalkerReplayFailure.LINK_FAULT, failure(LinkError.LINK_FAULT))
        assertEquals(ReplayStart.NoRecording, runStalker { null })
    }

    @Test
    fun `a stalker request that throws is no answer with retry and not no recording`() = runTest {
        assertEquals(ReplayStart.Refused(StalkerReplayFailure.NO_ANSWER), runStalker { error("boom") })
    }

    @Test
    fun `a second press while a mint is in flight mints nothing`() = runTest {
        val gate = ReplayMintGate()
        var mints = 0
        lateinit var second: ReplayStart
        val first = startReplay(
            isM3uPlaylist = false, sourceType = "stalker",
            contentId = "c:1", programmeTitle = "Match", startMs = start, endMs = end, nowMs = now,
            m3uWalk = M3uReplayWalk(MMemory()), dialectWalk = CatchUpDialectWalk(XMemory()),
            m3uRequest = { error("unused") }, xtreamRequest = { error("unused") },
            isStalker = true, mintGate = gate,
            stalkerLink = {
                mints++
                // The viewer presses again while this mint is still open.
                second = startReplay(
                    isM3uPlaylist = false, sourceType = "stalker",
                    contentId = "c:1", programmeTitle = "Match", startMs = start, endMs = end, nowMs = now,
                    m3uWalk = M3uReplayWalk(MMemory()), dialectWalk = CatchUpDialectWalk(XMemory()),
                    m3uRequest = { error("unused") }, xtreamRequest = { error("unused") },
                    isStalker = true, mintGate = gate,
                    stalkerLink = { mints++; StalkerArchiveAnswer("http://portal/second.ts", LinkError.NONE) },
                )
                StalkerArchiveAnswer("http://portal/first.ts", LinkError.NONE)
            },
        )
        assertEquals(ReplayStart.InFlight, second)
        assertEquals(1, mints, "exactly one single-use link was minted")
        assertEquals("http://portal/first.ts", assertIs<ReplayStart.Started>(first).session.attempt.url)
        // The gate is released afterwards: a later Retry mints again.
        assertIs<ReplayStart.Started>(runStalkerGated(gate) { StalkerArchiveAnswer("http://portal/third.ts", LinkError.NONE) })
    }

    @Test
    fun `the gate is released when a mint throws`() = runTest {
        val gate = ReplayMintGate()
        assertEquals(ReplayStart.Refused(StalkerReplayFailure.NO_ANSWER), runStalkerGated(gate) { error("boom") })
        assertIs<ReplayStart.Started>(runStalkerGated(gate) { StalkerArchiveAnswer("http://portal/a.ts", LinkError.NONE) })
    }

    private suspend fun runStalkerGated(gate: ReplayMintGate, link: suspend () -> StalkerArchiveAnswer?) = startReplay(
        isM3uPlaylist = false, sourceType = "stalker",
        contentId = "c:1", programmeTitle = "Match", startMs = start, endMs = end, nowMs = now,
        m3uWalk = M3uReplayWalk(MMemory()), dialectWalk = CatchUpDialectWalk(XMemory()),
        m3uRequest = { error("unused") }, xtreamRequest = { error("unused") },
        isStalker = true, stalkerLink = link, mintGate = gate,
    )

    @Test
    fun `retry mints a fresh link each time`() = runTest {
        var mints = 0
        val link: suspend () -> StalkerArchiveAnswer? = {
            mints++
            StalkerArchiveAnswer("http://portal/a" + mints + ".ts", LinkError.NONE)
        }
        val first = assertIs<ReplayStart.Started>(runStalker(link)).session
        val second = assertIs<ReplayStart.Started>(runStalker(link)).session
        assertEquals(2, mints)
        assertTrue(first.attempt.url != second.attempt.url, "never the spent URL")
        assertTrue(first.attempt.token != second.attempt.token)
    }

    private suspend fun run(
        isM3u: Boolean,
        m3u: suspend () -> M3uReplayWalk.Request? = { m3uRequest },
        xtream: suspend () -> CatchUpDialectWalk.Request? = { xtreamRequest },
    ) = startReplay(
        isM3uPlaylist = isM3u, sourceType = if (isM3u) "m3u_url" else "xtream",
        contentId = "c:1", programmeTitle = "Match", startMs = start, endMs = end, nowMs = now,
        m3uWalk = M3uReplayWalk(MMemory()), dialectWalk = CatchUpDialectWalk(XMemory()),
        m3uRequest = m3u, xtreamRequest = xtream,
    )

    @Test
    fun `a launch replay of an m3u channel walks the playlist's own link and not the xtream dialects`() = runTest {
        var xtreamAsked = false
        val result = run(isM3u = true, xtream = { xtreamAsked = true; null })
        val started = assertIs<ReplayStart.Started>(result, "an M3U channel with catch-up must replay, not say no recording")
        assertEquals(CatchUpDialectWalk.Dialect.PLAYLIST_LINK, started.session.attempt.dialect)
        assertTrue(started.session.continues)
        assertFalse(xtreamAsked, "the Xtream request is never built for a playlist channel")
    }

    @Test
    fun `an m3u channel without catch-up says no recording`() = runTest {
        assertEquals(ReplayStart.NoRecording, run(isM3u = true, m3u = { null }))
    }

    @Test
    fun `an m3u open that throws is a failure with retry and not no recording`() = runTest {
        assertEquals(ReplayStart.Failed(timedOut = false), run(isM3u = true, m3u = { error("db") }))
    }

    @Test
    fun `an xtream channel walks the dialects and never builds the playlist request`() = runTest {
        var m3uAsked = false
        val result = run(isM3u = false, m3u = { m3uAsked = true; null })
        val started = assertIs<ReplayStart.Started>(result)
        assertEquals(CatchUpDialectWalk.Dialect.PATH_TS, started.session.attempt.dialect)
        assertFalse(m3uAsked)
    }

    @Test
    fun `an xtream channel with no request says no recording`() = runTest {
        assertEquals(ReplayStart.NoRecording, run(isM3u = false, xtream = { null }))
    }
}
