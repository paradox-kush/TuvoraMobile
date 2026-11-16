package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvGuidePreviewPolicyTest {
    private val stall = TvGuidePreviewPolicy.STALL_MS

    @Test
    fun `a preview that has not started is loading until the deadline then failed with a message`() {
        assertEquals(TvGuidePhase.Loading, TvGuidePreviewPolicy.status(1_000, playing = false, error = null).phase, "early")
        assertEquals(TvGuidePhase.Loading, TvGuidePreviewPolicy.status(stall - 1, playing = false, error = null).phase, "just before")
        val failed = TvGuidePreviewPolicy.status(stall, playing = false, error = null)
        assertEquals(TvGuidePhase.Failed, failed.phase, "at the deadline")
        assertEquals(TvGuidePreviewPolicy.STALLED_MESSAGE, failed.message, "visible reason")
    }

    @Test
    fun `an engine error shows at once with its own message`() {
        val s = TvGuidePreviewPolicy.status(500, playing = false, error = "Stream unavailable")
        assertEquals(TvGuidePhase.Failed, s.phase, "error")
        assertEquals("Stream unavailable", s.message, "engine message kept")
    }

    @Test
    fun `playing wins even when it starts after the deadline`() {
        assertEquals(TvGuidePhase.Playing, TvGuidePreviewPolicy.status(stall * 2, playing = true, error = null).phase, "late start")
    }
}
