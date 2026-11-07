package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TvPlayerFailureCodeTest {
    @Test
    fun `codes say live or vod and whether playback ever started and never echo the message`() {
        assertEquals("live_open_failed", TvPlayerFailureCode.of(true, 0, "Couldn't open https://host/x"))
        assertEquals("live_stalled", TvPlayerFailureCode.of(true, 12_000, "x"))
        assertEquals("vod_open_failed", TvPlayerFailureCode.of(false, 0, null))
        assertEquals("vod_stalled", TvPlayerFailureCode.of(false, 5, null))
        assertEquals("no_engine", TvPlayerFailureCode.of(false, 0, "No player available"))
        assertFalse(TvPlayerFailureCode.of(true, 0, "https://host/x").contains("host"))
    }
}
