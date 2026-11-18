package com.nuvio.app.core.analytics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fires the reconnect for a frozen live channel, backed off and capped by
 * [LivePlaybackRecoveryPolicy], and keeps the freeze record open across the attempts so the
 * emitted event can say whether the reconnect is what brought the picture back.
 *
 * Held per live surface alongside the reporter. The ladder belongs to a stream identity (the channel
 * or content id): it starts over on a new channel and on the viewer's Retry, never on the pipeline's
 * own reconnects or on a URL change.
 */
class LivePlaybackReconnector(private val reporter: LivePlaybackFreezeReporter) {

    private var attempts = 0
    private var lastAttemptAtMs = 0L
    private var gaveUp = false
    private var attemptsWithoutPlayback = 0

    /** Whether the reconnect ladder has been exhausted for the open freeze. */
    val hasGivenUp: Boolean get() = gaveUp

    /** Why the stream last failed, as far as the engine said (see [noteFailure]); "stopped" when it said nothing. */
    private var lastReason = LiveReconnectReason.STREAM_STOPPED

    private val _status = MutableStateFlow<LiveReconnectStatus?>(null)

    /**
     * The surface's "Reconnecting… (attempt N of M)" line, or null when no reconnect is running. Attempts count
     * toward the give-up ([LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK]), so N never passes M; a
     * rendered frame, a zap or the viewer's Retry clears it, and the give-up hands the screen to the error + Retry.
     */
    val status: StateFlow<LiveReconnectStatus?> = _status.asStateFlow()

    /**
     * The engine reported a failure on this stream. Only the reason code is kept (never [message]: it can
     * carry a host or a token); it words the next reconnect line, and the one on screen now.
     */
    fun noteFailure(message: String?) {
        lastReason = LiveReconnectReasonPolicy.fromFailureText(message)
        _status.value = _status.value?.copy(reason = lastReason)
    }

    /** Clears the ladder. Callers use [onUserRetry], [onChannelChanged] or [onPlaybackArmed]. */
    fun reset() {
        attempts = 0
        lastAttemptAtMs = 0L
        gaveUp = false
        attemptsWithoutPlayback = 0
        lastReason = LiveReconnectReason.STREAM_STOPPED
        _status.value = null
    }

    /** The stream identity (channel / content id, never the URL) the ladder belongs to. */
    private var armedIdentity: String? = null

    /**
     * The viewer pressed Retry: one fresh ladder. Only a user action calls this; the automatic
     * reconnect never does (skill 02 2.1), so Failed still ends the wait on a dead provider.
     */
    fun onUserRetry() {
        reset()
        armedIdentity = null
    }

    /** A zap: the next channel (even the same one tuned again) does not inherit this ladder. */
    fun onChannelChanged() {
        reset()
        armedIdentity = null
    }

    /**
     * The watcher armed on [streamIdentity]. A different stream starts a fresh ladder; re-arming the
     * same one (the full player re-arms on every URL change, and a Stalker re-mint changes the URL
     * each time) keeps the attempt count, so the backoff and the give-up still hold. Null identity
     * means "unknown", and is treated as new.
     */
    fun onPlaybackArmed(streamIdentity: String?) {
        if (streamIdentity == null || streamIdentity != armedIdentity) reset()
        armedIdentity = streamIdentity
    }

    /**
     * A frame is rendering again: the "provider never lets it back" streak ends. Called by the
     * snapshot adapter on healthy samples; a channel that plays between drops never gives up.
     */
    fun onPlaybackConfirmed() {
        attemptsWithoutPlayback = 0
        lastReason = LiveReconnectReason.STREAM_STOPPED
        _status.value = null
        // Playing again (e.g. after the viewer's Retry) re-opens the ladder for later drops.
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
        // Terminal until the viewer retries (which resets this): a Failed channel is not re-asked on a timer.
        if (gaveUp) return false
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
                    attemptsWithoutPlayback = attemptsWithoutPlayback,
                )
            )
        ) {
            LivePlaybackRecoveryPolicy.Decision.Wait -> false

            LivePlaybackRecoveryPolicy.Decision.ResetVideo -> {
                attempts += 1
                attemptsWithoutPlayback += 1
                lastAttemptAtMs = nowMs
                reporter.onRecoveryAttempt(nowMs)
                publishAttempt()
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
                // The error + Retry owns the screen now: no "reconnecting" line under it.
                _status.value = null
                reporter.onRecoveryGaveUp(nowMs)
                false
            }

            LivePlaybackRecoveryPolicy.Decision.Reconnect -> {
                attempts += 1
                attemptsWithoutPlayback += 1
                lastAttemptAtMs = nowMs
                reporter.onRecoveryAttempt(nowMs)
                publishAttempt()
                reconnect()
                true
            }
        }
    }

    /**
     * The channel's open failed before any frame, with the engine's [message] (or none). Returns the wait before
     * re-opening it when [LiveOpenRetryPolicy] says a retry can help and the ladder has attempts left (the same
     * [LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK] cap, counted together with freeze reconnects), and
     * publishes the attempt; null means fail now (error + Retry). Out of attempts is the terminal give-up.
     */
    fun onOpenFailed(message: String?): Long? {
        if (gaveUp) return null
        lastReason = LiveReconnectReasonPolicy.fromFailureText(message)
        if (!LiveOpenRetryPolicy.retries(lastReason)) {
            _status.value = null
            return null
        }
        if (attemptsWithoutPlayback >= LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK) {
            gaveUp = true
            _status.value = null
            return null
        }
        val delay = LiveOpenRetryPolicy.delayMs(attemptsWithoutPlayback)
        attempts += 1
        attemptsWithoutPlayback += 1
        publishAttempt()
        return delay
    }

    private fun publishAttempt() {
        _status.value = LiveReconnectStatus(
            attempt = attemptsWithoutPlayback.coerceAtMost(LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK),
            maxAttempts = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK,
            reason = lastReason,
        )
    }
}
