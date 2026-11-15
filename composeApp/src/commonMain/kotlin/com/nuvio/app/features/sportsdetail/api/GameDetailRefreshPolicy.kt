package com.nuvio.app.features.sportsdetail.api

/**
 * What a game-detail screen should fetch NOW. Pure: state + clock + last-fetch times in, a plan out — so the
 * cadence unit-tests without the network (repo CLAUDE.md "recurring network work": delta-shaped,
 * lifecycle-bound, decision extracted). The CALLER owns the lifecycle: run the loop only while the screen is
 * RESUMED (`repeatOnLifecycle(Lifecycle.State.RESUMED)`), never a bare `while (true)`, and sleep for
 * [Plan.nextCheckInMs] (or stop when it is null).
 *
 *  - LIVE: event tick every `liveMs` (15 s) = the single-event scoreboard (score, clock, situation; ~2-3 KB gzip), summary
 *    every `summaryMs` (60 s) = plays, stats, leaders, lineups, standings (35-52 KB gzip). A mapping without the single-event
 *    template makes the session fall back to the summary on the event tick, exactly as before.
 *  - UPCOMING within [Config.preKickoffLeadMs] of kickoff: event tick, so the card flips to live promptly; plus the summary
 *    on its own cadence when `wantsUpcomingSummary` (soccer publishes lineups before kickoff).
 *  - Final, postponed, or live 12 h past kickoff: idle — ZERO requests and no next check.
 *  - Far-future upcoming: zero requests, but [Plan.nextCheckInMs] says when the lead window opens (the screen wakes).
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
    /** A live game this long after kickoff is not live any more (a stuck provider status); stop polling it. */
    private const val MAX_LIVE_MS = 12L * 60 * 60 * 1000
    private const val STALE_UPCOMING_RECHECK_MS = 5L * 60 * 1000
    /** Client-side floor: a remote mapping can tune cadence but never below this. */
    const val CLIENT_FLOOR_MS = 10_000L
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
        /** Soccer publishes lineups before kickoff: inside the pre-kickoff window the summary is refreshed on its own (slow) cadence. */
        wantsUpcomingSummary: Boolean = false,
    ): Plan {
        val backoff = 1L shl consecutiveFailures.coerceIn(0, MAX_BACKOFF_SHIFT)
        val eventEvery = maxOf(config.liveMs, config.minMs, CLIENT_FLOOR_MS) * backoff
        val summaryEvery = maxOf(config.summaryMs, config.minMs, CLIENT_FLOOR_MS) * backoff
        val eventWait = remaining(lastEventFetchMs, nowMs, eventEvery)
        val summaryWait = remaining(lastSummaryFetchMs, nowMs, summaryEvery)
        return when (state) {
            GameState.FINAL, GameState.POSTPONED -> IDLE
            GameState.UPCOMING -> {
                val untilKickoff = startsAtMs - nowMs
                when {
                    // Not yet: no request, but the screen must wake when the lead window opens.
                    untilKickoff > config.preKickoffLeadMs -> Plan(false, false, untilKickoff - config.preKickoffLeadMs)
                    // Hours past kickoff and still "upcoming": a delayed or mislabeled game; look slowly, never stop.
                    -untilKickoff > STALE_UPCOMING_MS -> {
                        val wait = remaining(lastEventFetchMs, nowMs, STALE_UPCOMING_RECHECK_MS)
                        Plan(fetchEvent = wait == 0L, fetchSummary = false, nextCheckInMs = wait)
                    }
                    wantsUpcomingSummary -> Plan(fetchEvent = eventWait == 0L, fetchSummary = summaryWait == 0L, nextCheckInMs = minOf(eventWait, summaryWait))
                    else -> Plan(fetchEvent = eventWait == 0L, fetchSummary = false, nextCheckInMs = eventWait)
                }
            }
            GameState.LIVE -> if (nowMs - startsAtMs > MAX_LIVE_MS) IDLE else Plan(
                fetchEvent = eventWait == 0L,
                fetchSummary = summaryWait == 0L,
                nextCheckInMs = minOf(eventWait, summaryWait),
            )
        }
    }

    /** Sports whose lineups are published before kickoff (they need the summary inside the pre-kickoff window). */
    fun wantsUpcomingSummary(sport: String): Boolean = sport.equals("soccer", ignoreCase = true)

    private fun remaining(lastMs: Long?, nowMs: Long, everyMs: Long): Long =
        if (lastMs == null) 0L else (everyMs - (nowMs - lastMs)).coerceAtLeast(0L)
}
