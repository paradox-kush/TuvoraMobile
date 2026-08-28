package com.nuvio.app.core.analytics

/**
 * Fires the reconnect for a frozen live channel, backed off and capped by
 * [LivePlaybackRecoveryPolicy], and keeps the freeze record open across the attempts so the
 * emitted event can say whether the reconnect is what brought the picture back.
 *
 * Held per live surface alongside the reporter, and reset whenever a new channel starts.
 */
class LivePlaybackReconnector(private val reporter: LivePlaybackFreezeReporter) {

    private var attempts = 0
    private var lastAttemptAtMs = 0L
    private var gaveUp = false

    /** Whether the reconnect ladder has been exhausted for the open freeze. */
    val hasGivenUp: Boolean get() = gaveUp

    /** Call when a new channel starts, so its ladder does not inherit the previous one's. */
    fun reset() {
        attempts = 0
        lastAttemptAtMs = 0L
        gaveUp = false
    }

    /**
     * Reconnects if the policy allows it. Returns true when [reconnect] was invoked.
     *
     * [reconnect] should re-prepare the current source at the live edge — the automated form of
     * the channel-change-and-back that viewers do by hand.
     */
    fun onFrozen(
        nowMs: Long,
        kind: LivePlaybackFreezePolicy.Kind = LivePlaybackFreezePolicy.Kind.STALLED,
        resetVideo: (() -> Boolean)? = null,
        reconnect: () -> Unit,
    ): Boolean {
        val sinceLastAttemptMs = if (lastAttemptAtMs == 0L) Long.MAX_VALUE else nowMs - lastAttemptAtMs
        return when (
            LivePlaybackRecoveryPolicy.evaluate(
                LivePlaybackRecoveryPolicy.Input(
                    attempts = attempts,
                    sinceLastAttemptMs = sinceLastAttemptMs,
                    kind = kind,
                    // An engine with no video-reset primitive skips straight to reconnecting
                    // rather than burning two attempts doing nothing.
                    videoResetAttempts = if (resetVideo == null) 0 else LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS,
                    retryIndefinitely = true,
                )
            )
        ) {
            LivePlaybackRecoveryPolicy.Decision.Wait -> false

            LivePlaybackRecoveryPolicy.Decision.ResetVideo -> {
                attempts += 1
                lastAttemptAtMs = nowMs
                reporter.onRecoveryAttempt(nowMs)
                // An engine that has no video-reset primitive reports false, and the attempt
                // escalates on the spot rather than being spent on a no-op.
                if (resetVideo?.invoke() != true) reconnect()
                true
            }

            // Out of attempts. Auto-report the terminal freeze (gave_up=true) so the fleet sees it
            // without the viewer acting, then let the surface show its error rather than looping on a
            // channel the provider is no longer serving.
            LivePlaybackRecoveryPolicy.Decision.GiveUp -> {
                gaveUp = true
                reporter.onRecoveryGaveUp(nowMs)
                false
            }

            LivePlaybackRecoveryPolicy.Decision.Reconnect -> {
                attempts += 1
                lastAttemptAtMs = nowMs
                reporter.onRecoveryAttempt(nowMs)
                reconnect()
                true
            }
        }
    }
}
