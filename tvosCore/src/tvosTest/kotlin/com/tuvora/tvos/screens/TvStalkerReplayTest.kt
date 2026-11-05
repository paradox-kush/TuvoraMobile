package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.stalker.StalkerArchiveAnswer
import com.nuvio.app.features.iptv.stalker.StalkerArchiveLink.LinkError
import com.tuvora.tvos.player.TvResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Starting and following a Stalker archive replay on Apple TV: one mint, one open, never a re-open. */
class TvStalkerReplayTest {
    private val fence = ReplayOpenFence()
    private val starter = TvStalkerReplayStarter(fence)

    private suspend fun start(
        mint: suspend () -> StalkerArchiveAnswer?,
        resolve: suspend (String) -> TvResolvedSource? = { TvResolvedSource(it, emptyMap()) },
    ) = starter.start("stalker", "Match", mint, resolve)

    private fun ok(url: String) = StalkerArchiveAnswer(url, LinkError.NONE)

    @Test
    fun `a minted link plays once with no probe`() = runBlocking {
        var mints = 0
        val resolved = mutableListOf<String>()
        val result = start({ mints++; ok("http://portal/a.mpg") }, { resolved += it; TvResolvedSource(it, emptyMap()) })
        assertEquals("http://portal/a.mpg", assertIs<TvStalkerStart.Play>(result).source.url)
        assertEquals(1, mints)
        assertEquals(listOf("http://portal/a.mpg"), resolved)
    }

    @Test
    fun `each portal refusal says its own words with retry`() = runBlocking {
        suspend fun notice(e: LinkError) = assertIs<TvStalkerStart.Notice>(start({ StalkerArchiveAnswer(null, e) }))
        assertEquals(TvStalkerReplayText.LIMIT, notice(LinkError.SESSION_LIMIT).text)
        assertEquals(TvStalkerReplayText.NOTHING_TO_PLAY, notice(LinkError.NOTHING_TO_PLAY).text)
        assertEquals(TvStalkerReplayText.LINK_FAULT, notice(LinkError.LINK_FAULT).text)
        assertTrue(notice(LinkError.LINK_FAULT).canRetry)
    }

    @Test
    fun `no programme id is the no-recording notice without retry`() = runBlocking {
        val n = assertIs<TvStalkerStart.Notice>(start({ null }))
        assertEquals(TvReplayPolicy.noRecordingText("Match"), n.text)
        assertFalse(n.canRetry)
    }

    @Test
    fun `a request that throws is no answer with retry and not no recording`() = runBlocking {
        val n = assertIs<TvStalkerStart.Notice>(start({ error("boom") }))
        assertEquals(TvStalkerReplayText.NO_ANSWER, n.text)
        assertTrue(n.canRetry)
    }

    @Test
    fun `a link whose host does not resolve fails with retry instead of opening`() = runBlocking {
        val n = assertIs<TvStalkerStart.Notice>(start({ ok("http://portal/a") }, { null }))
        assertTrue(n.canRetry)
    }

    @Test
    fun `a double press mints one link and the first press still plays`() = runBlocking {
        var mints = 0
        lateinit var second: TvStalkerStart
        val first = start({
            mints++
            second = start({ mints++; ok("http://portal/second.mpg") })
            ok("http://portal/first.mpg")
        })
        assertEquals(TvStalkerStart.Ignore, second)
        assertEquals(1, mints)
        assertEquals("http://portal/first.mpg", assertIs<TvStalkerStart.Play>(first).source.url)
    }

    @Test
    fun `retry after a failure mints a fresh link`() = runBlocking {
        val urls = mutableListOf<String>()
        start({ StalkerArchiveAnswer(null, LinkError.LINK_FAULT) })
        val again = start({ ok("http://portal/fresh${urls.size}.mpg").also { urls += it.url!! } })
        assertEquals("http://portal/fresh0.mpg", assertIs<TvStalkerStart.Play>(again).source.url)
        assertFalse(urls.isEmpty())
    }

    @Test
    fun `a newer open that overtakes the mint publishes nothing`() = runBlocking {
        val result = start({ fence.next(); ok("http://portal/late.mpg") })
        assertEquals(TvStalkerStart.Ignore, result)
    }

    private class Playback(var error: String? = null, var playing: Boolean = false, closed: Boolean = false) : TvReplayPlayback {
        override var isClosed = closed
        var closes = 0
        override fun close() { closes++; isClosed = true }
        override fun snapshot() = TvReplaySnapshot(playing, if (playing) 1_000 else 0, false, error)
        override fun retitle(title: String) {}
        override fun setReplayBar(originMs: Long, spanMs: Long, programmeStartMs: Long, programmeEndMs: Long) {}
    }

    @Test
    fun `a spent link that errors ends with the playback words retry and no successor session`() = runBlocking {
        val ticket = fence.next()
        val playback = Playback(error = "Failed to open")
        val step = TvStalkerReplay(fence, ticket, tickMs = 1).superviseOn(playback)!!
        assertNull(step.session, "a single-use link is never re-opened in a second session")
        assertEquals(TvStalkerReplayText.PLAYBACK, step.notice)
        assertTrue(step.canRetry)
        assertEquals(0, playback.closes, "the supervisor leaves closing to Swift")
    }

    @Test
    fun `a closed session or a newer open ends supervision quietly`() = runBlocking {
        val ticket = fence.next()
        assertNull(TvStalkerReplay(fence, ticket, tickMs = 1).superviseOn(Playback(closed = true)))
        val stale = fence.next().let { ticket }
        assertNull(TvStalkerReplay(fence, stale, tickMs = 1).superviseOn(Playback(playing = true)))
    }
}
