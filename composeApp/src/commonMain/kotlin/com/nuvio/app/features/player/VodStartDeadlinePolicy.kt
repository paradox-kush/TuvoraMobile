package com.nuvio.app.features.player

/**
 * Every VOD start ends (CLAUDE.md "every loading state has a deadline"): B214 (libmpv loaded the
 * file but its video output never initialised, so no frame, no error) and B217 (offline start)
 * both sat on the loading screen with no way out. The engines' own watchdogs are best effort and
 * cannot cover a native call that never returns, so the screen holds this backstop itself.
 *
 * Pure: the caller supplies the elapsed time since the attempt began (the clock restarts on a new
 * source, Retry and "Start from beginning", never on an engine failover) and acts on the result.
 */
internal object VodStartDeadlinePolicy {
    /** No first frame by now: try the other engine, if the viewer allows automatic failover. */
    const val FAILOVER_AFTER_MS = 30_000L

    /** No first frame by now: stop waiting. The screen shows Failed with Retry. */
    const val FAIL_AFTER_MS = 75_000L

    /** How often the caller re-evaluates. */
    const val POLL_INTERVAL_MS = 1_000L

    enum class Action { Wait, FailOver, Fail }

    fun decide(
        elapsedMs: Long,
        started: Boolean,
        isLive: Boolean,
        isTorrent: Boolean,
        suspended: Boolean,
        hasError: Boolean,
        canFailover: Boolean,
    ): Action {
        // Live has its own startup/reconnect ladder; a torrent legitimately waits on peers; a paused
        // or backgrounded load is not slow, it is parked; an error is already a terminal state.
        if (started || isLive || isTorrent || suspended || hasError) return Action.Wait
        return when {
            elapsedMs >= FAIL_AFTER_MS -> Action.Fail
            canFailover && elapsedMs >= FAILOVER_AFTER_MS -> Action.FailOver
            else -> Action.Wait
        }
    }
}
