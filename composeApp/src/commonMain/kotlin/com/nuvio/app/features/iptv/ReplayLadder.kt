package com.nuvio.app.features.iptv

/**
 * One replay's link ladder, whatever the provider: [M3uReplayWalk] (a playlist's own links) and
 * [CatchUpDialectWalk] (Xtream URL dialects) both implement it, so a screen follows a replay the same
 * way for both: report each attempt's outcome, and ask for a fresh window when the served one runs out.
 */
interface ReplayLadder {
    /** The current attempt played: record the proof. [CatchUpDialectWalk.Step.Stale] when the token is dead. */
    fun onSuccess(token: Long): CatchUpDialectWalk.Step

    /** The current attempt failed; the answer says what to try next, or that nothing is left. */
    fun onFailure(token: Long, kind: CatchUpDialectWalk.FailureKind): CatchUpDialectWalk.Step

    /**
     * A fresh window starting at [atMs] (a continuation past the served window), the winner first.
     * Null = the replay is gone or no link can be built.
     */
    fun restartAt(token: Long, atMs: Long, nowMs: Long): M3uReplayWalk.Begun?
}
