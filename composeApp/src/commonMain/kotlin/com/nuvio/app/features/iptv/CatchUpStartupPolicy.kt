package com.nuvio.app.features.iptv

import kotlin.time.TimeSource

/**
 * How long a catch-up replay may wait for its first frame, per link and in total.
 *
 * A replay link that accepts the connection and never answers used to hold "Starting stream" for as
 * long as the viewer waited: the link walks ([M3uReplayWalk], [CatchUpDialectWalk]) only advance on a
 * player ERROR, which never comes. Every load ends Loaded, Empty or Failed within a deadline
 * (repo CLAUDE.md), so each link gets [LINK_DEADLINE_MS], a timeout is treated as a transport failure
 * (the walk moves to the next candidate) and the walk as a whole is bounded by [TOTAL_BUDGET_MS] /
 * [MAX_TIMEOUTS]. The count lives on the replay ([CatchUpStartupBudget]), never keyed on the URL:
 * every rung is a different string.
 */
object CatchUpStartupPolicy {
    const val LINK_DEADLINE_MS = 15_000L
    const val TOTAL_BUDGET_MS = 45_000L
    const val MAX_TIMEOUTS = 3

    /** What the replay does when a link's deadline passes with no frame. */
    enum class Decision { ADVANCE, GIVE_UP }

    /** The wait for the link now loading, trimmed so the whole walk never overruns the budget. */
    fun deadlineMs(elapsedMs: Long): Long =
        minOf(LINK_DEADLINE_MS, TOTAL_BUDGET_MS - elapsedMs).coerceAtLeast(MIN_DEADLINE_MS)

    /** [timeoutCount] counts the timeout that just fired. */
    fun onTimeout(timeoutCount: Int, elapsedMs: Long): Decision =
        if (timeoutCount >= MAX_TIMEOUTS || elapsedMs >= TOTAL_BUDGET_MS) Decision.GIVE_UP else Decision.ADVANCE

    private const val MIN_DEADLINE_MS = 1_000L
}

/**
 * One replay's startup budget. The clock starts at the FIRST [arm] and is not restarted by later
 * links, engine restarts or the screen re-arming after a pause, so the total stays bounded. [clockMs]
 * must be monotonic (wall-clock changes must not stretch or cut a deadline).
 */
class CatchUpStartupBudget(
    private val clockMs: () -> Long = monotonicClockMs(),
) {
    private var began = NOT_STARTED
    private var timeouts = 0
    private var played = false
    private var gaveUp = false

    /** The wait for the link now loading, or null when no deadline applies (it already played, or the walk gave up). */
    fun arm(): Long? {
        if (played || gaveUp) return null
        val now = clockMs()
        if (began == NOT_STARTED) began = now
        return CatchUpStartupPolicy.deadlineMs(now - began)
    }

    /** A link's deadline passed with no frame. GIVE_UP latches until [reset]. */
    fun onTimeout(): CatchUpStartupPolicy.Decision {
        timeouts++
        val now = clockMs()
        val start = if (began == NOT_STARTED) now else began
        val decision = CatchUpStartupPolicy.onTimeout(timeouts, now - start)
        if (decision == CatchUpStartupPolicy.Decision.GIVE_UP) gaveUp = true
        return decision
    }

    /** The replay rendered a frame: no further deadline applies to it. */
    fun markPlayed() {
        played = true
    }

    /**
     * A fresh attempt: the viewer pressed Retry, or a continuing replay restarted on a NEW link. Either
     * way the new link must reach a frame within its own deadline, even if an earlier link played.
     */
    fun reset() {
        began = NOT_STARTED
        timeouts = 0
        gaveUp = false
        played = false
    }

    private companion object {
        const val NOT_STARTED = Long.MIN_VALUE

        fun monotonicClockMs(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
