package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvGuidePreviewPolicyTest {
    private val stall = TvGuidePreviewPolicy.STALL_MS

    @Test
    fun `a preview that has not started is loading until the deadline then failed with a message`() {
        assertEquals(TvGuidePhase.Loading, TvGuidePreviewPolicy.status(1_000, started = false, error = null).phase, "early")
        assertEquals(TvGuidePhase.Loading, TvGuidePreviewPolicy.status(stall - 1, started = false, error = null).phase, "just before")
        val failed = TvGuidePreviewPolicy.status(stall, started = false, error = null)
        assertEquals(TvGuidePhase.Failed, failed.phase, "at the deadline")
        assertEquals(TvGuidePreviewPolicy.STALLED_MESSAGE, failed.message, "visible reason")
    }

    @Test
    fun `a reconnecting preview shows the attempt line and gets the ladder's budget past the stall deadline`() {
        // Device pass: a 503 retried four times over ~27 s with only a spinner, then read "isn't starting".
        val line = "Reconnecting… (attempt 2 of 5)\nProvider server error"
        val s = TvGuidePreviewPolicy.status(stall + 1_000, started = false, error = null, reconnecting = line)
        assertEquals(TvGuidePhase.Loading, s.phase, "the ladder is still running")
        assertEquals(line, s.message, "the attempt line is shown")
        val capped = TvGuidePreviewPolicy.status(stall + TvGuidePreviewPolicy.RECONNECT_BUDGET_MS, started = false, error = null, reconnecting = line)
        assertEquals(TvGuidePhase.Failed, capped.phase, "the strip still holds its own (longer) deadline")
    }

    @Test
    fun `a failure's status hint is shown under the error`() {
        val hint = "Too many connections on this account. Close the stream on another device, or try again in a minute."
        val s = TvGuidePreviewPolicy.status(500, started = false, error = "Couldn't reach this channel", hint = hint)
        assertEquals("Couldn't reach this channel\n$hint", s.message)
    }

    @Test
    fun `an engine error shows at once with its own message`() {
        val s = TvGuidePreviewPolicy.status(500, started = false, error = "Stream unavailable")
        assertEquals(TvGuidePhase.Failed, s.phase, "error")
        assertEquals("Stream unavailable", s.message, "engine message kept")
    }

    @Test
    fun `a started preview wins even when it starts after the deadline`() {
        assertEquals(TvGuidePhase.Playing, TvGuidePreviewPolicy.status(stall * 2, started = true, error = null).phase, "late start")
    }

    @Test
    fun `an engine that reports playing without a decoded frame is still loading then failed`() {
        // The engine's raw playing flag is true while idle; only a started stream (frame or position) counts.
        assertEquals(TvGuidePhase.Loading, TvGuidePreviewPolicy.status(5_000, started = false, error = null).phase, "idle engine early")
        assertEquals(TvGuidePhase.Failed, TvGuidePreviewPolicy.status(stall + 1, started = false, error = null).phase, "idle engine late")
    }

    @Test
    fun `a channel link that fails to resolve fails at once with a reason and Retry`() {
        val s = TvGuidePreviewPolicy.resolveFailed()
        assertEquals(TvGuidePhase.Failed, s.phase, "no waiting for the deadline")
        assertEquals(TvGuidePreviewPolicy.UNREACHABLE_MESSAGE, s.message, "visible reason")
        assertEquals(true, TvGuidePreviewPolicy.offersRetry(s), "Retry offered")
        assertEquals(false, TvGuidePreviewPolicy.offersRetry(TvGuidePreviewPolicy.status(1_000, started = false, error = null)), "not while loading")
    }
}
