package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.LoadOutcome
import com.nuvio.app.features.iptv.stalker.StalkerArchiveLink.LinkError
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Stalker archive replay is a tv_archive create_link naming the portal's own EPG programme id, never a time range. */
class StalkerArchiveLinkTest {

    @Test
    fun `the archive cmd is the programme id on the auto media path`() {
        assertEquals("auto /media/123456.mpg", StalkerArchiveLink.cmd("123456"))
    }

    @Test
    fun `the request is a tv_archive create_link carrying that cmd`() {
        assertEquals(
            mapOf(
                "type" to "tv_archive", "action" to "create_link", "cmd" to "auto /media/77.mpg",
                "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0",
            ),
            StalkerArchiveLink.params("77"),
        )
    }

    @Test
    fun `a live create_link keeps its shape`() {
        assertEquals(
            mapOf(
                "type" to "itv", "action" to "create_link", "cmd" to "ffrt http://h/ch/1",
                "forced_storage" to "undefined", "disable_ad" to "0",
            ),
            StalkerArchiveLink.createLinkParams("itv", "ffrt http://h/ch/1", emptyMap()),
        )
    }

    @Test
    fun `the portal's refusals are told apart`() {
        fun of(raw: String) = StalkerArchiveLink.linkErrorOf(raw)
        assertEquals(LinkError.SESSION_LIMIT, of("""{"error":"limit"}"""))
        assertEquals(LinkError.NOTHING_TO_PLAY, of("""{"error":"nothing_to_play"}"""))
        assertEquals(LinkError.LINK_FAULT, of("""{"cmd":"link_fault"}"""))
        assertEquals(LinkError.NONE, of("""{"cmd":"http://h/archive.ts"}"""))
        assertEquals(LinkError.NONE, of("""{"cmd":"http://h/nothing_to_play.ts"}"""), "a recording named like an error is not an error")
        assertEquals(LinkError.NONE, of("""{"name":"No Limits TV"}"""))
    }

    private fun answer(url: String?, error: LinkError = LinkError.NONE) = StalkerArchiveAnswer(url, error)

    @Test
    fun `a playable answer is a single use link`() {
        assertEquals(StalkerReplayOutcome.Link("http://h/a.ts"), StalkerReplayOutcome.ofAnswer(answer("http://h/a.ts")))
    }

    @Test
    fun `each refusal is its own failure`() {
        fun of(e: LinkError) = StalkerReplayOutcome.ofAnswer(answer(null, e))
        assertEquals(StalkerReplayOutcome.Refused(StalkerReplayFailure.SESSION_LIMIT), of(LinkError.SESSION_LIMIT))
        assertEquals(StalkerReplayOutcome.Refused(StalkerReplayFailure.NOTHING_TO_PLAY), of(LinkError.NOTHING_TO_PLAY))
        assertEquals(StalkerReplayOutcome.Refused(StalkerReplayFailure.LINK_FAULT), of(LinkError.LINK_FAULT))
        assertEquals(StalkerReplayOutcome.Refused(StalkerReplayFailure.LINK_FAULT), of(LinkError.NONE), "no url and no named refusal is a fault")
    }

    @Test
    fun `a failed or hung request is no answer and never no recording`() {
        assertEquals(
            StalkerReplayOutcome.Refused(StalkerReplayFailure.NO_ANSWER),
            StalkerReplayOutcome.ofLoad(LoadOutcome.Failed(error = null, timedOut = true)),
        )
        assertEquals(
            StalkerReplayOutcome.Refused(StalkerReplayFailure.NO_ANSWER),
            StalkerReplayOutcome.ofLoad(LoadOutcome.Failed(error = IllegalStateException("boom"), timedOut = false)),
        )
    }

    @Test
    fun `no programme id is no recording`() {
        assertEquals(StalkerReplayOutcome.NoRecording, StalkerReplayOutcome.ofLoad(LoadOutcome.Loaded(null)))
        assertEquals(StalkerReplayOutcome.NoRecording, StalkerReplayOutcome.ofLoad(LoadOutcome.Empty(null)))
    }

    @Test
    fun `a loaded answer is mapped through the answer rules`() {
        assertEquals(
            StalkerReplayOutcome.Refused(StalkerReplayFailure.SESSION_LIMIT),
            StalkerReplayOutcome.ofLoad(LoadOutcome.Loaded(answer(null, LinkError.SESSION_LIMIT))),
        )
    }

    @Test
    fun `a link that opened and never showed a frame is a hang and an engine error is a playback error`() {
        assertEquals(StalkerReplayFailure.STARTUP_HANG, StalkerReplayOutcome.ofSession(startupTimedOut = true))
        assertEquals(StalkerReplayFailure.PLAYBACK_ERROR, StalkerReplayOutcome.ofSession(startupTimedOut = false))
    }
}
