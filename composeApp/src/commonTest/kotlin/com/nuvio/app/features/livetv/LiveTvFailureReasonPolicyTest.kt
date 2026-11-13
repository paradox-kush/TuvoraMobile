package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.LiveTvFailureReasonPolicy.Badge
import com.nuvio.app.features.livetv.LiveTvFailureReasonPolicy.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    // Regression (UX191 on the phone): Android and iOS libmpv end a failed open with END_FILE "loading failed" and
    // no message at all, so a startup failure reached the policy as (startup_failed, null) and the pill showed no line.
    @Test fun `a channel that never started says so when nothing names the cause`() {
        assertEquals(Reason.NOT_STARTED, reason(null, "startup_failed"))
        assertEquals(Reason.NOT_STARTED, reason("", "startup_failed"))
        assertEquals(Reason.NOT_STARTED, reason("[mpv] loading failed", "startup_failed"))
    }

    @Test fun `a startup failure that names its cause keeps the specific reason`() {
        assertEquals(Reason.PROVIDER_REFUSED, reason("Source error (HTTP 403)", "startup_failed"))
        assertEquals(Reason.NETWORK, reason("Source error (network error)", "startup_failed"))
    }

    @Test fun `only a startup failure claims the stream did not start`() {
        assertEquals(Reason.UNKNOWN, reason(null, "reconnect_gave_up"), "it played before it dropped")
        assertEquals(Reason.UNKNOWN, reason(null, "playback_error"))
        assertEquals(Reason.UNKNOWN, reason(null, null))
    }

    @Test fun `not started adds nothing to the telemetry code`() {
        assertEquals("startup_failed", LiveTvFailureReasonPolicy.telemetryCode("startup_failed", Reason.NOT_STARTED))
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

    private fun offlineReason(message: String?, code: String? = "playback_error") =
        LiveTvFailureReasonPolicy.classify(code, message, offline = true)

    // UX343: with no connectivity the stream cannot start, and the playlist advice sent people to fix the wrong thing.
    @Test fun `a failure with no connectivity reads as offline whatever the engine said`() {
        assertEquals(Reason.OFFLINE, offlineReason(null, "startup_failed"))
        assertEquals(Reason.OFFLINE, offlineReason("[mpv] loading failed", "startup_failed"))
        assertEquals(Reason.OFFLINE, offlineReason("Source error (network error)"))
        assertEquals(Reason.OFFLINE, offlineReason(null, "resolve_failed"))
        assertEquals(Reason.OFFLINE, offlineReason(null, "reconnect_gave_up"))
        assertEquals(Reason.OFFLINE, offlineReason(null, null))
    }

    @Test fun `an answer from the provider proves the device was online`() {
        assertEquals(Reason.PROVIDER_REFUSED, offlineReason("Source error (HTTP 403)"), "a stale offline flag must not hide a refusal")
        assertEquals(Reason.TOO_MANY_CONNECTIONS, offlineReason("Source error (HTTP 458)"))
        assertEquals(Reason.PROVIDER_DOWN, offlineReason("Source error (HTTP 503)"))
        assertEquals(Reason.UNSUPPORTED, offlineReason("Decoder init failed"))
    }

    @Test fun `online classification is unchanged`() {
        assertEquals(Reason.NOT_STARTED, LiveTvFailureReasonPolicy.classify("startup_failed", null, offline = false))
        assertEquals(Reason.NOT_STARTED, reason(null, "startup_failed"))
    }

    @Test fun `offline telemetry code carries the offline reason`() {
        assertEquals("startup_failed_offline", LiveTvFailureReasonPolicy.telemetryCode("startup_failed", Reason.OFFLINE))
    }

    @Test fun `a failed channel shows no badge`() {
        assertEquals(Badge.NONE, badge(failed = true, loading = false, playing = false))
        assertEquals(Badge.NONE, badge(failed = true, loading = true, playing = false))
    }

    // UX344: LIVE must not sit on a channel that has not started, nor on the retry that follows a failure.
    @Test fun `a channel still loading shows connecting`() {
        assertEquals(Badge.CONNECTING, badge(loading = true, playing = false))
    }

    @Test fun `a retry that has not resolved a source shows connecting even if the old snapshot is idle`() {
        assertEquals(Badge.CONNECTING, badge(sourceReady = false, loading = false, playing = false))
    }

    @Test fun `a playing channel shows live`() {
        assertEquals(Badge.LIVE, badge(loading = false, playing = true))
    }

    @Test fun `a viewer paused channel stays live`() {
        assertEquals(Badge.LIVE, badge(loading = false, playing = false))
    }

    @Test fun `a time shifted channel stays live`() {
        assertEquals(Badge.LIVE, badge(loading = true, playing = false, timeShifted = true))
    }

    private fun stalker(message: String?, code: String? = "startup_failed", offline: Boolean = false, insideWindow: Boolean = false) =
        LiveTvFailureReasonPolicy.classify(code, message, offline = offline, singleUseLinkMinted = true, retriedInsideConnectionWindow = insideWindow)

    // B259: create_link succeeded every time, then mpv ended with "loading failed": the provider's stream server sent
    // nothing. The pill said "The stream did not start. Try again or check your playlist", blaming the playlist.
    @Test fun `a minted single use link that never started reads as the provider sending no data`() {
        assertEquals(Reason.PROVIDER_NO_DATA, stalker(null))
        assertEquals(Reason.PROVIDER_NO_DATA, stalker(""))
        assertEquals(Reason.PROVIDER_NO_DATA, stalker("[mpv] loading failed"))
    }

    @Test fun `a named cause still wins over provider no data`() {
        assertEquals(Reason.PROVIDER_REFUSED, stalker("Source error (HTTP 401)"))
        assertEquals(Reason.TOO_MANY_CONNECTIONS, stalker("Source error (HTTP 458)"))
        assertEquals(Reason.NETWORK, stalker("Source error (network error)"))
        assertEquals(Reason.UNSUPPORTED, stalker("Decoder init failed"))
    }

    @Test fun `offline still wins over provider no data`() {
        assertEquals(Reason.OFFLINE, stalker(null, offline = true))
        assertEquals(Reason.OFFLINE, stalker(null, offline = true, insideWindow = true))
    }

    @Test fun `provider no data needs a startup failure`() {
        assertEquals(Reason.LINK_UNAVAILABLE, stalker(null, code = "resolve_failed"), "no link was minted")
        assertEquals(Reason.UNKNOWN, stalker(null, code = "reconnect_gave_up"), "it played before it dropped")
        assertEquals(Reason.UNKNOWN, stalker(null, code = "playback_error"))
    }

    @Test fun `a reusable link that never started keeps the generic line`() {
        assertEquals(Reason.NOT_STARTED, LiveTvFailureReasonPolicy.classify("startup_failed", null, singleUseLinkMinted = false))
        assertEquals(Reason.NOT_STARTED, LiveTvFailureReasonPolicy.classify("startup_failed", null, singleUseLinkMinted = false, retriedInsideConnectionWindow = true))
    }

    @Test fun `a retry inside the connection window that never started says the provider may hold the old connection`() {
        assertEquals(Reason.CONNECTION_HELD, stalker(null, insideWindow = true))
        assertEquals(Reason.PROVIDER_REFUSED, stalker("Source error (HTTP 403)", insideWindow = true), "a named cause still wins")
    }

    @Test fun `the new reasons reach telemetry as codes only`() {
        assertEquals("startup_failed_provider_no_data", LiveTvFailureReasonPolicy.telemetryCode("startup_failed", Reason.PROVIDER_NO_DATA))
        assertEquals("startup_failed_connection_held", LiveTvFailureReasonPolicy.telemetryCode("startup_failed", Reason.CONNECTION_HELD))
    }

    private fun badge(
        failed: Boolean = false,
        sourceReady: Boolean = true,
        loading: Boolean,
        playing: Boolean,
        timeShifted: Boolean = false,
    ) = LiveTvFailureReasonPolicy.badge(failed, sourceReady, loading, playing, timeShifted)
}
