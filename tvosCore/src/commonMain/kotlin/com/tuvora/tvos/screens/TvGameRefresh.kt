package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.GameDetailRefreshPolicy
import com.nuvio.app.features.sportsdetail.api.GameState

/**
 * The game screen's refresh plan: core's [GameDetailRefreshPolicy] plus the one sport-dependent input, kept in one pure place so it
 * is tested (Swift/tvosMain only run the loop). Soccer publishes lineups before kickoff, so its pre-kickoff window refreshes the summary.
 */
object TvGameRefresh {
    fun plan(
        sport: String, state: GameState, startsAtMs: Long, nowMs: Long, lastEventMs: Long?, lastSummaryMs: Long?,
        failures: Int, config: GameDetailRefreshPolicy.Config,
    ): GameDetailRefreshPolicy.Plan = GameDetailRefreshPolicy.plan(
        state = state, startsAtMs = startsAtMs, nowMs = nowMs, lastEventFetchMs = lastEventMs, lastSummaryFetchMs = lastSummaryMs,
        consecutiveFailures = failures, config = config, wantsUpcomingSummary = GameDetailRefreshPolicy.wantsUpcomingSummary(sport),
    )
}
