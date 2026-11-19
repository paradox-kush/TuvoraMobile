package com.nuvio.app.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveReconnectStatusTest {
    private val reconnector = LivePlaybackReconnector(LivePlaybackFreezeReporter(capture = { _, _ -> }))

    private fun frozen(nowMs: Long) = reconnector.onFrozen(
        nowMs = nowMs,
        kind = LivePlaybackFreezePolicy.Kind.ENDED,
        reconnect = {},
    )

    private val max = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK

    @Test
    fun `each reconnect publishes its attempt number out of the give-up cap`() {
        assertNull(reconnector.status.value, "nothing is reconnecting before the first freeze")
        frozen(60_000L)
        assertEquals(LiveReconnectStatus(1, max, LiveReconnectReason.STREAM_STOPPED), reconnector.status.value)
        frozen(120_000L)
        assertEquals(2, reconnector.status.value?.attempt)
    }

    @Test
    fun `the engine's failure names the reason and updates the line already on screen`() {
        frozen(60_000L)
        reconnector.noteFailure("Source error (HTTP 503)")
        assertEquals(LiveReconnectReason.PROVIDER_SERVER_ERROR, reconnector.status.value?.reason)
        frozen(120_000L)
        assertEquals(LiveReconnectStatus(2, max, LiveReconnectReason.PROVIDER_SERVER_ERROR), reconnector.status.value)
    }

    @Test
    fun `a rendered frame clears the line and forgets the reason`() {
        reconnector.noteFailure("HTTP error 509")
        frozen(60_000L)
        reconnector.onPlaybackConfirmed()
        assertNull(reconnector.status.value)
        frozen(120_000L)
        assertEquals(LiveReconnectReason.STREAM_STOPPED, reconnector.status.value?.reason, "an old failure does not word a new outage")
    }

    @Test
    fun `the give-up hands the screen to the error and Retry`() {
        var now = 0L
        repeat(max) { now += 60_000L; frozen(now) }
        assertEquals(max, reconnector.status.value?.attempt, "the last attempt is N of N")
        now += 60_000L
        frozen(now)
        assertEquals(true, reconnector.hasGivenUp)
        assertNull(reconnector.status.value, "no reconnecting line under the error")
    }

    @Test
    fun `a zap or the viewer's Retry clears the line`() {
        frozen(60_000L)
        reconnector.onChannelChanged()
        assertNull(reconnector.status.value)
        frozen(120_000L)
        reconnector.onUserRetry()
        assertNull(reconnector.status.value)
    }

    @Test
    fun `statuses map to the closed reason set`() {
        val cases = mapOf(
            "Source error (HTTP 456)" to LiveReconnectReason.PROVIDER_FIREWALL,
            "[ffmpeg] http: HTTP error 458" to LiveReconnectReason.CONNECTION_LIMIT,
            "HTTP error 509 Bandwidth Limit Exceeded" to LiveReconnectReason.CONNECTION_LIMIT,
            "Response code: 429" to LiveReconnectReason.CONNECTION_LIMIT,
            "Server returned 404 Not Found" to LiveReconnectReason.STREAM_NOT_FOUND,
            "Source error (HTTP 503)" to LiveReconnectReason.PROVIDER_SERVER_ERROR,
            "Source error (network error)" to LiveReconnectReason.UNREACHABLE,
            "Unable to resolve host" to LiveReconnectReason.UNREACHABLE,
            "Source error (HTTP 403)" to LiveReconnectReason.STREAM_STOPPED,
            "end of file" to LiveReconnectReason.STREAM_STOPPED,
        )
        for ((message, reason) in cases) {
            assertEquals(reason, LiveReconnectReasonPolicy.fromFailureText(message), message)
        }
        assertEquals(LiveReconnectReason.STREAM_STOPPED, LiveReconnectReasonPolicy.fromFailureText(null))
    }

    @Test
    fun `a port or a channel id is never read as a status`() {
        assertNull(LiveReconnectReasonPolicy.httpStatusIn("Failed to open tcp host 8080 channel 456"))
        assertEquals(456, LiveReconnectReasonPolicy.httpStatusIn("HTTP error 456"))
    }

    @Test
    fun `an open refused with a 5xx retries with backoff and shows each attempt`() {
        // Device pass: a 503 at open went straight to the error with one request.
        val first = reconnector.onOpenFailed("[ffmpeg] http: HTTP error 503 Service Unavailable")
        assertEquals(LiveOpenRetryPolicy.delayMs(0), first)
        assertEquals(LiveReconnectStatus(1, max, LiveReconnectReason.PROVIDER_SERVER_ERROR), reconnector.status.value)
        var attempts = 1
        while (reconnector.onOpenFailed("Source error (HTTP 503)") != null) attempts++
        assertEquals(max, attempts, "bounded by the same cap as the freeze ladder")
        assertEquals(true, reconnector.hasGivenUp)
        assertNull(reconnector.status.value, "the give-up hands the screen to the error and Retry")
    }

    @Test
    fun `an open refused for a reason a retry cannot fix fails at once`() {
        for (message in listOf("HTTP error 458", "HTTP error 509", "HTTP error 456", "HTTP error 404", "HTTP error 403", "Source error (unsupported format)")) {
            reconnector.onUserRetry()
            assertNull(reconnector.onOpenFailed(message), "$message")
            assertNull(reconnector.status.value)
        }
    }

    @Test
    fun `an open that failed with no readable status gets the bounded reconnect too`() {
        // Device pass (Android libmpv): END_FILE only says "loading failed"; with no status to read, the open used to
        // fail at once as "did not start". Unknown is not final: same ladder as a 5xx, same cap.
        for (message in listOf(null, "", "  ", "loading failed")) {
            reconnector.onUserRetry()
            assertEquals(LiveOpenRetryPolicy.delayMs(0), reconnector.onOpenFailed(message), "$message")
            assertEquals(LiveReconnectStatus(1, max, LiveReconnectReason.STREAM_STOPPED), reconnector.status.value)
        }
        reconnector.onUserRetry()
        var attempts = 0
        while (reconnector.onOpenFailed(null) != null) attempts++
        assertEquals(max, attempts, "bounded by the shared cap")
        assertEquals(true, reconnector.hasGivenUp)
        assertNull(reconnector.status.value)
    }

    @Test
    fun `an unreachable provider at open is retried too`() {
        assertEquals(LiveOpenRetryPolicy.delayMs(0), reconnector.onOpenFailed("Source error (network error)"))
        assertEquals(LiveReconnectReason.UNREACHABLE, reconnector.status.value?.reason)
    }

    @Test
    fun `open retry delays back off and never hammer the provider`() {
        assertEquals(2_000L, LiveOpenRetryPolicy.delayMs(0))
        assertEquals(20_000L, LiveOpenRetryPolicy.delayMs(10))
    }
}
