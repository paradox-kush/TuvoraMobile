package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader

/** Small hand-built [GameDetail]s for the pure policy tests (no network, no assembler). */
internal fun detailOf(
    vararg blocks: GameBlock,
    id: String = "espn:1",
    state: GameState = GameState.LIVE,
    home: Side = Side("Home", short = "HOM", score = "0"),
    away: Side = Side("Away", short = "AWY", score = "0"),
    sport: String = "Basketball",
    live: LiveInfo? = null,
) = GameDetail(
    gameId = id, sport = sport, leagueId = "4387", leagueName = "NBA", state = state,
    header = TeamsHeader(home, away), live = live, blocks = blocks.toList(),
)

internal fun withScore(d: GameDetail, home: String? = null, away: String? = null): GameDetail {
    val h = d.header as TeamsHeader
    return d.copy(header = TeamsHeader(h.home.copy(score = home ?: h.home.score), h.away.copy(score = away ?: h.away.score)))
}
