package com.tuvora.tvos.player

import com.nuvio.app.core.analytics.LiveOpenRetryPolicy
import com.nuvio.app.core.analytics.LivePlaybackRecoveryPolicy
import com.nuvio.app.core.analytics.LiveReconnectReasonPolicy

/**
 * Apple TV's bounded retry for a live channel whose FIRST open failed with a reason a retry can fix (5xx, unreachable;
 * [LiveOpenRetryPolicy]) — the phone's [com.nuvio.app.core.analytics.LivePlaybackReconnector.onOpenFailed] for the
 * session. Same backoff and the same five-attempt cap; a played stream, a status-less end (the freeze ladder and its
 * re-mint own those) and a non-retryable refusal are not its business. One per session; the viewer's Retry resets it.
 */
class TvLiveOpenRetry {
    var attempts = 0
        private set

    /** True once the cap was hit: the next failure is the terminal error + Retry. */
    var exhausted = false
        private set

    /** The wait before re-opening, or null when this failure is not an open retry (or the budget is spent). */
    fun next(started: Boolean, engineError: String?): Long? {
        if (started || exhausted) return null
        if (!LiveOpenRetryPolicy.retries(LiveReconnectReasonPolicy.fromFailureText(engineError))) return null
        if (attempts >= LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK) { exhausted = true; return null }
        val delay = LiveOpenRetryPolicy.delayMs(attempts)
        attempts += 1
        if (attempts >= LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK) exhausted = true
        return delay
    }

    fun reset() { attempts = 0; exhausted = false }
}
