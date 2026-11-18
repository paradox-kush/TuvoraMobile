package com.tuvora.tvos.player

import com.nuvio.app.core.analytics.LiveOpenRetryPolicy
import com.nuvio.app.core.analytics.LivePlaybackRecoveryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression (Apple TV guide preview, a 503 at open): two requests, then "This channel isn't starting" — the open-time
 * 5xx never entered a bounded reconnect and no "Reconnecting… (attempt N of 5)" line showed. Same ladder as the phone.
 */
class TvLiveOpenRetryTest {

    @Test
    fun `a 5xx open retries with the shared backoff up to the cap then stops`() {
        val retry = TvLiveOpenRetry()
        val delays = generateSequence { retry.next(started = false, engineError = "[ffmpeg] http: HTTP error 503") }.toList()
        assertEquals(List(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK) { LiveOpenRetryPolicy.delayMs(it) }, delays)
        assertEquals(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK, retry.attempts)
        assertEquals(true, retry.exhausted)
    }

    @Test
    fun `caps firewalls a played stream and status-less ends are not open retries`() {
        assertNull(TvLiveOpenRetry().next(false, "HTTP error 458"))
        assertNull(TvLiveOpenRetry().next(false, "HTTP error 456"))
        assertNull(TvLiveOpenRetry().next(true, "HTTP error 503"), "a played stream belongs to the freeze ladder")
        assertNull(TvLiveOpenRetry().next(false, "[mpv] loading failed"), "no named cause: the ladder (and its re-mint) handles it")
    }

    @Test
    fun `the viewer's Retry starts a fresh budget`() {
        val retry = TvLiveOpenRetry()
        repeat(5) { retry.next(false, "HTTP error 503") }
        retry.reset()
        assertEquals(LiveOpenRetryPolicy.delayMs(0), retry.next(false, "HTTP error 503"))
    }
}
