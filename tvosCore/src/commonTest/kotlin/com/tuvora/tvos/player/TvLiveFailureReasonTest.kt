package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvLiveFailureReasonTest {
    @Test fun `no connection says so and drops provider advice`() {
        assertEquals("No network connection", TvLiveFailureReason.of("[mpv] loading failed", offline = true))
        assertEquals("No network connection", TvLiveFailureReason.of(null, offline = true))
    }

    @Test fun `a provider answer still wins over a stale offline flag`() {
        assertEquals(
            "Your provider refused this stream. The subscription may have expired, or this device is blocked.",
            TvLiveFailureReason.of("Source error (HTTP 403)", offline = true),
        )
    }

    @Test fun `online behaviour is unchanged`() {
        assertEquals(null, TvLiveFailureReason.of("[mpv] loading failed", offline = false))
    }
}
