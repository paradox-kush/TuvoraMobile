package com.nuvio.app.features.sportsdetail.api

/**
 * What a game-detail screen should fetch NOW. Pure: state + clock + last-fetch times in, a plan out — so the
 * cadence unit-tests without the network (repo CLAUDE.md "recurring network work": delta-shaped,
 * lifecycle-bound, decision extracted). The CALLER owns the lifecycle: run the loop only while the screen is
 * RESUMED (`repeatOnLifecycle(Lifecycle.State.RESUMED)`), never a bare `while (true)`, and sleep for
 * [Plan.nextCheckInMs] (or stop when it is null).
 *
 *  - LIVE: event tick every `liveMs` (15 s), summary every `summaryMs` (60 s).
 *  - UPCOMING within [Config.preKickoffLeadMs] of kickoff: event tick only, so the card flips to live promptly.
 *  - Anything else (final, postponed, far-future upcoming): idle — ZERO requests and no next check.
 *  - Failures back off 2x/4x/8x so a flaky provider is not hammered.
 */
object GameDetailRefreshPolicy {
    data class Config(
        val liveMs: Long = 15_000L,
        val summaryMs: Long = 60_000L,
        /** Floor the remote mapping cannot push the cadence below. */
        val minMs: Long = 6_000L,
        val preKickoffLeadMs: Long = 30L * 60 * 1000,
    )

    data class Plan(val fetchEvent: Boolean, val fetchSummary: Boolean, val nextCheckInMs: Long?) {
        /** Nothing to fetch and nothing to wait for. */
        val isIdle: Boolean get() = nextCheckInMs == null
    }

    private const val STALE_UPCOMING_MS = 3L * 60 * 60 * 1000
    private const val MAX_BACKOFF_SHIFT = 3

    private val IDLE = Plan(fetchEvent = false, fetchSummary = false, nextCheckInMs = null)

    fun configFrom(liveMs: Long, summaryMs: Long, minMs: Long): Config = Config(liveMs = liveMs, summaryMs = summaryMs, minMs = minMs)

    fun plan(
        state: GameState,
        startsAtMs: Long,
        nowMs: Long,
        lastEventFetchMs: Long?,
        lastSummaryFetchMs: Long?,
        consecutiveFailures: Int = 0,
        config: Config = Config(),
    ): Plan {
        val backoff = 1L shl consecutiveFailures.coerceIn(0, MAX_BACKOFF_SHIFT)
        val eventEvery = maxOf(config.liveMs, config.minMs) * backoff
        val summaryEvery = maxOf(config.summaryMs, config.minMs) * backoff
        val eventWait = remaining(lastEventFetchMs, nowMs, eventEvery)
        val summaryWait = remaining(lastSummaryFetchMs, nowMs, summaryEvery)
        return when (state) {
            GameState.FINAL, GameState.POSTPONED -> IDLE
            GameState.UPCOMING -> {
                val untilKickoff = startsAtMs - nowMs
                if (untilKickoff > config.preKickoffLeadMs || -untilKickoff > STALE_UPCOMING_MS) IDLE
                else Plan(fetchEvent = eventWait == 0L, fetchSummary = false, nextCheckInMs = eventWait)
            }
            GameState.LIVE -> Plan(
                fetchEvent = eventWait == 0L,
                fetchSummary = summaryWait == 0L,
                nextCheckInMs = minOf(eventWait, summaryWait),
            )
        }
    }

    private fun remaining(lastMs: Long?, nowMs: Long, everyMs: Long): Long =
        if (lastMs == null) 0L else (everyMs - (nowMs - lastMs)).coerceAtLeast(0L)
}
