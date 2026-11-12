package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.LiveTvFailureReasonPolicy.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvFailureReasonPolicyTest {
    private fun reason(message: String?, code: String? = "playback_error") = LiveTvFailureReasonPolicy.classify(code, message)

    @Test fun `a refused link status reads as the provider refusing the line`() {
        for (status in listOf(401, 403, 404, 410, 451, 456)) {
            assertEquals(Reason.PROVIDER_REFUSED, reason("Source error (HTTP $status)"), "HTTP $status")
        }
        assertEquals(Reason.PROVIDER_REFUSED, reason("Server returned 403 Forbidden"), "ffmpeg wording")
        assertEquals(Reason.PROVIDER_REFUSED, reason("Response code: 403"), "media3 wording")
    }

    @Test fun `connection cap statuses and text read as too many connections`() {
        assertEquals(Reason.TOO_MANY_CONNECTIONS, reason("Source error (HTTP 429)"))
        assertEquals(Reason.TOO_MANY_CONNECTIONS, reason("Source error (HTTP 458)"))
        assertEquals(Reason.TOO_MANY_CONNECTIONS, reason("Maximum connections reached"))
    }

    @Test fun `a provider 5xx is the provider being down`() {
        assertEquals(Reason.PROVIDER_DOWN, reason("Source error (HTTP 503)"))
    }

    @Test fun `network failures read as network`() {
        assertEquals(Reason.NETWORK, reason("Source error (network error)"))
        assertEquals(Reason.NETWORK, reason("Unable to resolve host"))
        assertEquals(Reason.NETWORK, reason("Source error (HTTP 408)"))
    }

    @Test fun `an unreadable stream reads as unsupported`() {
        assertEquals(Reason.UNSUPPORTED, reason("[mpv] unrecognized file format"))
        assertEquals(Reason.UNSUPPORTED, reason("Decoder init failed"))
    }

    @Test fun `a failed link resolve is its own reason whatever the message`() {
        assertEquals(Reason.LINK_UNAVAILABLE, reason(null, "resolve_failed"))
        assertEquals(Reason.LINK_UNAVAILABLE, reason("anything", "resolve_failed"))
    }

    @Test fun `nothing known stays unknown`() {
        assertEquals(Reason.UNKNOWN, reason(null))
        assertEquals(Reason.UNKNOWN, reason(""))
        assertEquals(Reason.UNKNOWN, reason("[mpv] loading failed"))
        assertEquals(Reason.UNKNOWN, reason("Source error"))
    }

    @Test fun `a bare number is not a status`() {
        assertEquals(Reason.UNKNOWN, reason("channel 403 stopped"))
    }

    @Test fun `telemetry carries the reason code and nothing from the message`() {
        val code = LiveTvFailureReasonPolicy.telemetryCode("playback_error", Reason.PROVIDER_REFUSED)
        assertEquals("playback_error_provider_refused", code)
        assertEquals("playback_error", LiveTvFailureReasonPolicy.telemetryCode("playback_error", Reason.UNKNOWN))
        assertEquals(null, LiveTvFailureReasonPolicy.telemetryCode(null, Reason.NETWORK))
        // Report-category inference must not misread a reason suffix as a freeze or a black screen.
        for (r in Reason.values()) assertFalse(
            listOf("black", "freeze", "stall", "stuck").any { it in r.code },
            "reason ${r.code} would re-categorise the report",
        )
    }

    @Test fun `the live badge is hidden while the channel is failed`() {
        assertFalse(LiveTvFailureReasonPolicy.showsLiveBadge(failed = true))
        assertTrue(LiveTvFailureReasonPolicy.showsLiveBadge(failed = false))
    }
}
