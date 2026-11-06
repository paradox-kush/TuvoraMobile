package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.player.PlayerLaunch
import com.tuvora.tvos.player.TvPlayerSession
import com.tuvora.tvos.player.TvResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * B229: the Xtream dialect walk never swapped, because the supervisor closed the failed session before
 * returning its successor and Swift's PlaybackCoordinator drops a successor whose predecessor is already
 * closed ("the viewer left"). The failed session must come back OPEN; Swift closes it on the swap.
 */
class TvReplayHandoverTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val start = now - 300 * minute
    private val end = start + 60 * minute

    private class Memory : CatchUpDialectWalk.WinnerMemory {
        override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? = null
        override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) {}
    }

    /** The session being followed, failing its first link. */
    private class FailedFirstLink(private val error: String?) : TvReplayPlayback {
        override var isClosed: Boolean = false
            private set
        override fun close() { isClosed = true }
        override fun snapshot() = TvReplaySnapshot(isPlaying = false, positionMs = 0L, isEnded = false, errorMessage = error)
        override fun retitle(title: String) {}
        override fun setReplayBar(originMs: Long, spanMs: Long, programmeStartMs: Long, programmeEndMs: Long) {}
    }

    private class FakeIo : TvReplayIo {
        val opened = mutableListOf<String>()
        val sessions = mutableListOf<TvPlayerSession>()
        override suspend fun resolve(url: String): TvResolvedSource? { opened += url; return TvResolvedSource(url, emptyMap()) }
        override suspend fun nextRow(endMs: Long): M3uReplayContinuation.Row? = null
        override suspend fun playLive(): TvPlayerSession? = null
        override fun newSession(launch: PlayerLaunch) = TvPlayerSession(launch).also { sessions += it }
        override fun nowMs() = 1_800_000_000_000L
        override fun providerName() = "Test"
    }

    private val channel = LiveGuideChannel(contentId = "xtream:acct:9", name = "News 1", logo = null, streamId = 9, categoryId = null)
    private val programme = XtreamProgram("Morning", "", start, end, nowPlaying = false)

    @Test
    fun `an Xtream link that fails hands over the next dialect and leaves the failed session open`() = runBlocking {
        val walk = CatchUpDialectWalk(Memory())
        val begun = walk.beginReplay(CatchUpDialectWalk.Request("acct", "http://h:80", "u", "p", 9, start, end), now)!!
        val fence = ReplayOpenFence()
        val io = FakeIo()
        val replay = TvReplay(walk, fence, fence.next(), channel, programme, io)
        val first = replay.start(begun)
        val firstSession = assertNotNull(first.session, "the first link opens a session")
        val playback = FailedFirstLink("Failed to open")

        val step = assertNotNull(replay.superviseOn(playback), "a failed link answers with a step")

        val next = assertNotNull(step.session, "the walk hands over the next dialect as a new session")
        assertNotSame(firstSession, next)
        assertEquals(2, io.opened.size)
        assertFalse(io.opened[0] == io.opened[1], "the second session plays another dialect")
        assertFalse(playback.isClosed, "the failed session must still be open when it is handed over")
        assertFalse(firstSession.isClosed, "the failed session must still be open when it is handed over")
        assertNull(step.notice)
    }
}
